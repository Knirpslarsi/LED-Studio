package de.knirpslarsi.ledstudio;

import de.knirpslarsi.ledstudio.audio.AudioEngine;
import de.knirpslarsi.ledstudio.audio.Music;
import de.knirpslarsi.ledstudio.effects.Effect;
import de.knirpslarsi.ledstudio.effects.Effects;
import de.knirpslarsi.ledstudio.effects.Params;
import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.LayoutTools;
import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.openrgb.Controller;
import de.knirpslarsi.ledstudio.openrgb.DemoBackend;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicTest {
    private static CompiledLayout layout() {
        List<Controller> c = new DemoBackend().controllers();
        return CompiledLayout.compile(LayoutTools.legacyNzxtChain(c), c);
    }

    /** Ein Takt aus kurzen Bass-Schlägen: alle period Sekunden für 40 ms laut, sonst leise. */
    private static double[] bassAt(double t, double period, int band) {
        double[] v = new double[4 + Music.SPECTRUM];
        boolean hit = (t % period) < 0.04;
        v[0] = hit ? 0.3 : 0.01;
        v[band] = hit ? 0.3 : 0.005;
        for (int s = 0; s < Music.SPECTRUM; s++) v[4 + s] = 0.002;
        return v;
    }

    private static int countBeats(boolean overlap, double period) {
        Music m = new Music();
        Project.Audio cfg = new Project.Audio();
        cfg.mode = "Beat-Balken";
        cfg.overlap = overlap;
        cfg.duration = 0.8;
        cfg.pause = 0.1;
        CompiledLayout l = layout();
        int[] out = new int[l.leds.length];
        double dt = 1.0 / 47; // ungefähr die Messrate der Audioaufnahme
        for (double t = 0.001; t < 6; t += dt) m.render(l, t, bassAt(t, period, 1), t, cfg, out);
        return m.beats;
    }

    @Test
    void noBeatIsSkippedWhileARunIsActive() {
        // 150 BPM: Ein Lauf (0,8 s) ist länger als der Abstand (0,4 s). Früher ging deshalb jeder zweite Beat verloren.
        int old = countBeats(false, 0.4), now = countBeats(true, 0.4);
        System.out.println("Beats in 6 s bei 150 BPM: früher " + old + ", jetzt " + now + " (erwartet 15)");
        assertTrue(now >= 14, "jetzt: " + now);
        assertTrue(old <= 8, "alte Variante überspringt: " + old);
    }

    @Test
    void threeBandBeatsReactPerFrequency() {
        Music m = new Music();
        Project.Audio cfg = new Project.Audio();
        cfg.mode = "Drei-Band-Beats";
        CompiledLayout l = layout();
        int[] out = new int[l.leds.length];
        double dt = 1.0 / 47;
        for (double t = 0.001; t < 3; t += dt) m.render(l, t, bassAt(t, 0.5, 3), t, cfg, out);
        assertTrue(m.bandBeats[3] >= 5, "Höhen: " + m.bandBeats[3]);
        assertEquals(0, m.bandBeats[1], "Bass schweigt");
    }

    @Test
    void equalizerLightsLowBandsLeft() {
        Music m = new Music();
        Project.Audio cfg = new Project.Audio();
        cfg.mode = "Equalizer";
        CompiledLayout l = layout();
        int[] out = new int[l.leds.length];
        double[] v = new double[4 + Music.SPECTRUM];
        for (int s = 0; s < Music.SPECTRUM; s++) v[4 + s] = s < 4 ? 0.3 : 0.0001;
        for (double t = 0.01; t < 1; t += 0.02) {
            v[4] = 0.3 * (0.8 + 0.2 * Math.sin(t * 9)); // Pegel schwankt, wie bei echter Musik
            m.render(l, t, v, t, cfg, out);
        }
        int left = 0, right = 0;
        for (int i = 0; i < l.leds.length; i++) {
            int lum = Math.max((out[i] >> 16) & 255, Math.max((out[i] >> 8) & 255, out[i] & 255));
            if (l.leds[i].x / l.width < 0.2) left = Math.max(left, lum);
            if (l.leds[i].x / l.width > 0.5) right = Math.max(right, lum);
        }
        assertTrue(left > 150 && right < 30, "links " + left + ", rechts " + right);
    }

    @Test
    void fixedTempoNeedsNoSound() {
        Music m = new Music();
        Project.Audio cfg = new Project.Audio();
        cfg.mode = "Beat-Ringe";
        cfg.trigger = "bpm";
        cfg.bpm = 120;
        CompiledLayout l = layout();
        int[] out = new int[l.leds.length];
        for (double t = 0; t < 2.0; t += 0.02) m.render(l, t, new double[0], 0, cfg, out);
        assertEquals(4, m.beats, "120 BPM sind 4 Schläge in 2 Sekunden");
    }

    @Test
    void pulseRingsAreANormalEffectAndFollowTheTempo() {
        Effect e = Effects.create("pulse_rings");
        assertTrue(!e.needsAudio(), "läuft ohne Ton");
        CompiledLayout l = layout();
        Effect.Frame f = new Effect.Frame();
        f.layout = l;
        f.out = new int[l.leds.length];
        f.p = new Params(e.params(), Map.of("origin", "fixture"));
        e.reset(l);
        boolean lit = false;
        for (int i = 0; i < 100; i++) {
            f.t = i * 0.02;
            e.render(f);
            for (int c : f.out) lit |= c > 0x101010 && ((c >> 16) & 255) > 60;
        }
        assertTrue(lit);
    }

    @Test
    void spectrumHasSixteenBandsInTheRightPlace() {
        float[] low = new float[2048 * 2], high = new float[2048 * 2];
        for (int i = 0; i < 2048; i++) {
            low[2 * i] = low[2 * i + 1] = (float) (0.5 * Math.sin(2 * Math.PI * 60 * i / 48000));
            high[2 * i] = high[2 * i + 1] = (float) (0.5 * Math.sin(2 * Math.PI * 8000 * i / 48000));
        }
        double[] a = AudioEngine.analyse(low, 2, 48000), b = AudioEngine.analyse(high, 2, 48000);
        assertEquals(4 + Music.SPECTRUM, a.length);
        assertTrue(argmax(a) <= 5, "60 Hz liegt ganz links: " + argmax(a));
        assertTrue(argmax(b) >= 4 + 13, "8 kHz liegt rechts: " + argmax(b));
    }

    private static int argmax(double[] v) {
        int best = 4;
        for (int i = 4; i < v.length; i++) if (v[i] > v[best]) best = i;
        return best;
    }

    @Test
    void everyMusicModeRendersWithSound() {
        CompiledLayout l = layout();
        for (String mode : Music.MODES) {
            Music m = new Music();
            Project.Audio cfg = new Project.Audio();
            cfg.mode = mode;
            int[] out = new int[l.leds.length];
            boolean lit = false;
            for (double t = 0.001; t < 3; t += 0.021) {
                double[] v = bassAt(t, 0.45, 1);
                for (int s = 0; s < Music.SPECTRUM; s++) v[4 + s] = 0.05 + 0.04 * Math.sin(t * 7 + s);
                v[2] = 0.1 + 0.08 * Math.sin(t * 5);
                v[3] = (t % 0.3) < 0.03 ? 0.2 : 0.01;
                java.util.Arrays.fill(out, 0x404040);
                m.render(l, t, v, t, cfg, out);
                for (int c : out) lit |= c != 0 && c != 0x404040;
            }
            assertTrue(lit, mode + " bleibt dunkel");
        }
    }
}
