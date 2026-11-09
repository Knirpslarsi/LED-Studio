package de.knirpslarsi.ledstudio.audio;

import de.knirpslarsi.ledstudio.util.Colors;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Musik-Effekte auf einem virtuellen LED-Streifen, im Stil von Visualisierern wie LedFx: Das Spektrum wird in Bänder
 * zerlegt, jedes Band steigt schnell an und fällt weich ab, und die Effekte zeichnen daraus Farbverläufe, die zur Mitte
 * gespiegelt und weichgezeichnet werden. Der Streifen wird danach auf die echten LEDs gelegt (Kette, jeder Lüfter
 * oder links nach rechts).
 */
final class StripFx {
    /** Auflösung des virtuellen Streifens; die LEDs lesen daraus mit Zwischenwerten. */
    static final int P = 96;
    private static final int H = P / 2;

    enum Kind {
        ENERGY("Energie"), SCROLL("Fluss"), WAVELENGTH("Spektrum-Welle"), POWER("Power"), RAIN("Regen"),
        MAGNITUDE("Pegel"), BAR("Takt-Balken"), MULTIBAR("Farbblöcke");

        final String label;

        Kind(String label) {
            this.label = label;
        }

        static Kind byLabel(String label) {
            for (Kind k : values()) if (k.label.equals(label)) return k;
            return null;
        }
    }

    static final List<Kind> CALM = List.of(Kind.WAVELENGTH, Kind.SCROLL, Kind.MAGNITUDE);
    static final List<Kind> GROOVE = List.of(Kind.ENERGY, Kind.SCROLL, Kind.WAVELENGTH, Kind.RAIN, Kind.BAR,
            Kind.MULTIBAR, Kind.POWER);
    static final List<Kind> BUILD = List.of(Kind.MAGNITUDE, Kind.SCROLL);
    static final List<Kind> DROP = List.of(Kind.POWER, Kind.ENERGY, Kind.RAIN, Kind.SCROLL);

    /** Was die Musik gerade macht, für alle Effekte eines Bildes gleich. */
    static final class Sound {
        /** 16 Bänder von tief nach hoch, 0..1, schnell steigend, weich fallend. */
        final double[] bands = new double[Music.SPECTRUM];
        /** Die drei Kanäle (0..1), je nach Einstellung z. B. Bass, Stimmen, Höhen. */
        final double[] ch = new double[3];
        /** 1, wenn im Kanal gerade ein neuer Schlag oder Einsatz kam. */
        final int[] hit = new int[3];
        double beat, tension, rate = 1;
        int[] gradient;
        // Aussehen
        boolean mirror = true, reverse;
        int blur = 1;
        double background, speed = 1, afterglow = 1, specFrom = 40, specTo = 16000;
    }

    private static final class Drop {
        double pos, age, life, width, strength;
        int color;
    }

    final Kind kind;
    final double rate;
    /** "chain", "fixture" oder "x": wie der Streifen auf die LEDs gelegt wird. */
    final String spread;
    final boolean flip;
    double weight;
    boolean leaving;
    final double[] r = new double[P], g = new double[P], b = new double[P];
    private final double[] sr = new double[H], sg = new double[H], sb = new double[H], sparks = new double[P], sparkTarget = new double[P];
    private final double[] tmp = new double[P];
    private final List<Drop> drops = new ArrayList<>();
    private final Random rnd;
    private double carry, kickGlow, kickTarget;

    StripFx(Kind kind, double rate, String spread, boolean flip, long seed) {
        this.kind = kind;
        this.rate = rate;
        this.spread = spread;
        this.flip = flip;
        this.rnd = new Random(seed);
    }

    private boolean reversed;

    private int color(int[] gradient, double pos) {
        pos = Colors.clamp01(pos);
        return Colors.gradient(gradient, flip != reversed ? 1 - pos : pos);
    }

    private static void put(double[] r, double[] g, double[] b, int i, int c, double v) {
        if (v <= 0) return;
        r[i] += Colors.r(c) / 255.0 * v;
        g[i] += Colors.g(c) / 255.0 * v;
        b[i] += Colors.b(c) / 255.0 * v;
    }

    /** Zeichnet ein Bild in r/g/b (0..1, kann über 1 gehen). */
    void render(Sound s, double dt) {
        java.util.Arrays.fill(r, 0);
        java.util.Arrays.fill(g, 0);
        java.util.Arrays.fill(b, 0);
        int[] grad = s.gradient;
        reversed = s.reverse;
        int cLow = color(grad, 0), cMid = color(grad, 0.5), cHigh = color(grad, 1);
        switch (kind) {
            case ENERGY -> {
                // Bass, Mitten und Höhen wachsen jeweils von der Mitte nach außen, ihre Farben mischen sich.
                double[] power = s.ch;
                int[] colors = {cLow, cMid, cHigh};
                for (int k = 0; k < 3; k++) {
                    double len = power[k] * H;
                    for (int d = 0; d < H; d++) put(r, g, b, H + d, colors[k], Colors.clamp01(len - d) * 0.85);
                }
                finish(s);
            }
            case SCROLL -> {
                // In der Mitte entsteht die Farbe des Moments und wandert nach außen, dabei verblasst sie.
                double fade = Math.exp(-dt / (0.6 * s.afterglow));
                for (int d = 0; d < H; d++) {
                    sr[d] *= fade;
                    sg[d] *= fade;
                    sb[d] *= fade;
                }
                double nr = 0, ng = 0, nb = 0;
                double[] power = s.ch;
                int[] colors = {cLow, cMid, cHigh};
                for (int k = 0; k < 3; k++) {
                    double v = 1.3 * Math.pow(Math.max(0, power[k] - 0.05) / 0.95, 1.2);
                    nr += Colors.r(colors[k]) / 255.0 * v;
                    ng += Colors.g(colors[k]) / 255.0 * v;
                    nb += Colors.b(colors[k]) / 255.0 * v;
                }
                carry += dt * 28 * rate * s.rate * s.speed;
                while (carry >= 1) {
                    carry -= 1;
                    for (int d = H - 1; d > 0; d--) {
                        sr[d] = sr[d - 1];
                        sg[d] = sg[d - 1];
                        sb[d] = sb[d - 1];
                    }
                }
                sr[0] = Math.max(sr[0], nr);
                sg[0] = Math.max(sg[0], ng);
                sb[0] = Math.max(sb[0], nb);
                for (int d = 0; d < H; d++) {
                    // Zwischen zwei Schritten weich verschieben.
                    double ar = d == 0 ? sr[0] : sr[d] + (sr[d - 1] - sr[d]) * carry;
                    double ag = d == 0 ? sg[0] : sg[d] + (sg[d - 1] - sg[d]) * carry;
                    double ab = d == 0 ? sb[0] : sb[d] + (sb[d - 1] - sb[d]) * carry;
                    r[H + d] = ar;
                    g[H + d] = ag;
                    b[H + d] = ab;
                }
                finish(s);
            }
            case WAVELENGTH -> {
                // Das Spektrum über den Streifen: Bass in der Mitte, Höhen außen, jede Stelle in ihrer Verlaufsfarbe.
                double lo = Math.max(40, Math.min(s.specFrom, s.specTo - 1)), hi = Math.min(16000, Math.max(s.specTo, lo + 1));
                for (int d = 0; d < H; d++) {
                    double f = d / (double) (H - 1);
                    // Stelle → Frequenz (logarithmisch) → Band mit Zwischenwerten
                    double hz = lo * Math.pow(hi / lo, f);
                    double band = Math.log(hz / 40) / Math.log(400) * Music.SPECTRUM - 0.5;
                    double v = bandAt(s.bands, band / (Music.SPECTRUM - 1));
                    put(r, g, b, H + d, color(grad, f), Math.min(1, 1.2 * Math.pow(v, 0.8)));
                }
                finish(s);
            }
            case POWER -> {
                // Der Bass füllt von der Mitte, Hi-Hats sprühen Funken, jede Kick lässt kurz alles aufleuchten.
                kickTarget = Math.max(kickTarget * Math.exp(-dt / (0.18 * s.afterglow)), s.hit[0] > 0 ? 1 : 0);
                kickGlow += (kickTarget - kickGlow) * (1 - Math.exp(-dt / 0.02));
                double len = s.ch[0] * H;
                for (int d = 0; d < H; d++) {
                    double v = Colors.clamp01(len - d) * (0.55 + 0.45 * s.ch[0]) + 0.12 * kickGlow;
                    put(r, g, b, H + d, color(grad, d / (double) H), v);
                }
                finish(s);
                int count = s.hit[2] * 3 + s.hit[1] * 5;
                for (int k = 0; k < count; k++) sparkTarget[rnd.nextInt(P)] = 1;
                double fadeSparks = Math.exp(-dt / (0.14 * s.afterglow)), rise = 1 - Math.exp(-dt / 0.015);
                for (int i = 0; i < P; i++) {
                    sparkTarget[i] *= fadeSparks;
                    sparks[i] += (sparkTarget[i] - sparks[i]) * (sparkTarget[i] > sparks[i] ? rise : 1);
                    if (sparks[i] > 0.01) put(r, g, b, i, Colors.lerp(cHigh, 0xFFFFFF, 0.6), sparks[i]);
                }
            }
            case RAIN -> {
                // Jeder Klang lässt einen Tropfen fallen, der sich ausbreitet: Bass groß, Mitten mittel, Höhen klein.
                spawn(s.hit[0], cLow, 0.8 * s.afterglow, 18, 1.3);
                spawn(s.hit[1], cMid, 0.55 * s.afterglow, 10, 1.1);
                spawn(s.hit[2], cHigh, 0.3 * s.afterglow, 4, 0.9);
                for (Drop d : drops) {
                    d.age += dt / d.life;
                    double w = 1 + d.width * Math.sqrt(Math.min(1, d.age));
                    double v = d.strength * Math.pow(Math.max(0, 1 - d.age), 1.5) * BeatMath.smooth01(d.age * 12);
                    int from = (int) Math.floor(d.pos - w), to = (int) Math.ceil(d.pos + w);
                    for (int i = Math.max(0, from); i <= Math.min(P - 1, to); i++) {
                        double edge = Math.max(0, 1 - Math.abs(i - d.pos) / w);
                        put(r, g, b, i, d.color, v * edge);
                    }
                }
                drops.removeIf(d -> d.age >= 1);
            }
            case MAGNITUDE -> {
                // Die Lautstärke füllt den Streifen von der Mitte; im Spannungsaufbau wächst er mit der Spannung.
                double level = Math.max(s.ch[0], s.tension);
                double len = level * H;
                for (int d = 0; d < H; d++) {
                    double v = Colors.clamp01(len - d) * (0.35 + 0.65 * d / (double) H);
                    double head = Math.max(0, 1 - Math.abs(len - d) / 3);
                    put(r, g, b, H + d, color(grad, d / (double) H), Math.max(v, head));
                }
                finish(s);
            }
            case BAR -> {
                // Ein Balken pendelt im Takt von Rand zu Rand und wechselt mit jedem Schlag die Farbe.
                double beat = s.beat * rate, p = frac(beat / 2) * 2, pos = BeatMath.smooth01(p < 1 ? p : 2 - p);
                double center = pos * (P - 1), width = P * 0.12;
                int c = color(grad, frac(Math.floor(beat) * 0.27));
                double v = 0.45 + 0.55 * s.ch[0];
                for (int i = 0; i < P; i++) put(r, g, b, i, c, v * Math.pow(Math.max(0, 1 - Math.abs(i - center) / width), 1.4));
            }
            case MULTIBAR -> {
                // Vier Farbblöcke, die auf jedem Schlag einen Block weiterrutschen; der Bass bestimmt die Helligkeit.
                double beat = s.beat * rate, whole = Math.floor(beat);
                double shift = whole + BeatMath.smooth01(Math.min(1, (beat - whole) * 3));
                int blocks = 4;
                double v = 0.3 + 0.7 * s.ch[0];
                for (int i = 0; i < P; i++) {
                    double seg = i / (double) P * blocks + shift;
                    int k = (int) Math.floor(seg);
                    double within = seg - k;
                    int c1 = color(grad, Math.floorMod(k, blocks) / (double) (blocks - 1));
                    int c2 = color(grad, Math.floorMod(k + 1, blocks) / (double) (blocks - 1));
                    // Weiche Kante zwischen den Blöcken.
                    double t = BeatMath.smooth01((within - 0.85) / 0.15);
                    put(r, g, b, i, Colors.lerp(c1, c2, t), v);
                }
            }
        }
        if (s.background > 0.002) {
            for (int i = 0; i < P; i++) put(r, g, b, i, color(grad, i / (double) (P - 1)), s.background);
        }
        for (int k = 0; k < s.blur; k++) {
            blur(r);
            blur(g);
            blur(b);
        }
    }

    private void spawn(int count, int color, double life, double width, double strength) {
        for (int k = 0; k < Math.min(count, 3); k++) {
            Drop d = new Drop();
            d.pos = rnd.nextDouble() * (P - 1);
            d.life = life;
            d.width = width;
            d.strength = strength;
            d.color = color;
            drops.add(d);
        }
        while (drops.size() > 40) drops.remove(0);
    }

    /** Gespiegelt: Mitte nach außen auf beide Seiten. Sonst wird die Hälfte über den ganzen Streifen gezogen. */
    private void finish(Sound s) {
        if (s.mirror) {
            mirror();
            return;
        }
        stretch(r);
        stretch(g);
        stretch(b);
    }

    private void stretch(double[] a) {
        for (int i = 0; i < P; i++) {
            double x = H + i / 2.0;
            int k = (int) Math.floor(x);
            int k2 = Math.min(P - 1, k + 1);
            tmp[i] = a[k] + (a[k2] - a[k]) * (x - k);
        }
        System.arraycopy(tmp, 0, a, 0, P);
    }

    /** Spiegelt die rechte Hälfte (Mitte nach außen) auf die linke. */
    private void mirror() {
        for (int d = 0; d < H; d++) {
            r[H - 1 - d] = r[H + d];
            g[H - 1 - d] = g[H + d];
            b[H - 1 - d] = b[H + d];
        }
    }

    /** Leichte Weichzeichnung über drei Pixel. */
    private void blur(double[] a) {
        for (int i = 0; i < P; i++) {
            double left = a[Math.max(0, i - 1)], right = a[Math.min(P - 1, i + 1)];
            tmp[i] = a[i] * 0.5 + (left + right) * 0.25;
        }
        System.arraycopy(tmp, 0, a, 0, P);
    }

    /** Band an einer Stelle 0..1 mit Zwischenwerten. */
    static double bandAt(double[] bands, double f) {
        double x = Colors.clamp01(f) * (bands.length - 1);
        int i = (int) Math.floor(x);
        if (i >= bands.length - 1) return bands[bands.length - 1];
        return bands[i] + (bands[i + 1] - bands[i]) * (x - i);
    }

    /** Liest den Streifen an der Stelle pos (0..1). */
    void sample(double pos, double[] rgb) {
        double x = Colors.clamp01(pos) * (P - 1);
        int i = (int) Math.floor(x);
        int j = Math.min(P - 1, i + 1);
        double f = x - i;
        rgb[0] = r[i] + (r[j] - r[i]) * f;
        rgb[1] = g[i] + (g[j] - g[i]) * f;
        rgb[2] = b[i] + (b[j] - b[i]) * f;
    }

    private static double frac(double x) {
        return x - Math.floor(x);
    }

    /** Kleine Helfer für weiche Übergänge. */
    static final class BeatMath {
        static double smooth01(double x) {
            x = x < 0 ? 0 : Math.min(1, x);
            return x * x * (3 - 2 * x);
        }
    }
}
