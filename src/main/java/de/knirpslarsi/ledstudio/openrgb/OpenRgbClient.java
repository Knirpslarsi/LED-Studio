package de.knirpslarsi.ledstudio.openrgb;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenRGB-SDK-Client. Spricht bewusst höchstens Protokoll 4: Der Server liefert älteren Clients weiter ihr
 * Format, damit bleibt das Parsen auch bei neuen OpenRGB-Versionen stabil. Segmente gibt es ab Version 4.
 */
public final class OpenRgbClient implements LedBackend {
    public static final int MAX_PROTOCOL = 4;
    private static final int HEADER = 16;
    private static final int REQUEST_CONTROLLER_COUNT = 0, REQUEST_CONTROLLER_DATA = 1, REQUEST_PROTOCOL_VERSION = 40,
            SET_CLIENT_NAME = 50, DEVICE_LIST_UPDATED = 100, REQUEST_RESCAN = 140, RESIZE_ZONE = 1000,
            CLEAR_SEGMENTS = 1001, ADD_SEGMENT = 1002, UPDATE_LEDS = 1050, SET_CUSTOM_MODE = 1100;

    private final Socket socket = new Socket();
    private final DataInputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private final BlockingQueue<Reply> replies = new ArrayBlockingQueue<>(64);
    private final AtomicBoolean listChanged = new AtomicBoolean();
    private volatile boolean closed;
    private volatile IOException failure;
    private final int protocol;
    private volatile List<Controller> controllers = List.of();

    private record Reply(int device, int id, byte[] body) {
    }

    public OpenRgbClient(String host, int port, String clientName) throws IOException {
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), 2000);
        in = new DataInputStream(socket.getInputStream());
        out = new BufferedOutputStream(socket.getOutputStream(), 1 << 16);
        Thread reader = new Thread(this::readLoop, "openrgb-reader");
        reader.setDaemon(true);
        reader.start();
        send(0, REQUEST_PROTOCOL_VERSION, u32(MAX_PROTOCOL));
        // Sehr alte Server (Protokoll 0) antworten nicht auf die Versionsabfrage.
        Reply version = poll(1500);
        protocol = version == null ? 0
                : Math.min(MAX_PROTOCOL, ByteBuffer.wrap(version.body).order(ByteOrder.LITTLE_ENDIAN).getInt());
        send(0, SET_CLIENT_NAME, (clientName + "\0").getBytes(StandardCharsets.UTF_8));
        refresh();
    }

    public int protocol() {
        return protocol;
    }

    private void readLoop() {
        byte[] header = new byte[HEADER];
        try {
            while (!closed) {
                in.readFully(header);
                ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                if (header[0] != 'O' || header[1] != 'R' || header[2] != 'G' || header[3] != 'B')
                    throw new IOException("Ungültige Antwort vom OpenRGB-Server");
                int device = h.getInt(4), id = h.getInt(8), size = h.getInt(12);
                if (size < 0 || size > 64 * 1024 * 1024) throw new IOException("Ungültige Paketgröße: " + size);
                byte[] body = new byte[size];
                in.readFully(body);
                if (id == DEVICE_LIST_UPDATED) listChanged.set(true);
                else if (id == REQUEST_PROTOCOL_VERSION || id == REQUEST_CONTROLLER_COUNT || id == REQUEST_CONTROLLER_DATA)
                    replies.offer(new Reply(device, id, body));
                // Alles andere wird bewusst ignoriert, damit neue Server-Nachrichten nichts kaputt machen.
            }
        } catch (IOException e) {
            if (!closed) failure = e;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void send(int device, int id, byte[] body) throws IOException {
        if (failure != null) throw failure;
        ByteBuffer h = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
        h.put((byte) 'O').put((byte) 'R').put((byte) 'G').put((byte) 'B').putInt(device).putInt(id).putInt(body.length);
        synchronized (writeLock) {
            out.write(h.array());
            out.write(body);
            out.flush();
        }
    }

    private Reply poll(long millis) throws IOException {
        try {
            return replies.poll(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Unterbrochen", e);
        }
    }

    private Reply await(int id, int device) throws IOException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            if (failure != null) throw failure;
            Reply r = poll(100);
            if (r != null && r.id == id && r.device == device) return r;
        }
        throw new IOException("OpenRGB antwortet nicht");
    }

    @Override
    public synchronized List<Controller> refresh() throws IOException {
        replies.clear();
        send(0, REQUEST_CONTROLLER_COUNT, new byte[0]);
        int count = ByteBuffer.wrap(await(REQUEST_CONTROLLER_COUNT, 0).body).order(ByteOrder.LITTLE_ENDIAN).getInt();
        List<Controller> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            send(i, REQUEST_CONTROLLER_DATA, protocol > 0 ? u32(protocol) : new byte[0]);
            list.add(parseController(i, await(REQUEST_CONTROLLER_DATA, i).body, protocol));
        }
        controllers = List.copyOf(list);
        return controllers;
    }

    @Override
    public List<Controller> controllers() {
        return controllers;
    }

    @Override
    public void enterDirectMode(int device) throws IOException {
        send(device, SET_CUSTOM_MODE, new byte[0]);
    }

    @Override
    public void setLeds(int device, int[] colors) throws IOException {
        send(device, UPDATE_LEDS, encodeLeds(colors));
    }

    static byte[] encodeLeds(int[] colors) {
        int size = 4 + 2 + colors.length * 4;
        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(size).putShort((short) colors.length);
        for (int c : colors) b.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) 0);
        return b.array();
    }

    @Override
    public void resizeZone(int device, int zone, int size) throws IOException {
        send(device, RESIZE_ZONE, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(zone).putInt(size).array());
    }

    @Override
    public boolean supportsSegments() {
        return protocol >= 4;
    }

    @Override
    public void clearSegments(int device, int zone) throws IOException {
        send(device, CLEAR_SEGMENTS, u32(zone));
    }

    @Override
    public void addSegment(int device, int zone, String name, int start, int count) throws IOException {
        send(device, ADD_SEGMENT, encodeSegment(zone, name, start, count));
    }

    static byte[] encodeSegment(int zone, String name, int start, int count) {
        byte[] n = (name + "\0").getBytes(StandardCharsets.UTF_8);
        int size = 4 + 4 + 2 + n.length + 4 + 4 + 4;
        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(size).putInt(zone).putShort((short) n.length).put(n).putInt(0 /* linear */).putInt(start).putInt(count);
        return b.array();
    }

    @Override
    public void rescan() throws IOException {
        send(0, REQUEST_RESCAN, new byte[0]);
    }

    @Override
    public boolean consumeDeviceListChanged() {
        return listChanged.getAndSet(false);
    }

    @Override
    public boolean alive() {
        return failure == null;
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static byte[] u32(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }

    private static String str(ByteBuffer b) {
        int len = Short.toUnsignedInt(b.getShort());
        if (len == 0) return "";
        byte[] raw = new byte[len];
        b.get(raw);
        int end = raw.length;
        while (end > 0 && raw[end - 1] == 0) end--;
        return new String(raw, 0, end, StandardCharsets.UTF_8);
    }

    /** Parst einen Controller-Block im Format der jeweiligen Protokollversion (bis 4). */
    public static Controller parseController(int index, byte[] data, int protocol) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        b.getInt(); // Gesamtgröße
        int type = b.getInt();
        String name = str(b);
        String vendor = protocol >= 1 ? str(b) : "";
        String description = str(b), version = str(b), serial = str(b), location = str(b);
        int numModes = Short.toUnsignedInt(b.getShort());
        int activeMode = b.getInt();
        List<String> modes = new ArrayList<>(numModes);
        for (int i = 0; i < numModes; i++) {
            modes.add(str(b));
            b.position(b.position() + 4 * 4); // value, flags, speed min/max
            if (protocol >= 3) b.position(b.position() + 2 * 4); // brightness min/max
            b.position(b.position() + 3 * 4); // colors min/max, speed
            if (protocol >= 3) b.position(b.position() + 4); // brightness
            b.position(b.position() + 2 * 4); // direction, color mode
            int colors = Short.toUnsignedInt(b.getShort());
            b.position(b.position() + colors * 4);
        }
        int numZones = Short.toUnsignedInt(b.getShort());
        List<Controller.Zone> zones = new ArrayList<>(numZones);
        int start = 0;
        for (int i = 0; i < numZones; i++) {
            String zoneName = str(b);
            int zoneType = b.getInt();
            int min = b.getInt(), max = b.getInt(), count = b.getInt();
            int matrixBytes = Short.toUnsignedInt(b.getShort());
            int h = 0, w = 0;
            int[] matrix = null;
            if (matrixBytes > 0) {
                h = b.getInt();
                w = b.getInt();
                matrix = new int[h * w];
                for (int m = 0; m < matrix.length; m++) matrix[m] = b.getInt();
            }
            List<Controller.Segment> segments = new ArrayList<>();
            if (protocol >= 4) {
                int numSegments = Short.toUnsignedInt(b.getShort());
                for (int s = 0; s < numSegments; s++) {
                    String segName = str(b);
                    segments.add(new Controller.Segment(segName, b.getInt(), b.getInt(), b.getInt()));
                }
            }
            zones.add(new Controller.Zone(i, zoneName, zoneType, min, max, count, start, h, w, matrix, List.copyOf(segments)));
            start += count;
        }
        int numLeds = Short.toUnsignedInt(b.getShort());
        List<String> leds = new ArrayList<>(numLeds);
        for (int i = 0; i < numLeds; i++) {
            leds.add(str(b));
            b.getInt(); // value
        }
        return new Controller(index, type, name, vendor, description, version, serial, location, activeMode,
                List.copyOf(modes), List.copyOf(zones), List.copyOf(leds));
    }
}
