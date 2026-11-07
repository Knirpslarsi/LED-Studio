package de.knirpslarsi.ledstudio.layout;

import de.knirpslarsi.ledstudio.model.Fixture;

/** Erzeugt die LED-Positionen einer Form. Lokale Koordinaten -0.5..0.5, y zeigt nach unten. */
public final class Shapes {
    private Shapes() {
    }

    public static double[][] local(Fixture f) {
        int n = Math.max(0, f.count);
        double[][] p = new double[n][2];
        if (n == 0) return p;
        double sign = f.reverse ? -1 : 1;
        double start = Math.toRadians(f.startAngle);
        switch (f.shape) {
            case RING -> ring(p, 0, n, 0.5, start, sign);
            case DUAL_RING -> {
                int inner = Math.max(0, Math.min(n, f.innerCount));
                ring(p, 0, inner, 0.3, start, sign);
                ring(p, inner, n - inner, 0.5, start, sign);
            }
            case SQUARE -> square(p, f, sign);
            case LINE -> {
                for (int i = 0; i < n; i++) {
                    double t = n == 1 ? 0.5 : (double) i / (n - 1);
                    p[i][0] = (f.reverse ? 0.5 - t : t - 0.5);
                }
            }
            case ARC -> {
                double span = Math.toRadians(f.arcDegrees);
                for (int i = 0; i < n; i++) {
                    double t = n == 1 ? 0.5 : (double) i / (n - 1);
                    double a = start + sign * t * span;
                    p[i][0] = 0.5 * Math.cos(a);
                    p[i][1] = 0.5 * Math.sin(a);
                }
            }
            case GRID -> {
                int cols = Math.max(1, f.cols);
                int rows = Math.max(1, (int) Math.ceil((double) n / cols));
                for (int i = 0; i < n; i++) {
                    int row = i / cols;
                    int col = i % cols;
                    if (f.serpentine && row % 2 == 1) col = cols - 1 - col;
                    if (f.reverse) col = cols - 1 - col;
                    p[i][0] = cols == 1 ? 0 : (double) col / (cols - 1) - 0.5;
                    p[i][1] = rows == 1 ? 0 : (double) row / (rows - 1) - 0.5;
                }
            }
            case FREE -> {
                for (int i = 0; i < n; i++) {
                    if (i < f.points.size()) {
                        p[i][0] = f.points.get(i)[0];
                        p[i][1] = f.points.get(i)[1];
                    } else {
                        // Neue LEDs (Anzahl erhöht) sichtbar unter dem Bauteil auffädeln.
                        int extra = i - f.points.size();
                        p[i][0] = -0.5 + (extra % 12) * 0.08;
                        p[i][1] = 0.6 + (extra / 12) * 0.08;
                    }
                }
            }
        }
        return p;
    }

    private static void ring(double[][] p, int from, int count, double radius, double start, double sign) {
        for (int k = 0; k < count; k++) {
            double a = start + sign * 2 * Math.PI * k / count;
            p[from + k][0] = radius * Math.cos(a);
            p[from + k][1] = radius * Math.sin(a);
        }
    }

    /** Verteilt die LEDs gleichmäßig über den Umfang eines abgerundeten Rechtecks. */
    private static void square(double[][] p, Fixture f, double sign) {
        double r = Math.max(0, Math.min(0.5, f.corner));
        double straight = 1 - 2 * r;
        double arc = Math.PI * r / 2;
        double perimeter = 4 * straight + 4 * arc;
        int n = p.length;
        double offset = (f.startAngle / 360.0) * perimeter;
        for (int i = 0; i < n; i++) {
            double s = offset + sign * perimeter * i / n;
            s = ((s % perimeter) + perimeter) % perimeter;
            pointOnRect(s, r, straight, arc, p[i]);
        }
    }

    private static void pointOnRect(double s, double r, double straight, double arc, double[] out) {
        double half = straight / 2;
        // Start rechts in der Mitte, dann im Uhrzeigersinn: rechts, unten, links, oben.
        double[][] centers = {{half, half}, {-half, half}, {-half, -half}, {half, -half}};
        double[][] dirs = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}};
        double[][] starts = {{0.5, 0}, {half, 0.5}, {-0.5, half}, {-half, -0.5}};
        double[] firstLen = {half, straight, straight, straight};
        double pos = s;
        for (int side = 0; side < 4; side++) {
            double len = firstLen[side];
            if (pos <= len) {
                out[0] = starts[side][0] + dirs[side][0] * pos;
                out[1] = starts[side][1] + dirs[side][1] * pos;
                return;
            }
            pos -= len;
            if (pos <= arc) {
                double a = side * Math.PI / 2 + (r == 0 ? 0 : pos / r);
                out[0] = centers[side][0] + r * Math.cos(a);
                out[1] = centers[side][1] + r * Math.sin(a);
                return;
            }
            pos -= arc;
        }
        out[0] = 0.5;
        out[1] = -half + Math.max(0, Math.min(half, pos));
    }

    /** Lokal nach Welt: spiegeln, skalieren, drehen, verschieben. */
    public static void toWorld(Fixture f, double lx, double ly, double[] out) {
        double x = (f.flipX ? -lx : lx) * f.width;
        double y = (f.flipY ? -ly : ly) * f.height;
        double a = Math.toRadians(f.rotation), c = Math.cos(a), s = Math.sin(a);
        out[0] = f.x + x * c - y * s;
        out[1] = f.y + x * s + y * c;
    }

    /** Welt nach lokal. */
    public static void toLocal(Fixture f, double wx, double wy, double[] out) {
        double a = Math.toRadians(-f.rotation), c = Math.cos(a), s = Math.sin(a);
        double dx = wx - f.x, dy = wy - f.y;
        double x = dx * c - dy * s, y = dx * s + dy * c;
        out[0] = (f.flipX ? -x : x) / Math.max(1e-6, f.width);
        out[1] = (f.flipY ? -y : y) / Math.max(1e-6, f.height);
    }
}
