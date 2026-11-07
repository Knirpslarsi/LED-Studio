package de.knirpslarsi.ledstudio;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.knirpslarsi.ledstudio.audio.Music;
import de.knirpslarsi.ledstudio.effects.Effect;
import de.knirpslarsi.ledstudio.effects.Effects;
import de.knirpslarsi.ledstudio.effects.Params;
import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.LayoutTools;
import de.knirpslarsi.ledstudio.openrgb.Controller;
import de.knirpslarsi.ledstudio.openrgb.DemoBackend;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vergleicht mit Werten, die aus der alten Python-Version erzeugt wurden (Abweichung höchstens 1 von 255). */
class GoldenParityTest {
    private static CompiledLayout legacyLayout() {
        List<Controller> devices = new DemoBackend().controllers();
        return CompiledLayout.compile(LayoutTools.legacyNzxtChain(devices), devices);
    }

    private static JsonObject golden() throws Exception {
        try (var r = new InputStreamReader(GoldenParityTest.class.getResourceAsStream("/golden.json"), StandardCharsets.UTF_8)) {
            return new Gson().fromJson(r, JsonObject.class);
        }
    }

    private static void compare(String what, CompiledLayout l, int[] out, JsonArray expected) {
        for (int i = 0; i < l.leds.length; i++) {
            JsonArray e = expected.get(l.leds[i].address).getAsJsonArray();
            int c = out[i];
            int[] actual = {(c >> 16) & 255, (c >> 8) & 255, c & 255};
            for (int ch = 0; ch < 3; ch++) {
                int want = e.get(ch).getAsInt();
                assertTrue(Math.abs(actual[ch] - want) <= 1,
                        what + " LED " + l.leds[i].address + " Kanal " + ch + ": erwartet " + want + ", war " + actual[ch]);
            }
        }
    }

    @Test
    void effectsMatchPython() throws Exception {
        CompiledLayout l = legacyLayout();
        assertEquals(56, l.leds.length);
        int checked = 0;
        for (var item : golden().getAsJsonArray("effects")) {
            JsonObject c = item.getAsJsonObject();
            Effect effect = Effects.create(c.get("effect").getAsString());
            Map<String, Object> params = new Gson().fromJson(c.get("params"), LinkedHashMap.class);
            params.put("soft", 0.0);
            double t = c.get("t").getAsDouble();
            Effect.Frame f = new Effect.Frame();
            f.layout = l;
            f.t = f.rawT = t;
            f.p = new Params(effect.params(), params);
            f.out = new int[l.leds.length];
            effect.render(f);
            compare(effect.id() + " t=" + t + " " + params, l, f.out, c.getAsJsonArray("colors"));
            checked++;
        }
        assertTrue(checked >= 40);
    }

    @Test
    void musicAnimationsMatchPython() throws Exception {
        CompiledLayout l = legacyLayout();
        for (var item : golden().getAsJsonArray("music")) {
            JsonObject c = item.getAsJsonObject();
            int[] out = new int[l.leds.length];
            Music.animation(l, c.get("mode").getAsString(), c.get("progress").getAsDouble(), 1.2, 0.8, 0.05, c.get("palette").getAsString(), out);
            compare(c.get("mode").getAsString() + " p=" + c.get("progress"), l, out, c.getAsJsonArray("colors"));
        }
    }
}
