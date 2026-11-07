package de.knirpslarsi.ledstudio;

import de.knirpslarsi.ledstudio.audio.AudioEngine;
import de.knirpslarsi.ledstudio.effects.Effect;
import de.knirpslarsi.ledstudio.effects.Effects;
import de.knirpslarsi.ledstudio.effects.Params;
import de.knirpslarsi.ledstudio.effects.ScriptEffects;
import de.knirpslarsi.ledstudio.engine.Engine;
import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.LayoutTools;
import de.knirpslarsi.ledstudio.layout.Shapes;
import de.knirpslarsi.ledstudio.model.Fixture;
import de.knirpslarsi.ledstudio.model.LegacyImport;
import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.openrgb.Controller;
import de.knirpslarsi.ledstudio.openrgb.DemoBackend;
import de.knirpslarsi.ledstudio.openrgb.LedBackend;
import de.knirpslarsi.ledstudio.util.Colors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineAndLayoutTest {
    private static final class Recording implements LedBackend {
        final List<Controller> list = new DemoBackend().controllers();
        final Map<Integer, int[]> last = new ConcurrentHashMap<>();
        final Set<Integer> direct = ConcurrentHashMap.newKeySet();
        final List<Integer> writes = Collections.synchronizedList(new ArrayList<>());

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
            direct.add(d);
        }

        @Override
        public void setLeds(int d, int[] c) {
            last.put(d, c.clone());
            writes.add(d);
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

    private static Fixture fan(Controller c, String zone, int start, double x) {
        Fixture f = new Fixture();
        f.deviceKey = c.key();
        f.zoneName = zone;
        f.start = start;
        f.count = 8;
        f.x = x;
        return f;
    }

    private static Project project() {
        Project p = new Project();
        p.presets = new ArrayList<>(Project.defaultPresets());
        p.current = "Rot";
        p.presets.add(new Project.Preset("Rot", "static", Map.of("hue", 0.0)));
        p.fps = 120;
        return p;
    }

    private static void waitFor(java.util.function.BooleanSupplier s) throws InterruptedException {
        long end = System.currentTimeMillis() + 4000;
        while (!s.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(5);
    }

    @Test
    void ringStartsRightAndRunsClockwise() {
        Fixture f = new Fixture();
        f.count = 4;
        double[][] p = Shapes.local(f);
        assertEquals(0.5, p[0][0], 1e-9);
        assertEquals(0.5, p[1][1], 1e-9, "zweite LED unten (y zeigt nach unten)");
        f.reverse = true;
        assertEquals(-0.5, Shapes.local(f)[1][1], 1e-9);
    }

    @Test
    void worldTransformRoundTrips() {
        Fixture f = new Fixture();
        f.x = 40;
        f.y = -30;
        f.rotation = 33;
        f.flipX = true;
        f.width = 90;
        f.height = 50;
        double[] w = new double[2], l = new double[2];
        Shapes.toWorld(f, 0.2, -0.4, w);
        Shapes.toLocal(f, w[0], w[1], l);
        assertEquals(0.2, l[0], 1e-9);
        assertEquals(-0.4, l[1], 1e-9);
    }

    @Test
    void allShapesFillEveryLedInBounds() {
        for (Fixture.Shape s : Fixture.Shape.values()) {
            for (int n : new int[]{1, 2, 3, 8, 13, 60}) {
                Fixture f = new Fixture();
                f.shape = s;
                f.count = n;
                f.cols = 4;
                f.innerCount = 3;
                double[][] p = Shapes.local(f);
                assertEquals(n, p.length);
                for (double[] q : p) {
                    assertTrue(Double.isFinite(q[0]) && Double.isFinite(q[1]), s + " " + n);
                    assertTrue(Math.abs(q[0]) <= 1.1 && Math.abs(q[1]) <= 1.1, s + " " + n + ": " + q[0] + "," + q[1]);
                }
            }
        }
    }

    @Test
    void squarePerimeterIsEvenlySpaced() {
        Fixture f = new Fixture();
        f.shape = Fixture.Shape.SQUARE;
        f.corner = 0.25;
        f.count = 16;
        double[][] p = Shapes.local(f);
        assertEquals(0.5, p[0][0], 1e-9, "Start rechts in der Mitte");
        for (int i = 0; i < p.length; i++) {
            double[] a = p[i], b = p[(i + 1) % p.length];
            double d = Math.hypot(a[0] - b[0], a[1] - b[1]);
            assertTrue(d > 0.1 && d < 0.3, "Abstand " + i + ": " + d);
        }
    }

    @Test
    void autoLayoutSplitsZonesIntoFansAndKeepsMatrixShape() {
        List<Controller> c = new DemoBackend().controllers();
        List<Fixture> all = LayoutTools.autoLayout(c);
        long ch1 = all.stream().filter(f -> f.zoneName.equals("Hue 2 Channel 1")).count();
        assertEquals(3, ch1, "24 LEDs = 3 Lüfter à 8");
        assertTrue(all.stream().noneMatch(f -> f.zoneName.equals("Hue 2 Channel 3")), "leere Zonen werden ausgelassen");
        Fixture keyboard = all.stream().filter(f -> f.zoneName.equals("Keyboard")).findFirst().orElseThrow();
        assertEquals(Fixture.Shape.FREE, keyboard.shape);
        assertEquals(keyboard.count, keyboard.points.size());
    }

    @Test
    void legacyChainMatchesOldWiring() {
        List<Fixture> chain = LayoutTools.legacyNzxtChain(new DemoBackend().controllers());
        assertEquals(7, chain.size());
        assertEquals("Hue 2 Channel 5", chain.get(0).zoneName);
        assertTrue(chain.get(0).flipX, "Channel 5 ist gespiegelt");
        assertFalse(chain.get(1).flipX);
        assertEquals("Hue 2 Channel 2", chain.get(6).zoneName);
    }

    @Test
    void analyzeFindsDuplicatesAndProblems() {
        List<Controller> c = new DemoBackend().controllers();
        Controller nzxt = c.get(0);
        List<Fixture> fixtures = new ArrayList<>();
        fixtures.add(fan(nzxt, "Hue 2 Channel 1", 0, 0));
        Fixture splitter = fan(nzxt, "Hue 2 Channel 1", 4, 150);
        fixtures.add(splitter);
        Fixture tooMany = fan(nzxt, "Hue 2 Channel 5", 4, 300);
        fixtures.add(tooMany);
        Fixture gone = fan(nzxt, "Gibt es nicht", 0, 450);
        fixtures.add(gone);
        var issues = LayoutTools.analyze(fixtures, c);
        assertTrue(issues.stream().anyMatch(i -> i.kind() == LayoutTools.Issue.Kind.DUPLICATE_DEVICE), "doppelt erkanntes Gerät");
        var shared = issues.stream().filter(i -> i.kind() == LayoutTools.Issue.Kind.SHARED_ADDRESS).findFirst().orElseThrow();
        assertTrue(shared.detail().startsWith("4 "), shared.detail());
        assertTrue(issues.stream().anyMatch(i -> i.kind() == LayoutTools.Issue.Kind.OUT_OF_RANGE && i.fixtureIds().contains(tooMany.id)));
        assertTrue(issues.stream().anyMatch(i -> i.kind() == LayoutTools.Issue.Kind.MISSING_DEVICE && i.fixtureIds().contains(gone.id)));
        assertTrue(issues.stream().anyMatch(i -> i.kind() == LayoutTools.Issue.Kind.UNMAPPED));
    }

    @Test
    void engineWritesOnlyPlacedDevicesAndTurnsOffOnStop() throws Exception {
        Recording b = new Recording();
        Project p = project();
        Controller nzxt = b.list.get(0);
        p.fixtures.add(fan(nzxt, "Hue 2 Channel 1", 0, 0));
        Engine e = new Engine(new AudioEngine(() -> p.audio), x -> {
        });
        e.apply(p, CompiledLayout.compile(p.fixtures, b.list));
        e.setBackend(b);
        e.start();
        waitFor(() -> b.last.containsKey(0));
        int[] frame = b.last.get(0);
        assertEquals(nzxt.ledCount(), frame.length);
        assertEquals(0xFF0000, frame[3], "Fixture-LED rot");
        assertEquals(0, frame[8], "nicht zugeordnete LED aus");
        assertTrue(b.direct.contains(0));
        assertFalse(b.last.containsKey(1), "andere Geräte bleiben unberührt");
        e.stop();
        assertEquals(0, b.last.get(0)[3], "beim Beenden aus");
    }

    @Test
    void sharedAddressTakesBrighterValueAndPreviewShowsItEverywhere() throws Exception {
        Recording b = new Recording();
        Project p = project();
        Controller nzxt = b.list.get(0);
        Fixture bright = fan(nzxt, "Hue 2 Channel 1", 0, 0);
        Fixture dark = fan(nzxt, "Hue 2 Channel 1", 0, 200);
        dark.brightness = 0;
        p.fixtures.add(dark);
        p.fixtures.add(bright);
        Engine e = new Engine(new AudioEngine(() -> p.audio), x -> {
        });
        e.apply(p, CompiledLayout.compile(p.fixtures, b.list));
        e.setBackend(b);
        e.start();
        waitFor(() -> b.last.containsKey(0));
        assertEquals(0xFF0000, b.last.get(0)[0]);
        waitFor(() -> e.preview().length == 16);
        assertEquals(0xFF0000, e.preview()[0], "die dunkle Position zeigt, was physisch leuchtet");
        e.stop();
    }

    @Test
    void skippedLedColorOrderAndRemovedDevice() throws Exception {
        Recording b = new Recording();
        Project p = project();
        Controller nzxt = b.list.get(0);
        Fixture f = fan(nzxt, "Hue 2 Channel 1", 0, 0);
        f.skip.add(2);
        f.colorOrder = "GRB";
        p.fixtures.add(f);
        Engine e = new Engine(new AudioEngine(() -> p.audio), x -> {
        });
        e.apply(p, CompiledLayout.compile(p.fixtures, b.list));
        e.setBackend(b);
        e.start();
        waitFor(() -> b.last.containsKey(0));
        assertEquals(0x00FF00, b.last.get(0)[0], "Rot auf einem GRB-Strip wird als G gesendet");
        assertEquals(0, b.last.get(0)[1], "ausgelassene LED");
        p.fixtures.clear();
        e.apply(p, CompiledLayout.compile(p.fixtures, b.list));
        waitFor(() -> b.last.get(0)[0] == 0);
        assertEquals(0, b.last.get(0)[0], "Gerät wird dunkel, wenn es aus dem Layout fliegt");
        e.stop();
    }

    @Test
    void identifyLightsOnlyChosenLeds() throws Exception {
        Recording b = new Recording();
        Project p = project();
        p.fixtures.add(fan(b.list.get(0), "Hue 2 Channel 1", 0, 0));
        Engine e = new Engine(new AudioEngine(() -> p.audio), x -> {
        });
        e.apply(p, CompiledLayout.compile(p.fixtures, b.list));
        e.setBackend(b);
        e.start();
        e.identify(Set.of(2), 5000);
        waitFor(() -> b.last.containsKey(0) && b.last.get(0)[2] == 0xFFFFFF);
        assertEquals(0xFFFFFF, b.last.get(0)[2]);
        assertEquals(0, b.last.get(0)[0]);
        e.stop();
    }

    @Test
    void everyEffectRendersFiniteColorsForAnyLayout() {
        List<Controller> c = new DemoBackend().controllers();
        CompiledLayout l = CompiledLayout.compile(LayoutTools.autoLayout(c), c);
        CompiledLayout empty = CompiledLayout.compile(List.of(), c);
        for (Effect effect : Effects.catalog()) {
            Effect.Frame f = new Effect.Frame();
            f.p = new Params(effect.params(), Map.of());
            for (CompiledLayout layout : new CompiledLayout[]{empty, l}) {
                effect.reset(layout);
                f.layout = layout;
                f.out = new int[layout.leds.length];
                boolean lit = layout.leds.length == 0;
                for (int i = 0; i < 400; i++) {
                    f.t = f.rawT = i * 0.033;
                    f.dt = 0.033;
                    effect.render(f);
                    for (int v : f.out) {
                        assertTrue(v >= 0 && v <= 0xFFFFFF, effect.id() + ": " + v);
                        lit |= v != 0;
                    }
                }
                assertTrue(lit, effect.id() + " bleibt komplett dunkel");
            }
        }
    }

    @Test
    void importsOldConfig(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        Files.writeString(file, """
                {"current":"Eigener","speed":1.5,"brightness":0.5,"fps":30,"openrgb_path":"C:/x/OpenRGB.exe",
                 "presets":[{"name":"Eigener","effect":"edge_sweep","params":{"hue":0.5,"bounce":1,"gradient_end":"#0000FF"}},
                            {"name":"Lila Balken","effect":"static","params":{}}],
                 "audio":{"enabled":false,"device":"xy","mode":"Bass-Komet","gain":2.0,"palette":"Cyan"}}""");
        Project p = new Project();
        p.presets = new ArrayList<>(Project.defaultPresets());
        int before = p.presets.size();
        assertEquals(2, LegacyImport.apply(p, file));
        assertEquals(before + 1, p.presets.size());
        assertEquals("static", p.preset("Lila Balken").effect, "gleichnamige Vorlage ersetzt");
        assertEquals("Eigener", p.current);
        assertEquals(1.5, p.speed);
        assertEquals("Bass-Komet", p.audio.mode);
        assertEquals("C:/x/OpenRGB.exe", p.openrgbPath);
    }

    @Test
    void customScriptEffect(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("blau.js"), """
                var INFO = { title: "Blau-Test", params: [ {name: "level", label: "Pegel", def: 200, min: 0, max: 255, step: 1} ] };
                function render(led, t, p) { return [0, 0, p.level]; }""");
        Files.writeString(dir.resolve("kaputt.js"), "das ist kein javascript (");
        List<String> errors = ScriptEffects.load(dir);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).startsWith("kaputt.js"));
        Effect e = Effects.create("js_blau");
        assertNotNull(e);
        assertEquals("Blau-Test", e.title());
        List<Controller> c = new DemoBackend().controllers();
        Effect.Frame f = new Effect.Frame();
        f.layout = CompiledLayout.compile(LayoutTools.legacyNzxtChain(c), c);
        f.out = new int[f.layout.leds.length];
        f.p = new Params(e.params(), Map.of());
        e.render(f);
        assertEquals(200, f.out[0]);
        ScriptEffects.load(dir.resolve("gibt-es-nicht"));
    }

    @Test
    void audioAnalysisSeparatesBands() {
        float[] bass = new float[2048 * 2], treble = new float[2048 * 2];
        for (int i = 0; i < 2048; i++) {
            bass[2 * i] = bass[2 * i + 1] = (float) (0.5 * Math.sin(2 * Math.PI * 100 * i / 48000));
            treble[2 * i] = treble[2 * i + 1] = (float) (0.5 * Math.sin(2 * Math.PI * 6000 * i / 48000));
        }
        double[] b = AudioEngine.analyse(bass, 2, 48000), t = AudioEngine.analyse(treble, 2, 48000);
        assertTrue(b[1] > b[3] * 5, "Bass: " + b[1] + " / " + b[3]);
        assertTrue(t[3] > t[1] * 5, "Höhen: " + t[3] + " / " + t[1]);
        assertEquals(0.5 / Math.sqrt(2), b[0], 0.03, "RMS eines Sinus");
    }

    @Test
    void colorHelpers() {
        assertEquals(0xFF0000, Colors.hsv(0, 1, 1));
        assertEquals("#A020FF", Colors.toHex(Colors.parseHex("#a020ff")));
        assertEquals(0x00FF00, Colors.reorder(0xFF0000, "GRB"));
        assertEquals(0x332211, Colors.reorder(0x112233, "BGR"));
    }
}
