package de.knirpslarsi.ledstudio;

import de.knirpslarsi.ledstudio.model.Show;
import de.knirpslarsi.ledstudio.model.ShowEdit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Markieren, Löschen, Kopieren, Einfügen, Verschieben und Einrasten auf der Zeitleiste. */
class ShowEditTest {
    private static Show.Clip clip(double start, double end) {
        Show.Clip c = new Show.Clip();
        c.start = start;
        c.end = end;
        return c;
    }

    private static Show show() {
        Show s = new Show();
        for (int i = 0; i < 3; i++) s.tracks.add(new Show.Track());
        s.tracks.get(0).clips.add(clip(1, 2));
        s.tracks.get(1).clips.add(clip(3, 4));
        s.tracks.get(2).clips.add(clip(5, 5.5));
        s.markers.add(new Show.Marker(7.3, "Refrain"));
        return s;
    }

    @Test
    void snapsToTheNearestEnabledTarget() {
        Show s = show();
        ShowEdit.Snap o = new ShowEdit.Snap();
        List<Double> beats = List.of(0.0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0);
        assertEquals(2.5, ShowEdit.snap(2.47, 0.1, o, s, beats, false, 9, null, null), 1e-9, "Beat");
        assertEquals(7.3, ShowEdit.snap(7.25, 0.1, o, s, beats, false, 9, null, null), 1e-9, "Markierung");
        assertEquals(5.5, ShowEdit.snap(5.53, 0.1, o, s, beats, false, 9, null, null), 1e-9, "Clip-Ende");
        assertEquals(9, ShowEdit.snap(8.95, 0.1, o, s, beats, false, 9, null, null), 1e-9, "Abspielkopf");
        assertEquals(8.5, ShowEdit.snap(8.5, 0.1, o, s, beats, false, 9, null, null), 1e-9, "nichts in der Nähe");
        o.markers = false;
        assertEquals(7.25, ShowEdit.snap(7.25, 0.1, o, s, beats, false, 9, null, null), 1e-9, "Markierungen aus");
        o.enabled = false;
        assertEquals(2.47, ShowEdit.snap(2.47, 0.1, o, s, beats, false, 9, null, null), 1e-9, "Einrasten aus");
        // Ein bewegter Clip rastet nicht an sich selbst ein.
        Show.Clip own = s.tracks.get(2).clips.get(0);
        o.enabled = true;
        assertEquals(5.53, ShowEdit.snap(5.53, 0.1, o, s, List.of(), false, 9, Set.of(own), null), 1e-9);
    }

    @Test
    void deleteCopyPasteAndDuplicateKeepTracks() {
        Show s = show();
        Show.Clip a = s.tracks.get(0).clips.get(0), b = s.tracks.get(1).clips.get(0);
        List<ShowEdit.Copied> copied = ShowEdit.copy(s, List.of(a, b));
        assertEquals(2, ShowEdit.delete(s, List.of(a, b)));
        assertEquals(0, s.tracks.get(0).clips.size());
        List<Show.Clip> pasted = ShowEdit.paste(s, copied, 10, 1);
        assertEquals(2, pasted.size());
        // Oberste kopierte Spur landet auf Spur 2 (Index 1), die zweite darunter; Abstände bleiben.
        assertEquals(10, s.tracks.get(1).clips.get(0).start, 1e-9);
        assertEquals(12, s.tracks.get(2).clips.get(1).start, 1e-9);
        List<Show.Clip> dup = ShowEdit.duplicate(s, pasted);
        assertEquals(13, dup.get(0).start, 1e-9, "direkt hinter dem Block");
    }

    @Test
    void moveNeverGoesBeforeZeroAndStepsByBeat() {
        Show s = show();
        Show.Clip a = s.tracks.get(0).clips.get(0), b = s.tracks.get(1).clips.get(0);
        double moved = ShowEdit.move(List.of(a, b), -5);
        assertEquals(-1, moved, 1e-9);
        assertEquals(0, a.start, 1e-9);
        assertEquals(2, b.start, 1e-9);
        List<Double> beats = List.of(0.0, 0.47, 0.94, 1.41);
        assertEquals(0.94, ShowEdit.step(0.5, 1, beats), 1e-9);
        assertEquals(0.47, ShowEdit.step(0.94, -1, beats), 1e-9);
        assertEquals(1.0, ShowEdit.step(0.7, 1, List.of()), 1e-9, "ohne Beats halbe Sekunden");
    }

    @Test
    void rerollGivesCopiesOtherLedsAndAnotherSeed() {
        List<String> pool = new java.util.ArrayList<>();
        for (int k = 0; k < 24; k++) pool.add("f#" + k);
        Show.Clip c = clip(0, 1);
        c.look.effect = "random_blink";
        c.randomLeds = 3;
        java.util.Random rnd = new java.util.Random(5);
        ShowEdit.reroll(c, pool, rnd);
        assertEquals(3, c.targets.size());
        Show.Clip copy = c.copy();
        java.util.Set<String> before = new java.util.HashSet<>(copy.targets);
        Object seed = copy.look.params.get("seed");
        ShowEdit.reroll(copy, pool, rnd);
        org.junit.jupiter.api.Assertions.assertNotEquals(before, new java.util.HashSet<>(copy.targets), "andere LEDs");
        org.junit.jupiter.api.Assertions.assertNotEquals(seed, copy.look.params.get("seed"), "anderer Zufall");
        assertEquals(3, new java.util.HashSet<>(copy.targets).size(), "keine LED doppelt");
    }

    @Test
    void shuffleKeepsEveryLedOnce() {
        List<Show.Clip> clips = new java.util.ArrayList<>();
        for (int k = 0; k < 8; k++) {
            Show.Clip c = clip(k, k + 1);
            c.targets.add("led:f#" + k);
            clips.add(c);
        }
        ShowEdit.shuffleLeds(clips, new java.util.Random(3));
        java.util.Set<String> all = new java.util.HashSet<>();
        for (Show.Clip c : clips) all.addAll(c.targets);
        assertEquals(8, all.size());
        int moved = 0;
        for (int k = 0; k < 8; k++) if (!clips.get(k).targets.get(0).equals("led:f#" + k)) moved++;
        org.junit.jupiter.api.Assertions.assertTrue(moved >= 4, "Reihenfolge wirklich gemischt: " + moved);
    }
}
