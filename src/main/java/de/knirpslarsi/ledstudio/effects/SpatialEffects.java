package de.knirpslarsi.ledstudio.effects;

import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.CompiledLayout.Led;
import de.knirpslarsi.ledstudio.util.Colors;
import de.knirpslarsi.ledstudio.util.Noise;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static de.knirpslarsi.ledstudio.effects.LegacyEffects.floorMod;
import static de.knirpslarsi.ledstudio.effects.ParamSpec.*;

/** Neue Effekte, die die echte Lage der LEDs nutzen. Deshalb lohnt sich ein sauberes Layout. */
final class SpatialEffects {
    private SpatialEffects() {
    }

    private static final String[] AXIS = {"x", "Links → rechts", "y", "Oben → unten", "chain", "Entlang der Kette"};

    private static double axis(String axis, CompiledLayout l, int i) {
        Led led = l.leds[i];
        return switch (axis) {
            case "y" -> led.y / Math.max(0.01, l.height);
            case "chain" -> (double) i / Math.max(1, l.leds.length - 1);
            default -> led.x / Math.max(0.01, l.width);
        };
    }

    /** Dreht jeden Lüfter wie einen Rotor; der Versatz lässt die Drehung durch die Kette wandern. */
    static final class Rotor extends LegacyEffects.Simple {
        Rotor() {
            super("rotor", "Rotor", "Leuchtende Flügel drehen sich in jedem Lüfter, versetzt über die Kette.",
                    List.of(color("color_a", "Farbe Flügel", "#A020FF"), color("color_b", "Farbe Spitze", "#00DDFF"),
                            color("background", "Hintergrund", "#000000"),
                            integer("arms", "Flügel", 2, 1, 6), num("speed", "Tempo (U/s)", 0.8, -5, 5, 0.05),
                            num("length", "Flügellänge", 0.6, 0.05, 1, 0.01), num("offset", "Versatz pro Lüfter", 0.12, 0, 1, 0.01),
                            bool("alternate", "Abwechselnd gegenläufig", false),
                            num("soft", "Weiche Vorderkante", 0.15, 0, 0.5, 0.01)));
        }

        @Override
        public void render(Frame f) {
            int a = f.p.color("color_a"), b = f.p.color("color_b"), bg = f.p.color("background");
            int arms = f.p.i("arms");
            double speed = f.p.d("speed"), len = f.p.d("length"), offset = f.p.d("offset");
            boolean alternate = f.p.b("alternate");
            double soft = f.p.d("soft");
            Led[] leds = f.layout.leds;
            for (int i = 0; i < leds.length; i++) {
                Led l = leds[i];
                double dir = alternate && l.fixture % 2 == 1 ? -1 : 1;
                double ph = floorMod((l.angle - dir * f.t * speed - l.fixture * offset) * arms, 1);
                double tail = 1 - ph;
                double v = tail < len ? 1 - tail / len : 0;
                if (soft > 0 && ph < soft) v = Math.max(v, 1 - ph / soft);
                v = v * v;
                f.out[i] = Colors.lerp(bg, Colors.lerp(a, b, Math.pow(v, 3)), v);
            }
        }
    }

    /** Ein Strudel um die Mitte des gesamten Layouts. */
    static final class Vortex extends LegacyEffects.Simple {
        Vortex() {
            super("vortex", "Vortex", "Farbspiralen drehen sich um die Mitte des gesamten Aufbaus.",
                    List.of(num("speed", "Tempo", 0.25, -3, 3, 0.01), num("twist", "Verdrillung", 3, 0, 12, 0.1),
                            integer("arms", "Arme", 3, 1, 8), num("hue_range", "Farbspektrum", 0.35, 0, 1, 0.01), hue(0.72)));
        }

        @Override
        public void render(Frame f) {
            double speed = f.p.d("speed"), twist = f.p.d("twist"), range = f.p.d("hue_range"), hue = f.p.d("hue");
            int arms = f.p.i("arms");
            double cx = f.layout.width / 2, cy = f.layout.height / 2;
            Led[] leds = f.layout.leds;
            for (int i = 0; i < leds.length; i++) {
                double dx = leds[i].x - cx, dy = leds[i].y - cy;
                double r = Math.hypot(dx, dy), ang = Math.atan2(dy, dx) / (2 * Math.PI);
                double w = floorMod((ang + r * twist * 0.25) * arms - f.t * speed * arms, 1);
                double v = Math.pow(0.5 + 0.5 * Math.cos(2 * Math.PI * w), 2);
                f.out[i] = Colors.hsv(hue + range * (Math.abs(2 * w - 1) - 0.5), 1, 0.08 + 0.92 * v);
            }
        }
    }

    static final class Plasma extends LegacyEffects.Simple {
        Plasma() {
            super("plasma", "Plasma", "Weich fließende Farbfelder, die sich überlagern.",
                    List.of(num("speed", "Tempo", 0.5, 0.02, 3, 0.01), num("scale", "Größe", 4, 0.5, 20, 0.1),
                            num("hue_range", "Farbspektrum", 0.6, 0.05, 1, 0.01), hue(0.72)));
        }

        @Override
        public void render(Frame f) {
            double s = f.p.d("scale"), t = f.t * f.p.d("speed"), range = f.p.d("hue_range"), hue = f.p.d("hue");
            Led[] leds = f.layout.leds;
            for (int i = 0; i < leds.length; i++) {
                double x = leds[i].x * s, y = leds[i].y * s;
                double v = Math.sin(x + t) + Math.sin(y * 1.3 - t * 1.1) + Math.sin((x + y) * 0.7 + t * 0.7)
                        + Math.sin(Math.hypot(x - s / 2, y - s / 2) * 1.5 - t * 1.4);
                v = v / 8 + 0.5;
                f.out[i] = Colors.hsv(hue + range * (v - 0.5), 1, 0.35 + 0.65 * v);
            }
        }
    }

    static final class Aurora extends LegacyEffects.Simple {
        Aurora() {
            super("aurora", "Aurora", "Polarlicht: Vorhänge aus Grün, Türkis und Violett ziehen langsam vorbei.",
                    List.of(color("low", "Farbe unten", "#00FF88"), color("mid", "Farbe Mitte", "#00B4FF"), color("high", "Farbe oben", "#8A2BFF"),
                            num("speed", "Tempo", 0.35, 0.02, 2, 0.01), num("scale", "Größe", 2.2, 0.3, 10, 0.1)));
        }

        @Override
        public void render(Frame f) {
            int[] stops = {f.p.color("low"), f.p.color("mid"), f.p.color("high")};
            double s = f.p.d("scale"), t = f.t * f.p.d("speed");
            Led[] leds = f.layout.leds;
            double h = Math.max(0.05, f.layout.height);
            for (int i = 0; i < leds.length; i++) {
                double x = leds[i].x, y = leds[i].y / h;
                double curtain = Noise.fbm(x * s + t * 0.6, t * 0.35, 1.5, 3);
                double band = Math.exp(-Math.pow((y - curtain) * 2.6, 2));
                double shimmer = 0.65 + 0.35 * Noise.at(x * s * 5, t * 2.2, 8);
                f.out[i] = Colors.scale(Colors.gradient(stops, Colors.clamp01(curtain * 1.5 - 0.25 + (1 - y) * 0.2)), Colors.clamp01(band * shimmer * 1.3));
            }
        }
    }

    /** Wellenringe breiten sich von zufälligen Punkten über den ganzen Aufbau aus. */
    static final class Ripple extends LegacyEffects.Simple {
        private final Random rnd = new Random();
        /** x, y, Alter, Radius, Farbton. */
        private final List<double[]> drops = new ArrayList<>();
        private double wait;

        Ripple() {
            super("ripple", "Wellenringe", "Tropfen fallen auf den Aufbau, Ringe laufen über alle Bauteile.",
                    List.of(color("color", "Farbe", "#00DDFF"), bool("rainbow", "Jeder Tropfen eigene Farbe", false),
                            num("rate", "Tropfen pro Sekunde", 0.8, 0.05, 6, 0.05), num("speed", "Tempo", 0.6, 0.05, 3, 0.01),
                            num("width", "Ringbreite", 0.1, 0.01, 0.5, 0.005), num("decay", "Ausklingen", 1.4, 0.1, 6, 0.05)));
        }

        @Override
        public void reset(CompiledLayout layout) {
            drops.clear();
            wait = 0;
        }

        @Override
        public void render(Frame f) {
            CompiledLayout l = f.layout;
            double speed = f.p.d("speed"), width = f.p.d("width"), decay = f.p.d("decay");
            wait -= f.dt;
            if (wait <= 0) {
                wait = (0.5 + rnd.nextDouble()) / f.p.d("rate");
                drops.add(new double[]{rnd.nextDouble() * l.width, rnd.nextDouble() * l.height, 0, 0, rnd.nextDouble()});
                if (drops.size() > 16) drops.remove(0);
            }
            for (double[] d : drops) {
                d[2] += f.dt;
                d[3] += f.dt * speed;
            }
            drops.removeIf(d -> Math.exp(-d[2] * decay) < 0.003);
            int base = f.p.color("color");
            boolean rainbow = f.p.b("rainbow");
            Led[] leds = l.leds;
            for (int i = 0; i < leds.length; i++) {
                int c = 0;
                for (double[] d : drops) {
                    double dist = Math.hypot(leds[i].x - d[0], leds[i].y - d[1]);
                    // Der Ring blendet beim Entstehen ein, statt auf den LEDs in der Nähe aufzublitzen.
                    double v = Math.exp(-Math.pow((dist - d[3]) / width, 2)) * Math.exp(-d[2] * decay) * Math.min(1, d[2] / 0.15);
                    if (v > 0.003) c = Colors.add(c, Colors.scale(rainbow ? Colors.hsv(d[4], 1, 1) : base, v));
                }
                f.out[i] = c;
            }
        }
    }

    static final class Meteor extends LegacyEffects.Simple {
        private final Random rnd = new Random();
        private double[] heat = new double[0];
        private final List<double[]> meteors = new ArrayList<>();

        Meteor() {
            super("meteor", "Meteorregen", "Meteore ziehen durch die Kette und zerfallen in glühende Reste.",
                    List.of(hue(0.77), bool("rainbow", "Regenbogen", false), num("speed", "Tempo (LEDs/s)", 28, 2, 150, 1),
                            num("rate", "Meteore pro Sekunde", 1.2, 0.1, 8, 0.1), num("decay", "Nachglühen", 2.5, 0.2, 12, 0.1)));
        }

        @Override
        public void reset(CompiledLayout layout) {
            heat = new double[layout.leds.length];
            meteors.clear();
        }

        @Override
        public void render(Frame f) {
            int n = f.out.length;
            if (heat.length != n) heat = new double[n];
            double fade = Math.exp(-f.dt * f.p.d("decay"));
            for (int i = 0; i < n; i++) heat[i] *= fade * Math.exp(-f.dt * rnd.nextDouble() * 1.2);
            if (rnd.nextDouble() < f.p.d("rate") * f.dt) meteors.add(new double[]{-1, 0.6 + rnd.nextDouble() * 0.8});
            double speed = f.p.d("speed");
            meteors.removeIf(m -> {
                double before = m[0];
                m[0] += speed * m[1] * f.dt;
                int head = (int) Math.floor(m[0]);
                for (int i = Math.max(0, (int) Math.floor(before)); i <= Math.min(n - 1, head); i++) heat[i] = 1;
                // Der Kopf blendet über zwei LEDs ein, damit er auch langsam nicht von LED zu LED springt.
                double frac = m[0] - Math.floor(m[0]);
                if (head + 1 >= 0 && head + 1 < n) heat[head + 1] = Math.max(heat[head + 1], 0.5 + 0.5 * frac);
                if (head + 2 >= 0 && head + 2 < n) heat[head + 2] = Math.max(heat[head + 2], 0.5 * frac);
                return m[0] >= n + 2;
            });
            double hue = f.p.d("hue");
            boolean rainbow = f.p.b("rainbow");
            for (int i = 0; i < n; i++) {
                double v = heat[i];
                double h = rainbow ? (double) i / Math.max(1, n) + f.rawT * 0.1 : hue;
                f.out[i] = Colors.hsv(h, 1 - Math.pow(v, 3) * 0.7, v * v);
            }
        }
    }

    static final class Radar extends LegacyEffects.Simple {
        Radar() {
            super("radar", "Radar", "Ein Strahl dreht sich um die Mitte und lässt eine Spur zurück.",
                    List.of(hue(0.42), num("speed", "Umdrehungen/s", 0.35, -3, 3, 0.01), num("trail", "Spurlänge", 0.45, 0.02, 1, 0.01),
                            num("base", "Grundlicht", 0.03, 0, 0.5, 0.01), num("soft", "Weiche Vorderkante", 0.05, 0, 0.3, 0.005)));
        }

        @Override
        public void render(Frame f) {
            double hue = f.p.d("hue"), speed = f.p.d("speed"), trail = f.p.d("trail"), base = f.p.d("base"), soft = f.p.d("soft");
            double cx = f.layout.width / 2, cy = f.layout.height / 2;
            Led[] leds = f.layout.leds;
            for (int i = 0; i < leds.length; i++) {
                double ang = Math.atan2(leds[i].y - cy, leds[i].x - cx) / (2 * Math.PI);
                double d = floorMod(f.t * speed - ang, 1);
                double v = d < trail ? Math.pow(1 - d / trail, 2.2) : 0;
                if (soft > 0 && d > 1 - soft) v = Math.max(v, (d - (1 - soft)) / soft);
                f.out[i] = Colors.hsv(hue, 1 - v * v * 0.6, base + (1 - base) * v);
            }
        }
    }

    static final class Scanner extends LegacyEffects.Simple {
        private double[] glow = new double[0];

        Scanner() {
            super("scanner", "Scanner", "Ein Lichtbalken fährt über den Aufbau hin und her, mit Nachzieher.",
                    List.of(hue(0.0), choice("axis", "Richtung", "x", AXIS), num("speed", "Tempo", 0.6, 0.05, 4, 0.01),
                            num("width", "Breite", 0.12, 0.01, 1, 0.005), num("tail", "Nachzieher", 0.35, 0, 1.5, 0.01)));
        }

        @Override
        public void reset(CompiledLayout layout) {
            glow = new double[layout.leds.length];
        }

        @Override
        public void render(Frame f) {
            if (glow.length != f.out.length) glow = new double[f.out.length];
            double hue = f.p.d("hue"), w = f.p.d("width"), speed = Math.abs(f.p.d("speed"));
            String axis = f.p.s("axis");
            double ph = floorMod(f.t * f.p.d("speed"), 2);
            double c = (ph <= 1 ? ph : 2 - ph) * (1 + 2 * w) - w;
            // Der Nachzieher klingt als Nachglühen ab. So bleibt er auch beim Umkehren am Rand ohne Sprung.
            double keep = Math.exp(-f.dt * 3 * speed / Math.max(0.01, f.p.d("tail")));
            for (int i = 0; i < f.out.length; i++) {
                double d = axis(axis, f.layout, i) - c;
                double head = Math.abs(d) < w ? 0.5 + 0.5 * Math.cos(Math.PI * d / w) : 0;
                glow[i] = Math.max(head, glow[i] * keep);
                f.out[i] = Colors.hsv(hue, 1 - Math.pow(glow[i], 8) * 0.7, glow[i]);
            }
        }
    }

    static final class Lava extends LegacyEffects.Simple {
        Lava() {
            super("lava", "Lava", "Zähflüssige, glühende Masse.",
                    List.of(color("dark", "Dunkel", "#200000"), color("mid", "Glut", "#FF3000"), color("hot", "Heiß", "#FFD040"),
                            num("speed", "Tempo", 0.2, 0.01, 2, 0.01), num("scale", "Größe", 3.5, 0.3, 15, 0.1)));
        }

        @Override
        public void render(Frame f) {
            int[] stops = {f.p.color("dark"), f.p.color("mid"), f.p.color("hot")};
            double s = f.p.d("scale"), t = f.t * f.p.d("speed");
            Led[] leds = f.layout.leds;
            for (int i = 0; i < leds.length; i++) {
                double warpX = Noise.at(leds[i].x * s, leds[i].y * s, t) * 1.4;
                double v = Noise.fbm(leds[i].x * s + warpX, leds[i].y * s + t * 0.5, t * 0.7, 3);
                f.out[i] = Colors.gradient(stops, Colors.clamp01((v - 0.28) * 2.3));
            }
        }
    }

    static final class Twinkle extends LegacyEffects.Simple {
        private final Random rnd = new Random();
        private double[] level = new double[0], hues = new double[0];
        private boolean[] rising = new boolean[0];

        Twinkle() {
            super("twinkle", "Sternenhimmel", "Einzelne LEDs funkeln auf und verglimmen.",
                    List.of(hue(0.6), bool("rainbow", "Bunt (Konfetti)", false), num("rate", "Funkeln pro LED/s", 0.25, 0.01, 3, 0.01),
                            num("decay", "Verglimmen", 2, 0.2, 10, 0.1), num("base", "Grundlicht", 0.03, 0, 0.5, 0.01)));
        }

        @Override
        public void reset(CompiledLayout layout) {
            level = new double[layout.leds.length];
            hues = new double[layout.leds.length];
            rising = new boolean[layout.leds.length];
        }

        @Override
        public void render(Frame f) {
            int n = f.out.length;
            if (level.length != n) {
                level = new double[n];
                hues = new double[n];
                rising = new boolean[n];
            }
            double rate = f.p.d("rate"), fade = Math.exp(-f.dt * f.p.d("decay")), hue = f.p.d("hue"), base = f.p.d("base");
            boolean rainbow = f.p.b("rainbow");
            for (int i = 0; i < n; i++) {
                if (rising[i]) {
                    level[i] += f.dt / 0.12;
                    if (level[i] >= 1) {
                        level[i] = 1;
                        rising[i] = false;
                    }
                } else {
                    level[i] *= fade;
                    if (level[i] < 0.05 && rnd.nextDouble() < rate * f.dt) {
                        rising[i] = true;
                        hues[i] = rnd.nextDouble();
                    }
                }
                double v = base + (1 - base) * level[i];
                f.out[i] = Colors.hsv(rainbow ? hues[i] : hue, rainbow ? 1 : 1 - level[i] * level[i] * 0.85, v);
            }
        }
    }

    static final class MatrixRain extends LegacyEffects.Simple {
        private double[] drops = new double[0], speeds = new double[0];
        private final Random rnd = new Random();

        MatrixRain() {
            super("matrix_rain", "Matrix-Regen", "Tropfen fallen in Spalten durch den Aufbau.",
                    List.of(color("color", "Farbe", "#00FF50"), num("speed", "Tempo", 0.7, 0.05, 4, 0.01), integer("columns", "Spalten", 18, 2, 80),
                            num("tail", "Schweif", 0.4, 0.05, 2, 0.01)));
        }

        @Override
        public void render(Frame f) {
            int cols = f.p.i("columns");
            if (drops.length != cols) {
                drops = new double[cols];
                speeds = new double[cols];
                for (int i = 0; i < cols; i++) {
                    drops[i] = rnd.nextDouble() * 2;
                    speeds[i] = 0.6 + rnd.nextDouble() * 0.8;
                }
            }
            double h = Math.max(0.05, f.layout.height), w = Math.max(0.05, f.layout.width), tail = f.p.d("tail"), speed = f.p.d("speed");
            for (int i = 0; i < cols; i++) {
                drops[i] += speeds[i] * speed * f.dt;
                if (drops[i] > 1 + tail + 0.2) {
                    drops[i] = -rnd.nextDouble() * 0.6;
                    speeds[i] = 0.6 + rnd.nextDouble() * 0.8;
                }
            }
            int color = f.p.color("color");
            Led[] leds = f.layout.leds;
            for (int i = 0; i < leds.length; i++) {
                int col = Math.min(cols - 1, (int) (leds[i].x / w * cols));
                double d = drops[col] - leds[i].y / h;
                double v = d >= 0 && d < tail ? Math.pow(1 - d / tail, 2) : d < 0 && d > -0.05 ? 1 + d / 0.05 : 0;
                double white = 0.7 * Math.max(0, 1 - Math.abs(d) / 0.06);
                f.out[i] = Colors.scale(Colors.lerp(color, 0xFFFFFF, white), v);
            }
        }
    }

    static final class GradientFlow extends LegacyEffects.Simple {
        GradientFlow() {
            super("gradient_flow", "Farbverlauf", "Frei wählbarer Verlauf über den Aufbau, optional wandernd.",
                    List.of(color("a", "Farbe 1", "#A020FF"), color("b", "Farbe 2", "#00DDFF"), color("c", "Farbe 3", "#FF2080"),
                            bool("three", "Dritte Farbe", true), choice("axis", "Richtung", "x", AXIS),
                            num("speed", "Tempo", 0.15, -2, 2, 0.01), num("repeat", "Wiederholungen", 1, 0.25, 8, 0.05)));
        }

        @Override
        public void render(Frame f) {
            int[] stops = f.p.b("three") ? new int[]{f.p.color("a"), f.p.color("b"), f.p.color("c"), f.p.color("a")}
                    : new int[]{f.p.color("a"), f.p.color("b"), f.p.color("a")};
            String axis = f.p.s("axis");
            double repeat = f.p.d("repeat"), speed = f.p.d("speed");
            for (int i = 0; i < f.out.length; i++) {
                f.out[i] = Colors.gradient(stops, floorMod(axis(axis, f.layout, i) * repeat + f.t * speed, 1));
            }
        }
    }

    static final class ColorWheel extends LegacyEffects.Simple {
        ColorWheel() {
            super("color_wheel", "Farbrad", "Jedes Bauteil ein eigenes rotierendes Farbrad, oder eins über alles.",
                    List.of(num("speed", "Tempo", 0.25, -3, 3, 0.01), bool("global", "Ein Rad über den ganzen Aufbau", false),
                            num("offset", "Versatz pro Bauteil", 0.1, 0, 1, 0.01)));
        }

        @Override
        public void render(Frame f) {
            double speed = f.p.d("speed"), offset = f.p.d("offset");
            boolean global = f.p.b("global");
            double cx = f.layout.width / 2, cy = f.layout.height / 2;
            Led[] leds = f.layout.leds;
            for (int i = 0; i < leds.length; i++) {
                double a = global ? Math.atan2(leds[i].y - cy, leds[i].x - cx) / (2 * Math.PI)
                        : leds[i].angle + leds[i].fixture * offset;
                f.out[i] = Colors.hsv(a + f.t * speed, 1, 1);
            }
        }
    }

    static final class Heartbeat extends LegacyEffects.Simple {
        Heartbeat() {
            super("heartbeat", "Herzschlag", "Doppelschlag, der sich von der Mitte ausbreitet.",
                    List.of(hue(0.97), num("speed", "Schläge/s", 0.9, 0.1, 3, 0.01), num("spread", "Ausbreitung", 0.35, 0, 1.5, 0.01),
                            num("base", "Grundlicht", 0.04, 0, 0.5, 0.01)));
        }

        private static double beat(double p) {
            return Math.exp(-Math.pow((p - 0.1) / 0.05, 2)) + 0.65 * Math.exp(-Math.pow((p - 0.32) / 0.07, 2));
        }

        @Override
        public void render(Frame f) {
            double hue = f.p.d("hue"), speed = f.p.d("speed"), spread = f.p.d("spread"), base = f.p.d("base");
            double cx = f.layout.width / 2, cy = f.layout.height / 2;
            Led[] leds = f.layout.leds;
            for (int i = 0; i < leds.length; i++) {
                double d = Math.hypot(leds[i].x - cx, leds[i].y - cy);
                double v = beat(floorMod(f.t * speed - d * spread, 1));
                f.out[i] = Colors.hsv(hue, 1, base + (1 - base) * Colors.clamp01(v));
            }
        }
    }

    static List<java.util.function.Supplier<Effect>> all() {
        return Arrays.asList(Rotor::new, Vortex::new, Plasma::new, Aurora::new, Ripple::new, Meteor::new, Radar::new,
                Scanner::new, Lava::new, Twinkle::new, MatrixRain::new, GradientFlow::new, ColorWheel::new, Heartbeat::new);
    }
}
