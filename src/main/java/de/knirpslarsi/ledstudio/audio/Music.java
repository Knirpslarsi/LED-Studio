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
    public static final String AUTO = "Auto-Regie";
    /** Auto-Regie zuerst, dann die Streifen-Effekte, die sie verwendet, dann die übrigen Musik-Animationen. */
    public static final List<String> MODES = modes();

    private static List<String> modes() {
        List<String> m = new ArrayList<>(List.of(AUTO));
        m.addAll(stripModes());
        m.addAll(List.of("Beat-Balken", "Bass-Komet", "Doppelwelle", "Fan-Kaskade", "Regenbogenlauf", "Beat-Ringe",
                "Drei-Band-Beats", "Equalizer", "Frequenz-Zonen", "Bass-Pumpe + Höhen-Funken", "Tonhöhen-Farbe",
                "Spektrum-Ringe", "VU-Meter", "Puls (aktueller Effekt)"));
        return List.copyOf(m);
    }
    public static final List<String> BANDS = List.of("Gesamt", "Bass", "Mitten", "Höhen");
    public static final List<String> PALETTES = List.of("Lila", "Blau", "Cyan", "Pink", "Rot", "Grün", "Regenbogen",
            "Tanzfläche", "Plasma", "Ozean", "Sonnenuntergang", "Neon", "Frost", "Dschungel");
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
        double start, duration, strength, seed, pitch;
        int band;
        boolean perFixture;
    }

    private final Detector[] detectors = new Detector[4];
    private final List<Pulse> pulses = new ArrayList<>();
    private final double[] lastPulse = new double[4];
    private final Random rnd;
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
    private MusicAnalyzer analyzer;

    public Music() {
        this(System.nanoTime());
    }

    /** Mit festem Zufall, damit Tests jedes Mal dieselben Effekte wählen. */
    public Music(long seed) {
        rnd = new Random(seed);
        reset();
    }

    /** Quelle der Musik-Analyse für die Auto-Regie. */
    public void setAnalyzer(MusicAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    public void reset() {
        resetDirector();
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

        if (AUTO.equals(cfg.mode) || StripFx.Kind.byLabel(cfg.mode) != null) {
            director(layout, now, cfg, bpm, values, stamp, colors, dt);
            return;
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

    // ---- Auto-Regie: Streifen-Effekte, die zum Song-Teil passen

    private final List<MusicAnalyzer.Event> fresh = new ArrayList<>();
    private final List<StripFx> strips = new ArrayList<>();
    private final StripFx.Sound sound = new StripFx.Sound();
    private long seenSeq = Long.MIN_VALUE;
    private double flash, flashTarget, fastUntil = Double.NEGATIVE_INFINITY, tensionVis, energyVis, hueOffset,
            lastBar = Double.NaN, lastPick = Double.NEGATIVE_INFINITY;
    private final double[] sectionWeight = new double[MusicAnalyzer.Section.values().length];
    private double[] acc = new double[0], demoLight = new double[0];
    private int chainLength = 1;
    private Object chainLayout;
    private MusicAnalyzer.Section stripSection;
    private String stripMode, stripSpread;
    private double demoSlot = Double.NaN;
    /** Eigene Analyse, falls keine aus der Audioaufnahme gesetzt ist (z. B. in Tests oder der Vorschau). */
    private MusicAnalyzer ownAnalyzer;
    private double ownStamp = Double.NaN;
    // Klang für die Streifen-Effekte
    private final double[] bandTarget = new double[SPECTRUM];
    private double soundStamp = Double.NaN, specPeakDb = -40, allPeak;
    // Drei frei wählbare Kanäle: worauf der Effekt reagiert
    private final double[] chTarget = new double[3], chPeak = new double[3], chPrev = new double[3], chPrevRaw = new double[3];
    private final double[] chLastHit = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
    private final boolean[] chHit = new boolean[3];
    private double demoKick, demoSnare, demoHat, demoAccent, demoPitch;
    /** Letzte Szene der Auto-Regie, für Anzeige und Tests. */
    public volatile MusicAnalyzer.Scene directorScene = MusicAnalyzer.Scene.EMPTY;
    /** Namen der gerade laufenden Effekte, für Anzeige und Tests. */
    public volatile List<String> activeAnimations = List.of();

    private void resetDirector() {
        strips.clear();
        seenSeq = Long.MIN_VALUE;
        flash = flashTarget = tensionVis = energyVis = hueOffset = 0;
        fastUntil = lastPick = Double.NEGATIVE_INFINITY;
        lastBar = Double.NaN;
        demoSlot = Double.NaN;
        soundStamp = Double.NaN;
        stripSection = null;
        stripMode = null;
        Arrays.fill(sectionWeight, 0);
        Arrays.fill(bandTarget, 0);
        Arrays.fill(sound.bands, 0);
        Arrays.fill(sound.ch, 0);
        Arrays.fill(chTarget, 0);
        Arrays.fill(chPeak, 0);
        demoKick = demoSnare = demoHat = demoAccent = 0;
    }

    /** Die Streifen-Effekte, die einzeln laufen können; die Auto-Regie wechselt zwischen ihnen. */
    public static List<String> stripModes() {
        List<String> out = new ArrayList<>();
        for (StripFx.Kind k : StripFx.Kind.values()) out.add(k.label);
        return out;
    }

    /** Farbverlauf einer Musikfarbe. Einzelne Farben werden zu einem kleinen Verlauf um diese Farbe. */
    static int[] gradient(String palette, double shift) {
        return switch (palette) {
            case "Regenbogen" -> {
                int[] stops = new int[7];
                for (int i = 0; i < 7; i++) stops[i] = Colors.hsv(shift + i / 7.0 * 0.85, 1, 1);
                yield stops;
            }
            case "Tanzfläche" -> new int[]{0xFF0050, 0xFF00C8, 0x6A00FF, 0x0050FF};
            case "Plasma" -> new int[]{0x2A0AA0, 0x7E03A8, 0xCC4778, 0xF89540, 0xF0E020};
            case "Ozean" -> new int[]{0x0020A0, 0x0066FF, 0x00C8FF, 0x00FFB0};
            case "Sonnenuntergang" -> new int[]{0xFF1800, 0xFF6A00, 0xFFB000, 0xFF4FA0};
            case "Neon" -> new int[]{0xFF00A0, 0xA000FF, 0x00E5FF};
            case "Frost" -> new int[]{0x2060FF, 0x80C8FF, 0xFFFFFF};
            case "Dschungel" -> new int[]{0x00C8A0, 0x00FF40, 0xA0FF00};
            default -> {
                double h = paletteHue(palette);
                yield new int[]{Colors.hsv(h - 0.07, 1, 1), Colors.hsv(h, 1, 1), Colors.hsv(h + 0.1, 0.85, 1)};
            }
        };
    }

    private MusicAnalyzer.Scene listen(double now, Project.Audio cfg, boolean bpm, double[] values, double stamp) {
        fresh.clear();
        if (bpm) return demoSong(now, Math.max(30, cfg.bpm), fresh);
        MusicAnalyzer source = analyzer;
        if (source == null) {
            // Ohne Analyse aus der Aufnahme selbst zuhören.
            if (ownAnalyzer == null) ownAnalyzer = new MusicAnalyzer();
            if (stamp != ownStamp && values.length >= 4 + SPECTRUM) {
                double hop = Double.isNaN(ownStamp) ? 1024 / 48000.0 : Math.max(0.005, Math.min(0.1, stamp - ownStamp));
                ownAnalyzer.feed(values, stamp, hop);
                ownStamp = stamp;
            }
            source = ownAnalyzer;
        }
        MusicAnalyzer.Scene s = source.scene();
        if (seenSeq == Long.MIN_VALUE) seenSeq = source.latestSeq();
        seenSeq = source.poll(seenSeq, fresh);
        return now - s.time() > 0.5 ? s.silent(now) : s;
    }

    private static SoundSource[] sources(Project.Audio cfg) {
        return new SoundSource[]{SoundSource.byId(cfg.react1), SoundSource.byId(cfg.react2), SoundSource.byId(cfg.react3)};
    }

    /** Signal-Einstellungen anwenden: Schwelle, Kontrast, Verstärkung. x ist 0..1 relativ zur eigenen Spitze. */
    private static double shape(double x, Project.Audio cfg) {
        double t = Colors.clamp01(cfg.threshold);
        x = Math.max(0, (x - t) / Math.max(0.05, 1 - t));
        return Math.min(1, Math.pow(x, Math.max(0.2, cfg.contrast)) * Math.max(0, cfg.boost));
    }

    /**
     * Spektrum und Kanäle aus einer Messung: Jedes Band ist auf das lauteste der letzten Sekunden bezogen, jeder Kanal
     * auf seine eigene Spitze. So schlägt jeder Bereich bis ganz nach oben aus, egal wie laut der Song ist.
     */
    private void hear(double[] values, double stamp, double now, Project.Audio cfg, SoundSource[] src) {
        if (values.length < 4 + SPECTRUM || now - stamp > 0.5) {
            Arrays.fill(bandTarget, 0);
            for (int c = 0; c < 3; c++) if (!src[c].isEvent()) chTarget[c] = 0;
            return;
        }
        if (stamp == soundStamp) return;
        double adt = Double.isNaN(soundStamp) ? 0.021 : Math.max(0.005, Math.min(0.25, stamp - soundStamp));
        soundStamp = stamp;
        double maxDb = -140;
        double[] db = new double[SPECTRUM];
        for (int b = 0; b < SPECTRUM; b++) {
            db[b] = 20 * Math.log10(values[4 + b] + 1e-7);
            maxDb = Math.max(maxDb, db[b]);
        }
        specPeakDb = Math.max(maxDb, Math.max(-70, specPeakDb - adt * 1.5));
        for (int b = 0; b < SPECTRUM; b++) {
            double x = Colors.clamp01((db[b] - (specPeakDb - 30)) / 30);
            bandTarget[b] = shape(x, cfg);
        }
        allPeak = Math.max(SoundChannel.spectrum(values), allPeak * Math.exp(-adt / 2.5));
        for (int c = 0; c < 3; c++) {
            SoundSource so = src[c];
            if (so.isEvent()) continue;
            double amp;
            if (so == SoundSource.ALL) {
                amp = values[0];
            } else {
                double[] w = so == SoundSource.CUSTOM ? SoundSource.weights(cfg.fromHz, cfg.toHz) : SoundSource.weights(so.lowHz, so.highHz);
                double sum = 0;
                for (int b = 0; b < SPECTRUM; b++) sum += w[b] * values[4 + b] * values[4 + b];
                amp = Math.sqrt(sum);
            }
            if (so == SoundSource.VOICE) {
                // Stimmen halten Töne: kurze Schläge (ein Messfenster lang) zählen nur zum Teil.
                double raw = amp;
                amp = Math.min(amp, chPrevRaw[c] * 1.3 + amp * 0.3);
                chPrevRaw[c] = raw;
            }
            chPeak[c] = Math.max(amp, chPeak[c] * Math.exp(-adt / 2.5));
            // Bezug: eigene Spitze, mindestens ein Viertel des ganzen Spektrums (dauerhaft Leises bleibt leise).
            double x = shape(amp / Math.max(1e-7, Math.max(chPeak[c], allPeak * 0.25)), cfg);
            if (x - chPrev[c] > 0.2 && x > 0.35 && stamp - chLastHit[c] > 0.1) {
                chHit[c] = true;
                chLastHit[c] = stamp;
            }
            chPrev[c] = x;
            chTarget[c] = x;
        }
    }

    /** Für die Vorführung ohne Ton: Spektrum und Kanäle aus den vorgeführten Schlägen. */
    private void hearDemo(double dt, double energy, int[] events, double accentPitch, Project.Audio cfg, SoundSource[] src) {
        demoKick *= Math.exp(-dt / 0.12);
        demoSnare *= Math.exp(-dt / 0.1);
        demoHat *= Math.exp(-dt / 0.05);
        demoAccent *= Math.exp(-dt / 0.25);
        if (events[MusicAnalyzer.KICK] > 0) demoKick = 1;
        if (events[MusicAnalyzer.SNARE] > 0) demoSnare = 1;
        if (events[MusicAnalyzer.HAT] > 0) demoHat = 1;
        if (events[MusicAnalyzer.ACCENT] > 0) {
            demoAccent = 1;
            demoPitch = accentPitch;
        }
        for (int b = 0; b < SPECTRUM; b++) {
            double f = b / (double) (SPECTRUM - 1);
            double v = b < 5 ? demoKick * (1 - b * 0.12) : b < 11 ? demoSnare * 0.8 : demoHat * 0.7;
            v = Math.max(v, demoAccent * Math.max(0, 1 - Math.abs(f - (0.3 + 0.5 * demoPitch)) * 6));
            bandTarget[b] = Math.max(v, 0.08 * energy);
        }
        for (int c = 0; c < 3; c++) {
            SoundSource so = src[c];
            if (so.isEvent()) continue;
            double center = so == SoundSource.CUSTOM ? Math.sqrt(Math.max(40, cfg.fromHz) * Math.max(41, cfg.toHz))
                    : Math.sqrt(so.lowHz * so.highHz);
            double level;
            int type;
            if (so == SoundSource.ALL) {
                level = Math.max(demoKick, Math.max(demoSnare * 0.7, demoHat * 0.5));
                type = MusicAnalyzer.KICK;
            } else if (center < 250) {
                level = demoKick;
                type = MusicAnalyzer.KICK;
            } else if (center < 3000) {
                level = Math.max(demoSnare * 0.7, demoAccent);
                type = events[MusicAnalyzer.SNARE] > 0 ? MusicAnalyzer.SNARE : MusicAnalyzer.ACCENT;
            } else {
                level = demoHat;
                type = MusicAnalyzer.HAT;
            }
            chTarget[c] = shape(level, cfg);
            if (events[type] > 0) chHit[c] = true;
        }
    }

    private void director(CompiledLayout layout, double now, Project.Audio cfg, boolean bpm, double[] values, double stamp,
                          int[] out, double dt) {
        boolean auto = AUTO.equals(cfg.mode);
        StripFx.Kind fixed = auto ? null : StripFx.Kind.byLabel(cfg.mode);
        MusicAnalyzer.Scene s = listen(now, cfg, bpm, values, stamp);
        directorScene = s;
        int n = layout.leds.length;
        if (acc.length != n * 3) {
            acc = new double[n * 3];
            demoLight = new double[n * 3];
        }
        Arrays.fill(acc, 0);
        if (chainLayout != layout) {
            chainLayout = layout;
            chainLength = 1;
            for (Led l : layout.leds) chainLength = Math.max(chainLength, l.seq + 1);
        }

        // Abschnitte weich überblenden, ein Drop schlägt fast sofort ein.
        MusicAnalyzer.Section section = s.section();
        for (MusicAnalyzer.Event e : fresh) if (e.type() == MusicAnalyzer.DROP_HIT) fastUntil = now + 0.5;
        if (now < fastUntil) section = MusicAnalyzer.Section.DROP;
        double sectionTau = now < fastUntil ? 0.04 : 0.6;
        for (int i = 0; i < sectionWeight.length; i++) {
            double target = i == section.ordinal() ? 1 : 0;
            sectionWeight[i] += (target - sectionWeight[i]) * (1 - Math.exp(-dt / sectionTau));
        }
        tensionVis += (s.tension() - tensionVis) * (1 - Math.exp(-dt / (s.tension() > tensionVis ? 0.25 : 0.12)));
        // Schnell lauter, langsam leiser: folgt der Lautstärke, ohne mit jedem Schlag zu pumpen.
        energyVis += (s.energy() - energyVis) * (1 - Math.exp(-dt / (s.energy() > energyVis ? 0.15 : 0.8)));

        boolean rainbow = "Regenbogen".equals(cfg.palette);
        double period = s.period();
        double beat = s.beat(now);
        boolean grid = s.confidence() > 0.25;
        double bar = Math.floor((now - s.downbeatTime()) / (4 * period));
        boolean phrase = !Double.isNaN(lastBar) && bar != lastBar && grid && ((long) bar) % 8 == 0;
        if (phrase && rainbow) hueOffset += 0.08;
        lastBar = bar;

        // Effekte wählen: neuer Song-Teil, alle 8 Takte, spätestens nach 30 Sekunden.
        boolean changed = !cfg.mode.equals(stripMode) || !cfg.spread.equals(stripSpread) || section != stripSection;
        if (changed || (auto && (phrase || now - lastPick > 30))) {
            pickStrips(section, fixed, cfg.spread, now);
            stripSection = section;
            stripMode = cfg.mode;
            stripSpread = cfg.spread;
        }
        double fade = 1 - Math.exp(-dt / Math.max(0.15, period * 0.6));
        for (StripFx f : strips) f.weight += ((f.leaving ? 0 : 1) - f.weight) * fade;
        strips.removeIf(f -> f.leaving && f.weight < 0.01);
        List<String> names = new ArrayList<>();
        for (StripFx f : strips) if (!f.leaving) names.add(f.kind.label);
        activeAnimations = List.copyOf(names);

        // Klänge dieses Bildes
        double sens = Math.max(0.05, cfg.sensitivity);
        int[] events = new int[4];
        double accentPitch = 0;
        for (MusicAnalyzer.Event e : fresh) {
            if (e.type() == MusicAnalyzer.DROP_HIT) {
                flashTarget = 1;
                if (rainbow) hueOffset += 0.33;
                continue;
            }
            if (e.strength() * sens < 1) continue;
            if (e.type() < events.length) events[e.type()]++;
            if (e.type() == MusicAnalyzer.KICK) {
                beats++;
                bandBeats[MusicAnalyzer.KICK]++;
            }
            if (e.type() == MusicAnalyzer.ACCENT) accentPitch = e.pitch();
        }
        SoundSource[] src = sources(cfg);
        Arrays.fill(chHit, false);
        double attack = Math.max(0.001, cfg.attackMs / 1000), release = Math.max(0.01, cfg.releaseMs / 1000);
        for (int c = 0; c < 3; c++) {
            // Erkannte Klänge: jeder Treffer setzt den Kanal auf voll, danach klingt er mit dem Abfall aus.
            if (!src[c].isEvent()) continue;
            chTarget[c] *= Math.exp(-dt / release);
            if (events[src[c].eventType()] > 0) {
                chTarget[c] = Math.min(1, Math.max(0, cfg.boost));
                chHit[c] = true;
            }
        }
        if (bpm) hearDemo(dt, s.energy(), events, accentPitch, cfg, src);
        else hear(values, stamp, now, cfg, src);
        double rise = 1 - Math.exp(-dt / attack), fall = 1 - Math.exp(-dt / release);
        for (int b = 0; b < SPECTRUM; b++) {
            double t = bandTarget[b];
            sound.bands[b] += (t - sound.bands[b]) * (t > sound.bands[b] ? rise : fall);
        }
        for (int c = 0; c < 3; c++) {
            sound.ch[c] += (chTarget[c] - sound.ch[c]) * (chTarget[c] > sound.ch[c] ? rise : fall);
            sound.hit[c] = chHit[c] ? 1 : 0;
        }
        sound.beat = beat;
        sound.tension = auto ? tensionVis : 0;
        sound.mirror = cfg.mirror;
        sound.reverse = cfg.reverse;
        sound.blur = (int) Math.round(Math.max(0, Math.min(4, cfg.blur)));
        sound.background = Colors.clamp01(cfg.background);
        sound.speed = Math.max(0.05, cfg.flowSpeed);
        sound.afterglow = Math.max(0.1, cfg.afterglow);
        sound.specFrom = cfg.specFrom;
        sound.specTo = cfg.specTo;
        sound.rate = switch (section) {
            case CALM, SILENCE -> 0.6;
            case DROP -> 1.6;
            case BUILD -> 1 + tensionVis;
            default -> 1;
        };
        sound.gradient = gradient(cfg.palette, hueOffset);
        for (StripFx f : strips) f.render(sound, dt);

        double build = sectionWeight[MusicAnalyzer.Section.BUILD.ordinal()];
        double silence = sectionWeight[MusicAnalyzer.Section.SILENCE.ordinal()];
        double tension = tensionVis;
        double strobe = 0;
        if (auto && tension > 0.6 && build > 0.05) {
            double sub = tension > 0.85 ? 4 : 2;
            double sp = beat * sub - Math.floor(beat * sub);
            strobe = (sp < 0.08 ? StripFx.BeatMath.smooth01(sp / 0.08) : Math.exp(-(sp - 0.08) * 12))
                    * (tension - 0.6) / 0.4 * 0.35 * build;
        }
        // Kurzer weicher Anstieg statt hartem Einschalten, danach ausklingen.
        flash += (flashTarget - flash) * (1 - Math.exp(-dt / 0.03));
        flashTarget *= Math.exp(-dt / 0.35);
        // Leise Stellen bleiben dunkel und ruhig, laute leuchten voll.
        double dyn = Colors.clamp01(cfg.dynamics);
        double master = 1 - dyn * (1 - (0.04 + 0.96 * Math.pow(Colors.clamp01(energyVis), 2)));
        if (silence > 0.5) master = Math.min(master, 1 - dyn * 0.85);
        double whiten = auto ? 0.6 * tension * build : 0;
        int flashColor = Colors.lerp(sound.gradient[0], 0xFFFFFF, 0.7);
        double[] rgb = new double[3];
        Led[] leds = layout.leds;
        for (int i = 0; i < n; i++) {
            Led l = leds[i];
            double cr = 0, cg = 0, cb = 0;
            for (StripFx f : strips) {
                if (f.weight < 0.005) continue;
                f.sample(position(f.spread, l, layout), rgb);
                cr += rgb[0] * f.weight;
                cg += rgb[1] * f.weight;
                cb += rgb[2] * f.weight;
            }
            if (whiten > 0.01) {
                // Spannung macht die Farben weißer.
                double lum = Math.max(cr, Math.max(cg, cb));
                cr += (lum - cr) * whiten;
                cg += (lum - cg) * whiten;
                cb += (lum - cb) * whiten;
            }
            double extra = strobe + flash * 0.9;
            cr += Colors.r(flashColor) / 255.0 * extra;
            cg += Colors.g(flashColor) / 255.0 * extra;
            cb += Colors.b(flashColor) / 255.0 * extra;
            // Über 100 % hinaus addierte Farben erst begrenzen (Farbton bleibt), dann leiser machen.
            double peak = Math.max(1, Math.max(cr, Math.max(cg, cb)));
            double k = master * 255 / peak;
            cr = Math.min(255, cr * k);
            cg = Math.min(255, cg * k);
            cb = Math.min(255, cb * k);
            if (bpm) {
                // Vorführung: in Effektzeit nachziehen, damit auch ein stark gebremstes Tempo weich bleibt.
                double a = 1 - Math.exp(-dt / 0.05);
                cr = demoLight[i * 3] += (cr - demoLight[i * 3]) * a;
                cg = demoLight[i * 3 + 1] += (cg - demoLight[i * 3 + 1]) * a;
                cb = demoLight[i * 3 + 2] += (cb - demoLight[i * 3 + 2]) * a;
            }
            out[i] = Colors.packD(cr, cg, cb);
            if (cfg.minimum > 0) out[i] = Colors.max(out[i], Colors.scale(sound.gradient[0], cfg.minimum));
        }
    }

    /** Stelle der LED auf dem virtuellen Streifen (0..1). */
    private double position(String spread, Led l, CompiledLayout layout) {
        return switch (spread) {
            case "fixture" -> l.angle - Math.floor(l.angle);
            case "x" -> l.x / Math.max(0.01, layout.width);
            default -> chainLength <= 1 ? 0.5 : l.seq / (double) (chainLength - 1);
        };
    }

    /** Neue Effekte für den Song-Teil wählen; die alten blenden aus. */
    private void pickStrips(MusicAnalyzer.Section section, StripFx.Kind fixed, String spread, double now) {
        List<StripFx.Kind> before = new ArrayList<>();
        for (StripFx f : strips) {
            if (!f.leaving) before.add(f.kind);
            f.leaving = true;
        }
        lastPick = now;
        List<StripFx.Kind> chosen = new ArrayList<>();
        if (fixed != null) {
            chosen.add(fixed);
        } else {
            List<StripFx.Kind> pool = switch (section) {
                case CALM -> StripFx.CALM;
                case GROOVE -> StripFx.GROOVE;
                case BUILD -> StripFx.BUILD;
                case DROP -> StripFx.DROP;
                default -> List.of();
            };
            List<StripFx.Kind> options = new ArrayList<>(pool);
            int count = section == MusicAnalyzer.Section.DROP ? 2 : 1;
            if (options.size() - before.size() >= count) options.removeAll(before);
            java.util.Collections.shuffle(options, rnd);
            chosen.addAll(options.subList(0, Math.min(count, options.size())));
        }
        for (StripFx.Kind kind : chosen) {
            String where = !"auto".equals(spread) ? spread : rnd.nextBoolean() ? "fixture" : "chain";
            double rate = section == MusicAnalyzer.Section.CALM ? 0.5 : 1;
            strips.add(new StripFx(kind, rate, where, rnd.nextBoolean(), rnd.nextLong()));
        }
        while (strips.size() > 6) strips.remove(0);
    }

    /**
     * Ein vorgeführter Song für den festen Takt: 16 Schläge Groove, 16 Schläge Aufbau, Drop, 16 Schläge ruhig.
     * So lässt sich die Auto-Regie auch ohne Musik ansehen.
     */
    private MusicAnalyzer.Scene demoSong(double t, double tempo, List<MusicAnalyzer.Event> out) {
        double period = 60 / tempo, beats = t / period;
        double slot = Math.floor(beats * 4);
        if (Double.isNaN(demoSlot) || slot < demoSlot || slot - demoSlot > 64) demoSlot = slot - 1;
        for (double sl = demoSlot + 1; sl <= slot; sl++) {
            double b = sl / 4;
            int sixteenth = (int) Math.floorMod((long) sl, 4L);
            double pos = b - Math.floor(b / 64) * 64;
            int whole = (int) Math.floor(pos);
            MusicAnalyzer.Section sec = demoSection(pos);
            double tension = sec == MusicAnalyzer.Section.BUILD ? (pos - 16) / 16 : 0;
            switch (sec) {
                case GROOVE -> {
                    if (sixteenth == 0) demoEvent(out, t, MusicAnalyzer.KICK, 0);
                    if (sixteenth == 0 && whole % 2 == 1) demoEvent(out, t, MusicAnalyzer.SNARE, 0.4);
                    if (sixteenth == 2) demoEvent(out, t, MusicAnalyzer.HAT, 0.9);
                    if (sixteenth == 3 && whole % 4 == 3) demoEvent(out, t, MusicAnalyzer.ACCENT, rnd.nextDouble());
                }
                case BUILD -> {
                    if (sixteenth == 0 && pos < 24) demoEvent(out, t, MusicAnalyzer.KICK, 0);
                    int every = tension < 0.5 ? 4 : tension < 0.75 ? 2 : 1;
                    if (sixteenth % every == 0) demoEvent(out, t, MusicAnalyzer.SNARE, 0.4);
                    if (sixteenth % 2 == 0) demoEvent(out, t, MusicAnalyzer.HAT, 0.9);
                }
                case DROP -> {
                    if (whole == 32 && sixteenth == 0) demoEvent(out, t, MusicAnalyzer.DROP_HIT, 0);
                    if (sixteenth == 0) demoEvent(out, t, MusicAnalyzer.KICK, 0);
                    if (sixteenth == 0 && whole % 2 == 1) demoEvent(out, t, MusicAnalyzer.SNARE, 0.4);
                    demoEvent(out, t, MusicAnalyzer.HAT, 0.95);
                    if (sixteenth == 2 && whole % 2 == 0) demoEvent(out, t, MusicAnalyzer.ACCENT, rnd.nextDouble());
                }
                default -> {
                    if (sixteenth == 0 && whole % 2 == 0) demoEvent(out, t, MusicAnalyzer.ACCENT, 0.2 + 0.6 * rnd.nextDouble());
                    if (sixteenth == 2 && whole % 4 == 1) demoEvent(out, t, MusicAnalyzer.HAT, 0.9);
                }
            }
        }
        demoSlot = slot;
        double pos = beats - Math.floor(beats / 64) * 64;
        MusicAnalyzer.Section sec = demoSection(pos);
        double cycleStart = (beats - pos) * period;
        double sectionStart = cycleStart + Math.floor(pos / 16) * 16 * period;
        double tension = sec == MusicAnalyzer.Section.BUILD ? (pos - 16) / 16 : 0;
        double energy = switch (sec) {
            case GROOVE -> 0.75;
            case BUILD -> 0.55 + 0.4 * tension;
            case DROP -> 1;
            default -> 0.4;
        };
        double lastDrop = pos >= 32 ? cycleStart + 32 * period : cycleStart - 32 * period;
        return new MusicAnalyzer.Scene(t, sec, sectionStart, energy, tension, sec == MusicAnalyzer.Section.CALM ? 0.1 : 0.8,
                0.4 + 0.3 * tension, 4, tempo, 1, Math.floor(beats) * period, (long) Math.floor(beats),
                Math.floor(beats / 4) * 4 * period, lastDrop);
    }

    private static MusicAnalyzer.Section demoSection(double pos) {
        return pos < 16 ? MusicAnalyzer.Section.GROOVE : pos < 32 ? MusicAnalyzer.Section.BUILD
                : pos < 48 ? MusicAnalyzer.Section.DROP : MusicAnalyzer.Section.CALM;
    }

    private static void demoEvent(List<MusicAnalyzer.Event> out, double t, int type, double pitch) {
        out.add(new MusicAnalyzer.Event(-1, t, type, 1.2, pitch));
    }
}
