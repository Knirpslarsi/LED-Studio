package de.knirpslarsi.ledstudio.effects;

import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.CompiledLayout.Led;
import de.knirpslarsi.ledstudio.util.Colors;
import de.knirpslarsi.ledstudio.util.Noise;

import java.util.List;

import static de.knirpslarsi.ledstudio.effects.ParamSpec.*;

/**
 * Animationen mit Anfang und Ende: Startanimation, Spielstart und Spiel-Ereignisse. Sie kennen ihre Länge (die Engine
 * setzt "_length"), laufen also einmal durch. Ohne Länge, z. B. in einer Vorlage, wiederholen sie sich.
 * Alle rechnen in Licht (0..1 je Kanal, additiv) und nutzen die echte Lage der LEDs: Ringe der Lüfter, Abstand zur Mitte,
 * oben und unten.
 */
final class Animations {
    private Animations() {
    }

    static double clamp(double v) {
        return v < 0 ? 0 : Math.min(1, v);
    }

    static double easeOut(double x) {
        x = clamp(x);
        return 1 - (1 - x) * (1 - x) * (1 - x);
    }

    static double smooth(double a, double b, double x) {
        double t = clamp((x - a) / (b - a));
        return t * t * (3 - 2 * t);
    }

    static double gauss(double x, double w) {
        return Math.exp(-(x * x) / (w * w));
    }

    static double hash(long a, long b, long c) {
        return RandomBlink.hash(a, b, c);
    }

    /** Gemeinsames Gerüst: Fortschritt, Lichtpuffer, Lage der LEDs. */
    abstract static class Anim extends Effect {
        private final String id, title, blurb;
        private final List<ParamSpec> specs;
        /** Länge einer Runde, wenn keine Länge vorgegeben ist (Sekunden). */
        private final double loop;
        double[] r = new double[0], g = new double[0], b = new double[0];
        /** Lage je LED: Abstand zur Mitte (0..1), x und y (0..1), Platz im Ring bzw. Streifen (0..1), Bauteil 0..1. */
        double[] dist = new double[0], nx = new double[0], ny = new double[0], ring = new double[0], part = new double[0];
        int parts = 1;
        CompiledLayout seen;

        Anim(String id, String title, String blurb, double loop, List<ParamSpec> specs) {
            this.id = id;
            this.title = title;
            this.blurb = blurb;
            this.loop = loop;
            this.specs = specs;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public String blurb() {
            return blurb;
        }

        @Override
        public List<ParamSpec> params() {
            return specs;
        }

        private void geometry(CompiledLayout l) {
            int n = l.leds.length;
            seen = l;
            r = new double[n];
            g = new double[n];
            b = new double[n];
            dist = new double[n];
            nx = new double[n];
            ny = new double[n];
            ring = new double[n];
            part = new double[n];
            double w = Math.max(0.01, l.width), h = Math.max(0.01, l.height);
            double cx = w / 2, cy = h / 2, maxD = Math.max(0.01, Math.hypot(cx, cy));
            int maxFix = 0;
            for (Led e : l.leds) maxFix = Math.max(maxFix, e.fixture);
            parts = maxFix + 1;
            for (int i = 0; i < n; i++) {
                Led e = l.leds[i];
                nx[i] = e.x / w;
                ny[i] = e.y / h;
                dist[i] = Math.hypot(e.x - cx, e.y - cy) / maxD;
                ring[i] = (e.k + 0.5) / Math.max(1, e.m);
                part[i] = parts <= 1 ? 0.5 : (double) e.fixture / (parts - 1);
            }
        }

        @Override
        public final void render(Frame f) {
            if (f.layout != seen || r.length != f.out.length) geometry(f.layout);
            java.util.Arrays.fill(r, 0);
            java.util.Arrays.fill(g, 0);
            java.util.Arrays.fill(b, 0);
            double len = f.p.d("_length");
            double t = Math.max(0, f.t);
            double length = len > 0 ? len : loop;
            double time = len > 0 ? t : t % loop;
            draw(f, time, len > 0 ? clamp(time / length) : time / length, length);
            for (int i = 0; i < f.out.length; i++) f.out[i] = Colors.packD(tone(r[i]) * 255, tone(g[i]) * 255, tone(b[i]) * 255);
        }

        /** Weiche Begrenzung: Überstrahlen wird weiß statt hart abgeschnitten. */
        private static double tone(double v) {
            return v <= 0.85 ? Math.max(0, v) : 0.85 + 0.15 * (1 - Math.exp(-(v - 0.85) / 0.15));
        }

        /** t Sekunden seit Start, p Fortschritt 0..1, length Gesamtlänge. */
        abstract void draw(Frame f, double t, double p, double length);

        void add(int i, int color, double amount) {
            if (amount <= 0) return;
            r[i] += Colors.r(color) / 255.0 * amount;
            g[i] += Colors.g(color) / 255.0 * amount;
            b[i] += Colors.b(color) / 255.0 * amount;
        }

        void white(int i, double amount) {
            if (amount <= 0) return;
            r[i] += amount;
            g[i] += amount;
            b[i] += amount;
        }

        /** Farbe zwischen zwei Farben, mit Regenbogen-Option. */
        static int mix(Params p, double x, double hueShift) {
            if (p.b("rainbow")) return Colors.hsv(x + hueShift, 1, 1);
            return Colors.lerp(p.color("color_a"), p.color("color_b"), clamp(x));
        }
    }

    private static ParamSpec colorA(String def) {
        return color("color_a", "Farbe 1", def);
    }

    private static ParamSpec colorB(String def) {
        return color("color_b", "Farbe 2", def);
    }

    private static ParamSpec rainbow() {
        return bool("rainbow", "Regenbogen statt Farben", false);
    }

    // ---------------------------------------------------------------- Hochfahren

    /** Startanimation: Lüfter zünden nacheinander, drehen hoch, Lichtblitz, danach ein ruhiger Farbwirbel. */
    static final class Boot extends Anim {
        Boot() {
            super("anim_boot", "Animation: Hochfahren", "Lüfter zünden nacheinander, drehen hoch, ein Lichtblitz geht "
                    + "durch alles und es bleibt ein Farbwirbel. Gemacht für den Start.", 4,
                    List.of(colorA("#A020FF"), colorB("#00DDFF"), rainbow(),
                            num("ignite", "Zeit zum Zünden (Anteil)", 0.38, 0.1, 0.7, 0.01),
                            num("flash", "Stärke des Blitzes", 1, 0, 2, 0.05),
                            num("sparkle", "Funkeln", 0.5, 0, 1, 0.01)));
        }

        @Override
        void draw(Frame f, double t, double p, double length) {
            double ignite = f.p.d("ignite"), burst = ignite + 0.18, flash = f.p.d("flash"), sparkle = f.p.d("sparkle");
            int frame = (int) (t * 30);
            for (int i = 0; i < r.length; i++) {
                int base = mix(f.p, 0.5 * nx[i] + 0.5 * part[i], 0);
                // 1) Zünden: jedes Bauteil füllt sich mit einem glühenden Kopf, eins nach dem anderen.
                double start = 0.03 + (ignite - 0.12) * part[i];
                double local = (p - start) / 0.12;
                double before = 1 - smooth(burst - 0.02, burst + 0.08, p);
                if (local > 0 && before > 0) {
                    double fill = Math.min(1, local);
                    double a = ring[i];
                    if (a <= fill) {
                        double glow = local < 1 ? 0.25 + 0.75 * Math.exp(-(fill - a) * 7) : 0.3;
                        add(i, base, glow * before);
                    }
                    if (local < 1) white(i, gauss(a - fill, 0.05) * 1.3 * before);
                }
                // 2) Hochdrehen: zwei Flügel je Bauteil, immer schneller und heller.
                double spin = smooth(ignite, burst, p) * before;
                if (spin > 0) {
                    double turns = 6 * Math.pow((p - ignite) / Math.max(0.01, burst - ignite), 2);
                    double arm = 0.5 + 0.5 * Math.cos(2 * Math.PI * (ring[i] * 2 - turns));
                    add(i, base, spin * (0.2 + 1.1 * Math.pow(arm, 3)));
                }
                // 3) Blitz und Schockwelle aus der Mitte.
                if (p >= burst) {
                    double since = (p - burst) * length;
                    white(i, flash * Math.exp(-since * 9));
                    double radius = easeOut(since / 0.7) * 1.4;
                    double wave = gauss(dist[i] - radius, 0.12) * Math.exp(-since * 2.2) * flash;
                    white(i, wave * 0.8);
                    add(i, base, wave);
                }
                // 4) Danach: Farbwirbel mit Funkeln, wird zum Ende ruhiger.
                double settle = smooth(burst + 0.02, burst + 0.15, p);
                if (settle > 0) {
                    double swirl = 0.5 + 0.5 * Math.sin(2 * Math.PI * (ring[i] + nx[i] * 0.8 - t * 0.6));
                    int c = mix(f.p, swirl, t * 0.1);
                    add(i, c, settle * (0.45 + 0.4 * swirl));
                    if (sparkle > 0 && hash(i, frame, 7) > 1 - 0.04 * sparkle) white(i, settle * 0.9);
                }
            }
        }
    }

    // ---------------------------------------------------------------- Lüfter hochdrehen

    /** Spielstart: jeder Lüfter dreht hoch wie eine Turbine, die Flügel werden zum vollen Ring, dann ein Puls. */
    static final class SpinUp extends Anim {
        SpinUp() {
            super("anim_spin", "Animation: Turbine", "Jeder Lüfter dreht hoch wie eine Turbine, bis er als voller Ring "
                    + "glüht, dann ein heller Puls. Gemacht für den Spielstart.", 3,
                    List.of(colorA("#FF7A00"), colorB("#FFD000"), rainbow(),
                            integer("arms", "Flügel", 3, 1, 6),
                            num("turns", "Umdrehungen", 9, 1, 30, 0.5),
                            num("offset", "Versatz zwischen Lüftern", 0.15, 0, 0.5, 0.01),
                            bool("alternate", "Abwechselnd gegenläufig", true)));
        }

        @Override
        void draw(Frame f, double t, double p, double length) {
            int arms = Math.max(1, f.p.i("arms"));
            double turns = f.p.d("turns"), offset = f.p.d("offset");
            boolean alternate = f.p.b("alternate");
            for (int i = 0; i < r.length; i++) {
                Led e = seen.leds[i];
                double delay = offset * part[i];
                double q = clamp((p - delay) / Math.max(0.2, 0.75 - delay));
                if (q <= 0) continue;
                double dir = alternate && e.fixture % 2 == 1 ? -1 : 1;
                // Beschleunigen: Winkel wächst mit q², die Flügel werden breiter, bis der Ring geschlossen ist.
                double angle = dir * turns * q * q;
                double width = 0.08 + 0.9 * Math.pow(q, 3);
                double phase = ((ring[i] * arms - angle) % 1 + 1) % 1;
                double bladeDist = Math.min(phase, 1 - phase) / arms;
                double blade = Math.max(0, 1 - bladeDist / (width / arms + 1e-3));
                double heat = 0.25 + 0.75 * q;
                int c = mix(f.p, 0.3 * ring[i] + 0.7 * q, t * 0.2);
                add(i, c, heat * (0.15 + Math.pow(blade, 1.5)));
                white(i, Math.pow(blade, 6) * q * 0.6);
                // Puls zum Schluss.
                double pulse = gauss(p - 0.82, 0.06);
                white(i, pulse * 0.9);
                add(i, c, pulse);
                // Ausklang: ruhig glühender Ring.
                add(i, c, smooth(0.8, 0.95, p) * 0.5);
            }
        }
    }

    // ---------------------------------------------------------------- Schockwelle

    static final class Shockwave extends Anim {
        Shockwave() {
            super("anim_shockwave", "Animation: Schockwelle", "Ringe aus Licht laufen von einem Punkt über alle LEDs, "
                    + "mit weißer Vorderkante und farbigem Nachglühen.", 2,
                    List.of(colorA("#2080FF"), colorB("#00FFE0"), rainbow(),
                            choice("origin", "Ursprung", "center", "center", "Mitte", "bottom", "Unten", "top", "Oben",
                                    "left", "Links", "right", "Rechts"),
                            integer("waves", "Wellen", 3, 1, 8),
                            num("width", "Breite", 0.12, 0.03, 0.5, 0.01),
                            num("spark", "Funken", 0.4, 0, 1, 0.01)));
        }

        @Override
        void draw(Frame f, double t, double p, double length) {
            String origin = f.p.s("origin");
            int waves = Math.max(1, f.p.i("waves"));
            double width = f.p.d("width"), spark = f.p.d("spark");
            int frame = (int) (t * 25);
            for (int i = 0; i < r.length; i++) {
                double d = switch (origin) {
                    case "bottom" -> 1 - ny[i];
                    case "top" -> ny[i];
                    case "left" -> nx[i];
                    case "right" -> 1 - nx[i];
                    default -> dist[i];
                };
                // Kurzer Kern-Blitz am Ursprung.
                white(i, Math.exp(-t * 14) * gauss(d, 0.25));
                for (int w = 0; w < waves; w++) {
                    double start = w * 0.55 / waves;
                    double q = (p - start) / 0.6;
                    if (q <= 0 || q > 1.2) continue;
                    double radius = easeOut(q) * 1.35;
                    double fade = 1 - smooth(0.6, 1.2, q);
                    double x = d - radius;
                    double edge = gauss(x, width * 0.35) * fade;
                    double trail = x < 0 ? Math.exp(x / (width * 2.5)) * 0.45 * fade : 0;
                    int c = mix(f.p, clamp(d + w * 0.25), 0.15 * w);
                    white(i, edge * 0.9);
                    add(i, c, edge * 1.2 + trail);
                    if (spark > 0 && trail > 0.05 && hash(i, frame, w) > 1 - 0.12 * spark) white(i, trail * 2);
                }
            }
        }
    }

    // ---------------------------------------------------------------- Explosion

    static final class Explosion extends Anim {
        Explosion() {
            super("anim_explosion", "Animation: Explosion", "Greller Blitz, ein Feuerball wächst brodelnd aus der Mitte, "
                    + "kühlt ab und lässt glühende Funken zurück.", 3.5,
                    List.of(color("color_hot", "Kern", "#FFF2C0"), color("color_mid", "Feuer", "#FF5A00"),
                            color("color_cool", "Glut", "#7A0000"),
                            num("size", "Größe", 1.2, 0.3, 2, 0.05),
                            num("embers", "Glut-Funken", 0.6, 0, 1, 0.01)));
        }

        @Override
        void draw(Frame f, double t, double p, double length) {
            int hot = f.p.color("color_hot"), mid = f.p.color("color_mid"), cool = f.p.color("color_cool");
            double size = f.p.d("size"), embers = f.p.d("embers");
            double radius = easeOut(t / 0.9) * size;
            double cooling = smooth(0.15, 0.85, p);
            int slot = (int) (t * 12);
            for (int i = 0; i < r.length; i++) {
                Led e = seen.leds[i];
                white(i, 1.6 * Math.exp(-t * 11));
                double turb = Noise.fbm(e.x * 5, e.y * 5 - t * 1.5, t * 0.9, 3);
                double inside = radius - dist[i] + (turb - 0.5) * 0.35;
                if (inside > 0) {
                    double heat = clamp(inside / Math.max(0.05, radius) * 1.6 + 0.2) * (1 - cooling);
                    int c = heat > 0.66 ? Colors.lerp(mid, hot, (heat - 0.66) / 0.34)
                            : heat > 0.33 ? Colors.lerp(cool, mid, (heat - 0.33) / 0.33) : Colors.scale(cool, heat / 0.33);
                    add(i, c, 0.35 + heat * 1.1);
                }
                // Druckwelle am Rand.
                add(i, mid, gauss(dist[i] - radius * 1.1, 0.08) * (1 - cooling) * 1.2);
                // Glut: zufällige Funken, die langsam verlöschen.
                if (embers > 0 && p > 0.25) {
                    double h = hash(i, slot, 3);
                    if (h > 1 - 0.18 * embers) {
                        double flicker = 0.4 + 0.6 * hash(i, slot, 9);
                        add(i, Colors.lerp(mid, hot, flicker * 0.5), flicker * (1 - p) * 1.2);
                    } else {
                        add(i, cool, 0.12 * (1 - p) * smooth(0.25, 0.4, p));
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Warp

    static final class Warp extends Anim {
        Warp() {
            super("anim_warp", "Animation: Hyperraum", "Sterne rasen aus der Mitte nach außen, immer schneller, "
                    + "bis alles in einem Lichtblitz endet.", 3,
                    List.of(colorA("#80C8FF"), colorB("#C060FF"), rainbow(),
                            integer("lanes", "Sternbahnen", 24, 6, 64),
                            num("trail", "Schweif", 0.25, 0.05, 0.8, 0.01)));
        }

        @Override
        void draw(Frame f, double t, double p, double length) {
            int lanes = Math.max(6, f.p.i("lanes"));
            double trail = f.p.d("trail");
            // Zurückgelegter Weg: Tempo wächst stark an.
            double travel = t * 0.4 + Math.pow(p, 3) * length * 2.2;
            double cx = 0.5, cy = 0.5;
            for (int i = 0; i < r.length; i++) {
                double ang = Math.atan2(ny[i] - cy, nx[i] - cx) / (2 * Math.PI) + 0.5;
                int lane = (int) (ang * lanes) % lanes;
                for (int k = 0; k < 3; k++) {
                    double phase = hash(lane, k, 11);
                    double pos = ((phase + travel * (0.7 + 0.6 * hash(lane, k, 12))) % 1.0) * 1.3;
                    double x = dist[i] - pos;
                    double head = gauss(x, 0.05);
                    double tail = x < 0 ? Math.exp(x / (trail * (0.4 + p))) : 0;
                    int c = mix(f.p, hash(lane, k, 13), t * 0.2);
                    add(i, c, (head + tail * 0.6) * (0.3 + p));
                    white(i, head * 0.6 * p);
                }
                add(i, mix(f.p, 0.5, 0), 0.08 + 0.3 * Math.pow(p, 4));
                white(i, smooth(0.9, 0.97, p) * (1 - smooth(0.97, 1, p)) * 1.5);
            }
        }
    }

    // ---------------------------------------------------------------- Scanner

    static final class Scan extends Anim {
        Scan() {
            super("anim_scan", "Animation: Scanner", "Eine helle Linie fährt über alles, eine zweite kommt zurück, "
                    + "zum Schluss ein kurzes Aufleuchten.", 1.2,
                    List.of(colorA("#FFFFFF"), colorB("#00E0FF"), rainbow(),
                            choice("axis", "Richtung", "x", "x", "Links → rechts", "y", "Oben → unten"),
                            num("width", "Linienbreite", 0.06, 0.01, 0.3, 0.01)));
        }

        @Override
        void draw(Frame f, double t, double p, double length) {
            boolean vertical = "y".equals(f.p.s("axis"));
            double width = f.p.d("width");
            double first = easeOut(p / 0.45) * 1.2 - 0.1, second = 1.1 - easeOut((p - 0.35) / 0.45) * 1.2;
            for (int i = 0; i < r.length; i++) {
                double x = vertical ? ny[i] : nx[i];
                if (p < 0.55) {
                    double d = x - first;
                    double line = gauss(d, width);
                    double glow = d < 0 ? Math.exp(d / 0.15) * 0.35 : 0;
                    add(i, f.p.color("color_a"), line * 1.3);
                    add(i, f.p.color("color_b"), glow);
                }
                if (p > 0.35 && p < 0.85) {
                    double d = second - x;
                    double line = gauss(d, width);
                    double glow = d < 0 ? Math.exp(d / 0.15) * 0.35 : 0;
                    add(i, f.p.color("color_b"), line * 1.3 + glow);
                }
                double lock = gauss(p - 0.88, 0.05);
                add(i, mix(f.p, x, 0), lock);
                white(i, lock * 0.4);
            }
        }
    }

    // ---------------------------------------------------------------- Feuerwerk

    static final class Fireworks extends Anim {
        Fireworks() {
            super("anim_fireworks", "Animation: Feuerwerk", "Raketen steigen auf und zerplatzen in bunte, funkelnde "
                    + "Ringe, die langsam herabsinken.", 4,
                    List.of(colorA("#00FF60"), colorB("#FFD000"), bool("rainbow", "Bunte Raketen", true),
                            integer("rockets", "Raketen", 6, 1, 16),
                            num("size", "Größe der Explosion", 0.45, 0.1, 1, 0.01)));
        }

        @Override
        void draw(Frame f, double t, double p, double length) {
            int rockets = Math.max(1, f.p.i("rockets"));
            double size = f.p.d("size");
            int frame = (int) (t * 20);
            for (int k = 0; k < rockets; k++) {
                double launch = (k + 0.3 * hash(k, 1, 1)) / rockets * length * 0.6;
                double rise = 0.45 + 0.25 * hash(k, 2, 1);
                double x0 = 0.1 + 0.8 * hash(k, 3, 1), y0 = 0.2 + 0.35 * hash(k, 4, 1);
                int c = f.p.b("rainbow") ? Colors.hsv(hash(k, 5, 1), 0.9, 1) : (k % 2 == 0 ? f.p.color("color_a") : f.p.color("color_b"));
                double since = t - launch;
                if (since < 0) continue;
                for (int i = 0; i < r.length; i++) {
                    if (since < rise) {
                        // Aufstieg: heller Punkt mit Schweif von unten.
                        double q = since / rise;
                        double y = 1.05 - (1.05 - y0) * easeOut(q);
                        double dx = (nx[i] - x0) * 3;
                        double dy = ny[i] - y;
                        double tail = dy > 0 ? Math.exp(-dy / 0.12) : gauss(dy, 0.03);
                        double v = gauss(dx, 0.12) * tail;
                        white(i, v * 0.8);
                        add(i, 0xFFA040, v * 0.6);
                    } else {
                        // Explosion: Ring wächst, sinkt ab, funkelt und verblasst.
                        double s = since - rise;
                        double radius = easeOut(s / 0.8) * size;
                        double cy = y0 + 0.12 * s * s;
                        double d = Math.hypot((nx[i] - x0) * seen.width / Math.max(seen.width, seen.height),
                                (ny[i] - cy) * seen.height / Math.max(seen.width, seen.height));
                        double fade = Math.exp(-s * 1.6);
                        double shell = gauss(d - radius, 0.07) * fade;
                        double inner = d < radius ? 0.25 * fade * (d / Math.max(0.01, radius)) : 0;
                        double twinkle = hash(i, frame, k) > 0.55 ? 1 : 0.35;
                        add(i, c, (shell * 1.3 + inner) * twinkle);
                        white(i, Math.exp(-s * 12) * gauss(d, 0.12) * 1.2);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Treffer

    static final class Hit extends Anim {
        Hit() {
            super("anim_hit", "Animation: Treffer", "Ein harter Lichtschlag, Funken stieben auseinander, "
                    + "die Ringe blitzen kurz auf.", 0.6,
                    List.of(colorA("#FFFFFF"), colorB("#FF3010"), rainbow(),
                            num("punch", "Wucht", 1, 0.2, 2, 0.05),
                            num("sparks", "Funken", 0.5, 0, 1, 0.01),
                            bool("ring", "Ring-Welle", true)));
        }

        @Override
        void draw(Frame f, double t, double p, double length) {
            double punch = f.p.d("punch"), sparks = f.p.d("sparks");
            boolean ringWave = f.p.b("ring");
            int a = f.p.color("color_a"), c = f.p.color("color_b");
            for (int i = 0; i < r.length; i++) {
                double hitV = Math.exp(-t * 18) * punch;
                add(i, a, hitV * 1.2);
                add(i, c, Math.exp(-t * 5) * 0.5 * punch);
                if (ringWave) {
                    double radius = easeOut(p) * 1.3;
                    add(i, c, gauss(dist[i] - radius, 0.1) * (1 - p) * punch);
                }
                if (sparks > 0) {
                    // Jede LED bekommt einen eigenen Moment, in dem ein Funke vorbeifliegt.
                    double when = hash(i, 17, 1) * 0.6;
                    if (hash(i, 18, 1) < sparks * 0.5) white(i, gauss(p - when, 0.05) * 1.2 * (1 - p));
                }
            }
        }
    }

    // ---------------------------------------------------------------- Alarm (Bombe)

    static final class Alarm extends Anim {
        Alarm() {
            super("anim_alarm", "Animation: Countdown", "Piepende Blitze wie bei einer Bombe: immer schneller, mit "
                    + "kreisendem Warnlicht, am Ende fast durchgehend.", 40,
                    List.of(colorA("#FF0000"), colorB("#FF6000"), rainbow(),
                            num("start", "Abstand am Anfang (s)", 1.0, 0.2, 3, 0.05),
                            num("end", "Abstand am Ende (s)", 0.1, 0.03, 1, 0.01),
                            num("sweep", "Kreisendes Warnlicht", 0.6, 0, 1, 0.01)));
        }

        @Override
        void draw(Frame f, double t, double p, double length) {
            double start = f.p.d("start"), end = f.p.d("end"), sweep = f.p.d("sweep");
            // Abstand der Pieptöne fällt exponentiell; Phase = Integral der Frequenz.
            double k = Math.log(start / end);
            double phase = k <= 1e-6 ? t / start : length / (start * k) * (Math.exp(k * p) - 1);
            double frac = phase - Math.floor(phase);
            double interval = start * Math.exp(-k * p);
            double beep = Math.exp(-frac * interval / 0.08);
            double rot = t * (0.6 + 3 * p * p);
            int a = f.p.color("color_a"), c = f.p.color("color_b");
            for (int i = 0; i < r.length; i++) {
                add(i, a, beep * 1.2);
                white(i, beep * beep * 0.35);
                double ember = 0.06 + 0.12 * p;
                add(i, a, ember);
                if (sweep > 0) {
                    double phaseRing = ((ring[i] - rot) % 1 + 1) % 1;
                    double light = Math.pow(Math.max(0, Math.cos(Math.PI * phaseRing)), 8);
                    add(i, c, light * sweep * (0.3 + 0.7 * p));
                }
                add(i, a, smooth(0.9, 1, p) * 0.8);
            }
        }
    }

    // ---------------------------------------------------------------- Tod

    static final class Flatline extends Anim {
        Flatline() {
            super("anim_flatline", "Animation: Ausgelöscht", "Roter Schlag, dann läuft das Licht wie Farbe nach unten ab "
                    + "und verlischt.", 2.5,
                    List.of(colorA("#FF0010"), colorB("#300000"), rainbow(),
                            num("drip", "Tropfen", 0.5, 0, 1, 0.01)));
        }

        @Override
        void draw(Frame f, double t, double p, double length) {
            int a = f.p.color("color_a"), dark = f.p.color("color_b");
            double drip = f.p.d("drip");
            double level = smooth(0.08, 0.95, p) * 1.25 - 0.1;
            for (int i = 0; i < r.length; i++) {
                Led e = seen.leds[i];
                white(i, Math.exp(-t * 12) * 0.8);
                double edge = level + (Noise.at(e.x * 9, 0, 3) - 0.5) * 0.25 * drip + drip * 0.2 * Math.pow(hash(e.fixture, e.k / 3, 5), 6);
                double below = ny[i] - edge;
                if (below > 0) {
                    double v = Math.exp(-below * 2.5) * 0.9 + 0.1;
                    add(i, Colors.lerp(a, dark, smooth(0.3, 1, p)), v * (1 - smooth(0.8, 1, p)));
                    add(i, a, gauss(below, 0.04) * 0.8 * (1 - p));
                }
            }
        }
    }

    // ---------------------------------------------------------------- Zerfall

    static final class Shatter extends Anim {
        Shatter() {
            super("anim_shatter", "Animation: Zerfall", "Alles leuchtet auf und zerfällt dann flackernd LED für LED.", 2.5,
                    List.of(colorA("#FF2020"), colorB("#600000"), rainbow(),
                            num("flicker", "Flackern", 0.6, 0, 1, 0.01)));
        }

        @Override
        void draw(Frame f, double t, double p, double length) {
            double flicker = f.p.d("flicker");
            int frame = (int) (t * 24);
            for (int i = 0; i < r.length; i++) {
                double die = 0.2 + 0.75 * hash(i, 21, 2);
                double alive = 1 - smooth(die - 0.04, die, p);
                if (alive <= 0) continue;
                double warn = smooth(die - 0.2, die - 0.04, p);
                double on = warn > 0 && hash(i, frame, 4) < warn * flicker ? 0.15 : 1;
                int c = mix(f.p, warn, 0);
                add(i, c, alive * on * (0.9 + 0.4 * Math.exp(-t * 8)));
                white(i, Math.exp(-t * 10) * 0.6);
            }
        }
    }

    // ---------------------------------------------------------------- Herzschlag (wiederholt)

    static final class Heartbeat extends Anim {
        Heartbeat() {
            super("anim_heartbeat", "Animation: Herzrasen", "Doppelter Herzschlag, der von der Mitte nach außen pocht, "
                    + "mit dunklem Rand. Läuft, solange er gebraucht wird.", 1.0,
                    List.of(colorA("#FF0018"), colorB("#500000"), rainbow(),
                            num("bpm", "Schläge pro Minute", 110, 40, 200, 1)));
        }

        @Override
        void draw(Frame f, double t, double p, double length) {
            double beat = 60 / Math.max(30, f.p.d("bpm"));
            double q = (f.t % beat) / beat;
            double pulse = Math.exp(-q * 18) + 0.6 * Math.exp(-Math.max(0, q - 0.22) * 16) * (q > 0.22 ? 1 : 0);
            int a = f.p.color("color_a"), dark = f.p.color("color_b");
            for (int i = 0; i < r.length; i++) {
                double wave = gauss(dist[i] - q * 1.6, 0.25);
                add(i, dark, 0.35);
                add(i, a, pulse * (0.4 + 0.6 * (1 - dist[i])) + wave * pulse * 0.6);
            }
        }
    }

    static List<Effect> all() {
        return List.of(new Boot(), new SpinUp(), new Shockwave(), new Explosion(), new Warp(), new Scan(), new Fireworks(),
                new Hit(), new Alarm(), new Flatline(), new Shatter(), new Heartbeat());
    }
}
