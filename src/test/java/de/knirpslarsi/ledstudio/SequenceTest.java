package de.knirpslarsi.ledstudio;

import de.knirpslarsi.ledstudio.audio.AudioEngine;
import de.knirpslarsi.ledstudio.engine.Engine;
import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.LayoutTools;
import de.knirpslarsi.ledstudio.layout.Targets;
import de.knirpslarsi.ledstudio.model.Fixture;
import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.model.Sequence;
import de.knirpslarsi.ledstudio.model.Show;
import de.knirpslarsi.ledstudio.openrgb.DemoBackend;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sequenzen und eigene LEDs pro Clip. */
class SequenceTest {
    private static final List<String> EIGHT = List.of("f#0", "f#1", "f#2", "f#3", "f#4", "f#5", "f#6", "f#7");

    private static List<String> firstLeds(List<Show.Clip> clips) {
        List<String> out = new ArrayList<>();
        for (Show.Clip c : clips) out.add(c.targets.get(0).substring(4));
        return out;
    }

    @Test
    void stepsFollowTheChosenOrder() {
        Sequence.Spec s = new Sequence.Spec();
        s.step = 0.5;
        List<Show.Clip> chain = Sequence.build(EIGHT, 10, s, new Random(1));
        assertEquals(8, chain.size());
        assertEquals(EIGHT, firstLeds(chain));
        assertEquals(10, chain.get(0).start, 1e-9);
        assertEquals(13.5, chain.get(7).start, 1e-9);
        assertEquals(0.5, chain.get(0).length(), 1e-9, "leuchtet bis zum nächsten Schritt");

        s.order = "reverse";
        assertEquals("f#7", firstLeds(Sequence.build(EIGHT, 0, s, new Random(1))).get(0));

        s.order = "pingpong";
        List<String> pp = firstLeds(Sequence.build(EIGHT, 0, s, new Random(1)));
        assertEquals(14, pp.size(), "hin (8) und zurück ohne die Enden (6)");
        assertEquals("f#6", pp.get(8));

        s.order = "center";
        List<Show.Clip> center = Sequence.build(EIGHT, 0, s, new Random(1));
        assertEquals(4, center.size(), "immer zwei LEDs gleichzeitig nach außen");
        assertEquals(List.of("led:f#3", "led:f#4"), center.get(0).targets);
        assertEquals(List.of("led:f#0", "led:f#7"), center.get(3).targets);
    }

    @Test
    void groupsHoldColorsAndRepeats() {
        Sequence.Spec s = new Sequence.Spec();
        s.step = 1;
        s.group = 3;
        s.hold = 2.5;
        s.repeats = 2;
        s.colorA = "#FF0000";
        s.colorB = "#0000FF";
        List<Show.Clip> clips = Sequence.build(EIGHT, 0, s, new Random(1));
        assertEquals(6, clips.size(), "3 Schritte (3+3+2 LEDs) mal 2");
        assertEquals(3, clips.get(0).targets.size());
        assertEquals(2, clips.get(2).targets.size());
        assertEquals(2.5, clips.get(0).length(), 1e-9, "Nachleuchten über 2,5 Schritte");
        assertEquals("#FF0000", clips.get(0).look.params.get("color"));
        assertEquals("#0000FF", clips.get(2).look.params.get("color"));
        assertEquals(3, clips.get(3).start, 1e-9, "zweite Runde schließt direkt an");
    }

    @Test
    void ledsComeInChainOrClickOrder() {
        DemoBackend b = new DemoBackend();
        List<Fixture> fx = LayoutTools.legacyNzxtChain(b.controllers());
        CompiledLayout layout = CompiledLayout.compile(fx, b.controllers());
        List<String> clicked = List.of(Targets.led(fx.get(0).id, 5), Targets.led(fx.get(0).id, 1), Targets.led(fx.get(0).id, 3));
        assertEquals(List.of(fx.get(0).id + "#5", fx.get(0).id + "#1", fx.get(0).id + "#3"), Sequence.leds(clicked, fx, layout, true));
        List<String> chain = Sequence.leds(clicked, fx, layout, false);
        assertEquals(3, chain.size());
        assertTrue(!chain.get(0).endsWith("#5"), "entlang der Kette sortiert");
        assertEquals(fx.get(0).count, Sequence.leds(List.of(Targets.fixture(fx.get(0).id)), fx, layout, false).size());
    }

    @Test
    void clipLedsOverrideTheTrack() throws Exception {
        Map<Integer, int[]> last = new ConcurrentHashMap<>();
        DemoBackend b = new DemoBackend() {
            @Override
            public void setLeds(int device, int[] colors) {
                last.put(device, colors.clone());
            }
        };
        List<Fixture> fx = LayoutTools.legacyNzxtChain(b.controllers());
        CompiledLayout layout = CompiledLayout.compile(fx, b.controllers());
        Project p = new Project();
        p.fixtures = fx;
        p.fps = 120;
        p.smoothMs = 0;
        p.presets = new ArrayList<>(Project.defaultPresets());
        Show show = new Show();
        Show.Track track = new Show.Track();
        track.targets.add(Targets.fixture(fx.get(0).id)); // Spur: ganzer Lüfter
        Show.Clip clip = new Show.Clip();
        clip.start = 0;
        clip.end = 10;
        clip.fadeIn = clip.fadeOut = 0;
        clip.look = Project.Layer.solid("#00FF00");
        clip.targets.add(Targets.led(fx.get(0).id, 2)); // Clip: nur LED 3
        track.clips.add(clip);
        show.tracks.add(track);
        Engine e = new Engine(new AudioEngine(() -> p.audio), x -> {
        });
        e.apply(p, layout);
        e.setShow(show, () -> 1.0);
        e.setBackend(b);
        e.start();
        Thread.sleep(200);
        int lit = 0;
        for (int[] colors : last.values()) for (int c : colors) if (c != 0) lit++;
        e.stop(); // schaltet beim Beenden alles aus, deshalb vorher zählen
        assertEquals(1, lit, "nur die LED des Clips, nicht der ganze Lüfter der Spur");
    }
}
