package de.knirpslarsi.ledstudio;

import de.knirpslarsi.ledstudio.audio.AudioEngine;
import de.knirpslarsi.ledstudio.audio.Music;
import de.knirpslarsi.ledstudio.audio.MusicAnalyzer;
import de.knirpslarsi.ledstudio.audio.MusicAnalyzer.Section;
import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.LayoutTools;
import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.openrgb.Controller;
import de.knirpslarsi.ledstudio.openrgb.DemoBackend;
import de.knirpslarsi.ledstudio.util.Colors;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ein künstlicher Song mit 128 BPM: Groove (0–20 s), Spannungsaufbau mit schneller werdendem Snare-Wirbel und
 * Riser ohne Kick und Bass (20–32 s), Drop (32–48 s), ruhiger Teil mit einzelnen Tönen (48–60 s).
 */
class MusicAnalyzerTest {
    static final int RATE = 48000;
    static final double BPM = 128, P = 60 / BPM, LENGTH = 60, OFFSET = 100;

    static Section part(double t) {
        return t < 20 ? Section.GROOVE : t < 32 ? Section.BUILD : t < 48 ? Section.DROP : Section.CALM;
    }

    static float[] song(double volume) {
        float[] x = new float[(int) (LENGTH * RATE)];
        Random r = new Random(7);
        double[] notes = {440, 523, 659, 784, 587, 880};
        for (int k = 0; k * P < LENGTH; k++) {
            double t0 = k * P;
            Section s = part(t0);
            if (s == Section.GROOVE || s == Section.DROP) {
                kick(x, t0, s == Section.DROP ? 0.9 : 0.75);
                if (k % 2 == 1) snare(x, t0, r, 0.35);
                hat(x, t0 + P / 2, r, 0.2);
                if (s == Section.DROP) {
                    hat(x, t0 + P / 4, r, 0.15);
                    hat(x, t0 + 3 * P / 4, r, 0.15);
                }
            } else if (s == Section.BUILD) {
                double interval = t0 < 24 ? P : t0 < 28 ? P / 2 : P / 4;
                for (double t = t0; t < t0 + P - 1e-9; t += interval) snare(x, t, r, 0.12 + 0.25 * (t0 - 20) / 12);
            } else {
                pluck(x, t0, notes[k % notes.length], 0.15);
            }
        }
        double prevNoise = 0, sweepPhase = 0;
        for (int i = 0; i < x.length; i++) {
            double t = (double) i / RATE;
            Section s = part(t);
            double v = 0.04 * (Math.sin(2 * Math.PI * 220 * t) + Math.sin(2 * Math.PI * 277 * t) + Math.sin(2 * Math.PI * 330 * t)) / 3;
            if (s == Section.GROOVE) v += 0.22 * Math.sin(2 * Math.PI * 55 * t);
            if (s == Section.DROP) v += 0.3 * Math.sin(2 * Math.PI * 55 * t);
            if (s == Section.BUILD) {
                double progress = (t - 20) / 12;
                double noise = r.nextGaussian();
                double hp = noise - prevNoise;
                prevNoise = noise;
                sweepPhase += 2 * Math.PI * (300 + 2700 * progress * progress) / RATE;
                v += (0.01 + 0.12 * progress) * hp + (0.02 + 0.12 * progress) * Math.sin(sweepPhase);
            }
            x[i] += (float) v;
        }
        for (int i = 0; i < x.length; i++) x[i] *= (float) volume;
        return x;
    }

    static void kick(float[] x, double t0, double amp) {
        double phase = 0;
        int start = (int) (t0 * RATE);
        for (int i = 0; i < 0.25 * RATE && start + i < x.length; i++) {
            double tt = (double) i / RATE;
            phase += 2 * Math.PI * (50 + 70 * Math.exp(-tt / 0.03)) / RATE;
            x[start + i] += (float) (amp * Math.exp(-tt / 0.12) * Math.sin(phase));
        }
    }

    static void snare(float[] x, double t0, Random r, double amp) {
        int start = (int) (t0 * RATE);
        for (int i = 0; i < 0.15 * RATE && start + i < x.length; i++) {
            double tt = (double) i / RATE;
            x[start + i] += (float) (amp * (r.nextGaussian() * 0.6 * Math.exp(-tt / 0.06) + 0.5 * Math.sin(2 * Math.PI * 200 * tt) * Math.exp(-tt / 0.05)));
        }
    }

    static void hat(float[] x, double t0, Random r, double amp) {
        int start = (int) (t0 * RATE);
        double prev = 0;
        for (int i = 0; i < 0.05 * RATE && start + i < x.length; i++) {
            double tt = (double) i / RATE, n = r.nextGaussian();
            x[start + i] += (float) (amp * (n - prev) * Math.exp(-tt / 0.015));
            prev = n;
        }
    }

    static void pluck(float[] x, double t0, double f, double amp) {
        int start = (int) (t0 * RATE);
        for (int i = 0; i < 0.3 * RATE && start + i < x.length; i++) {
            double tt = (double) i / RATE;
            x[start + i] += (float) (amp * Math.sin(2 * Math.PI * f * tt) * Math.exp(-tt / 0.1) * Math.min(1, tt / 0.004));
        }
    }

    /** Ergebnis eines Durchlaufs: Szene je Zeitpunkt und alle Ereignisse. */
    record Run(TreeMap<Double, MusicAnalyzer.Scene> scenes, List<MusicAnalyzer.Event> events) {
        MusicAnalyzer.Scene at(double t) {
            return scenes.floorEntry(t + OFFSET).getValue();
        }

        long count(int type, double from, double to) {
            return events.stream().filter(e -> e.type() == type && e.strength() >= 1
                    && e.time() >= from + OFFSET && e.time() < to + OFFSET).count();
        }
    }

    static Run analyse(double volume) {
        float[] pcm = song(volume);
        MusicAnalyzer a = new MusicAnalyzer();
        TreeMap<Double, MusicAnalyzer.Scene> scenes = new TreeMap<>();
        List<MusicAnalyzer.Event> events = new ArrayList<>();
        long seen = -1;
        float[] window = new float[2048];
        for (int start = 0; start + 2048 <= pcm.length; start += 1024) {
            System.arraycopy(pcm, start, window, 0, 2048);
            double stamp = OFFSET + (start + 2048.0) / RATE;
            a.feed(AudioEngine.analyse(window, 1, RATE), stamp, 1024.0 / RATE);
            seen = a.poll(seen, events);
            scenes.put(stamp, a.scene());
        }
        return new Run(scenes, events);
    }

    @Test
    void findsTempoAndBeatGrid() {
        Run run = analyse(1);
        MusicAnalyzer.Scene s = run.at(18);
        System.out.printf("Tempo bei 18 s: %.1f BPM, Sicherheit %.2f%n", s.bpm(), s.confidence());
        assertEquals(BPM, s.bpm(), BPM * 0.02);
        assertTrue(s.confidence() > 0.4, "Sicherheit " + s.confidence());
        // Das Gitter liegt auf den echten Schlägen (kleine Verzögerung durch das Messfenster ist normal).
        double beat = s.beatTime() - OFFSET;
        double error = beat / P - Math.rint(beat / P);
        System.out.printf("Gitterfehler: %.0f ms%n", error * P * 1000);
        assertTrue(Math.abs(error * P) < 0.07, "Gitterfehler " + error * P);
        assertEquals(BPM, run.at(44).bpm(), BPM * 0.02, "auch im Drop");
    }

    @Test
    void separatesKickSnareHatAndSmallSounds() {
        Run run = analyse(1);
        long kicks = run.count(MusicAnalyzer.KICK, 3, 18), snares = run.count(MusicAnalyzer.SNARE, 3, 18);
        long hats = run.count(MusicAnalyzer.HAT, 3, 18), accents = run.count(MusicAnalyzer.ACCENT, 49, 59);
        double beats = 15 / P;
        System.out.printf("Groove 15 s (%.0f Schläge): %d Kicks, %d Snares, %d Hi-Hats; ruhiger Teil: %d Neben-Sounds%n",
                beats, kicks, snares, hats, accents);
        assertTrue(Math.abs(kicks - beats) <= 4, "Kicks " + kicks);
        assertTrue(snares >= beats / 2 - 5, "Snares " + snares);
        assertTrue(hats >= beats * 0.6, "Hi-Hats " + hats);
        assertTrue(accents >= 12, "Neben-Sounds " + accents);
        assertTrue(run.count(MusicAnalyzer.KICK, 49, 59) <= 2, "im ruhigen Teil gibt es keine Kicks");
    }

    @Test
    void feelsTensionBuildAndTheDrop() {
        checkSong(analyse(1), "normal laut");
    }

    @Test
    void worksTheSameWhenMasteredQuieter() {
        checkSong(analyse(0.5), "6 dB leiser");
    }

    /** Wie bei Survivor: Der Song beginnt leise mit einzelnen Tönen, erst dann setzt alles ein. */
    @Test
    void quietIntroIsDarkAndCalm() {
        float[] intro = new float[20 * RATE];
        double[] notes = {330, 392, 440, 392};
        for (int k = 0; k * P < 20; k++) pluck(intro, k * P, notes[k % 4], 0.05);
        for (int i = 0; i < intro.length; i++) intro[i] += (float) (0.01 * Math.sin(2 * Math.PI * 220 * i / (double) RATE));
        float[] full = song(1.6); // fertig gemastert laut
        float[] pcm = new float[intro.length + 20 * RATE];
        System.arraycopy(intro, 0, pcm, 0, intro.length);
        System.arraycopy(full, 0, pcm, intro.length, 20 * RATE);
        MusicAnalyzer a = new MusicAnalyzer();
        Music m = new Music(42);
        m.setAnalyzer(a);
        Project.Audio cfg = new Project.Audio();
        CompiledLayout l = layout();
        int[] out = new int[l.leds.length];
        float[] window = new float[2048];
        TreeMap<Double, double[]> looks = new TreeMap<>();
        TreeMap<Double, MusicAnalyzer.Scene> scenes = new TreeMap<>();
        for (int start = 0; start + 2048 <= pcm.length; start += 1024) {
            System.arraycopy(pcm, start, window, 0, 2048);
            double stamp = OFFSET + (start + 2048.0) / RATE;
            double[] values = AudioEngine.analyse(window, 1, RATE);
            a.feed(values, stamp, 1024.0 / RATE);
            m.render(l, stamp, values, stamp, cfg, out);
            looks.put(stamp - OFFSET, new double[]{topBrightness(out)});
            scenes.put(stamp - OFFSET, a.scene());
        }
        double intro1 = avg(looks, 3, 19, 0), loud = avg(looks, 28, 39, 0);
        System.out.printf("Leises Intro: Helligkeit %.3f, Abschnitt %s, Energie %.2f; danach %.3f, %s%n", intro1,
                scenes.floorEntry(15.0).getValue().section(), scenes.floorEntry(15.0).getValue().energy(), loud,
                scenes.floorEntry(35.0).getValue().section());
        assertEquals(Section.CALM, scenes.floorEntry(15.0).getValue().section());
        assertTrue(intro1 < loud * 0.4, "Intro deutlich dunkler: " + intro1 + " gegen " + loud);
        assertTrue(intro1 > 0.005, "aber nicht ganz aus");
    }

    private static void checkSong(Run run, String name) {
        StringBuilder timeline = new StringBuilder(name + ":");
        for (int t = 2; t < 60; t += 2) {
            MusicAnalyzer.Scene s = run.at(t);
            timeline.append(String.format(" %ds=%s/%.2f", t, s.section().name().charAt(0), s.tension()));
        }
        System.out.println(timeline);
        assertEquals(Section.GROOVE, run.at(15).section(), name);
        assertTrue(run.at(15).tension() < 0.25, name + ": im Groove keine Spannung");
        double peak = 0;
        for (double t = 26; t < 32; t += 0.1) peak = Math.max(peak, run.at(t).tension());
        assertTrue(peak > 0.45, name + ": Spannung steigt vor dem Drop auf " + peak);
        assertEquals(Section.BUILD, run.at(31.5).section(), name);

        List<Double> drops = run.events().stream().filter(e -> e.type() == MusicAnalyzer.DROP_HIT)
                .map(e -> e.time() - OFFSET).toList();
        System.out.println(name + ": Drops bei " + drops);
        assertTrue(drops.stream().anyMatch(d -> d >= 32 && d < 33), name + ": Drop erkannt");
        assertTrue(drops.stream().noneMatch(d -> d > 4 && d < 31.9), name + ": kein falscher Drop vorher");
        assertEquals(Section.DROP, run.at(36).section(), name);
        assertTrue(run.at(33.5).tension() < 0.2, name + ": Spannung entlädt sich im Drop");
        assertEquals(Section.CALM, run.at(56).section(), name);
    }

    // ---- Auto-Regie

    private static CompiledLayout layout() {
        List<Controller> c = new DemoBackend().controllers();
        return CompiledLayout.compile(LayoutTools.legacyNzxtChain(c), c);
    }

    private static double brightness(int[] out) {
        double sum = 0;
        for (int c : out) sum += Math.max(Colors.r(c), Math.max(Colors.g(c), Colors.b(c)));
        return sum / out.length / 255;
    }

    /** Helligkeit der hellsten 5 % der LEDs: wie hell die Animation leuchtet, egal wie viel gerade an ist. */
    private static double topBrightness(int[] out) {
        double[] v = new double[out.length];
        for (int i = 0; i < out.length; i++) v[i] = Math.max(Colors.r(out[i]), Math.max(Colors.g(out[i]), Colors.b(out[i]))) / 255.0;
        java.util.Arrays.sort(v);
        double sum = 0;
        int k = Math.max(1, v.length / 20);
        for (int i = v.length - k; i < v.length; i++) sum += v[i];
        return sum / k;
    }

    private static double whiteness(int[] out) {
        double sum = 0, n = 0;
        for (int c : out) {
            int max = Math.max(Colors.r(c), Math.max(Colors.g(c), Colors.b(c)));
            if (max < 20) continue;
            sum += Math.min(Colors.r(c), Math.min(Colors.g(c), Colors.b(c))) / (double) max;
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    @Test
    void autoDirectorFollowsTheSong() {
        // Echte Analyse und Auto-Regie zusammen: Spannung macht es heller und weißer, der Drop ist der Höhepunkt.
        float[] pcm = song(1);
        MusicAnalyzer a = new MusicAnalyzer();
        Music m = new Music(42);
        m.setAnalyzer(a);
        Project.Audio cfg = new Project.Audio();
        assertEquals(Music.AUTO, cfg.mode, "Auto-Regie ist die Standard-Animation");
        CompiledLayout l = layout();
        int[] out = new int[l.leds.length];
        float[] window = new float[2048];
        TreeMap<Double, double[]> looks = new TreeMap<>();
        for (int start = 0; start + 2048 <= pcm.length; start += 1024) {
            System.arraycopy(pcm, start, window, 0, 2048);
            double stamp = OFFSET + (start + 2048.0) / RATE;
            double[] values = AudioEngine.analyse(window, 1, RATE);
            a.feed(values, stamp, 1024.0 / RATE);
            m.render(l, stamp, values, stamp, cfg, out);
            looks.put(stamp - OFFSET, new double[]{brightness(out), whiteness(out)});
        }
        double groove = avg(looks, 10, 19, 0), build = avg(looks, 30, 31.9, 0), buildWhite = avg(looks, 30, 31.9, 1);
        double grooveWhite = avg(looks, 10, 19, 1), drop = avg(looks, 32, 33, 0), calm = avg(looks, 52, 59, 0);
        System.out.printf("Helligkeit: Groove %.2f, Ende Aufbau %.2f, Drop %.2f, ruhig %.2f; Weißanteil Groove %.2f, Aufbau %.2f%n",
                groove, build, drop, calm, grooveWhite, buildWhite);
        assertTrue(buildWhite > grooveWhite + 0.1, "Spannung wird weißer");
        assertTrue(drop > groove && drop > build, "Drop ist der Höhepunkt");
        assertTrue(calm < groove, "ruhiger Teil ist ruhiger");
        assertTrue(m.bandBeats[MusicAnalyzer.KICK] > 50, "Kicks lösen Ringe aus: " + m.bandBeats[0]);
    }

    private static double avg(TreeMap<Double, double[]> looks, double from, double to, int index) {
        return looks.subMap(from, to).values().stream().mapToDouble(v -> v[index]).average().orElse(0);
    }

    /** Kein gleichmäßiges Dauerleuchten: Im Groove bewegt sich das Licht mit der Musik, und es wechseln Effekte. */
    @Test
    void grooveMovesWithTheMusic() {
        float[] pcm = song(1);
        MusicAnalyzer a = new MusicAnalyzer();
        Music m = new Music(42);
        m.setAnalyzer(a);
        Project.Audio cfg = new Project.Audio();
        CompiledLayout l = layout();
        int[] out = new int[l.leds.length], prev = new int[l.leds.length];
        float[] window = new float[2048];
        double litShare = 0, moved = 0;
        int frames = 0;
        java.util.Set<String> seen = new java.util.TreeSet<>();
        for (int start = 0; start + 2048 <= pcm.length; start += 1024) {
            System.arraycopy(pcm, start, window, 0, 2048);
            double stamp = OFFSET + (start + 2048.0) / RATE;
            double[] values = AudioEngine.analyse(window, 1, RATE);
            a.feed(values, stamp, 1024.0 / RATE);
            System.arraycopy(out, 0, prev, 0, out.length);
            m.render(l, stamp, values, stamp, cfg, out);
            seen.addAll(m.activeAnimations);
            double t = stamp - OFFSET;
            if (t > 8 && t < 19) {
                int lit = 0, changed = 0;
                for (int i = 0; i < out.length; i++) {
                    int max = Math.max(Colors.r(out[i]), Math.max(Colors.g(out[i]), Colors.b(out[i])));
                    if (max > 40) lit++;
                    if (Math.abs(max - Math.max(Colors.r(prev[i]), Math.max(Colors.g(prev[i]), Colors.b(prev[i])))) > 3) changed++;
                }
                litShare += lit / (double) out.length;
                moved += changed / (double) out.length;
                frames++;
            }
        }
        System.out.printf("Groove: im Mittel %.0f %% der LEDs an, %.0f %% ändern sich je Bild; Animationen im Song: %s%n",
                100 * litShare / frames, 100 * moved / frames, seen);
        assertTrue(litShare / frames < 0.8, "nicht alles leuchtet dauernd");
        assertTrue(moved / frames > 0.05, "das Licht bewegt sich mit der Musik");
        assertTrue(seen.size() >= 3, "mehrere Effekte im Song: " + seen);
    }

    @Test
    void everyBeatAnimationRunsAlone() {
        CompiledLayout l = layout();
        for (String mode : Music.stripModes()) {
            Music m = new Music(42);
            Project.Audio cfg = new Project.Audio();
            cfg.mode = mode;
            cfg.trigger = "bpm";
            int[] out = new int[l.leds.length];
            double light = 0;
            for (double t = 0; t < 24; t += 1 / 40.0) {
                m.render(l, t, new double[0], 0, cfg, out);
                light += brightness(out);
            }
            System.out.printf("%-14s mittlere Helligkeit %.3f%n", mode, light / (24 * 40));
            assertTrue(light > 0.5, mode + " bleibt dunkel");
            assertEquals(List.of(mode), m.activeAnimations, mode);
        }
    }

    @Test
    void autoDirectorDemoRunsWithoutSound() {
        Music m = new Music(42);
        Project.Audio cfg = new Project.Audio();
        cfg.trigger = "bpm";
        cfg.bpm = 120;
        CompiledLayout l = layout();
        int[] out = new int[l.leds.length];
        double[] lightPerSection = new double[Section.values().length];
        int[] frames = new int[lightPerSection.length];
        for (double t = 0; t < 32; t += 1 / 40.0) {
            m.render(l, t, new double[0], 0, cfg, out);
            Section s = m.directorScene.section();
            lightPerSection[s.ordinal()] += brightness(out);
            frames[s.ordinal()]++;
        }
        for (Section s : List.of(Section.GROOVE, Section.BUILD, Section.DROP, Section.CALM)) {
            assertTrue(frames[s.ordinal()] > 0, "Vorführung zeigt " + s);
            assertTrue(lightPerSection[s.ordinal()] / frames[s.ordinal()] > 0.005, s + " leuchtet");
        }
        assertTrue(m.bandBeats[MusicAnalyzer.KICK] >= 40, "Kicks: " + m.bandBeats[0]);
    }
}
