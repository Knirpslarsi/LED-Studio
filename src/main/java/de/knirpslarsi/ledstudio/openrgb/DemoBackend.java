package de.knirpslarsi.ledstudio.openrgb;

import java.util.ArrayList;
import java.util.List;

/** Simulierte Hardware: damit läuft die komplette App ohne OpenRGB (Start mit --demo, Tests, Screenshots). */
public class DemoBackend implements LedBackend {
    private final List<Controller> controllers = new ArrayList<>();
    public volatile long framesWritten;

    public DemoBackend() {
        controllers.add(device(0, 3, "NZXT RGB & Fan Controller 2024", "usb-demo-1", "HID-1",
                zone("Hue 2 Channel 1", 0, 96, 24), zone("Hue 2 Channel 2", 0, 96, 24),
                zone("Hue 2 Channel 3", 0, 96, 0), zone("Hue 2 Channel 5", 0, 96, 8)));
        controllers.add(device(1, 0, "ASUS ROG STRIX B550-F GAMING", "usb-demo-2", "HID-2",
                zone("Aura Mainboard", 5, 5, 5), zone("Aura Addressable 1", 0, 120, 10)));
        controllers.add(device(2, 1, "RGB Fusion 2 DRAM", "i2c-demo", "", zone("DRAM", 6, 6, 6)));
        int[] matrix = new int[6 * 14];
        for (int i = 0; i < matrix.length; i++) matrix[i] = i % 14 == 13 && i < 28 ? 0xFFFFFFFF : i;
        int leds = 0;
        for (int i = 0; i < matrix.length; i++) if (matrix[i] != 0xFFFFFFFF) matrix[i] = leds++;
        controllers.add(new Controller(3, 5, "Demo Tastatur", "Demo", "Demo-Gerät", "1.0", "KB-1", "hid-kb", 0,
                List.of("Direct"), List.of(new Controller.Zone(0, "Keyboard", 2, leds, leds, leds, 0, 6, 14, matrix, List.of())),
                names(leds)));
        // Absichtlich doppelt erkannt: gleiche Seriennummer wie Gerät 3.
        controllers.add(device(4, 5, "Demo Tastatur", "hid-kb-2", "KB-1", zone("Keyboard", 4, 4, 4)));
    }

    private static Controller.Zone zone(String name, int min, int max, int count) {
        return new Controller.Zone(0, name, 1, min, max, count, 0, 0, 0, null, List.of());
    }

    private static List<String> names(int n) {
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add("LED " + (i + 1));
        return out;
    }

    private static Controller device(int index, int type, String name, String location, String serial, Controller.Zone... zones) {
        List<Controller.Zone> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < zones.length; i++) {
            Controller.Zone z = zones[i];
            out.add(new Controller.Zone(i, z.name(), z.type(), z.ledsMin(), z.ledsMax(), z.ledCount(), start, 0, 0, null, z.segments()));
            start += z.ledCount();
        }
        return new Controller(index, type, name, "Demo", "Demo-Gerät", "1.0", serial, location, 0, List.of("Direct"), out, names(start));
    }

    @Override
    public List<Controller> controllers() {
        return List.copyOf(controllers);
    }

    @Override
    public List<Controller> refresh() {
        return controllers();
    }

    @Override
    public void enterDirectMode(int device) {
    }

    @Override
    public void setLeds(int device, int[] colors) {
        framesWritten++;
    }

    @Override
    public void resizeZone(int device, int zone, int size) {
        Controller c = controllers.get(device);
        List<Controller.Zone> zones = new ArrayList<>();
        int start = 0;
        for (Controller.Zone z : c.zones()) {
            int count = z.index() == zone ? size : z.ledCount();
            zones.add(new Controller.Zone(z.index(), z.name(), z.type(), z.ledsMin(), z.ledsMax(), count, start, 0, 0, null, z.segments()));
            start += count;
        }
        controllers.set(device, new Controller(c.index(), c.type(), c.name(), c.vendor(), c.description(), c.version(),
                c.serial(), c.location(), c.activeMode(), c.modes(), zones, names(start)));
    }

    @Override
    public boolean supportsSegments() {
        return true;
    }

    @Override
    public void clearSegments(int device, int zone) {
    }

    @Override
    public void addSegment(int device, int zone, String name, int start, int count) {
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
    }
}
