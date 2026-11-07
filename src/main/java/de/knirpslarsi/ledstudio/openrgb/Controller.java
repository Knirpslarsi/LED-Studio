package de.knirpslarsi.ledstudio.openrgb;

import java.util.List;

/** Ein von OpenRGB gemeldetes Gerät samt Zonen und LEDs. */
public record Controller(int index, int type, String name, String vendor, String description, String version,
                         String serial, String location, int activeMode, List<String> modes, List<Zone> zones,
                         List<String> ledNames) {

    public int ledCount() {
        return ledNames.size();
    }

    /** Bleibt über Neustarts stabil, anders als der Listenindex. */
    public String key() {
        return name + "|" + location + "|" + serial;
    }

    public String displayName() {
        if (!name.isBlank()) return name;
        if (!description.isBlank()) return description;
        return typeName() + " (" + index + ")";
    }

    public Zone zone(String zoneName) {
        for (Zone z : zones) if (z.name().equals(zoneName)) return z;
        return null;
    }

    public String typeName() {
        return switch (type) {
            case 0 -> "Mainboard";
            case 1 -> "RAM";
            case 2 -> "Grafikkarte";
            case 3 -> "Kühler";
            case 4 -> "LED-Strip";
            case 5 -> "Tastatur";
            case 6 -> "Maus";
            case 7 -> "Mauspad";
            case 8 -> "Headset";
            case 9 -> "Headset-Halter";
            case 10 -> "Gamepad";
            case 11 -> "Licht";
            case 12 -> "Lautsprecher";
            default -> "Gerät";
        };
    }

    public record Segment(String name, int type, int start, int count) {
    }

    /** {@code start} ist der Index der ersten Zonen-LED innerhalb des Geräts. */
    public record Zone(int index, String name, int type, int ledsMin, int ledsMax, int ledCount, int start,
                       int matrixHeight, int matrixWidth, int[] matrix, List<Segment> segments) {
        public boolean resizable() {
            return ledsMin != ledsMax;
        }

        public boolean isMatrix() {
            return matrix != null && matrixHeight > 0 && matrixWidth > 0;
        }
    }
}
