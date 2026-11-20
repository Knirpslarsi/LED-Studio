package de.knirpslarsi.ledstudio;

import de.knirpslarsi.ledstudio.effects.Effect;
import de.knirpslarsi.ledstudio.effects.Effects;
import de.knirpslarsi.ledstudio.effects.Params;
import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.LayoutTools;
import de.knirpslarsi.ledstudio.model.Games;
import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.openrgb.DemoBackend;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Die Animationen laufen einmal durch, zeigen etwas, bewegen sich und enden ruhig. */
class AnimationsTest {
    static final String[] IDS = {"anim_boot", "anim_spin", "anim_shockwave", "anim_explosion", "anim_warp", "anim_scan",
            "anim_fireworks", "anim_hit", "anim_alarm", "anim_flatline", "anim_shatter", "anim_heartbeat"};

    private static CompiledLayout layout() {
        DemoBackend b = new DemoBackend();
        return CompiledLayout.compile(LayoutTools.legacyNzxtChain(b.controllers()), b.controllers());
    }

    /** Rendert eine Animation zu den Zeiten times (Sekunden) mit der Länge length. */
    static List<int[]> run(String id, Map<String, Object> params, double length, double[] times) {
        CompiledLayout l = layout();
        Effect e = Effects.create(id);
        Map<String, Object> values = new HashMap<>(params);
        values.put("_length", length);
        Effect.Frame f = new Effect.Frame();
        f.layout = l;
        f.p = new Params(e.params(), values);
        e.reset(l);
        List<int[]> out = new ArrayList<>();
        for (double t : times) {
            f.out = new int[l.leds.length];
            f.t = t;
            f.rawT = t;
            f.now = t;
            f.dt = 1 / 40.0;
            e.render(f);
            out.add(f.out);
        }
        return out;
    }

    private static double light(int[] frame) {
        double sum = 0;
        for (int c : frame) sum += ((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255);
        return sum / Math.max(1, frame.length) / 765.0;
    }

    @Test
    void everyAnimationShowsMovesAndIsRegistered() {
        for (String id : IDS) {
            assertTrue(Effects.create(id) != null, id + " registriert");
            double length = id.equals("anim_alarm") ? 10 : 3;
            double[] times = new double[30];
            for (int i = 0; i < times.length; i++) times[i] = length * i / (times.length - 1);
            List<int[]> frames = run(id, Map.of(), length, times);
            double max = 0;
            int changes = 0;
            for (int i = 0; i < frames.size(); i++) {
                max = Math.max(max, light(frames.get(i)));
                if (i > 0 && !java.util.Arrays.equals(frames.get(i), frames.get(i - 1))) changes++;
            }
            assertTrue(max > 0.15, id + " leuchtet sichtbar: " + max);
            assertTrue(changes > 20, id + " bewegt sich: " + changes);
        }
    }

    @Test
    void withoutLengthTheyLoopInPresets() {
        List<int[]> a = run("anim_shockwave", Map.of(), 0, new double[]{0.5, 2.5});
        assertEquals(java.util.Arrays.toString(a.get(0)), java.util.Arrays.toString(a.get(1)), "Runde von 2 s wiederholt sich");
    }

    @Test
    void untouchedOldDefaultsAreUpgradedOwnOnesStay() {
        Games g = new Games();
        g.appIntro = Project.GSON.fromJson(Project.GSON.toJson(legacy("appIntro")), Games.Animation.class);
        g.appIntro.enabled = false;
        Games.Animation mine = Games.cs2Defaults().get("kill");
        mine.look = Project.Layer.solid("#00FF00");
        g.cs2.events.put("kill", mine);
        g.cs2.events.put("death", legacyEvent("death"));
        g.style = 0;
        g.complete();
        assertEquals("anim_boot", g.appIntro.look.effect, "alte Startanimation ersetzt");
        assertFalse(g.appIntro.enabled, "aber weiter ausgeschaltet");
        assertEquals("static", g.cs2.events.get("kill").look.effect, "eigene bleibt");
        assertEquals("anim_flatline", g.cs2.events.get("death").look.effect);
    }

    private static Object legacy(String what) {
        try {
            var m = Games.class.getDeclaredMethod("legacyAppIntro");
            m.setAccessible(true);
            return m.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Games.Animation legacyEvent(String id) {
        try {
            var m = Games.class.getDeclaredMethod("legacyCs2");
            m.setAccessible(true);
            return ((Map<String, Games.Animation>) m.invoke(null)).get(id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Vorschaubild: je Animation eine Zeile mit 12 Momenten, LEDs als Punkte an ihrer Lage. -Danim.sheet=pfad */
    @Test
    void previewSheet() throws Exception {
        String path = System.getProperty("anim.sheet");
        if (path == null) return;
        CompiledLayout l = layout();
        int cols = 12, cell = 150, label = 150;
        BufferedImage img = new BufferedImage(label + cols * cell, IDS.length * cell, BufferedImage.TYPE_INT_RGB);
        Graphics2D gr = img.createGraphics();
        gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        gr.setColor(new Color(12, 12, 16));
        gr.fillRect(0, 0, img.getWidth(), img.getHeight());
        double scale = (cell - 16) / Math.max(l.width, l.height);
        for (int row = 0; row < IDS.length; row++) {
            String id = IDS[row];
            double length = id.equals("anim_alarm") ? 12 : id.equals("anim_hit") ? 0.8 : 3.2;
            double[] times = new double[cols];
            for (int i = 0; i < cols; i++) times[i] = length * (i + 0.5) / cols;
            List<int[]> frames = run(id, Map.of(), length, times);
            gr.setColor(Color.LIGHT_GRAY);
            gr.drawString(id, 8, row * cell + cell / 2);
            for (int c = 0; c < cols; c++) {
                int[] fr = frames.get(c);
                int ox = label + c * cell + 8, oy = row * cell + 8;
                for (int i = 0; i < fr.length; i++) {
                    int px = ox + (int) (l.leds[i].x * scale), py = oy + (int) (l.leds[i].y * scale);
                    gr.setColor(new Color(fr[i]));
                    gr.fillOval(px - 4, py - 4, 8, 8);
                }
            }
        }
        ImageIO.write(img, "png", new File(path));
    }
}
