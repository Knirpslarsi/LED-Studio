package de.knirpslarsi.ledstudio.effects;

import de.knirpslarsi.ledstudio.layout.CompiledLayout.Led;
import de.knirpslarsi.ledstudio.util.Colors;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Eigene Effekte als JavaScript-Datei im Effekt-Ordner (Ersatz für die alten Python-Dateien):
 * <pre>
 * var INFO = { title: "Mein Effekt", params: [ {name: "speed", label: "Tempo", def: 1, min: 0, max: 5, step: 0.1} ] };
 * function render(led, t, p) { return [255 * led.x, 0, 255 * led.y]; }
 * </pre>
 * led hat x, y, u, s, angle, fixture, k, m, seq, rim und count (LEDs gesamt).
 */
public final class ScriptEffects {
    private static final Logger LOG = Logger.getLogger("ScriptEffects");

    private ScriptEffects() {
    }

    /** Lädt alle .js-Dateien. Gibt für jede defekte Datei eine Meldung zurück. */
    public static List<String> load(Path folder) {
        List<String> errors = new ArrayList<>();
        Map<String, Supplier<Effect>> found = new LinkedHashMap<>();
        try (Stream<Path> files = Files.exists(folder) ? Files.list(folder) : Stream.empty()) {
            files.filter(p -> p.toString().endsWith(".js")).sorted().forEach(path -> {
                String id = "js_" + path.getFileName().toString().replaceFirst("\\.js$", "");
                try {
                    String source = Files.readString(path);
                    new ScriptEffect(id, source); // früh prüfen, damit Fehler beim Laden auffallen
                    found.put(id, () -> new ScriptEffect(id, source));
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Effekt " + path + " fehlerhaft", e);
                    errors.add(path.getFileName() + ": " + e.getMessage());
                }
            });
        } catch (IOException e) {
            errors.add(e.getMessage());
        }
        Effects.setCustom(found);
        return errors;
    }

    private static final class ScriptEffect extends Effect {
        private final String id, source;
        private final String title;
        private final List<ParamSpec> specs = new ArrayList<>();
        private Scriptable scope;
        private Function render;
        private Thread owner;
        private Context cx;

        ScriptEffect(String id, String source) {
            this.id = id;
            this.source = source;
            Context c = enter();
            try {
                Scriptable s = c.initSafeStandardObjects();
                c.evaluateString(s, source, id, 1, null);
                if (!(ScriptableObject.getProperty(s, "render") instanceof Function))
                    throw new IllegalArgumentException("Funktion render(led, t, p) fehlt");
                String t = id.substring(3);
                Object info = ScriptableObject.getProperty(s, "INFO");
                if (info instanceof Scriptable i) {
                    Object name = ScriptableObject.getProperty(i, "title");
                    if (name instanceof CharSequence) t = name.toString();
                    Object ps = ScriptableObject.getProperty(i, "params");
                    if (ps instanceof NativeArray array) {
                        for (Object o : array) if (o instanceof Scriptable q) specs.add(spec(q));
                    }
                }
                title = t;
            } finally {
                Context.exit();
            }
        }

        private static Context enter() {
            Context c = Context.enter();
            c.setOptimizationLevel(-1);
            c.setLanguageVersion(Context.VERSION_ES6);
            return c;
        }

        private static ParamSpec spec(Scriptable q) {
            String name = String.valueOf(ScriptableObject.getProperty(q, "name"));
            Object labelValue = ScriptableObject.getProperty(q, "label");
            String label = labelValue instanceof CharSequence ? labelValue.toString() : name;
            Object def = ScriptableObject.getProperty(q, "def");
            if (def instanceof Boolean b) return ParamSpec.bool(name, label, b);
            if (def instanceof CharSequence s) return ParamSpec.color(name, label, s.toString());
            return ParamSpec.num(name, label, num(def, 0), num(ScriptableObject.getProperty(q, "min"), 0),
                    num(ScriptableObject.getProperty(q, "max"), 1), num(ScriptableObject.getProperty(q, "step"), 0.01));
        }

        private static double num(Object o, double fallback) {
            return o instanceof Number n ? n.doubleValue() : fallback;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public String blurb() {
            return "Eigener Effekt aus dem Effekt-Ordner.";
        }

        @Override
        public List<ParamSpec> params() {
            return specs;
        }

        @Override
        public void render(Frame f) {
            // Rhino-Kontexte sind an den Thread gebunden, deshalb erst im Render-Thread aufbauen.
            if (owner != Thread.currentThread()) {
                owner = Thread.currentThread();
                cx = enter();
                scope = cx.initSafeStandardObjects();
                cx.evaluateString(scope, source, id, 1, null);
                render = (Function) ScriptableObject.getProperty(scope, "render");
            }
            Scriptable params = cx.newObject(scope);
            for (ParamSpec s : specs) {
                Object v = switch (s.type) {
                    case BOOL -> f.p.b(s.name);
                    case COLOR, CHOICE -> f.p.s(s.name);
                    default -> f.p.d(s.name);
                };
                ScriptableObject.putProperty(params, s.name, v);
            }
            Led[] leds = f.layout.leds;
            Scriptable led = cx.newObject(scope);
            ScriptableObject.putProperty(led, "count", leds.length);
            Object[] args = {led, f.t, params};
            for (int i = 0; i < leds.length; i++) {
                Led l = leds[i];
                ScriptableObject.putProperty(led, "x", l.x);
                ScriptableObject.putProperty(led, "y", l.y);
                ScriptableObject.putProperty(led, "u", l.u);
                ScriptableObject.putProperty(led, "s", l.s);
                ScriptableObject.putProperty(led, "angle", l.angle);
                ScriptableObject.putProperty(led, "fixture", l.fixture);
                ScriptableObject.putProperty(led, "k", l.k);
                ScriptableObject.putProperty(led, "m", l.m);
                ScriptableObject.putProperty(led, "seq", i);
                ScriptableObject.putProperty(led, "rim", l.rim);
                Object result = render.call(cx, scope, scope, args);
                if (result instanceof NativeArray a && a.size() >= 3) {
                    f.out[i] = Colors.pack((int) num(a.get(0), 0), (int) num(a.get(1), 0), (int) num(a.get(2), 0));
                } else {
                    f.out[i] = 0;
                }
            }
        }
    }
}
