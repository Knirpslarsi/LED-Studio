package de.knirpslarsi.ledstudio.engine;

/** Macht den paketinternen Filter für Tests in anderen Paketen nutzbar. */
public final class SmoothProbe {
    private final SmoothFilter filter = new SmoothFilter();

    public void apply(int[] colors, double dt, double seconds) {
        filter.apply(colors, dt, seconds);
    }
}
