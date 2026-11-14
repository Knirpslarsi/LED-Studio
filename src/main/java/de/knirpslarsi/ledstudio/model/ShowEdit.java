package de.knirpslarsi.ledstudio.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Bearbeiten einer Show: Einrasten, Löschen, Kopieren, Einfügen, Verschieben. Ohne Oberfläche, damit testbar. */
public final class ShowEdit {
    private ShowEdit() {
    }

    /** Woran Zeiten einrasten. */
    public static final class Snap {
        public boolean enabled = true, beats = true, markers = true, clips = true, playhead = true;
    }

    /** Ein kopierter Clip und seine Spur relativ zur obersten kopierten Spur. */
    public record Copied(int trackOffset, Show.Clip clip) {
    }

    /**
     * Nächster Einrastpunkt innerhalb der Toleranz (Sekunden), sonst t selbst. Bewegte Clips (exclude) und eine bewegte
     * Markierung zählen nicht als Ziel.
     */
    public static double snap(double t, double tolerance, Snap o, Show show, List<Double> beats, boolean halfBeats,
                              double playhead, Set<Show.Clip> exclude, Show.Marker movingMarker) {
        if (o == null || !o.enabled) return Math.max(0, t);
        double best = t, dist = tolerance;
        if (o.beats && beats != null) {
            for (int i = 0; i < beats.size(); i++) {
                double b = beats.get(i);
                if (Math.abs(b - t) < dist) {
                    dist = Math.abs(b - t);
                    best = b;
                }
                if (halfBeats && i + 1 < beats.size()) {
                    double h = (b + beats.get(i + 1)) / 2;
                    if (Math.abs(h - t) < dist) {
                        dist = Math.abs(h - t);
                        best = h;
                    }
                }
            }
        }
        if (o.markers) {
            for (Show.Marker m : show.markers) {
                if (m != movingMarker && Math.abs(m.time - t) < dist) {
                    dist = Math.abs(m.time - t);
                    best = m.time;
                }
            }
        }
        if (o.clips) {
            for (Show.Track tr : show.tracks) {
                for (Show.Clip c : tr.clips) {
                    if (exclude != null && exclude.contains(c)) continue;
                    if (Math.abs(c.start - t) < dist) {
                        dist = Math.abs(c.start - t);
                        best = c.start;
                    }
                    if (Math.abs(c.end - t) < dist) {
                        dist = Math.abs(c.end - t);
                        best = c.end;
                    }
                }
            }
        }
        if (o.playhead && Math.abs(playhead - t) < dist) best = playhead;
        return Math.max(0, best);
    }

    /** Nächster Schlag nach t (dir 1) oder davor (dir -1); ohne Beats in halben Sekunden. */
    public static double step(double t, int dir, List<Double> beats) {
        double eps = 1e-3;
        if (beats != null && beats.size() >= 2) {
            if (dir > 0) {
                for (double b : beats) if (b > t + eps) return b;
                double last = beats.get(beats.size() - 1), len = last - beats.get(beats.size() - 2);
                return t + len;
            }
            for (int i = beats.size() - 1; i >= 0; i--) if (beats.get(i) < t - eps) return beats.get(i);
            return 0;
        }
        double grid = 0.5;
        return Math.max(0, dir > 0 ? Math.floor(t / grid + eps) * grid + grid : Math.ceil(t / grid - eps) * grid - grid);
    }

    public static Show.Track trackOf(Show show, Show.Clip c) {
        for (Show.Track t : show.tracks) if (t.clips.contains(c)) return t;
        return null;
    }

    public static int delete(Show show, Collection<Show.Clip> clips) {
        int n = 0;
        for (Show.Track t : show.tracks) {
            int before = t.clips.size();
            t.clips.removeAll(clips);
            n += before - t.clips.size();
        }
        return n;
    }

    /** Kopien der Clips mit ihrer Spur relativ zur obersten. */
    public static List<Copied> copy(Show show, Collection<Show.Clip> clips) {
        int top = Integer.MAX_VALUE;
        for (Show.Clip c : clips) top = Math.min(top, show.tracks.indexOf(trackOf(show, c)));
        List<Copied> out = new ArrayList<>();
        for (Show.Clip c : clips) {
            int i = show.tracks.indexOf(trackOf(show, c));
            if (i >= 0) out.add(new Copied(i - top, c.copy()));
        }
        return out;
    }

    /** Fügt Kopien so ein, dass der früheste Clip bei at beginnt und die oberste Spur auf firstTrack liegt. */
    public static List<Show.Clip> paste(Show show, List<Copied> copied, double at, int firstTrack) {
        List<Show.Clip> added = new ArrayList<>();
        if (copied.isEmpty() || show.tracks.isEmpty()) return added;
        double min = Double.MAX_VALUE;
        for (Copied c : copied) min = Math.min(min, c.clip().start);
        for (Copied c : copied) {
            Show.Clip clip = c.clip().copy();
            double len = clip.length();
            clip.start = Math.max(0, at + clip.start - min);
            clip.end = clip.start + len;
            int ti = Math.max(0, Math.min(show.tracks.size() - 1, firstTrack + c.trackOffset()));
            show.tracks.get(ti).clips.add(clip);
            added.add(clip);
        }
        return added;
    }

    /** Verschiebt Clips um delta Sekunden, ohne dass einer vor 0 rutscht. Gibt die tatsächliche Verschiebung zurück. */
    public static double move(Collection<Show.Clip> clips, double delta) {
        double min = Double.MAX_VALUE;
        for (Show.Clip c : clips) min = Math.min(min, c.start);
        if (min + delta < 0) delta = -min;
        for (Show.Clip c : clips) {
            c.start += delta;
            c.end += delta;
        }
        return delta;
    }

    /** Hat der Effekt des Clips einen Zufalls-Startwert? */
    public static boolean hasSeed(Show.Clip c) {
        de.knirpslarsi.ledstudio.effects.Effect e = de.knirpslarsi.ledstudio.effects.Effects.create(c.look.effect);
        return e != null && e.params().stream().anyMatch(p -> p.name.equals("seed"));
    }

    /**
     * Neu würfeln: neuer Zufalls-Startwert für den Effekt und, wenn der Clip zufällige LEDs hat, eine neue Auswahl aus
     * den LEDs der Spur (trackLeds als "bauteil#index").
     */
    public static void reroll(Show.Clip c, List<String> trackLeds, java.util.Random rnd) {
        if (hasSeed(c)) c.look.params.put("seed", (double) rnd.nextInt(10000));
        if (c.randomLeds > 0 && !trackLeds.isEmpty()) {
            List<String> pool = new ArrayList<>(trackLeds);
            java.util.Collections.shuffle(pool, rnd);
            c.targets = new ArrayList<>();
            for (String led : pool.subList(0, Math.min(c.randomLeds, pool.size()))) c.targets.add("led:" + led);
        }
    }

    /** Mischt die eigenen LEDs der Clips untereinander (z. B. eine Sequenz in zufälliger Reihenfolge). */
    public static void shuffleLeds(List<Show.Clip> clips, java.util.Random rnd) {
        List<List<String>> targets = new ArrayList<>();
        for (Show.Clip c : clips) targets.add(c.targets);
        java.util.Collections.shuffle(targets, rnd);
        for (int i = 0; i < clips.size(); i++) clips.get(i).targets = new ArrayList<>(targets.get(i));
    }

    /** Hängt Kopien direkt hinter den Clips an (Duplizieren). */
    public static List<Show.Clip> duplicate(Show show, Collection<Show.Clip> clips) {
        double min = Double.MAX_VALUE, max = 0;
        for (Show.Clip c : clips) {
            min = Math.min(min, c.start);
            max = Math.max(max, c.end);
        }
        List<Show.Clip> added = new ArrayList<>();
        for (Show.Clip c : clips) {
            Show.Track t = trackOf(show, c);
            if (t == null) continue;
            Show.Clip copy = c.copy();
            copy.start += max - min;
            copy.end += max - min;
            t.clips.add(copy);
            added.add(copy);
        }
        return added;
    }
}
