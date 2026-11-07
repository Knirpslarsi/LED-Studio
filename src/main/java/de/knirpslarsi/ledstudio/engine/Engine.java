package de.knirpslarsi.ledstudio.engine;

import de.knirpslarsi.ledstudio.audio.AudioEngine;
import de.knirpslarsi.ledstudio.audio.Music;
import de.knirpslarsi.ledstudio.effects.Effect;
import de.knirpslarsi.ledstudio.effects.Effects;
import de.knirpslarsi.ledstudio.effects.Params;
import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.LayerView;
import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.openrgb.LedBackend;
import de.knirpslarsi.ledstudio.util.Colors;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bildschleife: berechnet alle Ebenen, legt sie übereinander, fasst mehrfach belegte Adressen zusammen und
 * schreibt an OpenRGB. Sie läuft auch ohne Verbindung weiter, damit die Vorschau immer lebt.
 */
public final class Engine {
    private static final Logger LOG = Logger.getLogger("Engine");
    private static final long KEEPALIVE_NANOS = 1_000_000_000L;
    private static final double MIN_EFFECT_SPEED = 0.001;

    /** Was über Änderungen hinweg erhalten bleibt, damit Regler den Effekt nicht neu starten. */
    private static final class Runtime {
        final Effect effect;
        double t;
        /** Mit welchem Tempo-Regler t zuletzt gerechnet wurde. */
        double speedUsed = Double.NaN;
        boolean broken;
        int[] trail = new int[0];
        String signature = "";
        volatile boolean resetPending = true;

        Runtime(Effect effect) {
            this.effect = effect;
        }
    }

    /** Eine Ebene, fertig zum Rechnen. */
    private static final class Run {
        Project.Layer layer;
        Runtime runtime;
        LayerView view;
        Params params;
        int[] stops;
        String id;
        int[] out;
    }

    /** Alles, was die Oberfläche auf einmal austauscht. */
    private static final class State {
        CompiledLayout layout;
        List<Run> runs = new ArrayList<>();
        double speed, brightness, trail, gain, floor, smoothSeconds;
        int fps;
        Project.Audio audio;
        boolean off;
        /** Geräte (Index in layout.devices), die eine aktive Ebene berührt. */
        boolean[] covered;
    }

    private static final Map<String, Double> COLOR_HUES = new HashMap<>();

    static {
        COLOR_HUES.put("Lila", 0.77);
        COLOR_HUES.put("Blau", 0.65);
        COLOR_HUES.put("Cyan", 0.5);
        COLOR_HUES.put("Grün", 0.33);
        COLOR_HUES.put("Gelb", 0.15);
        COLOR_HUES.put("Orange", 0.07);
        COLOR_HUES.put("Rot", 0.0);
        COLOR_HUES.put("Pink", 0.88);
    }

    public static final String[] GLOBAL_COLORS = {"preset", "Lila", "Blau", "Cyan", "Grün", "Gelb", "Orange", "Rot", "Pink", "Regenbogen"};

    private volatile State state;
    private volatile LedBackend backend;
    private volatile boolean running;
    private Thread thread;
    private final AudioEngine audio;
    public final Music music = new Music();
    private final Consumer<Throwable> onBackendFailure;
    private volatile Consumer<String> onEffectError = s -> {
    };
    private final Map<String, Runtime> runtimes = new HashMap<>();
    private final double[] layerLevels = new double[4];
    private final SmoothFilter smooth = new SmoothFilter();

    private volatile Set<Integer> identify;
    private volatile long identifyUntil;
    private volatile int[] preview = new int[0];
    private volatile double measuredFps;
    private volatile long framesSent;
    private volatile String solo;

    private final Set<Integer> directSet = new HashSet<>();
    private final Map<Integer, int[]> lastSent = new HashMap<>();
    private final Map<Integer, Long> lastSendTime = new HashMap<>();
    private final Map<Integer, Integer> touched = new HashMap<>();
    private volatile boolean connectionReset;

    public Engine(AudioEngine audio, Consumer<Throwable> onBackendFailure) {
        this.audio = audio;
        this.onBackendFailure = onBackendFailure;
    }

    public void setOnEffectError(Consumer<String> c) {
        onEffectError = c;
    }

    public void setBackend(LedBackend b) {
        backend = b;
        connectionReset = true;
    }

    public void start() {
        running = true;
        thread = new Thread(this::loop, "render");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
    }

    /** Beendet die Schleife und schaltet alle gesteuerten LEDs aus. */
    public void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public int[] preview() {
        return preview;
    }

    public double fps() {
        return measuredFps;
    }

    public long framesSent() {
        return framesSent;
    }

    /** Zeigt zum Einstellen nur diese Ebene. null zeigt wieder alle. Wird nicht gespeichert. */
    public void solo(String layerId) {
        solo = layerId;
    }

    public String solo() {
        return solo;
    }

    /** LEDs (Indizes in layout.leds) weiß zeigen, alles andere der Geräte dunkel. Leere Menge beendet das. */
    public void identify(Set<Integer> leds, long millis) {
        identifyUntil = System.currentTimeMillis() + millis;
        identify = leds == null || leds.isEmpty() ? null : Set.copyOf(leds);
    }

    /** Übernimmt Einstellungen und Layout. Muss nach jeder Änderung aufgerufen werden. */
    public synchronized void apply(Project project, CompiledLayout layout) {
        State s = new State();
        s.layout = layout;
        s.speed = project.speed;
        s.brightness = project.brightness;
        s.trail = project.trail;
        s.smoothSeconds = project.smoothMs / 1000;
        s.fps = Math.max(5, Math.min(120, project.fps));
        s.audio = copy(project.audio);
        s.gain = project.audio.gain;
        s.floor = project.audio.floor;
        s.covered = new boolean[layout.devices.size()];
        Project.Preset preset = Project.OFF.equals(project.current) ? null : project.preset(project.current);
        s.off = preset == null;
        boolean needsAudio = false;
        Map<String, Runtime> alive = new HashMap<>();
        if (preset != null) {
            List<Project.Layer> layers = preset.layers();
            // Unten anfangen: Die erste Ebene der Liste liegt obenauf.
            for (int i = layers.size() - 1; i >= 0; i--) {
                Project.Layer l = layers.get(i);
                Runtime old = runtimes.get(l.id);
                Runtime rt = old != null && old.effect.id().equals(l.effect) ? old : null;
                if (rt == null) {
                    Effect e = Effects.create(l.effect);
                    if (e == null) continue;
                    rt = new Runtime(e);
                }
                alive.put(l.id, rt);
                if (!l.enabled) continue;
                Run run = new Run();
                run.layer = l;
                run.id = l.id;
                run.runtime = rt;
                run.view = LayerView.build(layout, project.fixtures, l);
                run.params = new Params(rt.effect.params(), l.params);
                if (run.params.has("speed")) {
                    // Effekte rechnen t * Tempo. Bei 0 ginge die Position verloren, deshalb bleibt ein winziger Rest.
                    double speed = run.params.d("speed");
                    if (Math.abs(speed) < MIN_EFFECT_SPEED) run.params.set("speed", speed < 0 ? -MIN_EFFECT_SPEED : MIN_EFFECT_SPEED);
                }
                applyGlobalColor(project.color, run.params);
                run.stops = LayerFx.stops(l);
                run.out = new int[run.view.toGlobal.length];
                String signature = System.identityHashCode(layout) + "|" + l.targets + "|" + l.ledFilter + "|" + l.scope;
                if (!signature.equals(rt.signature)) {
                    rt.signature = signature;
                    rt.resetPending = true;
                }
                s.runs.add(run);
                needsAudio |= rt.effect.needsAudio() || !"off".equals(l.audioBand);
                for (int g : run.view.toGlobal) {
                    int device = layout.leds[g].device;
                    if (device >= 0) s.covered[device] = true;
                }
            }
        }
        runtimes.keySet().retainAll(alive.keySet());
        runtimes.putAll(alive);
        audio.setForced(needsAudio);
        state = s;
    }

    private static Project.Audio copy(Project.Audio a) {
        Project.Audio c = new Project.Audio();
        c.enabled = a.enabled;
        c.device = a.device;
        c.mode = a.mode;
        c.gain = a.gain;
        c.smoothing = a.smoothing;
        c.floor = a.floor;
        c.minimum = a.minimum;
        c.duration = a.duration;
        c.pause = a.pause;
        c.length = a.length;
        c.band = a.band;
        c.palette = a.palette;
        return c;
    }

    private static void applyGlobalColor(String color, Params p) {
        if (color == null || "preset".equals(color)) return;
        if ("Regenbogen".equals(color)) {
            if (p.has("rainbow")) p.set("rainbow", 1);
            return;
        }
        Double hue = COLOR_HUES.get(color);
        if (hue == null) return;
        if (p.has("hue")) p.set("hue", hue);
        if (p.has("rainbow")) p.set("rainbow", 0);
        if (p.has("gradient_mode")) p.set("gradient_mode", "off");
    }

    private void loop() {
        long startNanos = System.nanoTime(), last = startNanos, next = startNanos;
        int[] out = new int[0], trail = new int[0];
        Effect.Frame frame = new Effect.Frame();
        long fpsWindow = startNanos;
        int fpsCount = 0;
        while (running) {
            State s = state;
            if (s == null) {
                LockSupport.parkNanos(20_000_000L);
                continue;
            }
            long now = System.nanoTime();
            double dt = Math.min(0.25, (now - last) / 1e9);
            last = now;
            int n = s.layout.leds.length;
            if (out.length != n) {
                out = new int[n];
                trail = new int[n];
            }
            Arrays.fill(out, 0);
            double[] values = audio.values;
            double stamp = audio.stamp, seconds = now / 1e9;
            updateLevels(values, stamp, seconds, dt, s);
            String soloId = solo;
            for (Run run : s.runs) {
                if (soloId != null && !soloId.equals(run.id)) continue;
                renderLayer(run, s, frame, dt, (now - startNanos) / 1e9, seconds, values, stamp, out);
            }
            if (s.audio.enabled && !s.off) music.render(s.layout, seconds, values, stamp, s.audio, out);
            post(s, out, trail, dt);
            smooth.apply(out, dt, s.smoothSeconds);
            Set<Integer> id = identify;
            boolean identifying = false;
            if (id != null && System.currentTimeMillis() < identifyUntil) {
                identifying = true;
                for (int i = 0; i < n; i++) out[i] = id.contains(i) ? 0xFFFFFF : 0;
            } else if (id != null) {
                identify = null;
            }
            writeAndPreview(s, out, now, identifying);

            fpsCount++;
            if (now - fpsWindow > 1_000_000_000L) {
                measuredFps = fpsCount * 1e9 / (now - fpsWindow);
                fpsWindow = now;
                fpsCount = 0;
            }
            long frameNanos = 1_000_000_000L / s.fps;
            next += frameNanos;
            long delay = next - System.nanoTime();
            if (delay > 0) LockSupport.parkNanos(delay);
            else next = System.nanoTime(); // zu langsam: nicht aufholen, neu takten
        }
        blackout();
    }

    private void updateLevels(double[] values, double stamp, double seconds, double dt, State s) {
        boolean fresh = seconds - stamp < 0.5;
        for (int i = 0; i < 4; i++) {
            double raw = fresh ? values[i] : 0;
            double target = Colors.clamp01(Math.max(0, raw - s.floor) * s.gain * 3);
            double tau = target > layerLevels[i] ? 0.015 : 0.25;
            layerLevels[i] += (target - layerLevels[i]) * (1 - Math.exp(-Math.max(0.001, dt) / tau));
        }
    }

    private void renderLayer(Run run, State s, Effect.Frame frame, double dt, double rawT, double seconds, double[] values, double stamp, int[] out) {
        Runtime rt = run.runtime;
        if (rt.broken) return;
        Project.Layer l = run.layer;
        CompiledLayout view = run.view.layout;
        int[] buf = run.out;
        Arrays.fill(buf, 0);
        double layerDt = dt * s.speed * l.speed;
        // Effekte rechnen t * Tempo. Ändert sich der Regler, wird t so umgerechnet, dass der Effekt an seiner
        // Stelle bleibt und nur schneller oder langsamer weiterläuft, statt an eine andere Stelle zu springen.
        boolean hasSpeed = run.params.has("speed");
        double effectSpeed = hasSpeed ? run.params.d("speed") : 1;
        if (hasSpeed && rt.speedUsed != effectSpeed) {
            if (!Double.isNaN(rt.speedUsed)) rt.t *= rt.speedUsed / effectSpeed;
            rt.speedUsed = effectSpeed;
        }
        rt.t += layerDt;
        try {
            if (rt.resetPending) {
                rt.effect.reset(view);
                rt.resetPending = false;
            }
            frame.layout = view;
            frame.t = rt.t + l.phase / effectSpeed;
            frame.rawT = rawT + l.phase;
            frame.dt = layerDt;
            frame.p = run.params;
            frame.out = buf;
            frame.audio = values;
            frame.audioStamp = stamp;
            frame.now = seconds;
            rt.effect.render(frame);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Fehler im Effekt " + rt.effect.id(), e);
            onEffectError.accept(rt.effect.title() + ": " + e.getMessage());
            rt.broken = true;
            return;
        }
        boolean recolor = !"off".equals(l.colorMode);
        boolean adjust = l.hueShift != 0 || l.saturation != 1 || l.gamma != 1;
        double audioFactor = 1;
        if (!"off".equals(l.audioBand)) {
            int band = Music.BANDS.indexOf(l.audioBand);
            double level = layerLevels[band < 0 ? 1 : band];
            audioFactor = 1 - l.audioAmount + l.audioAmount * level;
        }
        double gain = l.brightness * audioFactor;
        if (rt.trail.length != buf.length) rt.trail = new int[buf.length];
        double keep = l.trail <= 0 ? 0 : Math.exp(-dt / Math.max(0.01, l.trail * 1.2));
        int[] map = run.view.toGlobal;
        for (int i = 0; i < buf.length; i++) {
            int c = buf[i];
            if (recolor) c = LayerFx.colorize(c, l.colorMode, run.stops, LayerFx.position(view, i));
            if (adjust) c = LayerFx.adjust(c, l.hueShift, l.saturation, l.gamma);
            if (gain != 1) c = gain > 1 ? Colors.pack((int) (Colors.r(c) * gain), (int) (Colors.g(c) * gain), (int) (Colors.b(c) * gain)) : Colors.scale(c, gain);
            if (keep > 0) {
                c = Colors.max(c, Colors.scale(rt.trail[i], keep));
                rt.trail[i] = c;
            }
            int g = map[i];
            out[g] = LayerFx.blend(out[g], c, l.blend, l.opacity);
        }
    }

    private static void post(State s, int[] out, int[] trail, double dt) {
        CompiledLayout.Led[] leds = s.layout.leds;
        double keep = s.trail <= 0 ? 0 : Math.exp(-dt / Math.max(0.01, s.trail * 1.2));
        for (int i = 0; i < leds.length; i++) {
            int c = out[i];
            if (leds[i].brightness < 0.999) c = Colors.scale(c, leds[i].brightness);
            if (keep > 0) {
                c = Colors.max(c, Colors.scale(trail[i], keep));
                trail[i] = c;
            }
            if (s.brightness < 0.999) c = Colors.scale(c, s.brightness);
            if (leds[i].skipped) c = 0;
            out[i] = c;
        }
    }

    private void writeAndPreview(State s, int[] out, long now, boolean identifying) {
        CompiledLayout layout = s.layout;
        int devices = layout.devices.size();
        int[][] buffers = new int[devices][];
        for (int d = 0; d < devices; d++) buffers[d] = new int[layout.devices.get(d).ledCount];
        CompiledLayout.Led[] leds = layout.leds;
        for (int i = 0; i < leds.length; i++) {
            CompiledLayout.Led l = leds[i];
            if (l.device < 0 || l.address >= buffers[l.device].length) continue;
            int[] b = buffers[l.device];
            // Eine Adresse kann nur eine Farbe haben: Der hellere Wert gewinnt, damit Effekte auf jedem Bauteil sichtbar bleiben.
            b[l.address] = Colors.max(b[l.address], out[i]);
        }
        // Die Vorschau zeigt, was physisch leuchtet: Doppelte Adressen sehen überall gleich aus.
        int[] shown = new int[leds.length];
        for (int i = 0; i < leds.length; i++) {
            CompiledLayout.Led l = leds[i];
            shown[i] = l.device >= 0 && l.address < buffers[l.device].length ? buffers[l.device][l.address] : out[i];
        }
        preview = shown;

        LedBackend b = backend;
        if (b == null) return;
        try {
            if (connectionReset) {
                connectionReset = false;
                directSet.clear();
                lastSent.clear();
                lastSendTime.clear();
                touched.clear();
            }
            Set<Integer> active = new HashSet<>();
            for (int d = 0; d < devices; d++) {
                // Ein Gerät ohne aktive Ebene bleibt in Ruhe und behält z. B. seinen eigenen Hardware-Effekt.
                if (!s.off && !identifying && !s.audio.enabled && !s.covered[d]) continue;
                CompiledLayout.DeviceSlot slot = layout.devices.get(d);
                active.add(slot.controllerIndex);
                int[] data = buffers[d];
                for (int a = 0; a < data.length; a++) {
                    String order = slot.colorOrder[a];
                    if (order != null && !order.equals("RGB")) data[a] = Colors.reorder(data[a], order);
                }
                send(b, slot.controllerIndex, data, now);
            }
            // Geräte, die eben noch gesteuert wurden, einmal dunkel schalten.
            for (Integer idx : touched.keySet().toArray(new Integer[0])) {
                if (!active.contains(idx)) {
                    b.setLeds(idx, new int[touched.get(idx)]);
                    touched.remove(idx);
                    lastSent.remove(idx);
                }
            }
        } catch (IOException e) {
            backend = null;
            onBackendFailure.accept(e);
        }
    }

    private void send(LedBackend b, int device, int[] data, long now) throws IOException {
        int[] last = lastSent.get(device);
        Long when = lastSendTime.get(device);
        if (last != null && when != null && Arrays.equals(last, data) && now - when < KEEPALIVE_NANOS) return;
        if (directSet.add(device)) b.enterDirectMode(device);
        b.setLeds(device, data);
        lastSent.put(device, data);
        lastSendTime.put(device, now);
        touched.put(device, data.length);
        framesSent++;
    }

    private void blackout() {
        LedBackend b = backend;
        if (b == null) return;
        try {
            for (Map.Entry<Integer, Integer> e : touched.entrySet()) b.setLeds(e.getKey(), new int[e.getValue()]);
            Thread.sleep(60); // dem Server Zeit geben, bevor die Verbindung zugeht
        } catch (IOException | InterruptedException e) {
            LOG.log(Level.FINE, "LEDs konnten nicht ausgeschaltet werden", e);
        }
    }
}
