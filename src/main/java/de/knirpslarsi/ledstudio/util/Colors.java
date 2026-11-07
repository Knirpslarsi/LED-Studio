package de.knirpslarsi.ledstudio.util;

/** Farbhelfer. Farben sind überall 0xRRGGBB. */
public final class Colors {
    private Colors() {
    }

    public static int pack(int r, int g, int b) {
        return (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
    }

    public static int packD(double r, double g, double b) {
        return pack((int) (r + 0.5), (int) (g + 0.5), (int) (b + 0.5));
    }

    public static int clamp8(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    public static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    public static int r(int c) {
        return (c >> 16) & 255;
    }

    public static int g(int c) {
        return (c >> 8) & 255;
    }

    public static int b(int c) {
        return c & 255;
    }

    /** Wie Pythons colorsys.hsv_to_rgb; Ergebnis 0..1. */
    public static void hsv(double h, double s, double v, double[] rgb) {
        h = h - Math.floor(h);
        s = clamp01(s);
        v = clamp01(v);
        if (s == 0) {
            rgb[0] = rgb[1] = rgb[2] = v;
            return;
        }
        int i = (int) (h * 6);
        double f = h * 6 - i, p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
        switch (i % 6) {
            case 0 -> set(rgb, v, t, p);
            case 1 -> set(rgb, q, v, p);
            case 2 -> set(rgb, p, v, t);
            case 3 -> set(rgb, p, q, v);
            case 4 -> set(rgb, t, p, v);
            default -> set(rgb, v, p, q);
        }
    }

    private static void set(double[] rgb, double a, double b, double c) {
        rgb[0] = a;
        rgb[1] = b;
        rgb[2] = c;
    }

    /** HSV nach 0xRRGGBB, kaufmännisch gerundet. */
    public static int hsv(double h, double s, double v) {
        double[] c = new double[3];
        hsv(h, s, v, c);
        return pack((int) (c[0] * 255 + 0.5), (int) (c[1] * 255 + 0.5), (int) (c[2] * 255 + 0.5));
    }

    /** HSV nach 0xRRGGBB, abgeschnitten (wie die alten Effekte). */
    public static int hsvFloor(double h, double s, double v) {
        double[] c = new double[3];
        hsv(h, s, v, c);
        return pack((int) (c[0] * 255), (int) (c[1] * 255), (int) (c[2] * 255));
    }

    public static int scale(int c, double f) {
        if (f >= 0.9995) return c;
        if (f <= 0) return 0;
        return pack((int) (r(c) * f), (int) (g(c) * f), (int) (b(c) * f));
    }

    public static int lerp(int a, int b, double t) {
        t = clamp01(t);
        return packD(r(a) + (r(b) - r(a)) * t, g(a) + (g(b) - g(a)) * t, b(a) + (b(b) - b(a)) * t);
    }

    public static int max(int a, int b) {
        return (Math.max(r(a), r(b)) << 16) | (Math.max(g(a), g(b)) << 8) | Math.max(b(a), b(b));
    }

    public static int add(int a, int b) {
        return pack(r(a) + r(b), g(a) + g(b), b(a) + b(b));
    }

    /** Verlauf über mehrere Farben, fraction 0..1. */
    public static int gradient(int[] stops, double fraction) {
        double x = clamp01(fraction) * (stops.length - 1);
        int i = Math.min(stops.length - 2, (int) x);
        return lerp(stops[i], stops[i + 1], x - i);
    }

    /** "#RRGGBB" nach Farbe; wirft bei ungültigem Format. */
    public static int parseHex(String text) {
        String t = text.strip();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() != 6) throw new IllegalArgumentException("Farbe muss das Format #RRGGBB haben");
        return Integer.parseInt(t, 16);
    }

    public static String toHex(int c) {
        return String.format("#%06X", c & 0xFFFFFF);
    }

    /** Farbkanäle für Strips mit anderer Reihenfolge tauschen. order z.B. "GRB". */
    public static int reorder(int c, String order) {
        if (order == null || order.equals("RGB")) return c;
        int out = 0;
        for (int i = 0; i < 3; i++) {
            int value = switch (order.charAt(i)) {
                case 'R' -> r(c);
                case 'G' -> g(c);
                default -> b(c);
            };
            out = (out << 8) | value;
        }
        return out;
    }
}
