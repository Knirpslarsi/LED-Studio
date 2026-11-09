package de.knirpslarsi.ledstudio;

import de.knirpslarsi.ledstudio.audio.Music;
import de.knirpslarsi.ledstudio.effects.Effects;
import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.LayoutTools;
import de.knirpslarsi.ledstudio.openrgb.Controller;
import de.knirpslarsi.ledstudio.openrgb.DemoBackend;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Jeder Musik-Effekt der Auto-Regie läuft auch bei langsamem Tempo ohne harte Sprünge, in jeder Verteilung. */
class StripSmoothnessTest {
    @Test
    void everyStripEffectMovesSmoothly() {
        List<Controller> c = new DemoBackend().controllers();
        CompiledLayout l = CompiledLayout.compile(LayoutTools.legacyNzxtChain(c), c);
        for (String mode : Music.stripModes()) {
            for (String spread : List.of("chain", "fixture", "x")) {
                int worst = FlickerProbeTest.maxJump(Effects.create("music"),
                        Map.of("mode", mode, "trigger", "bpm", "bpm", 124.0, "dynamics", 0.0, "spread", spread), l, 0.1, 30, false);
                System.out.println(mode + " / " + spread + ": größter Sprung " + worst);
                assertTrue(worst <= 40, mode + " / " + spread + " springt: " + worst);
            }
        }
    }
}
