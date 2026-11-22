package de.knirpslarsi.ledstudio.wled;

import de.knirpslarsi.ledstudio.model.WledDevice;
import de.knirpslarsi.ledstudio.openrgb.Controller;
import de.knirpslarsi.ledstudio.openrgb.LedBackend;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/** WLED-Geräte als Geräte wie aus OpenRGB: je Gerät ein LED-Streifen, Farben gehen per DDP raus. */
public final class WledBackend implements LedBackend {
    /** Ort im Controller-Schlüssel: gleich für alle WLED-Geräte, die Seriennummer ist die MAC. */
    public static final String LOCATION = "WLED";

    private final List<WledDevice> devices;
    private final List<Controller> controllers = new ArrayList<>();
    private final InetAddress[] addresses;
    private final DatagramSocket socket;
    private int sequence;

    public WledBackend(List<WledDevice> devices) throws IOException {
        this.devices = List.copyOf(devices);
        this.addresses = new InetAddress[this.devices.size()];
        socket = new DatagramSocket();
        for (int i = 0; i < this.devices.size(); i++) {
            WledDevice d = this.devices.get(i);
            controllers.add(controller(i, d));
            try {
                addresses[i] = InetAddress.getByName(d.address());
            } catch (IOException e) {
                addresses[i] = null; // Name nicht auflösbar: Gerät bleibt im Layout, bekommt aber nichts
            }
        }
    }

    static Controller controller(int index, WledDevice d) {
        int n = Math.max(1, d.leds);
        List<String> names = new ArrayList<>(n);
        for (int k = 0; k < n; k++) names.add("LED " + (k + 1));
        Controller.Zone zone = new Controller.Zone(0, "LED-Streifen", 1, n, n, n, 0, 0, 0, null, List.of());
        return new Controller(index, 4, "WLED " + d.name, "WLED", "WLED " + d.host, "", d.id(), LOCATION, 0,
                List.of("Direct"), List.of(zone), names);
    }

    @Override
    public List<Controller> controllers() {
        return controllers;
    }

    @Override
    public List<Controller> refresh() {
        return controllers;
    }

    @Override
    public void enterDirectMode(int device) {
    }

    @Override
    public void setLeds(int device, int[] colors) throws IOException {
        if (device < 0 || device >= devices.size() || addresses[device] == null) return;
        sequence = (sequence % 15) + 1;
        Wled.send(socket, addresses[device], devices.get(device).ddpPort, colors, sequence);
    }

    @Override
    public void resizeZone(int device, int zone, int size) throws IOException {
        throw new IOException("Die LED-Zahl eines WLED-Geräts stellst du in WLED selbst ein.");
    }

    @Override
    public boolean supportsSegments() {
        return false;
    }

    @Override
    public void clearSegments(int device, int zone) {
    }

    @Override
    public void addSegment(int device, int zone, String name, int start, int count) throws IOException {
        throw new IOException("Segmente gibt es bei WLED-Geräten hier nicht.");
    }

    @Override
    public void rescan() {
    }

    @Override
    public boolean consumeDeviceListChanged() {
        return false;
    }

    @Override
    public void close() {
        socket.close();
    }
}
