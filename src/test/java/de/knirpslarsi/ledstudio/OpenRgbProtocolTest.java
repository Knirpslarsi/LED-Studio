package de.knirpslarsi.ledstudio;

import de.knirpslarsi.ledstudio.openrgb.Controller;
import de.knirpslarsi.ledstudio.openrgb.OpenRgbClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prüft das SDK-Protokoll gegen einen kleinen Testserver. */
class OpenRgbProtocolTest {
    private record Packet(int device, int id, byte[] body) {
    }

    private static final class Buf {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();

        Buf u32(int v) {
            o.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array());
            return this;
        }

        Buf u16(int v) {
            o.writeBytes(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) v).array());
            return this;
        }

        Buf str(String s) {
            byte[] b = (s + "\0").getBytes(StandardCharsets.UTF_8);
            u16(b.length);
            o.writeBytes(b);
            return this;
        }
    }

    /** Baut einen Controller im Format von Protokoll 4: Modus, zwei Zonen (eine Matrix), ein Segment. */
    private static byte[] controllerV4() {
        Buf b = new Buf();
        b.u32(0).u32(3).str("NZXT Test").str("NZXT").str("Beschreibung").str("1.2").str("SER1").str("HID: 1");
        b.u16(1).u32(0); // Modi, aktiver Modus
        b.str("Direct").u32(0).u32(32).u32(0).u32(0).u32(0).u32(0).u32(0).u32(0).u32(0).u32(0).u32(0).u32(1).u16(0);
        b.u16(2);
        b.str("Channel 1").u32(1).u32(0).u32(40).u32(3).u16(0).u16(1);
        b.str("Fan 1").u32(0).u32(0).u32(3);
        b.str("Matrix").u32(2).u32(4).u32(4).u32(4).u16(4 * 4 + 8).u32(2).u32(2).u32(0).u32(1).u32(0xFFFFFFFF).u32(3).u16(0);
        b.u16(7);
        for (int i = 0; i < 7; i++) b.str("LED " + i).u32(0);
        b.u16(0);
        byte[] raw = b.o.toByteArray();
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(0, raw.length);
        return raw;
    }

    @Test
    void parsesControllerV4() {
        Controller c = OpenRgbClient.parseController(0, controllerV4(), 4);
        assertEquals("NZXT Test", c.name());
        assertEquals("SER1", c.serial());
        assertEquals(List.of("Direct"), c.modes());
        assertEquals(2, c.zones().size());
        Controller.Zone first = c.zones().get(0), second = c.zones().get(1);
        assertTrue(first.resizable());
        assertEquals(1, first.segments().size());
        assertEquals("Fan 1", first.segments().get(0).name());
        assertEquals(3, first.segments().get(0).count());
        assertEquals(3, second.start());
        assertTrue(second.isMatrix());
        assertFalse(second.resizable());
        assertEquals(7, c.ledCount());
    }

    @Test
    void talksToServer() throws Exception {
        List<Packet> received = Collections.synchronizedList(new ArrayList<>());
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread t = new Thread(() -> serve(server, received));
            t.setDaemon(true);
            t.start();
            try (OpenRgbClient client = new OpenRgbClient("127.0.0.1", server.getLocalPort(), "LED Studio")) {
                assertEquals(4, client.protocol(), "Client deckelt das Protokoll, auch wenn der Server 6 kann");
                assertEquals(1, client.controllers().size());
                client.enterDirectMode(0);
                client.setLeds(0, new int[]{0x102030, 0xFF0080});
                client.resizeZone(0, 1, 24);
                client.clearSegments(0, 1);
                client.addSegment(0, 1, "Lüfter 1", 8, 8);
                long end = System.currentTimeMillis() + 3000;
                while (received.stream().noneMatch(p -> p.id == 1002) && System.currentTimeMillis() < end) Thread.sleep(10);
            }
        }
        // Client-Name: nullterminiert.
        assertEquals("LED Studio\0", new String(find(received, 50).body, StandardCharsets.UTF_8));
        // Die Controller-Anfrage nennt die ausgehandelte Version.
        assertEquals(4, ByteBuffer.wrap(find(received, 1).body).order(ByteOrder.LITTLE_ENDIAN).getInt());
        assertEquals(0, find(received, 1100).body.length);

        ByteBuffer leds = ByteBuffer.wrap(find(received, 1050).body).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(14, leds.getInt());
        assertEquals(2, leds.getShort());
        byte[] rgbx = new byte[8];
        leds.get(rgbx);
        assertArrayEquals(new byte[]{0x10, 0x20, 0x30, 0, (byte) 0xFF, 0, (byte) 0x80, 0}, rgbx);

        ByteBuffer resize = ByteBuffer.wrap(find(received, 1000).body).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, resize.getInt());
        assertEquals(24, resize.getInt());

        assertEquals(1, ByteBuffer.wrap(find(received, 1001).body).order(ByteOrder.LITTLE_ENDIAN).getInt());

        // Segment: Größe, Zone, Name, Typ, Start, Anzahl; Größe muss exakt zur Paketgröße passen.
        byte[] seg = find(received, 1002).body;
        ByteBuffer s = ByteBuffer.wrap(seg).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(seg.length, s.getInt());
        assertEquals(1, s.getInt());
        int nameLength = Short.toUnsignedInt(s.getShort());
        byte[] name = new byte[nameLength];
        s.get(name);
        assertEquals("Lüfter 1\0", new String(name, StandardCharsets.UTF_8));
        assertEquals(0, s.getInt());
        assertEquals(8, s.getInt());
        assertEquals(8, s.getInt());
        assertEquals(0, s.remaining());
    }

    private static Packet find(List<Packet> list, int id) {
        synchronized (list) {
            return list.stream().filter(p -> p.id == id).findFirst().orElseThrow(() -> new AssertionError("Paket " + id + " fehlt"));
        }
    }

    private static void reply(OutputStream out, int device, int id, byte[] body) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        h.put((byte) 'O').put((byte) 'R').put((byte) 'G').put((byte) 'B').putInt(device).putInt(id).putInt(body.length);
        out.write(h.array());
        out.write(body);
        out.flush();
    }

    private static void serve(ServerSocket server, List<Packet> received) {
        try (Socket s = server.accept()) {
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            byte[] header = new byte[16];
            while (true) {
                in.readFully(header);
                ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                int device = h.getInt(4), id = h.getInt(8), size = h.getInt(12);
                byte[] body = new byte[size];
                in.readFully(body);
                received.add(new Packet(device, id, body));
                if (id == 40) reply(out, 0, 40, new Buf().u32(6).o.toByteArray());
                else if (id == 0) {
                    reply(out, 0, 100, new byte[0]); // unaufgeforderte Meldung darf nichts durcheinanderbringen
                    reply(out, 0, 0, new Buf().u32(1).o.toByteArray());
                } else if (id == 1) reply(out, device, 1, controllerV4());
            }
        } catch (IOException ignored) {
        }
    }

    /** Liest nur die Geräteliste des echten Servers. Aktivieren mit LEDSTUDIO_LIVE=1. */
    @Test
    @EnabledIfEnvironmentVariable(named = "LEDSTUDIO_LIVE", matches = "1")
    void liveDeviceList() throws Exception {
        try (OpenRgbClient client = new OpenRgbClient("127.0.0.1", 6742, "LED Studio (Test)")) {
            System.out.println("Protokoll " + client.protocol());
            assertFalse(client.controllers().isEmpty());
            for (Controller c : client.controllers()) {
                int zoneLeds = c.zones().stream().mapToInt(Controller.Zone::ledCount).sum();
                System.out.printf("%d [%s] '%s' vendor='%s' serial='%s' location='%s' leds=%d modes=%s%n", c.index(), c.typeName(), c.name(), c.vendor(), c.serial(), c.location(), c.ledCount(), c.modes());
                for (Controller.Zone z : c.zones()) {
                    System.out.printf("    zone '%s' type=%d %d..%d count=%d start=%d matrix=%dx%d segments=%s%n", z.name(), z.type(), z.ledsMin(), z.ledsMax(), z.ledCount(), z.start(), z.matrixWidth(), z.matrixHeight(), z.segments());
                }
                assertEquals(c.ledCount(), zoneLeds, "Zonen-LEDs passen zu Geräte-LEDs: " + c.name());
            }
        }
    }
}
