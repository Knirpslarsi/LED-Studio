package de.knirpslarsi.ledstudio.util;

/** Kleines, schnelles 3D-Value-Noise für die organischen Effekte. */
public final class Noise {
    private Noise() {
    }

    private static double hash(int x, int y, int z) {
        int h = x * 374761393 + y * 668265263 + z * 2147483629;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0x7FFFFFFF) / 2147483647.0;
    }

    private static double fade(double t) {
        return t * t * (3 - 2 * t);
    }

    /** Ergebnis 0..1. */
    public static double at(double x, double y, double z) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        double u = fade(x - xi), v = fade(y - yi), w = fade(z - zi);
        double c00 = lerp(hash(xi, yi, zi), hash(xi + 1, yi, zi), u);
        double c10 = lerp(hash(xi, yi + 1, zi), hash(xi + 1, yi + 1, zi), u);
        double c01 = lerp(hash(xi, yi, zi + 1), hash(xi + 1, yi, zi + 1), u);
        double c11 = lerp(hash(xi, yi + 1, zi + 1), hash(xi + 1, yi + 1, zi + 1), u);
        return lerp(lerp(c00, c10, v), lerp(c01, c11, v), w);
    }

    /** Mehrere Oktaven, Ergebnis 0..1. */
    public static double fbm(double x, double y, double z, int octaves) {
        double sum = 0, amp = 0.5, norm = 0;
        for (int i = 0; i < octaves; i++) {
            sum += at(x, y, z) * amp;
            norm += amp;
            amp *= 0.5;
            x *= 2.03;
            y *= 2.03;
            z *= 1.7;
        }
        return sum / norm;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
