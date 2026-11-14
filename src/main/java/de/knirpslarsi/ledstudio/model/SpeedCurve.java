package de.knirpslarsi.ledstudio.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tempo, das sich mit der Zeit ändert: Ein Effekt läuft mal schneller, mal langsamer. Liefert den Faktor zu einer
 * Zeit und die daraus gelaufene Effektzeit (Integral), damit eine Show beim Spulen immer an derselben Stelle ist.
 */
public final class SpeedCurve {
    private SpeedCurve() {
    }

    public static final Map<String, String> MODES = new LinkedHashMap<>();

    static {
        MODES.put("off", "Gleichmäßig");
        MODES.put("wave", "Wellenförmig schneller und langsamer");
        MODES.put("random", "Zufällig schwankend");
        MODES.put("up", "Immer schneller (beschleunigen)");
        MODES.put("down", "Immer langsamer (abbremsen)");
        MODES.put("pulse", "Schübe: kurz schnell, dann langsam");
    }

    public static boolean active(Project.Layer l) {
        return l.speedMode != null && !"off".equals(l.speedMode) && l.speedMax > 0;
    }

    /**
     * Tempofaktor zur Zeit u (Sekunden). span: Dauer, über die beschleunigt oder abgebremst wird (Clip-Länge oder
     * Dauer eines Wechsels); bei normalen Ebenen wiederholt sich der Verlauf.
     */
    public static double factor(Project.Layer l, double u, double span) {
        double lo = Math.max(0.01, Math.min(l.speedMin, l.speedMax)), hi = Math.max(l.speedMin, l.speedMax);
        double period = Math.max(0.05, l.speedPeriod);
        double x;
        switch (l.speedMode == null ? "off" : l.speedMode) {
            case "wave" -> x = 0.5 - 0.5 * Math.cos(2 * Math.PI * u / period);
            case "random" -> {
                // Glattes Rauschen: alle halbe Periode ein neuer Zufallswert, weich dazwischen.
                double k = u / (period / 2);
                long i = (long) Math.floor(k);
                double f = k - i, s = f * f * (3 - 2 * f);
                x = rand(i) + (rand(i + 1) - rand(i)) * s;
            }
            case "up", "down" -> {
                double len = span > 0 ? span : period;
                double p = u / len;
                p = span > 0 ? Math.max(0, Math.min(1, p)) : p - Math.floor(p);
                x = "up".equals(l.speedMode) ? p : 1 - p;
            }
            case "pulse" -> {
                double p = u / period - Math.floor(u / period);
                x = Math.exp(-p * 6);
            }
            default -> {
                return 1;
            }
        }
        return lo + (hi - lo) * x;
    }

    /** Gelaufene Effektzeit von a bis b (numerisch, fein genug für flüssige Bewegung). */
    public static double integral(Project.Layer l, double a, double b, double span) {
        if (b <= a) return 0;
        int steps = (int) Math.max(4, Math.min(20000, Math.ceil((b - a) / 0.01)));
        double h = (b - a) / steps, sum = 0;
        for (int i = 0; i < steps; i++) {
            double u = a + (i + 0.5) * h;
            sum += factor(l, u, span);
        }
        return sum * h;
    }

    private static double rand(long i) {
        long h = i * 0x9E3779B97F4A7C15L + 0x2545F4914F6CDD1DL;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        return (h & 0xFFFFFF) / (double) 0xFFFFFF;
    }
}
