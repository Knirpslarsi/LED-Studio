package de.knirpslarsi.ledstudio.engine;

import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.util.Colors;

/** Der Feinschliff einer Ebene: Farben umfärben, anpassen und auf die Ebenen darunter legen. */
final class LayerFx {
    private LayerFx() {
    }

    static int[] stops(Project.Layer l) {
        int a = hex(l.color1, 0xA020FF), b = hex(l.color2, 0x00DDFF);
        return l.useColor3 ? new int[]{a, b, hex(l.color3, 0xFF2080)} : new int[]{a, b};
    }

    private static int hex(String s, int fallback) {
        try {
            return Colors.parseHex(s);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Färbt die Effektausgabe um. Die Helligkeit des Effekts bleibt erhalten, nur die Farbe wird ersetzt. */
    static int colorize(int c, String mode, int[] stops, double position) {
        double v = Math.max(Colors.r(c), Math.max(Colors.g(c), Colors.b(c))) / 255.0;
        return switch (mode) {
            case "solid" -> Colors.scale(stops[0], v);
            case "value" -> Colors.scale(Colors.gradient(stops, v), v);
            case "position" -> Colors.scale(Colors.gradient(stops, position), v);
            default -> c;
        };
    }

    /** Farbton, Sättigung und Weichheit. */
    static int adjust(int c, double hueShift, double saturation, double gamma) {
        if (c == 0) return 0;
        double r = Colors.r(c) / 255.0, g = Colors.g(c) / 255.0, b = Colors.b(c) / 255.0;
        if (gamma != 1) {
            r = Math.pow(r, gamma);
            g = Math.pow(g, gamma);
            b = Math.pow(b, gamma);
        }
        if (hueShift != 0 || saturation != 1) {
            double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
            double h = 0;
            if (d > 0) {
                if (max == r) h = ((g - b) / d) / 6;
                else if (max == g) h = ((b - r) / d + 2) / 6;
                else h = ((r - g) / d + 4) / 6;
            }
            double s = max == 0 ? 0 : d / max;
            double[] rgb = new double[3];
            Colors.hsv(h + hueShift, Math.min(1, s * saturation), max, rgb);
            r = rgb[0];
            g = rgb[1];
            b = rgb[2];
        }
        return Colors.pack((int) (r * 255 + 0.5), (int) (g * 255 + 0.5), (int) (b * 255 + 0.5));
    }

    /** Legt eine Farbe auf die darunterliegende. */
    static int blend(int below, int top, String mode, double opacity) {
        int mixed = switch (mode) {
            case "add" -> Colors.add(below, top);
            case "max" -> Colors.max(below, top);
            case "multiply" -> Colors.pack(Colors.r(below) * Colors.r(top) / 255, Colors.g(below) * Colors.g(top) / 255, Colors.b(below) * Colors.b(top) / 255);
            case "subtract" -> Colors.pack(Colors.r(below) - Colors.r(top), Colors.g(below) - Colors.g(top), Colors.b(below) - Colors.b(top));
            default -> top;
        };
        return opacity >= 0.999 ? mixed : Colors.lerp(below, mixed, opacity);
    }

    static double position(CompiledLayout view, int i) {
        CompiledLayout.Led l = view.leds[i];
        return Colors.clamp01(l.x / Math.max(0.01, view.width));
    }
}
