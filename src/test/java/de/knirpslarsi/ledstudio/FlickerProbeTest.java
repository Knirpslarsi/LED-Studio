package de.knirpslarsi.ledstudio;

import de.knirpslarsi.ledstudio.audio.AudioEngine;
import de.knirpslarsi.ledstudio.effects.Effect;
import de.knirpslarsi.ledstudio.effects.Effects;
import de.knirpslarsi.ledstudio.effects.Params;
import de.knirpslarsi.ledstudio.engine.Engine;
import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.LayoutTools;
import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.openrgb.Controller;
import de.knirpslarsi.ledstudio.openrgb.DemoBackend;
import de.knirpslarsi.ledstudio.openrgb.LedBackend;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Langsame Effekte dürfen nicht zucken, und der Tempo-Regler darf den Effekt nicht an eine andere Stelle werfen. */
class FlickerProbeTest {
    private static int luma(int c) {
        return (77 * ((c >> 16) & 255) + 150 * ((c >> 8) & 255) + 29 * (c & 255)) >> 8;
    }

    /** Größter Helligkeitssprung einer LED zwischen zwei Bildern. Beim Ziehen rechnet t um wie die Bildschleife. */
    static int maxJump(Effect e, Map<String, Object> params, CompiledLayout l, double speed, double seconds, boolean dragSpeed) {
        e.reset(l);
        Effect.Frame f = new Effect.Frame();
        f.layout = l;
        f.out = new int[l.leds.length];
        Map<String, Object> p = new LinkedHashMap<>(params);
        f.p = new Params(e.params(), p);
        double dt = 1.0 / 40, t = 100, raw = 100, base = f.p.d("speed"), used = base;
        int n = (int) (seconds / dt), max = 0;
        int[] last = new int[l.leds.length];
        for (int i = 0; i < n; i++) {
            if (dragSpeed) {
                double s = base * (1 - 0.5 * i / n);
                p.put("speed", s);
                f.p = new Params(e.params(), p);
                t *= used / s;
                used = s;
            }
            t += dt * speed;
            raw += dt;
            f.t = t;
            f.rawT = raw;
            f.dt = dt * speed;
            e.render(f);
            for (int k = 0; k < f.out.length; k++) {
                int v = luma(f.out[k]);
                if (i > 0) max = Math.max(max, Math.abs(v - last[k]));
                last[k] = v;
            }
        }
        return max;
    }

    /** Dein Lauflicht: So wechselt eine LED wirklich, Bild für Bild (Stromwert → wie hell es wirkt). */
    @Test
    void nextLedFadesInVisibly() {
        CompiledLayout l = layout();
        Effect e = Effects.create("rim_chase");
        Effect.Frame f = new Effect.Frame();
        f.layout = l;
        f.out = new int[l.leds.length];
        f.p = new Params(e.params(), Map.of("hue", 0.0, "gap", 0.0, "tail", 3.0, "speed", 3.0));
        de.knirpslarsi.ledstudio.engine.SmoothProbe filter = new de.knirpslarsi.ledstudio.engine.SmoothProbe();
        double dt = 1.0 / 40;
        StringBuilder trace = new StringBuilder();
        int previous = 0, biggestPerceivedStep = 0, risingFrames = 0;
        for (int i = 0; i < 80; i++) {
            f.t = i * dt;
            f.rawT = f.t;
            e.render(f);
            filter.apply(f.out, dt, 0.07);
            int v = (f.out[3] >> 16) & 255;
            int perceived = (int) Math.round(100 * Math.pow(v / 255.0, 1 / 2.2));
            trace.append(perceived).append(' ');
            if (i > 0 && perceived > previous) {
                biggestPerceivedStep = Math.max(biggestPerceivedStep, perceived - previous);
                risingFrames++;
            }
            previous = perceived;
        }
        System.out.println("Wahrgenommene Helligkeit LED 4 in %: " + trace + " | groesster Schritt " + biggestPerceivedStep + " | Bilder " + risingFrames);
        assertTrue(biggestPerceivedStep <= 25, "LED springt: " + trace);
        assertTrue(risingFrames >= 6, "Anstieg zu kurz: " + trace);
    }

    private static CompiledLayout layout() {
        List<Controller> c = new DemoBackend().controllers();
        return CompiledLayout.compile(LayoutTools.legacyNzxtChain(c), c);
    }

    @Test
    void slowEffectsAreSmoothAtEveryPreset() {
        CompiledLayout l = layout();
        StringBuilder report = new StringBuilder();
        List<String> tooHard = new ArrayList<>();
        for (Project.Preset p : Project.defaultPresets()) {
            Project.Layer layer = p.layers().get(0);
            int slow = maxJump(Effects.create(layer.effect), layer.params, l, 0.1, 30, false);
            int drag = maxJump(Effects.create(layer.effect), layer.params, l, 1, 5, true);
            int normal = maxJump(Effects.create(layer.effect), layer.params, l, 1, 20, false);
            report.append(String.format("%-22s x1 %3d  x0.1 %3d  Regler %3d%n", p.name, normal, slow, drag));
            if (slow > 40) tooHard.add(p.name + " bei x0.1: " + slow);
            if (drag > normal + 40) tooHard.add(p.name + " beim Ziehen: " + drag + " statt " + normal);
        }
        System.out.print(report);
        assertTrue(tooHard.isEmpty(), tooHard + "\n" + report);
    }

    /** Die echte Bildschleife: Tempo-Regler ziehen, gemessen an dem, was bei der Hardware ankommt. */
    @Test
    void draggingTheSpeedSliderDoesNotJump() throws Exception {
        List<int[]> frames = java.util.Collections.synchronizedList(new ArrayList<>());
        LedBackend backend = new DemoBackend() {
            @Override
            public void setLeds(int device, int[] colors) {
                frames.add(colors.clone());
            }
        };
        List<Controller> c = backend.controllers();
        Project p = new Project();
        p.fixtures = LayoutTools.legacyNzxtChain(c);
        Project.Preset preset = new Project.Preset("Regenbogen", "edge_sweep", Map.of("rainbow", 1, "speed", 2.0));
        p.presets = new ArrayList<>(List.of(preset));
        p.current = preset.name;
        p.fps = 40;
        p.smoothMs = 0;
        Engine e = new Engine(new AudioEngine(() -> p.audio), x -> {
        });
        CompiledLayout layout = CompiledLayout.compile(p.fixtures, c);
        e.apply(p, layout);
        e.setBackend(backend);
        e.start();
        Thread.sleep(3000); // t groß werden lassen: Ohne Umrechnung wären die Sprünge dann riesig
        frames.clear();
        for (double s = 2.0; s > 0.5; s -= 0.02) {
            preset.layers().get(0).params.put("speed", s);
            e.apply(p, layout);
            Thread.sleep(16);
        }
        e.stop();
        int max = 0;
        // Das letzte Paket ist das Ausschalten beim Beenden.
        for (int i = 1; i < frames.size() - 1; i++) {
            int[] a = frames.get(i - 1), b = frames.get(i);
            for (int k = 0; k < a.length; k++) max = Math.max(max, Math.abs(luma(a[k]) - luma(b[k])));
        }
        assertTrue(frames.size() > 20, "Pakete: " + frames.size());
        assertTrue(max < 90, "größter Sprung beim Ziehen: " + max);
    }
}
