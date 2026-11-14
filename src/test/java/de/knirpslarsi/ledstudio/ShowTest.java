package de.knirpslarsi.ledstudio;

import de.knirpslarsi.ledstudio.audio.AudioEngine;
import de.knirpslarsi.ledstudio.audio.Song;
import de.knirpslarsi.ledstudio.engine.Engine;
import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.LayerView;
import de.knirpslarsi.ledstudio.layout.LayoutTools;
import de.knirpslarsi.ledstudio.layout.Targets;
import de.knirpslarsi.ledstudio.model.Fixture;
import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.model.Show;
import de.knirpslarsi.ledstudio.openrgb.Controller;
import de.knirpslarsi.ledstudio.openrgb.DemoBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Timecode-Show: speichern, einzelne LEDs, Clips zur richtigen Zeit, Songs laden und Beats finden. */
class ShowTest {
    private static final class Recording extends DemoBackend {
        final Map<Integer, int[]> last = new ConcurrentHashMap<>();

        @Override
        public void setLeds(int device, int[] colors) {
            last.put(device, colors.clone());
        }
    }

    @Test
    void saveAndLoadKeepsEverything(@TempDir Path dir) throws Exception {
        Show s = new Show();
        s.name = "Test";
        s.audio = "C:/Musik/song.mp3";
        Show.Track t = new Show.Track();
        t.targets.add(Targets.led("abc", 3));
        Show.Clip c = new Show.Clip();
        c.start = 1.5;
        c.end = 2.25;
        c.look = Project.Layer.solid("#00FF00");
        t.clips.add(c);
        s.tracks.add(t);
        Path file = dir.resolve("test" + Show.EXTENSION);
        s.save(file);
        Show back = Show.load(file);
        assertEquals("Test", back.name);
        assertEquals(List.of("led:abc#3"), back.tracks.get(0).targets);
        assertEquals(1.5, back.tracks.get(0).clips.get(0).start);
        assertEquals("#00FF00", back.tracks.get(0).clips.get(0).look.params.get("color"));
    }

    @Test
    void fadesFollowTheClip() {
        Show.Clip c = new Show.Clip();
        c.start = 1;
        c.end = 2;
        c.fadeIn = 0.2;
        c.fadeOut = 0.5;
        assertEquals(0, c.envelope(0.99));
        assertEquals(0.5, c.envelope(1.1), 1e-9);
        assertEquals(1, c.envelope(1.4), 1e-9);
        assertEquals(0.5, c.envelope(1.75), 1e-9);
        assertEquals(0, c.envelope(2.0));
    }

    @Test
    void singleLedTargetsSelectExactlyThatLed() {
        List<Controller> c = new DemoBackend().controllers();
        List<Fixture> fixtures = LayoutTools.legacyNzxtChain(c);
        CompiledLayout layout = CompiledLayout.compile(fixtures, c);
        Project.Layer l = Project.Layer.solid("#FF0000");
        l.targets = new ArrayList<>(List.of(Targets.led(fixtures.get(1).id, 2), Targets.led(fixtures.get(1).id, 5)));
        LayerView v = LayerView.build(layout, fixtures, l);
        assertEquals(2, v.toGlobal.length);
        for (int g : v.toGlobal) {
            assertEquals(1, layout.leds[g].fixture);
            assertTrue(layout.leds[g].k == 2 || layout.leds[g].k == 5);
        }
        // Eine LED aus einem ganzen Lüfter herausnehmen, dann wieder dazu: wieder der ganze Lüfter.
        CompiledLayout.Led led = java.util.Arrays.stream(layout.leds).filter(x -> x.fixture == 0 && x.k == 4).findFirst().orElseThrow();
        List<String> t = Targets.toggleLed(List.of(Targets.fixture(fixtures.get(0).id)), fixtures, layout, led, false);
        assertEquals(fixtures.get(0).count - 1, t.size());
        t = Targets.toggleLed(t, fixtures, layout, led, false);
        assertEquals(List.of(Targets.fixture(fixtures.get(0).id)), t);
    }

    @Test
    void clipsPlayAtTheirTime() throws Exception {
        Recording b = new Recording();
        List<Fixture> fixtures = LayoutTools.legacyNzxtChain(b.controllers());
        Project p = new Project();
        p.fixtures = fixtures;
        p.fps = 120;
        p.smoothMs = 0;
        p.presets = new ArrayList<>(Project.defaultPresets());
        CompiledLayout layout = CompiledLayout.compile(fixtures, b.controllers());

        Show show = new Show();
        Show.Track track = new Show.Track();
        track.targets.add(Targets.led(fixtures.get(0).id, 0));
        Show.Clip clip = new Show.Clip();
        clip.start = 1;
        clip.end = 2;
        clip.fadeIn = 0;
        clip.fadeOut = 0;
        clip.look = Project.Layer.solid("#0000FF");
        track.clips.add(clip);
        show.tracks.add(track);

        double[] clock = {0.5};
        Engine e = new Engine(new AudioEngine(() -> p.audio), x -> {
        });
        e.apply(p, layout);
        e.setShow(show, () -> clock[0]);
        e.setBackend(b);
        e.start();
        int first = -1;
        for (int i = 0; i < layout.leds.length; i++) if (layout.leds[i].fixture == 0 && layout.leds[i].k == 0) first = layout.leds[i].address;
        int device = java.util.Arrays.stream(layout.leds).filter(l -> l.fixture == 0).findFirst().orElseThrow().device;
        Thread.sleep(150);
        int before = b.last.get(device)[first];
        clock[0] = 1.5;
        Thread.sleep(150);
        int[] during = b.last.get(device);
        clock[0] = 2.5;
        Thread.sleep(150);
        int after = b.last.get(device)[first];
        e.stop();
        assertEquals(0, before, "vor dem Clip aus");
        assertEquals(0x0000FF, during[first], "im Clip blau");
        int lit = 0;
        for (int c : during) if (c != 0) lit++;
        assertEquals(1, lit, "nur genau diese eine LED");
        assertEquals(0, after, "nach dem Clip aus");
    }

    @Test
    void songLoadsAndBeatsAreFound(@TempDir Path dir) throws Exception {
        float[] pcm = MusicAnalyzerTest.song(1);
        int frames = 30 * MusicAnalyzerTest.RATE;
        byte[] bytes = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            int v = (int) Math.max(-32768, Math.min(32767, pcm[i] * 20000));
            bytes[2 * i] = (byte) v;
            bytes[2 * i + 1] = (byte) (v >> 8);
        }
        AudioFormat f = new AudioFormat(MusicAnalyzerTest.RATE, 16, 1, true, false);
        Path wav = dir.resolve("song.wav");
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(bytes), f, frames), AudioFileFormat.Type.WAVE, wav.toFile());
        Song song = Song.load(wav);
        assertEquals(30, song.duration, 0.01);
        assertTrue(song.peaks.length >= 2999);
        song.analyse();
        System.out.printf("Song: %.1f BPM, %d Beats, Teile %s%n", song.bpm, song.beats.size(), song.parts);
        assertEquals(128, song.bpm, 3);
        assertTrue(song.beats.size() > 45, "Beats: " + song.beats.size());
        // Abstand der Beats im Groove passt zum Tempo
        double gap = song.beats.get(20) - song.beats.get(19);
        assertEquals(60 / 128.0, gap, 0.06);
    }
}
