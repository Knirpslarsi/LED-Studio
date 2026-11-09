package de.knirpslarsi.ledstudio;

import de.knirpslarsi.ledstudio.audio.AudioEngine;
import de.knirpslarsi.ledstudio.effects.Effect;
import de.knirpslarsi.ledstudio.effects.Effects;
import de.knirpslarsi.ledstudio.effects.Params;
import de.knirpslarsi.ledstudio.engine.Engine;
import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.LayerView;
import de.knirpslarsi.ledstudio.layout.LayoutTools;
import de.knirpslarsi.ledstudio.layout.Targets;
import de.knirpslarsi.ledstudio.model.Fixture;
import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.openrgb.Controller;
import de.knirpslarsi.ledstudio.openrgb.DemoBackend;
import de.knirpslarsi.ledstudio.openrgb.LedBackend;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayerTest {
    private static final class Recording implements LedBackend {
        final List<Controller> list = new DemoBackend().controllers();
        final Map<Integer, int[]> last = new ConcurrentHashMap<>();

        @Override
        public List<Controller> controllers() {
            return list;
        }

        @Override
        public List<Controller> refresh() {
            return list;
        }

        @Override
        public void enterDirectMode(int d) {
        }

        @Override
        public void setLeds(int d, int[] c) {
            last.put(d, c.clone());
        }

        @Override
        public void resizeZone(int d, int z, int s) {
        }

        @Override
        public boolean supportsSegments() {
            return true;
        }

        @Override
        public void clearSegments(int d, int z) {
        }

        @Override
        public void addSegment(int d, int z, String n, int s, int c) {
        }

        @Override
        public void rescan() {
        }

        @Override
        public boolean consumeDeviceListChanged() {
            return false;
        }

        @Override
        public void close() {
        }
    }

    private static Project.Layer solid(String hex, String... targets) {
        Project.Layer l = new Project.Layer();
        l.effect = "static";
        l.params = new LinkedHashMap<>(Map.of("use_color", 1, "color", hex));
        l.targets = new ArrayList<>(List.of(targets));
        return l;
    }

    private static Project project(List<Fixture> fixtures, Project.Layer... layers) {
        Project p = new Project();
        Project.Preset preset = new Project.Preset();
        preset.name = "Test";
        preset.layers = new ArrayList<>(List.of(layers));
        p.presets = new ArrayList<>(List.of(preset));
        p.current = "Test";
        p.fps = 120;
        p.smoothMs = 0;
        p.fixtures = fixtures;
        return p;
    }

    private static void waitFor(java.util.function.BooleanSupplier s) throws InterruptedException {
        long end = System.currentTimeMillis() + 4000;
        while (!s.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(5);
    }

    private static Engine run(Project p, Recording b) {
        Engine e = new Engine(new AudioEngine(() -> p.audio), x -> {
        });
        e.apply(p, CompiledLayout.compile(p.fixtures, b.list));
        e.setBackend(b);
        e.start();
        return e;
    }

    /** NZXT (Lüfter) und ASUS (Strips), jedes Gerät mit eigenem Bauteil. */
    private static List<Fixture> twoDevices(List<Controller> c) {
        List<Fixture> f = new ArrayList<>(LayoutTools.legacyNzxtChain(c));
        f.addAll(LayoutTools.fromDevice(c.get(1), 300));
        return f;
    }

    @Test
    void eachDeviceGetsItsOwnEffect() throws Exception {
        Recording b = new Recording();
        Controller nzxt = b.list.get(0), asus = b.list.get(1);
        List<Fixture> fixtures = twoDevices(b.list);
        Project p = project(fixtures, solid("#FF0000", Targets.device(nzxt.key())), solid("#0000FF", Targets.device(asus.key())));
        Engine e = run(p, b);
        waitFor(() -> b.last.containsKey(0) && b.last.containsKey(1));
        assertEquals(0xFF0000, b.last.get(0)[0]);
        assertEquals(0x0000FF, b.last.get(1)[0]);
        e.stop();
    }

    @Test
    void singleFanInOwnColorOnTopOfTheRest() throws Exception {
        Recording b = new Recording();
        List<Fixture> fixtures = LayoutTools.legacyNzxtChain(b.list);
        Fixture one = fixtures.get(1); // Channel 1, erster Lüfter: Adressen 0..7
        Project p = project(fixtures, solid("#00FF00", Targets.fixture(one.id)), solid("#FF0000"));
        Engine e = run(p, b);
        waitFor(() -> b.last.containsKey(0) && b.last.get(0)[0] == 0x00FF00);
        assertEquals(0x00FF00, b.last.get(0)[0], "obere Ebene liegt auf dem Lüfter");
        assertEquals(0xFF0000, b.last.get(0)[8], "untere Ebene überall sonst");
        e.stop();
    }

    @Test
    void deviceWithoutLayerStaysUntouched() throws Exception {
        Recording b = new Recording();
        List<Fixture> fixtures = twoDevices(b.list);
        Project p = project(fixtures, solid("#FF0000", Targets.device(b.list.get(0).key())));
        Engine e = run(p, b);
        waitFor(() -> b.last.containsKey(0));
        Thread.sleep(100);
        assertFalse(b.last.containsKey(1), "ASUS hat keine Ebene und behält seinen eigenen Zustand");
        e.stop();
    }

    @Test
    void blendModes() throws Exception {
        Recording b = new Recording();
        List<Fixture> fixtures = LayoutTools.legacyNzxtChain(b.list);
        Project.Layer top = solid("#0000FF");
        top.blend = "add";
        Project p = project(fixtures, top, solid("#FF0000"));
        Engine e = run(p, b);
        waitFor(() -> b.last.containsKey(0) && b.last.get(0)[0] == 0xFF00FF);
        assertEquals(0xFF00FF, b.last.get(0)[0], "Addieren: Rot + Blau");
        top.blend = "normal";
        top.opacity = 0;
        e.apply(p, CompiledLayout.compile(p.fixtures, b.list));
        waitFor(() -> b.last.get(0)[0] == 0xFF0000);
        assertEquals(0xFF0000, b.last.get(0)[0], "Deckkraft 0 zeigt die Ebene darunter");
        e.stop();
    }

    @Test
    void disabledAndSoloLayers() throws Exception {
        Recording b = new Recording();
        List<Fixture> fixtures = LayoutTools.legacyNzxtChain(b.list);
        Project.Layer top = solid("#0000FF");
        Project.Layer bottom = solid("#FF0000");
        top.enabled = false;
        Project p = project(fixtures, top, bottom);
        Engine e = run(p, b);
        waitFor(() -> b.last.containsKey(0) && b.last.get(0)[0] == 0xFF0000);
        assertEquals(0xFF0000, b.last.get(0)[0], "abgeschaltete Ebene zählt nicht");
        top.enabled = true;
        e.solo(bottom.id);
        e.apply(p, CompiledLayout.compile(p.fixtures, b.list));
        Thread.sleep(100);
        assertEquals(0xFF0000, b.last.get(0)[0], "Solo zeigt nur diese Ebene");
        e.solo(null);
        waitFor(() -> b.last.get(0)[0] == 0x0000FF);
        assertEquals(0x0000FF, b.last.get(0)[0]);
        e.stop();
    }

    @Test
    void recolorAndAdjustments() throws Exception {
        Recording b = new Recording();
        List<Fixture> fixtures = LayoutTools.legacyNzxtChain(b.list);
        Project.Layer l = solid("#FF0000");
        l.colorMode = "solid";
        l.color1 = "#00FF00";
        Project p = project(fixtures, l);
        Engine e = run(p, b);
        waitFor(() -> b.last.containsKey(0) && b.last.get(0)[0] == 0x00FF00);
        assertEquals(0x00FF00, b.last.get(0)[0], "Farbe ersetzt");
        l.colorMode = "off";
        l.hueShift = 1.0 / 3;
        e.apply(p, CompiledLayout.compile(p.fixtures, b.list));
        waitFor(() -> b.last.get(0)[0] == 0x00FF00 && l.hueShift > 0);
        assertEquals(0x00FF00, b.last.get(0)[0], "Rot um ein Drittel verschoben ist Grün");
        l.hueShift = 0;
        l.saturation = 0;
        e.apply(p, CompiledLayout.compile(p.fixtures, b.list));
        waitFor(() -> b.last.get(0)[0] == 0xFFFFFF);
        assertEquals(0xFFFFFF, b.last.get(0)[0], "ohne Sättigung weiß");
        l.saturation = 1;
        l.brightness = 0.5;
        e.apply(p, CompiledLayout.compile(p.fixtures, b.list));
        waitFor(() -> (b.last.get(0)[0] >> 16) < 200);
        assertEquals(127, b.last.get(0)[0] >> 16, 1);
        e.stop();
    }

    @Test
    void layerViewIsTheEffectsWholeWorld() {
        List<Controller> c = new DemoBackend().controllers();
        List<Fixture> fixtures = LayoutTools.legacyNzxtChain(c);
        CompiledLayout parent = CompiledLayout.compile(fixtures, c);
        Project.Layer l = new Project.Layer();
        l.targets = new ArrayList<>(List.of(Targets.fixture(fixtures.get(3).id), Targets.fixture(fixtures.get(5).id)));
        LayerView view = LayerView.build(parent, fixtures, l);
        assertEquals(16, view.layout.leds.length);
        assertEquals(2, view.layout.fanCount, "Kette besteht nur aus dem Ziel");
        double minX = 9, maxX = -9;
        for (CompiledLayout.Led led : view.layout.leds) {
            minX = Math.min(minX, led.x);
            maxX = Math.max(maxX, led.x);
        }
        assertEquals(0, minX, 1e-9);
        assertEquals(1, maxX, 1e-9, "Effekt füllt genau sein Ziel");

        l.scope = "global";
        CompiledLayout.Led kept = LayerView.build(parent, fixtures, l).layout.leds[0];
        assertEquals(parent.leds[view.toGlobal[0]].x, kept.x, 1e-9, "globaler Bereich behält die Position im Aufbau");
    }

    @Test
    void reverseAndMirrorAndFilter() {
        List<Controller> c = new DemoBackend().controllers();
        List<Fixture> fixtures = LayoutTools.legacyNzxtChain(c);
        CompiledLayout parent = CompiledLayout.compile(fixtures, c);
        Project.Layer l = new Project.Layer();
        l.reverse = true;
        LayerView rev = LayerView.build(parent, fixtures, l);
        assertEquals(parent.leds.length - 1, rev.toGlobal[0], "Kette läuft rückwärts");
        assertEquals(parent.leds[parent.leds.length - 1].fixture, parent.fanCount - 1);
        assertEquals(0, rev.layout.leds[0].fixture);

        Project.Layer m = new Project.Layer();
        m.mirrorX = true;
        LayerView mirrored = LayerView.build(parent, fixtures, m);
        assertEquals(parent.width - parent.leds[0].x, mirrored.layout.leds[0].x, 1e-9);

        Project.Layer f = new Project.Layer();
        f.ledFilter = "rim";
        assertEquals(7 * 6, LayerView.build(parent, fixtures, f).layout.leds.length, "6 von 8 LEDs je Lüfter liegen am Rand");
        f.ledFilter = "sides";
        assertEquals(7 * 2, LayerView.build(parent, fixtures, f).layout.leds.length);
    }

    @Test
    void defaultLayerRendersExactlyLikeTheEffectAlone() {
        List<Controller> c = new DemoBackend().controllers();
        List<Fixture> fixtures = LayoutTools.legacyNzxtChain(c);
        CompiledLayout parent = CompiledLayout.compile(fixtures, c);
        LayerView view = LayerView.build(parent, fixtures, new Project.Layer());
        Effect effect = Effects.create("edge_sweep");
        int[] a = new int[parent.leds.length], b = new int[parent.leds.length];
        for (CompiledLayout layout : new CompiledLayout[]{parent, view.layout}) {
            Effect.Frame f = new Effect.Frame();
            f.layout = layout;
            f.t = f.rawT = 1.7;
            f.p = new Params(effect.params(), Map.of("gradient_mode", "flow"));
            f.out = layout == parent ? a : b;
            effect.render(f);
        }
        assertArrayEquals(a, b, "ohne Einstellungen bleibt die Ausgabe gleich");
    }

    @Test
    void targetsKeepTheCoarsestForm() {
        List<Controller> c = new DemoBackend().controllers();
        List<Fixture> f = twoDevices(c);
        String nzxt = c.get(0).key();
        assertEquals(f.size(), Targets.resolve(List.of(), f).size(), "leer heißt alles");

        List<String> t = Targets.toggle(List.of(), f, Set.of(f.get(0).id), false);
        assertTrue(t.contains(Targets.device(c.get(1).key())), "unberührtes Gerät bleibt als Gerät: " + t);
        assertEquals(f.size() - 1, Targets.resolve(t, f).size());

        List<String> back = Targets.toggle(t, f, Set.of(f.get(0).id), true);
        assertTrue(back.isEmpty(), "wieder alles");

        List<String> none = Targets.toggle(List.of(), f, Targets.resolve(List.of(), f), false);
        assertEquals(List.of(Targets.NONE), none);
        assertTrue(Targets.resolve(none, f).isEmpty());

        Set<String> nzxtIds = new java.util.HashSet<>();
        f.stream().filter(x -> x.deviceKey.equals(nzxt)).forEach(x -> nzxtIds.add(x.id));
        assertEquals(List.of(Targets.device(nzxt)), Targets.toggle(none, f, nzxtIds, true));

        // Ein neuer Lüfter desselben Geräts macht automatisch mit.
        Fixture extra = f.get(0).copy();
        List<Fixture> more = new ArrayList<>(f);
        more.add(extra);
        assertTrue(Targets.resolve(List.of(Targets.device(nzxt)), more).contains(extra.id));
    }

    @Test
    void oldFlatPresetsBecomeOneLayerAndCopiesAreDeep() {
        Project.Preset flat = new Project.Preset("Alt", "edge_sweep", Map.of("hue", 0.5));
        List<Project.Layer> layers = flat.layers();
        assertEquals(1, layers.size());
        assertEquals("edge_sweep", layers.get(0).effect);
        assertEquals(0.5, layers.get(0).params.get("hue"));
        assertTrue(layers.get(0).targets.isEmpty());

        Project.Preset copy = flat.copy("Neu");
        copy.layers().get(0).params.put("hue", 0.9);
        assertEquals(0.5, flat.layers().get(0).params.get("hue"));
        assertFalse(copy.layers().get(0).id.equals(flat.layers().get(0).id));

        // Beim Speichern und Laden bleibt die Vorlage erhalten.
        Project.Preset loaded = Project.GSON.fromJson(Project.GSON.toJson(flat), Project.Preset.class);
        assertEquals("edge_sweep", loaded.layers().get(0).effect);
    }

    @Test
    void musicEffectRendersPerLayer() {
        List<Controller> c = new DemoBackend().controllers();
        List<Fixture> fixtures = LayoutTools.legacyNzxtChain(c);
        CompiledLayout parent = CompiledLayout.compile(fixtures, c);
        Effect effect = Effects.create("music");
        assertTrue(effect.needsAudio());
        Effect.Frame f = new Effect.Frame();
        f.layout = parent;
        f.p = new Params(effect.params(), Map.of("gain", 20.0, "mode", "Beat-Balken"));
        f.out = new int[parent.leds.length];
        boolean lit = false;
        for (int i = 0; i < 40; i++) {
            f.now = i * 0.02;
            f.audioStamp = f.now;
            f.audio = new double[]{0.5, i % 10 == 0 ? 0.8 : 0.01, 0.1, 0.1};
            effect.render(f);
            for (int v : f.out) lit |= v != 0;
        }
        assertTrue(lit, "ein Beat startet die Animation");
    }

    /** Feinschliff: Eine normale Ebene reagiert auf genau den gewählten Bereich der Musik. */
    private static int[] reactRange(String source) throws Exception {
        Recording b = new Recording();
        Project.Layer layer = solid("#FF0000");
        layer.audioBand = source;
        layer.audioAmount = 1;
        Project p = project(LayoutTools.legacyNzxtChain(b.list), layer);
        AudioEngine audio = new AudioEngine(() -> p.audio);
        Engine e = new Engine(audio, x -> {
        });
        e.apply(p, CompiledLayout.compile(p.fixtures, b.list));
        e.setBackend(b);
        e.start();
        int lo = 255, hi = 0;
        long start = System.nanoTime();
        while (System.nanoTime() - start < 2_500_000_000L) {
            double now = System.nanoTime() / 1e9;
            boolean on = ((System.nanoTime() - start) / 250_000_000L) % 2 == 0;
            double[] v = new double[4 + de.knirpslarsi.ledstudio.audio.Music.SPECTRUM];
            for (int k = 0; k < de.knirpslarsi.ledstudio.audio.Music.SPECTRUM; k++) v[4 + k] = k >= 12 && on ? 0.3 : 0.01;
            v[0] = 0.1;
            audio.values = v;
            audio.stamp = now;
            Thread.sleep(21);
            int[] colors = b.last.get(0);
            if (colors != null && System.nanoTime() - start > 800_000_000L) {
                int red = (colors[0] >> 16) & 255;
                lo = Math.min(lo, red);
                hi = Math.max(hi, red);
            }
        }
        e.stop();
        return new int[]{lo, hi};
    }

    @Test
    void layerReactsToTheChosenSound() throws Exception {
        int[] high = reactRange("high"), bass = reactRange("bass");
        System.out.printf("Rote Ebene, nur Höhen pulsieren: Reaktion auf Höhen %d..%d, auf Bass %d..%d%n", high[0], high[1], bass[0], bass[1]);
        assertTrue(high[1] > 200 && high[0] < 100, "pulsiert mit den Höhen");
        assertTrue(bass[1] < 60, "auf Bass gestellt bleibt sie dunkel");
        org.junit.jupiter.api.Assertions.assertEquals(de.knirpslarsi.ledstudio.audio.SoundSource.MID,
                de.knirpslarsi.ledstudio.audio.SoundSource.byId("Mitten"), "alte Einstellung wird übernommen");
    }
}
