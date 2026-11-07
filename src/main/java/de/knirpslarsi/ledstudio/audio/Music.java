package de.knirpslarsi.ledstudio.audio;

import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.CompiledLayout.Led;
import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.util.Colors;

import java.util.List;

/** Musik-Animationen. Ein erkannter Impuls startet einen Lauf; weitere Impulse in Lauf und Pause werden ignoriert. */
public final class Music {
    public static final List<String> MODES = List.of("Beat-Balken", "Bass-Komet", "Doppelwelle", "Fan-Kaskade",
            "Regenbogenlauf", "Beat-Ringe", "Spektrum-Ringe", "VU-Meter", "Puls (aktueller Effekt)");
    public static final List<String> BANDS = List.of("Gesamt", "Bass", "Mitten", "Höhen");
    public static final List<String> PALETTES = List.of("Lila", "Blau", "Cyan", "Pink", "Regenbogen");

    private double start = Double.NaN, until = Double.NEGATIVE_INFINITY, baseline, previous, sampleStamp = Double.NaN;
    private String mode;
    private double strength, duration = 0.8, lastRender;
    public volatile int beats;
    /** Geglättete Pegel für die Anzeige, 0..1. */
    public final double[] levels = new double[4];

    public void reset() {
        start = Double.NaN;
        until = Double.NEGATIVE_INFINITY;
        baseline = previous = strength = 0;
        sampleStamp = Double.NaN;
        duration = 0.8;
        java.util.Arrays.fill(levels, 0);
    }

    private static int bandIndex(String band) {
        int i = BANDS.indexOf(band);
        return i < 0 ? 1 : i;
    }

    /** colors enthält den normalen Effekt und wird hier überschrieben. now in Sekunden (monoton). */
    public void render(CompiledLayout layout, double now, double[] values, double stamp, Project.Audio cfg, int[] colors) {
        if (!cfg.mode.equals(mode)) {
            reset();
            mode = cfg.mode;
        }
        if (now - stamp >= 0.5) values = new double[4];
        double dt = Math.max(0.001, Math.min(0.2, now - lastRender));
        lastRender = now;
        for (int i = 0; i < 4; i++) {
            double target = Colors.clamp01(Math.max(0, values[i] - cfg.floor) * cfg.gain * 3);
            double tau = target > levels[i] ? 0.015 : 0.03 + cfg.smoothing * 0.6;
            levels[i] += (target - levels[i]) * (1 - Math.exp(-dt / tau));
        }
        int band = bandIndex(cfg.band);
        // Onset vor dem Begrenzen erkennen, damit laute Musik ihre Dynamik behält.
        double energy = Math.max(0, values[band] - cfg.floor) * cfg.gain * 3;
        if (stamp != sampleStamp) {
            double sdt = Double.isNaN(sampleStamp) ? 0.043 : Math.max(0.001, Math.min(0.5, stamp - sampleStamp));
            boolean onset = energy > 0.08 && (energy > baseline * 1.4 + 0.035 || energy - previous > 0.12);
            if (onset && now >= until) {
                start = now;
                duration = Math.max(0.15, cfg.duration);
                until = now + duration + Math.max(0, cfg.pause);
                strength = Math.min(1, Math.max(0.35, energy));
                beats++;
            }
            baseline += (energy - baseline) * (1 - Math.exp(-sdt / 0.35));
            previous = energy;
            sampleStamp = stamp;
        }
        double progress = Double.isNaN(start) ? -1 : (now - start) / duration;
        switch (cfg.mode) {
            case "Spektrum-Ringe" -> spectrum(layout, cfg, colors);
            case "VU-Meter" -> vu(layout, cfg, colors, levels[band]);
            case "Puls (aktueller Effekt)" -> {
                double factor = cfg.minimum + (1 - cfg.minimum) * levels[band];
                for (int i = 0; i < colors.length; i++) colors[i] = Colors.scale(colors[i], factor);
            }
            default -> animation(layout, cfg.mode, progress, cfg.length, strength, cfg.minimum, cfg.palette, colors);
        }
    }

    private static double paletteHue(String palette) {
        return switch (palette) {
            case "Blau" -> 0.65;
            case "Cyan" -> 0.5;
            case "Pink" -> 0.9;
            default -> 0.77;
        };
    }

    /** Port von render_animation der Python-Version, zusätzlich Beat-Ringe. */
    public static void animation(CompiledLayout layout, String mode, double progress, double width, double strength,
                                 double minimum, String palette, int[] out) {
        Led[] leds = layout.leds;
        int count = layout.fanCount;
        boolean active = progress >= 0 && progress < 1;
        width = Math.max(0.2, Math.min(4.0, width));
        double head = -width + progress * (count + 2 * width);
        double cx = layout.width / 2, cy = layout.height / 2;
        double maxRadius = Math.max(0.1, Math.hypot(cx, cy));
        for (int i = 0; i < leds.length; i++) {
            Led l = leds[i];
            double position = l.fixture + (1 + l.u) / 2;
            double value = 0;
            if (active) {
                switch (mode) {
                    case "Beat-Balken", "Regenbogenlauf" -> {
                        double d = Math.abs(position - head) / width;
                        value = l.rim ? Math.pow(Math.max(0, 1 - d), 1.6) : 0;
                    }
                    case "Bass-Komet" -> {
                        double d = head - position;
                        value = d >= 0 && d <= width * 2 ? Math.pow(Math.max(0, 1 - d / (width * 2)), 2) : 0;
                    }
                    case "Doppelwelle" -> {
                        double radius = progress * (count / 2.0 + width);
                        double d = Math.abs(Math.abs(position - count / 2.0) - radius) / width;
                        value = l.rim ? Math.pow(Math.max(0, 1 - d), 1.5) : 0;
                    }
                    case "Fan-Kaskade" -> {
                        double age = progress * (count + width) - l.fixture;
                        value = age >= 0 && age <= width ? Math.pow(Math.max(0, 1 - age / width), 2) : 0;
                    }
                    case "Beat-Ringe" -> {
                        double d = Math.hypot(l.x - cx, l.y - cy) / maxRadius;
                        value = Math.pow(Math.max(0, 1 - Math.abs(d - progress * 1.3) / (0.12 * width)), 1.5);
                    }
                    default -> {
                    }
                }
            }
            double hue = paletteHue(palette);
            if ("Regenbogen".equals(palette) || "Regenbogenlauf".equals(mode)) {
                hue = position / Math.max(1, count) + Math.max(0, progress) * 0.4;
            }
            double v = minimum + (1 - minimum) * value * strength;
            out[i] = Colors.hsv(hue, 1, v);
        }
    }

    private void spectrum(CompiledLayout layout, Project.Audio cfg, int[] out) {
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
