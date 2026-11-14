package de.knirpslarsi.ledstudio;

import de.knirpslarsi.ledstudio.effects.Effect;
import de.knirpslarsi.ledstudio.effects.Effects;
import de.knirpslarsi.ledstudio.effects.Params;
import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.LayoutTools;
import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.model.SpeedCurve;
import de.knirpslarsi.ledstudio.openrgb.Controller;
import de.knirpslarsi.ledstudio.openrgb.DemoBackend;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Zufalls-Blinken und schwankendes Tempo. */
class BlinkAndSpeedTest {
    private static CompiledLayout layout() {
        List<Controller> c = new DemoBackend().controllers();
        return CompiledLayout.compile(LayoutTools.legacyNzxtChain(c), c);
    }

    private static int[] render(Map<String, Object> params, double t) {
        CompiledLayout l = layout();
        Effect e = Effects.create("random_blink");
        Effect.Frame f = new Effect.Frame();
        f.layout = l;
        f.out = new int[l.leds.length];
        f.p = new Params(e.params(), params);
        f.t = t;
        e.reset(l);
        e.render(f);
        return f.out;
    }

    @Test
    void randomBlinkIsRepeatableAndHitsTheChosenShare() {
        Map<String, Object> p = new HashMap<>(Map.of("amount", 0.4, "duration", 0.5, "softness", 0.0, "jitter", 1.0));
        assertArrayEquals(render(p, 12.34), render(p, 12.34), "gleicher Zeitpunkt, gleiches Bild (wichtig fürs Spulen)");
        double lit = 0;
        int frames = 0;
        for (double t = 0; t < 40; t += 0.13) {
            for (int c : render(p, t)) if (c != 0) lit++;
            frames++;
        }
        double share = lit / frames / layout().leds.length;
        System.out.printf("Zufalls-Blinken: im Mittel %.0f %% der LEDs an (erwartet etwa 40 %% × 50 %% = 20 %%)%n", share * 100);
        assertEquals(0.2, share, 0.05);
    }

    @Test
    void withoutJitterAllBlinkInTheSameRhythm() {
        Map<String, Object> p = new HashMap<>(Map.of("amount", 1.0, "duration", 0.5, "softness", 0.0, "jitter", 0.0, "rate", 1.0));
        int[] on = render(p, 3.2), off = render(p, 3.7);
        assertTrue(Arrays.stream(on).allMatch(c -> c != 0), "erste Hälfte: alle an");
        assertTrue(Arrays.stream(off).allMatch(c -> c == 0), "zweite Hälfte: alle aus");
    }

    @Test
    void speedCurvesStayInRangeAndAddUp() {
        Project.Layer l = new Project.Layer();
        l.speedMin = 0.5;
        l.speedMax = 2;
        l.speedPeriod = 4;
        for (String mode : SpeedCurve.MODES.keySet()) {
            if ("off".equals(mode)) continue;
            l.speedMode = mode;
            for (double u = 0; u < 20; u += 0.37) {
                double f = SpeedCurve.factor(l, u, 0);
                assertTrue(f >= 0.5 - 1e-9 && f <= 2 + 1e-9, mode + ": " + f);
            }
        }
        l.speedMode = "off";
        assertEquals(10, SpeedCurve.integral(l, 0, 10, 0), 1e-9, "gleichmäßig: Effektzeit = Zeit");
        l.speedMode = "up";
        assertEquals(1.25 * 8, SpeedCurve.integral(l, 0, 8, 8), 0.01, "beschleunigen über den Clip: im Mittel 1,25");
        l.speedMode = "wave";
        assertEquals(1.25 * 8, SpeedCurve.integral(l, 0, 8, 0), 0.01, "Welle über volle Perioden: im Mittel 1,25");
        // Stückweise gerechnet ergibt dasselbe wie am Stück (so rechnet die Engine beim Abspielen).
        double pieces = 0;
        for (double a = 0; a < 6; a += 0.025) pieces += SpeedCurve.integral(l, a, a + 0.025, 0);
        assertEquals(SpeedCurve.integral(l, 0, 6, 0), pieces, 1e-3);
    }
}
