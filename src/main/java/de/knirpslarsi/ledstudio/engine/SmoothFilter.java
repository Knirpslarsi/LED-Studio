package de.knirpslarsi.ledstudio.engine;

import de.knirpslarsi.ledstudio.util.Colors;

/**
 * Glättet jede LED zeitlich, damit sie weich zur nächsten Farbe übergeht. Gerechnet wird in der wahrgenommenen
 * Helligkeit: LEDs wirken bei kleinen Werten schon hell, deshalb sähe ein gleichmäßiger Anstieg der Werte aus,
 * als ginge die LED sofort an. Mit dieser Kurve wird sie sichtbar gleichmäßig heller und dunkler.
 */
final class SmoothFilter {
    private static final double GAMMA = 2.2;
    private static final double[] PERCEIVED = new double[256];

    static {
        for (int i = 0; i < 256; i++) PERCEIVED[i] = Math.pow(i / 255.0, 1 / GAMMA);
    }

    private double[] state = new double[0];

    /** Verarbeitet colors direkt. Bei Zeit 0 ist der Filter aus und folgt beim nächsten Einschalten dem Ist-Zustand. */
    void apply(int[] colors, double dt, double seconds) {
        if (seconds <= 0) {
            state = new double[0];
            return;
        }
        boolean fresh = state.length != colors.length * 3;
        if (fresh) state = new double[colors.length * 3];
        double a = 1 - Math.exp(-Math.max(0, dt) / seconds);
        for (int i = 0; i < colors.length; i++) {
            int c = colors[i];
            int r = step(i * 3, Colors.r(c), a, fresh);
            int g = step(i * 3 + 1, Colors.g(c), a, fresh);
            int b = step(i * 3 + 2, Colors.b(c), a, fresh);
            colors[i] = (r << 16) | (g << 8) | b;
        }
    }

    private int step(int index, int target, double a, boolean fresh) {
        double goal = PERCEIVED[target];
        double p = fresh ? goal : state[index] + (goal - state[index]) * a;
        // Nah am Ziel einrasten, damit eine LED wirklich ganz aus- oder angeht.
        if (Math.abs(goal - p) < 0.002) p = goal;
        state[index] = p;
        return (int) (Math.pow(p, GAMMA) * 255 + 0.5);
    }
}
