package de.knirpslarsi.ledstudio;

import de.knirpslarsi.ledstudio.audio.Music;
import de.knirpslarsi.ledstudio.audio.MusicAnalyzer;
import de.knirpslarsi.ledstudio.audio.AudioEngine;
import de.knirpslarsi.ledstudio.effects.Effect;
import de.knirpslarsi.ledstudio.effects.Effects;
import de.knirpslarsi.ledstudio.effects.ParamSpec;
import de.knirpslarsi.ledstudio.effects.Params;
import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.LayoutTools;
import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.openrgb.Controller;
import de.knirpslarsi.ledstudio.openrgb.DemoBackend;
import de.knirpslarsi.ledstudio.util.Colors;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Worauf ein Musik-Effekt reagiert, ist einstellbar und wirkt auch so. */
class SoundSourceTest {
    private static CompiledLayout layout() {
        List<Controller> c = new DemoBackend().controllers();
        return CompiledLayout.compile(LayoutTools.legacyNzxtChain(c), c);
    }

    /** Spektrum, in dem nur ein Bereich im Takt pulsiert, alles andere ist leise und gleichmäßig. */
    private static double[] pulse(double t, int fromBand, int toBand) {
        double[] v = new double[4 + Music.SPECTRUM];
        boolean on = (t % 0.5) < 0.15;
        for (int b = 0; b < Music.SPECTRUM; b++) v[4 + b] = b >= fromBand && b <= toBand && on ? 0.3 : 0.01;
        v[0] = on ? 0.2 : 0.05;
        v[1] = v[2] = v[3] = 0.05;
        return v;
    }

    /** Wie stark die Helligkeit schwankt: Unterschied zwischen hellstem und dunkelstem Bild. */
    private static double swing(String react, int fromBand, int toBand) {
        Music m = new Music(3);
        Project.Audio cfg = new Project.Audio();
        cfg.mode = "Pegel";
        cfg.react1 = react;
        cfg.dynamics = 0;
        cfg.spread = "chain";
        CompiledLayout l = layout();
        int[] out = new int[l.leds.length];
        double lo = 1e9, hi = 0;
        for (double t = 0.001; t < 6; t += 1 / 47.0) {
            m.render(l, t, pulse(t, fromBand, toBand), t, cfg, out);
            if (t < 2) continue;
            double sum = 0;
            for (int c : out) sum += Math.max(Colors.r(c), Math.max(Colors.g(c), Colors.b(c)));
            lo = Math.min(lo, sum);
            hi = Math.max(hi, sum);
        }
        return (hi - lo) / (255.0 * out.length);
    }

    @Test
    void reactsOnlyToTheChosenRange() {
        // Nur die Höhen pulsieren (Bänder 12–15).
        double high = swing("high", 12, 15), bass = swing("bass", 12, 15), voice = swing("voice", 12, 15);
        System.out.printf("Höhen pulsieren: Kanal auf Höhen %.2f, auf Bass %.2f, auf Stimmen %.2f%n", high, bass, voice);
        assertTrue(high > 0.3, "folgt den Höhen");
        assertTrue(bass < high * 0.25, "Bass bleibt ruhig");
        // Nur der Gesangsbereich pulsiert (Bänder 6–10, etwa 375 Hz bis 2,4 kHz).
        double v2 = swing("voice", 6, 10), b2 = swing("bass", 6, 10);
        System.out.printf("Stimmen pulsieren: Kanal auf Stimmen %.2f, auf Bass %.2f%n", v2, b2);
        assertTrue(v2 > 0.3 && b2 < v2 * 0.25);
    }

    @Test
    void customRangeWorks() {
        Music m = new Music(3);
        Project.Audio cfg = new Project.Audio();
        cfg.mode = "Pegel";
        cfg.react1 = "custom";
        cfg.fromHz = 5000;
        cfg.toHz = 9000;
        cfg.dynamics = 0;
        CompiledLayout l = layout();
        int[] out = new int[l.leds.length];
        double peak = 0;
        for (double t = 0.001; t < 4; t += 1 / 47.0) {
            m.render(l, t, pulse(t, 13, 14), t, cfg, out);
            for (int c : out) peak = Math.max(peak, Colors.r(c) + Colors.g(c) + Colors.b(c));
        }
        assertTrue(peak > 200, "eigener Bereich 5–9 kHz reagiert: " + peak);
    }

    @Test
    void detectedKicksCanDriveAChannel() {
        float[] pcm = MusicAnalyzerTest.song(1.4);
        MusicAnalyzer a = new MusicAnalyzer();
        Music m = new Music(3);
        m.setAnalyzer(a);
        Project.Audio cfg = new Project.Audio();
        cfg.mode = "Regen";
        cfg.react1 = "kick";
        cfg.react2 = "kick";
        cfg.react3 = "kick";
        cfg.dynamics = 0;
        CompiledLayout l = layout();
        int[] out = new int[l.leds.length];
        float[] window = new float[2048];
        int lit = 0, dark = 0;
        for (int start = 0; start + 2048 <= 60 * MusicAnalyzerTest.RATE; start += 1024) {
            System.arraycopy(pcm, start, window, 0, 2048);
            double stamp = 100 + (start + 2048.0) / MusicAnalyzerTest.RATE;
            double[] v = AudioEngine.analyse(window, 1, MusicAnalyzerTest.RATE);
            a.feed(v, stamp, 1024.0 / MusicAnalyzerTest.RATE);
            m.render(l, stamp, v, stamp, cfg, out);
            double t = stamp - 100;
            boolean any = false;
            for (int c : out) any |= c != 0;
            if (t > 10 && t < 19 && any) lit++;
            if (t > 50 && t < 59 && any) dark++;
        }
        System.out.println("Regen auf Kicks: Bilder mit Tropfen im Groove " + lit + ", im ruhigen Teil ohne Kicks " + dark);
        assertTrue(lit > 200, "Kicks lassen Tropfen fallen");
        assertTrue(dark < lit / 4, "ohne Kicks kaum Tropfen");
    }

    @Test
    void settingsShowOnlyWhatFitsTheAnimation() {
        Effect e = Effects.create("music");
        Params auto = new Params(e.params(), Map.of("mode", Music.AUTO));
        Params classic = new Params(e.params(), Map.of("mode", "Beat-Balken"));
        ParamSpec react = e.params().stream().filter(p -> p.name.equals("react1")).findFirst().orElseThrow();
        ParamSpec duration = e.params().stream().filter(p -> p.name.equals("duration")).findFirst().orElseThrow();
        assertTrue(react.visible(auto));
        assertFalse(react.visible(classic));
        assertTrue(duration.visible(classic));
        assertFalse(duration.visible(auto));
    }
}
