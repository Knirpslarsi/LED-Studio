package de.knirpslarsi.ledstudio.effects;

import de.knirpslarsi.ledstudio.layout.CompiledLayout.Led;
import de.knirpslarsi.ledstudio.util.Colors;
import de.knirpslarsi.ledstudio.util.Noise;

import java.util.List;

import static de.knirpslarsi.ledstudio.effects.ParamSpec.*;

/** Die Effekte der alten Python-Version. Rechnen gleich, laufen aber über das echte Layout statt fester Verkabelung. */
final class LegacyEffects {
    private LegacyEffects() {
    }

    static final class EdgeSweep extends Effect {
        @Override
        public String id() {
            return "edge_sweep";
        }

        @Override
        public String title() {
            return "Balken über die Lüfterränder";
        }

        @Override
        public String blurb() {
            return "Ein weicher Balken fährt als ein Lauf über die obere und untere Kante aller Lüfter.";
        }

        @Override
        public List<ParamSpec> params() {
            return List.of(
                    choice("gradient_mode", "Farbeffekt", "off", "off", "Einfarbig / Regenbogen", "bar", "Verlauf im Balken",
                            "layout", "Verlauf über alle Lüfter", "flow", "Wandernder Farbverlauf")
                            .help("Der Verlauf überblendet zwei oder optional drei frei gewählte Farben."),
                    color("gradient_start", "Verlauf: Startfarbe", "#A020FF"),
                    color("gradient_end", "Verlauf: Endfarbe", "#00DDFF"),
                    bool("gradient_three", "Dritte Farbe verwenden", false),
                    color("gradient_middle", "Verlauf: Mittelfarbe", "#FF2080"),
                    num("gradient_speed", "Verlaufstempo", 0.15, 0, 1, 0.01).help("Nur für den wandernden Verlauf; 0 hält die Farben an."),
                    bool("background_enabled", "Fester Hintergrund", false),
                    color("background_color", "Hintergrundfarbe", "#301050"),
                    num("background_brightness", "Hintergrundhelligkeit", 0.15, 0, 1, 0.01),
                    hue(0.77),
                    bool("rainbow", "Regenbogen", false),
                    num("speed", "Tempo (Lüfter/s)", 2.0, 0.05, 12, 0.05),
                    num("width", "Balkenbreite (Lüfter)", 2.4, 0.2, 10, 0.05),
                    integer("bars", "Anzahl Balken", 1, 1, 6),
                    num("spacing", "Balkenabstand (Lüfter)", 3.5, 0.5, 12, 0.1),
                    num("gamma", "Weichheit", 1.8, 0.2, 5, 0.05),
                    num("ease", "Sanft anfahren", 0.5, 0, 1, 0.01),
                    bool("bounce", "Hin und zurück", true),
                    num("pause", "Pause an den Enden", 0.6, 0, 8, 0.05),
                    num("gap", "Pause vor Neustart", 0.5, 0, 10, 0.05),
                    num("sides", "Seitliche LEDs", 0.0, 0, 1, 0.01),
                    num("hole", "Loch im Balken", 0.0, 0, 1, 0.01),
                    bool("reverse", "Richtung umkehren", false));
        }

        private static double smooth(double x) {
            x = Colors.clamp01(x);
            return x * x * (3 - 2 * x);
        }

        private static double bar(double x, double hole) {
            if (x >= 1.0) return 0;
            double bell = 0.5 * (1 + Math.cos(Math.PI * x));
            if (hole <= 0 || x >= 0.5) return bell;
            return bell * (1 - hole * 0.5 * (1 + Math.cos(2 * Math.PI * x)));
        }

        private static double norm(double hole) {
            double max = 0;
            for (int i = 0; i <= 200; i++) max = Math.max(max, bar(i / 200.0, hole));
            return max == 0 ? 1 : max;
        }

        @Override
        public void render(Frame f) {
            Params p = f.p;
            int fans = f.layout.fanCount;
            if (fans == 0) return;
            double speed = p.d("speed"), width = p.d("width"), gamma = p.d("gamma"), ease = p.d("ease"),
                    pause = p.d("pause"), gap = p.d("gap"), hue = p.d("hue"), sides = p.d("sides"),
                    hole = p.d("hole"), gs = p.d("gradient_speed"), spacing = p.d("spacing");
            boolean bounce = p.b("bounce"), rainbow = p.b("rainbow"), reverse = p.b("reverse");
            String mode = p.s("gradient_mode");
            int bars = Math.max(1, p.i("bars"));
            double hw = Math.max(width, 0.2) / 2;
            double norm = norm(hole);
            double c;
            if (bounce) {
                double span = fans + width + pause;
                double ph = floorMod(f.t * speed / span, 2.0);
                double q = ph <= 1.0 ? ph : 2.0 - ph;
                q += ease * (smooth(q) - q);
                c = -hw - pause / 2 + q * span;
                c = Math.max(-hw, Math.min(fans + hw, c));
            } else {
                c = floorMod(f.t * speed, fans + width + gap) - hw;
            }
            int[] stops = p.b("gradient_three")
                    ? new int[]{p.color("gradient_start"), p.color("gradient_middle"), p.color("gradient_end")}
                    : new int[]{p.color("gradient_start"), p.color("gradient_end")};
            double bgR = 0, bgG = 0, bgB = 0;
            if (p.b("background_enabled")) {
                int bg = p.color("background_color");
                double b = Colors.clamp01(p.d("background_brightness"));
                bgR = Colors.r(bg) * b;
                bgG = Colors.g(bg) * b;
                bgB = Colors.b(bg) * b;
            }
            double[] rgb = new double[3];
            Led[] leds = f.layout.leds;
            for (int i = 0; i < leds.length; i++) {
                Led l = leds[i];
                double pos = l.fixture + (1 + l.u) / 2;
                if (reverse) pos = fans - pos;
                double v = 0, center = c;
                for (int j = 0; j < bars; j++) {
                    double cj = c - j * spacing;
                    double vj = bar(Math.abs(pos - cj) / hw, hole) / norm;
                    if (vj > v) {
                        v = vj;
                        center = cj;
                    }
                }
                if (!l.rim) v *= sides;
                v = Math.pow(v, gamma);
                double mix;
                switch (mode) {
                    case "bar" -> mix = Colors.clamp01((pos - center + hw) / (2 * hw));
                    case "layout" -> mix = pos / Math.max(1, fans);
                    case "flow" -> {
                        double phase = floorMod(pos / Math.max(1, fans) + f.rawT * gs, 2);
                        mix = phase <= 1 ? phase : 2 - phase;
                    }
                    default -> mix = -1;
                }
                if (mix < 0) {
                    Colors.hsv(hue + (rainbow ? pos / fans + f.rawT * 0.05 : 0), 1, Colors.clamp01(v), rgb);
                    int fr = (int) (rgb[0] * 255 + 0.5), fg = (int) (rgb[1] * 255 + 0.5), fb = (int) (rgb[2] * 255 + 0.5);
                    f.out[i] = Colors.pack((int) (fr + bgR * (1 - v) + 0.5), (int) (fg + bgG * (1 - v) + 0.5), (int) (fb + bgB * (1 - v) + 0.5));
                } else {
                    double x = Colors.clamp01(mix) * (stops.length - 1);
                    int s = Math.min(stops.length - 2, (int) x);
                    double ratio = x - s;
                    double fr = Colors.r(stops[s]) + (Colors.r(stops[s + 1]) - Colors.r(stops[s])) * ratio;
                    double fg = Colors.g(stops[s]) + (Colors.g(stops[s + 1]) - Colors.g(stops[s])) * ratio;
                    double fb = Colors.b(stops[s]) + (Colors.b(stops[s + 1]) - Colors.b(stops[s])) * ratio;
                    f.out[i] = Colors.pack((int) (fr * v + bgR * (1 - v) + 0.5), (int) (fg * v + bgG * (1 - v) + 0.5), (int) (fb * v + bgB * (1 - v) + 0.5));
                }
            }
        }
    }

    static double floorMod(double a, double b) {
        return a - Math.floor(a / b) * b;
    }

    static final class RimChase extends Effect {
        @Override
        public String id() {
            return "rim_chase";
        }

        @Override
        public String title() {
            return "Lauflicht über die Ränder";
        }

        @Override
        public String blurb() {
            return "Lichtpunkt mit Schweif läuft LED für LED durch die Kette; oder ganze Lüfter nacheinander.";
        }

        @Override
        public List<ParamSpec> params() {
            return List.of(hue(0.55), bool("rainbow", "Regenbogen", false),
                    choice("mode", "Modus", "led", "led", "LED für LED", "fan", "Ganze Lüfter"),
                    num("speed", "Tempo (LEDs/s)", 10, 0.5, 80, 0.5),
                    num("tail", "Schweiflänge", 5, 1, 40, 0.5),
                    num("gap", "Pause am Ende", 8, 0, 60, 0.5));
        }

        @Override
        public void render(Frame f) {
            Params p = f.p;
            Led[] leds = f.layout.leds;
            int total = leds.length;
            if (total == 0) return;
            double speed = p.d("speed"), tail = Math.max(0.01, p.d("tail")), gap = p.d("gap"), hue = p.d("hue");
            boolean rainbow = p.b("rainbow"), fanMode = "fan".equals(p.s("mode"));
            double head = floorMod(f.t * speed, total + gap);
            for (int i = 0; i < total; i++) {
                double v;
                if (fanMode) {
                    double d = head / Math.max(1, leds[i].m) - leds[i].fixture;
                    v = d < 0 ? 0 : d < 1 ? 1 : Math.max(0, 2 - d);
                } else {
                    double d = head - i;
                    v = d >= 0 && d < tail ? Math.pow(1 - d / tail, 2) : 0;
                }
                f.out[i] = Colors.hsvFloor(hue + (rainbow ? (double) i / total + f.rawT * 0.1 : 0), 1, v);
            }
        }
    }

    static final class Rainbow extends Simple {
        Rainbow() {
            super("rainbow", "Regenbogen", "Das ganze Spektrum wandert durch die Kette.",
                    List.of(num("speed", "Tempo", 0.2, 0.01, 3, 0.01), bool("spatial", "Nach Position statt Kette", false),
                            num("repeat", "Wiederholungen", 1, 0.1, 10, 0.1), num("sat", "Sättigung", 1, 0, 1, 0.01)));
        }

        @Override
        public void render(Frame f) {
            Led[] leds = f.layout.leds;
            double speed = f.p.d("speed");
            boolean spatial = f.p.b("spatial");
            double repeat = f.p.d("repeat"), sat = f.p.d("sat");
            for (int i = 0; i < leds.length; i++) {
                double offset = spatial ? leds[i].x + leds[i].y * 0.5 : (double) i / leds.length;
                f.out[i] = Colors.hsvFloor(f.t * speed + offset * repeat, sat, 1);
            }
        }
    }

    static final class Breathing extends Simple {
        Breathing() {
            super("breathing", "Atmen", "Sanftes Ein- und Ausblenden in einer Farbe.",
                    List.of(hue(0.6), num("speed", "Tempo", 0.5, 0.02, 4, 0.01), num("min", "Dunkelste Stufe", 0, 0, 1, 0.01),
                            num("max", "Hellste Stufe", 1, 0, 1, 0.01), num("spread", "Versatz pro Bauteil", 0, 0, 1, 0.01)));
        }

        @Override
        public void render(Frame f) {
            double speed = f.p.d("speed"), min = f.p.d("min"), max = f.p.d("max"), spread = f.p.d("spread"), hue = f.p.d("hue");
            Led[] leds = f.layout.leds;
            for (int i = 0; i < leds.length; i++) {
                double v = (Math.sin(2 * Math.PI * (speed * f.t - leds[i].fixture * spread)) + 1) / 2;
                f.out[i] = Colors.hsvFloor(hue, 1, min + (max - min) * v);
            }
        }
    }

    static final class Comet extends Simple {
        Comet() {
            super("comet", "Komet", "Heller Kopf mit langem Schweif, einmal rundherum.",
                    List.of(hue(0.55), num("speed", "Tempo (LEDs/s)", 12, 0.5, 100, 0.5), num("tail", "Schweiflänge", 6, 1, 60, 0.5),
                            integer("count", "Anzahl Kometen", 1, 1, 12)));
        }

        @Override
        public void render(Frame f) {
            int n = f.out.length;
            if (n == 0) return;
            double head = floorMod(f.t * f.p.d("speed"), n), tail = f.p.d("tail"), hue = f.p.d("hue");
            double spacing = (double) n / Math.max(1, f.p.i("count"));
            for (int i = 0; i < n; i++) {
                double d = floorMod(head - i, spacing);
                f.out[i] = Colors.hsvFloor(hue, 1, Math.pow(Math.max(0, 1 - d / tail), 2));
            }
        }
    }

    static final class Wave extends Simple {
        Wave() {
            super("wave", "Welle", "Helligkeitswellen mit leichtem Farbspiel.",
                    List.of(hue(0.75), num("speed", "Tempo", 1, 0.05, 6, 0.05), num("width", "Wellenlänge", 8, 1, 40, 0.5)));
        }

        @Override
        public void render(Frame f) {
            double hue = f.p.d("hue"), speed = f.p.d("speed"), width = f.p.d("width");
            for (int i = 0; i < f.out.length; i++) {
                f.out[i] = Colors.hsvFloor(hue + 0.1 * Math.sin(i / width + f.t), 1,
                        (Math.sin(i / width * 2 * Math.PI / 3 - f.t * speed * 3) + 1) / 2);
            }
        }
    }

    static final class Fire extends Simple {
        Fire() {
            super("fire", "Feuer", "Flammen steigen im Layout von unten nach oben.",
                    List.of(num("speed", "Tempo", 1, 0.1, 4, 0.05), num("scale", "Flammengröße", 3, 0.5, 10, 0.1),
                            num("hue", "Farbton", 0.03, 0, 0.2, 0.005)));
        }

        @Override
        public void render(Frame f) {
            double speed = f.p.d("speed"), scale = f.p.d("scale"), hue = f.p.d("hue");
            Led[] leds = f.layout.leds;
            double h = Math.max(0.05, f.layout.height);
            for (int i = 0; i < leds.length; i++) {
                Led l = leds[i];
                double up = 1 - l.y / h;
                double heat = Noise.fbm(l.x * scale * 2, l.y * scale * 2 + f.t * speed * 2.2, f.t * speed * 0.6, 3);
                double v = Colors.clamp01((heat * 1.7 - 0.25) * (1.25 - up * 0.8));
                f.out[i] = Colors.hsv(hue + v * 0.08, 1 - Math.pow(v, 4) * 0.5, Math.pow(v, 1.4));
            }
        }
    }

    static final class Static extends Simple {
        Static() {
            super("static", "Statisch", "Eine feste Farbe.",
                    List.of(bool("use_color", "Genaue Farbe statt Farbton", false), color("color", "Genaue Farbe", "#A020FF"),
                            hue(0.0), num("sat", "Sättigung", 1, 0, 1, 0.01), num("val", "Helligkeit", 1, 0, 1, 0.01)));
        }

        @Override
        public void render(Frame f) {
            int c = f.p.b("use_color") ? f.p.color("color") : Colors.hsvFloor(f.p.d("hue"), f.p.d("sat"), f.p.d("val"));
            for (int i = 0; i < f.out.length; i++) f.out[i] = c;
        }
    }

    static final class CounterWave extends Simple {
        CounterWave() {
            super("my_effect", "Gegenwelle", "Zwei gegenläufige Wellen: eine rot, eine blau.",
                    List.of(num("speed", "Tempo", 1.5, 0.05, 10, 0.05)));
        }

        @Override
        public void render(Frame f) {
            double speed = f.p.d("speed");
            for (int i = 0; i < f.out.length; i++) {
                double red = (Math.sin(i * 0.5 - f.t * speed) + 1) / 2, blue = (Math.sin(i * 0.5 + f.t * speed) + 1) / 2;
                f.out[i] = Colors.pack((int) (255 * red), 0, (int) (255 * blue));
            }
        }
    }

    abstract static class Simple extends Effect {
        private final String id, title, blurb;
        private final List<ParamSpec> params;

        Simple(String id, String title, String blurb, List<ParamSpec> params) {
            this.id = id;
            this.title = title;
            this.blurb = blurb;
            this.params = params;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public String blurb() {
            return blurb;
        }

        @Override
        public List<ParamSpec> params() {
            return params;
        }
    }
}
