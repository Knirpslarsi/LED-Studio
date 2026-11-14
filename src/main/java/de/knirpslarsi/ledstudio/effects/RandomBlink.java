package de.knirpslarsi.ledstudio.effects;

import de.knirpslarsi.ledstudio.util.Colors;

import java.util.List;

import static de.knirpslarsi.ledstudio.effects.ParamSpec.choice;
import static de.knirpslarsi.ledstudio.effects.ParamSpec.color;
import static de.knirpslarsi.ledstudio.effects.ParamSpec.num;

/**
 * Zufällige LEDs blinken oder leuchten auf. Alles hängt nur von der Zeit ab, nicht vom Zufall des Moments: Beim
 * Spulen in einer Show sieht derselbe Zeitpunkt immer gleich aus.
 */
final class RandomBlink extends LegacyEffects.Simple {
    RandomBlink() {
        super("random_blink", "Zufalls-Blinken", "Zufällige LEDs blinken oder leuchten auf: wie viele, wie oft, wie lange "
                        + "und wie weich ist einstellbar.",
                List.of(num("speed", "Tempo", 1, 0.05, 5, 0.01),
                        num("amount", "Wie viele LEDs", 0.3, 0.01, 1, 0.01).help("Anteil der LEDs, die pro Durchgang aufleuchten."),
                        num("rate", "Blinken pro Sekunde (je LED)", 1.5, 0.05, 12, 0.05),
                        num("duration", "Leuchtdauer", 0.5, 0.05, 1, 0.01).help("Anteil eines Durchgangs, in dem die LED an ist."),
                        num("softness", "Weichheit", 0.5, 0, 1, 0.01).help("0: hartes Blinken, 1: weiches Auf- und Abglimmen."),
                        num("jitter", "Unregelmäßigkeit", 0.7, 0, 1, 0.01)
                                .help("0: alle im gleichen Takt, 1: jede LED in ihrem eigenen Rhythmus."),
                        choice("color_mode", "Farben", "one", "one", "Eine Farbe", "two", "Zwei Farben gemischt",
                                "random", "Zufällige Farben", "rainbow", "Regenbogen nach Position"),
                        color("color", "Farbe", "#A020FF"),
                        color("color2", "Zweite Farbe", "#00DDFF"),
                        num("background", "Hintergrundleuchten", 0, 0, 0.5, 0.01),
                        num("seed", "Zufall (Startwert)", 1, 0, 9999, 1)
                                .help("Eine andere Zahl lässt andere LEDs blinken. „Neu würfeln“ im Show-Clip setzt sie zufällig.")));
    }

    /** Gleichverteilte Zufallszahl 0..1, nur abhängig von den Eingaben. */
    static double hash(long a, long b, long c) {
        long h = a * 0x9E3779B97F4A7C15L ^ (b + 0x632BE59BD9B4E019L) * 0xC2B2AE3D27D4EB4FL ^ c * 0x165667B19E3779F9L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 32;
        return (h & 0xFFFFFFL) / (double) 0x1000000;
    }

    private static double smooth(double x) {
        x = x < 0 ? 0 : Math.min(1, x);
        return x * x * (3 - 2 * x);
    }

    /** Helligkeit innerhalb eines Durchgangs (p 0..1): an für die Leuchtdauer, Ränder je nach Weichheit. */
    static double envelope(double p, double duration, double softness) {
        if (p >= duration) return 0;
        double edge = Math.max(1e-4, softness * duration / 2);
        return Math.min(smooth(p / edge), smooth((duration - p) / edge));
    }

    @Override
    public void render(Frame f) {
        Params p = f.p;
        double t = f.t * p.d("speed");
        double amount = p.d("amount"), base = 1 / Math.max(0.01, p.d("rate")), duration = p.d("duration");
        double softness = p.d("softness"), jitter = p.d("jitter"), bg = p.d("background");
        String mode = p.s("color_mode");
        int c1 = p.color("color"), c2 = p.color("color2");
        int n = f.out.length;
        int background = Colors.scale(c1, bg);
        long seed = Math.round(p.d("seed")) * 1_000_003L;
        for (int i = 0; i < n; i++) {
            // Jede LED hat ihren eigenen Rhythmus und Versatz (je nach Unregelmäßigkeit).
            long led = i + seed;
            double period = base * (1 + jitter * (hash(led, 1, 7) - 0.5) * 1.2);
            double x = t / period + jitter * hash(led, 2, 7);
            long cycle = (long) Math.floor(x);
            double phase = x - cycle;
            double v = hash(led, cycle, 3) < amount ? envelope(phase, duration, softness) : 0;
            if (v <= 0.002) {
                f.out[i] = background;
                continue;
            }
            int c = switch (mode) {
                case "two" -> hash(led, cycle, 5) < 0.5 ? c1 : c2;
                case "random" -> Colors.hsv(hash(led, cycle, 9), 1, 1);
                case "rainbow" -> Colors.hsv(n <= 1 ? 0 : (double) f.layout.leds[i].seq / n, 1, 1);
                default -> c1;
            };
            f.out[i] = Colors.max(background, Colors.scale(c, v));
        }
    }
}
