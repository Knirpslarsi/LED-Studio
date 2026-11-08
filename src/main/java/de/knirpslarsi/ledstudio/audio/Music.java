package de.knirpslarsi.ledstudio.audio;

import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.CompiledLayout.Led;
import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.util.Colors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Musik-Animationen. Ein erkannter Beat startet einen Lauf. Mit „überlagern“ startet jeder Beat einen eigenen
 * Lauf, auch wenn der vorige noch läuft. So geht kein Beat verloren. Einige Animationen folgen statt Beats
 * direkt den Pegeln einzelner Frequenzbereiche.
 */
public final class Music {
    public static final List<String> MODES = List.of("Beat-Balken", "Bass-Komet", "Doppelwelle", "Fan-Kaskade",
            "Regenbogenlauf", "Beat-Ringe", "Drei-Band-Beats", "Equalizer", "Frequenz-Zonen", "Bass-Pumpe + Höhen-Funken",
            "Tonhöhen-Farbe", "Spektrum-Ringe", "VU-Meter", "Puls (aktueller Effekt)");
    public static final List<String> BANDS = List.of("Gesamt", "Bass", "Mitten", "Höhen");
    public static final List<String> PALETTES = List.of("Lila", "Blau", "Cyan", "Pink", "Rot", "Grün", "Regenbogen");
    /** Zahl der Spektrum-Bänder hinter den vier Pegeln in den Audiowerten. */
    public static final int SPECTRUM = 16;
    private static final int MAX_PULSES = 10;

    /** Erkennt Pegelsprünge in einem Frequenzbereich. */
    private static final class Detector {
        double baseline, previous;

        boolean onset(double energy, double dt, double sensitivity) {
            double s = Math.max(0.05, sensitivity);
            boolean hit = energy > 0.08 / s && (energy > baseline * (1 + 0.4 / s) + 0.035 / s || energy - previous > 0.12 / s);
            baseline += (energy - baseline) * (1 - Math.exp(-dt / 0.35));
            previous = energy;
            return hit;
        }
    }

    private static final class Pulse {
        double start, duration, strength, seed;
        int band;
    }

    private final Detector[] detectors = new Detector[4];
    private final List<Pulse> pulses = new ArrayList<>();
    private final double[] lastPulse = new double[4];
    private final Random rnd = new Random();
    private double until = Double.NEGATIVE_INFINITY, sampleStamp = Double.NaN, lastRender, lastBpmBeat = Double.NaN, centroid;
    private String mode;
    /** Automatisch angeglichene Pegel (0..1): vier Bereiche, danach das Spektrum. */
    private final double[] agc = new double[4 + SPECTRUM], peaks = new double[4 + SPECTRUM];
    private double[] spark = new double[0];
    private Object centerLayout;
    private double[] fx = new double[0], fy = new double[0], fr = new double[0];
    public volatile int beats;
    public final int[] bandBeats = new int[4];
    /** Geglättete Pegel für die Anzeige, 0..1. */
    public final double[] levels = new double[4];

    public Music() {
        reset();
    }

    public void reset() {
        for (int i = 0; i < 4; i++) detectors[i] = new Detector();
        pulses.clear();
        Arrays.fill(lastPulse, Double.NEGATIVE_INFINITY);
        until = Double.NEGATIVE_INFINITY;
        sampleStamp = Double.NaN;
        lastBpmBeat = Double.NaN;
        Arrays.fill(levels, 0);
        Arrays.fill(agc, 0);
        Arrays.fill(peaks, 0);
        Arrays.fill(spark, 0);
        centroid = 0;
    }

    private static int bandIndex(String band) {
        int i = BANDS.indexOf(band);
        return i < 0 ? 1 : i;
    }

    private static double at(double[] v, int i) {
        return i < v.length ? v[i] : 0;
    }

    /**
     * colors enthält den normalen Effekt und wird hier überschrieben. now in Sekunden. values: Gesamt, Bass, Mitten,
     * Höhen, danach optional das Spektrum.
     */
    public void render(CompiledLayout layout, double now, double[] values, double stamp, Project.Audio cfg, int[] colors) {
        if (!cfg.mode.equals(mode)) {
            reset();
            mode = cfg.mode;
        }
        boolean bpm = "bpm".equals(cfg.trigger);
        double dt = Math.max(0.001, Math.min(0.2, now - lastRender));
        lastRender = now;
        boolean[] onset = new boolean[4];
        double[] energy = new double[4];

        if (bpm) {
            beatClock(now, cfg, onset, energy, dt);
        } else {
            if (now - stamp >= 0.5) values = new double[0];
            for (int i = 0; i < 4; i++) {
                double target = Colors.clamp01(Math.max(0, at(values, i) - cfg.floor) * cfg.gain * 3);
                double tau = target > levels[i] ? 0.015 : 0.03 + cfg.smoothing * 0.6;
                levels[i] += (target - levels[i]) * (1 - Math.exp(-dt / tau));
            }
            for (int i = 0; i < agc.length; i++) autoGain(i, at(values, i), dt, cfg);
            if (stamp != sampleStamp) {
                double sdt = Double.isNaN(sampleStamp) ? 0.043 : Math.max(0.001, Math.min(0.5, stamp - sampleStamp));
                for (int b = 0; b < 4; b++) {
                    // Onset vor dem Begrenzen erkennen, damit laute Musik ihre Dynamik behält.
                    energy[b] = Math.max(0, at(values, b) - cfg.floor) * cfg.gain * 3;
                    onset[b] = detectors[b].onset(energy[b], sdt, cfg.sensitivity);
                }
                sampleStamp = stamp;
            }
        }

        int band = bandIndex(cfg.band);
        switch (cfg.mode) {
            case "Spektrum-Ringe" -> spectrumRings(layout, cfg, colors);
            case "VU-Meter" -> vu(layout, cfg, colors, levels[band]);
            case "Puls (aktueller Effekt)" -> {
                double factor = cfg.minimum + (1 - cfg.minimum) * levels[band];
                for (int i = 0; i < colors.length; i++) colors[i] = Colors.scale(colors[i], factor);
            }
            case "Equalizer" -> equalizer(layout, cfg, colors);
            case "Frequenz-Zonen" -> zones(layout, cfg, colors);
            case "Bass-Pumpe + Höhen-Funken" -> pump(layout, cfg, colors, onset[3], dt);
            case "Tonhöhen-Farbe" -> pitch(layout, cfg, colors, dt);
            case "Drei-Band-Beats" -> {
                for (int b = 1; b <= 3; b++) {
                    if (onset[b]) trigger(now, b, energy[b], cfg, b == 3 ? 0.5 : b == 2 ? 0.8 : 1.0, bpm);
                }
                threeBand(layout, now, cfg, colors);
            }
            default -> {
                if (onset[band]) trigger(now, band, energy[band], cfg, 1.0, bpm);
                pulses(layout, now, cfg, colors);
            }
        }
    }

    /** Startet einen Lauf, sofern die Pause seit dem letzten es erlaubt. */
    private void trigger(double now, int band, double energy, Project.Audio cfg, double durationScale, boolean bpm) {
        double duration = Math.max(0.15, cfg.duration) * durationScale;
        if (cfg.overlap) {
            if (now - lastPulse[band] < Math.max(0.08, cfg.pause)) return;
        } else if (now < until) {
            return;
        }
        Pulse p = new Pulse();
        p.start = now;
        p.duration = duration;
        p.strength = bpm ? 1 : Math.min(1, Math.max(0.35, energy));
        p.band = band;
        p.seed = rnd.nextDouble() * 1000;
        if (!cfg.overlap) pulses.clear();
        pulses.add(p);
        while (pulses.size() > MAX_PULSES) pulses.remove(0);
        lastPulse[band] = now;
        until = now + duration + Math.max(0, cfg.pause);
        beats++;
        bandBeats[band]++;
    }

    /** Fester Takt statt Musik: Bass auf jedem Schlag, Mitten auf jedem zweiten, Höhen auf jedem halben. */
    private void beatClock(double now, Project.Audio cfg, boolean[] onset, double[] energy, double dt) {
        double beat = now * Math.max(1, cfg.bpm) / 60;
        double whole = Math.floor(beat), half = Math.floor(beat * 2);
        if (Double.isNaN(lastBpmBeat) || half != lastBpmBeat) {
            boolean first = Double.isNaN(lastBpmBeat);
            boolean newWhole = first || Math.floor(lastBpmBeat / 2) != whole;
            onset[3] = true;
            onset[0] = onset[1] = newWhole;
            onset[2] = newWhole && ((long) whole) % 2 == 0;
            lastBpmBeat = half;
        }
        Arrays.fill(energy, 1);
        double frac = beat - Math.floor(beat);
        double bass = Math.exp(-frac * 5), mid = Math.exp(-(beat / 2 - Math.floor(beat / 2)) * 4) * 0.8;
        double high = Math.exp(-(beat * 2 - Math.floor(beat * 2)) * 6) * 0.7;
        double[] target = {Math.max(bass, mid), bass, mid, high};
        for (int i = 0; i < 4; i++) {
            levels[i] = target[i];
            agc[i] = target[i];
        }
        for (int s = 0; s < SPECTRUM; s++) {
            double shape = s < 4 ? bass : s < 10 ? mid : high;
            agc[4 + s] = shape * (0.65 + 0.35 * Math.sin(now * (1.3 + s * 0.37) + s));
        }
    }

    /** Hält jeden Bereich unabhängig von der Lautstärke zwischen 0 und 1. */
    private void autoGain(int i, double raw, double dt, Project.Audio cfg) {
        peaks[i] = Math.max(raw, peaks[i] * Math.exp(-dt / 4));
        double gate = cfg.floor * 0.25;
        double target = raw <= gate ? 0 : Colors.clamp01((raw - gate) / Math.max(1e-4, peaks[i] - gate));
        double tau = target > agc[i] ? 0.02 : 0.08 + cfg.smoothing * 0.3;
        agc[i] += (target - agc[i]) * (1 - Math.exp(-dt / tau));
    }

    private static double paletteHue(String palette) {
        return switch (palette) {
            case "Blau" -> 0.65;
            case "Cyan" -> 0.5;
            case "Pink" -> 0.9;
            case "Rot" -> 0.0;
            case "Grün" -> 0.33;
            default -> 0.77;
        };
    }

    // ---- Läufe, die durch Beats starten

    /** Port von render_animation der Python-Version (ein Lauf), zusätzlich Beat-Ringe. */
    public static void animation(CompiledLayout layout, String mode, double progress, double width, double strength,
                                 double minimum, String palette, int[] out) {
        Led[] leds = layout.leds;
        int count = layout.fanCount;
        width = Math.max(0.2, Math.min(4.0, width));
        double cx = layout.width / 2, cy = layout.height / 2, maxRadius = Math.max(0.1, Math.hypot(cx, cy));
        for (int i = 0; i < leds.length; i++) {
            Led l = leds[i];
            double position = l.fixture + (1 + l.u) / 2;
            double value = pulseValue(mode, l, position, progress, width, count, Math.hypot(l.x - cx, l.y - cy) / maxRadius);
            double hue = paletteHue(palette);
            if ("Regenbogen".equals(palette) || "Regenbogenlauf".equals(mode)) {
                hue = position / Math.max(1, count) + Math.max(0, progress) * 0.4;
            }
            out[i] = Colors.hsv(hue, 1, minimum + (1 - minimum) * value * strength);
        }
    }

    /** Helligkeit einer LED in einem Lauf. radius: Abstand zum Ursprung der Ringe, 0..1. */
    private static double pulseValue(String mode, Led l, double position, double progress, double width, int count, double radius) {
        if (!(progress >= 0 && progress < 1)) return 0;
        double head = -width + progress * (count + 2 * width);
        return switch (mode) {
            case "Beat-Balken", "Regenbogenlauf" -> l.rim ? Math.pow(Math.max(0, 1 - Math.abs(position - head) / width), 1.6) : 0;
            case "Bass-Komet" -> {
                double d = head - position;
                yield d >= 0 && d <= width * 2 ? Math.pow(Math.max(0, 1 - d / (width * 2)), 2) : 0;
            }
            case "Doppelwelle" -> {
                double r = progress * (count / 2.0 + width);
                double d = Math.abs(Math.abs(position - count / 2.0) - r) / width;
                yield l.rim ? Math.pow(Math.max(0, 1 - d), 1.5) : 0;
            }
            case "Fan-Kaskade" -> {
                double age = progress * (count + width) - l.fixture;
                yield age >= 0 && age <= width ? Math.pow(Math.max(0, 1 - age / width), 2) : 0;
            }
            case "Beat-Ringe" -> Math.pow(Math.max(0, 1 - Math.abs(radius - progress * 1.3) / (0.12 * width)), 1.5);
            default -> 0;
        };
    }

    private void centers(CompiledLayout layout) {
        if (centerLayout == layout) return;
        centerLayout = layout;
        int n = Math.max(1, layout.fanCount);
        fx = new double[n];
        fy = new double[n];
        fr = new double[n];
        int[] count = new int[n];
        for (Led l : layout.leds) {
            if (l.fixture >= n) continue;
            fx[l.fixture] += l.x;
            fy[l.fixture] += l.y;
            count[l.fixture]++;
        }
        for (int i = 0; i < n; i++) {
            fx[i] /= Math.max(1, count[i]);
            fy[i] /= Math.max(1, count[i]);
        }
        for (Led l : layout.leds) {
            if (l.fixture < n) fr[l.fixture] = Math.max(fr[l.fixture], Math.hypot(l.x - fx[l.fixture], l.y - fy[l.fixture]));
        }
    }

    private double radius(CompiledLayout layout, Led l, boolean perFixture) {
        if (perFixture && l.fixture < fx.length) {
            return Math.hypot(l.x - fx[l.fixture], l.y - fy[l.fixture]) / Math.max(0.01, fr[l.fixture]);
        }
        double cx = layout.width / 2, cy = layout.height / 2;
        return Math.hypot(l.x - cx, l.y - cy) / Math.max(0.1, Math.hypot(cx, cy));
    }

    private void pulses(CompiledLayout layout, double now, Project.Audio cfg, int[] out) {
        pulses.removeIf(p -> now - p.start >= p.duration);
        centers(layout);
        Led[] leds = layout.leds;
        int count = layout.fanCount;
        double width = Math.max(0.2, Math.min(4.0, cfg.length));
        boolean perFixture = "fixture".equals(cfg.origin);
        boolean rainbow = "Regenbogen".equals(cfg.palette) || "Regenbogenlauf".equals(cfg.mode);
        for (int i = 0; i < leds.length; i++) {
            Led l = leds[i];
            double position = l.fixture + (1 + l.u) / 2, r = radius(layout, l, perFixture);
            double value = 0, progressOfBrightest = 0;
            for (Pulse p : pulses) {
                double progress = (now - p.start) / p.duration;
                double v = pulseValue(cfg.mode, l, position, progress, width, count, r) * p.strength;
                if (v > value) {
                    value = v;
                    progressOfBrightest = progress;
                }
            }
            double hue = rainbow ? position / Math.max(1, count) + progressOfBrightest * 0.4 : paletteHue(cfg.palette);
            out[i] = Colors.hsv(hue, 1, cfg.minimum + (1 - cfg.minimum) * Math.min(1, value));
        }
    }

    /** Bass: Ring aus der Mitte. Mitten: Welle von links nach rechts. Höhen: Funkenregen. Jeder Bereich in eigener Farbe. */
    private void threeBand(CompiledLayout layout, double now, Project.Audio cfg, int[] out) {
        pulses.removeIf(p -> now - p.start >= p.duration);
        centers(layout);
        double hue = "Regenbogen".equals(cfg.palette) ? 0 : paletteHue(cfg.palette);
        int base = Colors.hsv(hue, 1, cfg.minimum);
        boolean perFixture = "fixture".equals(cfg.origin);
        double width = Math.max(0.2, Math.min(4.0, cfg.length));
        Led[] leds = layout.leds;
        for (int i = 0; i < leds.length; i++) {
            Led l = leds[i];
            int c = base;
            for (Pulse p : pulses) {
                double progress = (now - p.start) / p.duration;
                double v;
                int color;
                switch (p.band) {
                    case 1 -> {
                        v = Math.pow(Math.max(0, 1 - Math.abs(radius(layout, l, perFixture) - progress * 1.3) / (0.12 * width)), 1.5);
                        color = Colors.hsv(hue, 1, 1);
                    }
                    case 2 -> {
                        double x = l.x / Math.max(0.01, layout.width), w = 0.12 * width;
                        double head = -w + progress * (1 + 2 * w);
                        v = Math.pow(Math.max(0, 1 - Math.abs(x - head) / w), 1.6);
                        color = Colors.hsv(hue + 0.33, 1, 1);
                    }
                    default -> {
                        double h = hash(i, p.seed);
                        v = h > 0.72 ? Math.pow(1 - progress, 2) * (0.6 + h * 0.4) : 0;
                        color = Colors.hsv(hue + 0.55, 0.35, 1);
                    }
                }
                if (v > 0.002) c = Colors.max(c, Colors.scale(color, Math.min(1, v * p.strength)));
            }
            out[i] = c;
        }
    }

    private static double hash(int i, double seed) {
        long h = Double.doubleToLongBits(seed) * 0x9E3779B97F4A7C15L + i * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h & 0xFFFFFF) / (double) 0xFFFFFF;
    }

    // ---- Animationen, die den Pegeln folgen

    /** 16 Bänder von links (tief) nach rechts (hoch), jedes füllt sich von unten nach oben. */
    private void equalizer(CompiledLayout layout, Project.Audio cfg, int[] out) {
        Led[] leds = layout.leds;
        boolean rainbow = "Regenbogen".equals(cfg.palette);
        double hue = paletteHue(cfg.palette), w = Math.max(0.01, layout.width), h = layout.height;
        for (int i = 0; i < leds.length; i++) {
            Led l = leds[i];
            int band = Math.min(SPECTRUM - 1, (int) (l.x / w * SPECTRUM));
            double level = agc[4 + band];
            double height = h < 0.05 ? 0 : 1 - l.y / h;
            double v = h < 0.05 ? level : Colors.clamp01((level - height) * 6 + 0.5);
            double ledHue = rainbow ? band / (double) SPECTRUM * 0.75 : hue + height * 0.2;
            out[i] = Colors.hsv(ledHue, 1, cfg.minimum + (1 - cfg.minimum) * v);
        }
    }

    /** Die Kette wird gedrittelt: Bass, Mitten, Höhen leuchten jeweils in ihrem Bereich und ihrer Farbe. */
    private void zones(CompiledLayout layout, Project.Audio cfg, int[] out) {
        Led[] leds = layout.leds;
        int count = layout.fanCount;
        double hue = "Regenbogen".equals(cfg.palette) ? 0 : paletteHue(cfg.palette);
        for (int i = 0; i < leds.length; i++) {
            Led l = leds[i];
            int zone = count >= 3 ? Math.min(2, l.fixture * 3 / count) : Math.min(2, l.k * 3 / Math.max(1, l.m));
            double level = agc[1 + zone];
            out[i] = Colors.hsv(hue + zone * 0.33, 1, cfg.minimum + (1 - cfg.minimum) * level);
        }
    }

    /** Der Bass lässt alles pumpen, die Höhen lassen einzelne LEDs aufblitzen. */
    private void pump(CompiledLayout layout, Project.Audio cfg, int[] out, boolean highBeat, double dt) {
        int n = layout.leds.length;
        if (spark.length != n) spark = new double[n];
        double hue = "Regenbogen".equals(cfg.palette) ? 0.8 : paletteHue(cfg.palette);
        double fade = Math.exp(-dt * 7), chance = agc[3] * dt * 5;
        int burst = highBeat ? Math.max(1, n / 8) : 0;
        for (int b = 0; b < burst; b++) spark[rnd.nextInt(Math.max(1, n))] = 1;
        int base = Colors.hsv(hue, 1, cfg.minimum + (1 - cfg.minimum) * Math.pow(agc[1], 1.4));
        int sparkColor = Colors.hsv(hue + 0.5, 0.25, 1);
        for (int i = 0; i < n; i++) {
            spark[i] *= fade;
            if (rnd.nextDouble() < chance * 0.15) spark[i] = Math.max(spark[i], 0.7);
            out[i] = Colors.max(base, Colors.scale(sparkColor, spark[i]));
        }
    }

    /** Die Farbe folgt der Tonhöhe (Schwerpunkt des Spektrums), die Helligkeit der Lautstärke. */
    private void pitch(CompiledLayout layout, Project.Audio cfg, int[] out, double dt) {
        double sum = 0, weighted = 0;
        for (int b = 0; b < SPECTRUM; b++) {
            sum += agc[4 + b];
            weighted += agc[4 + b] * b;
        }
        double target = sum > 0.05 ? weighted / sum / (SPECTRUM - 1) : centroid;
        centroid += (target - centroid) * (1 - Math.exp(-dt / 0.3));
        boolean rainbow = "Regenbogen".equals(cfg.palette);
        double hue = rainbow ? centroid * 0.9 : paletteHue(cfg.palette) + centroid * 0.6;
        double v = cfg.minimum + (1 - cfg.minimum) * agc[0];
        Led[] leds = layout.leds;
        for (int i = 0; i < leds.length; i++) {
            out[i] = Colors.hsv(hue + leds[i].x * 0.08, 1, v);
        }
    }

    private void spectrumRings(CompiledLayout layout, Project.Audio cfg, int[] out) {
        Led[] leds = layout.leds;
        double hue = paletteHue(cfg.palette);
        boolean rainbow = "Regenbogen".equals(cfg.palette);
        for (int i = 0; i < leds.length; i++) {
            Led l = leds[i];
            double level = levels[1 + l.fixture % 3];
            double pos = (double) l.k / Math.max(1, l.m);
            double v = Colors.clamp01((level - pos) * l.m);
            double h = rainbow ? pos * 0.7 + l.fixture * 0.12 : hue + pos * 0.15;
            out[i] = Colors.hsv(h, 1, cfg.minimum + (1 - cfg.minimum) * v);
        }
    }

    private static void vu(CompiledLayout layout, Project.Audio cfg, int[] out, double level) {
        int n = layout.leds.length;
        for (int i = 0; i < n; i++) {
            double pos = (double) i / Math.max(1, n);
            double v = Colors.clamp01((level - pos) * n);
            // Klassische Anzeige: grün, ab 70 % gelb, ganz oben rot.
            double hue = "Regenbogen".equals(cfg.palette) ? pos : 0.33 * (1 - Colors.clamp01((pos - 0.55) / 0.4));
            out[i] = Colors.hsv(hue, 1, cfg.minimum + (1 - cfg.minimum) * v);
        }
    }
}
