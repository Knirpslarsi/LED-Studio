package de.knirpslarsi.ledstudio.engine;

import de.knirpslarsi.ledstudio.audio.AudioEngine;
import de.knirpslarsi.ledstudio.audio.Music;
import de.knirpslarsi.ledstudio.effects.Effect;
import de.knirpslarsi.ledstudio.effects.Effects;
import de.knirpslarsi.ledstudio.effects.Params;
import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.openrgb.LedBackend;
import de.knirpslarsi.ledstudio.util.Colors;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bildschleife: berechnet den Effekt, legt mehrfach belegte Adressen zusammen und schreibt an OpenRGB.
 * Sie läuft auch ohne Verbindung weiter, damit die Vorschau im Editor immer lebt.
 */
public final class Engine {
    private static final Logger LOG = Logger.getLogger("Engine");
    private static final long KEEPALIVE_NANOS = 1_000_000_000L;

    /** Alles, was die Oberfläche auf einmal austauscht. */
    private static final class State {
        CompiledLayout layout;
        Effect effect;
        Params params;
        double speed, brightness, trail;
        int fps;
        Project.Audio audio;
        boolean resetEffect;
        String label;
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

    private volatile Set<Integer> identify;
    private volatile long identifyUntil;
    private volatile int[] preview = new int[0];
    private volatile double measuredFps;
    private volatile long framesSent;

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

    /** LEDs (Indizes in layout.leds) weiß zeigen, alles andere der Geräte dunkel. Leere Menge beendet das. */
    public void identify(Set<Integer> leds, long millis) {
        identifyUntil = System.currentTimeMillis() + millis;
        identify = leds == null || leds.isEmpty() ? null : Set.copyOf(leds);
    }

    /** Übernimmt Einstellungen und Layout. Muss nach jeder Änderung aufgerufen werden. */
    public synchronized void apply(Project project, CompiledLayout layout) {
        State old = state;
        State s = new State();
        s.layout = layout;
        s.speed = project.speed;
        s.brightness = project.brightness;
        s.trail = project.trail;
        s.fps = Math.max(5, Math.min(120, project.fps));
        s.audio = copy(project.audio);
        Project.Preset preset = Project.OFF.equals(project.current) ? null : project.preset(project.current);
        if (preset != null) {
            boolean same = old != null && old.effect != null && old.effect.id().equals(preset.effect);
            s.effect = same ? old.effect : Effects.create(preset.effect);
            if (s.effect != null) {
                s.params = new Params(s.effect.params(), preset.params);
                applyGlobalColor(project.color, s.params);
                s.resetEffect = !same || old.layout != layout || old.resetEffect;
            }
            s.label = preset.name;
        } else {
            s.label = "Aus";
        }
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
        double scaled = 0;
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
            scaled += dt * s.speed;
            int n = s.layout.leds.length;
            if (out.length != n) {
                out = new int[n];
                trail = new int[n];
            }
            Arrays.fill(out, 0);
            Effect effect = s.effect;
            if (effect != null) {
                try {
                    if (s.resetEffect) {
                        effect.reset(s.layout);
                        s.resetEffect = false;
                    }
                    frame.layout = s.layout;
                    frame.t = scaled;
                    frame.rawT = (now - startNanos) / 1e9;
                    frame.dt = dt * s.speed;
                    frame.p = s.params;
                    frame.out = out;
                    effect.render(frame);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Fehler im Effekt " + effect.id(), e);
                    onEffectError.accept(effect.title() + ": " + e.getMessage());
                    s.effect = null;
                    Arrays.fill(out, 0);
                }
                if (s.audio.enabled) {
                    music.render(s.layout, now / 1e9, audio.values, audio.stamp, s.audio, out);
                }
            }
            post(s, out, trail, dt);
            Set<Integer> id = identify;
            if (id != null && System.currentTimeMillis() < identifyUntil) {
                for (int i = 0; i < n; i++) out[i] = id.contains(i) ? 0xFFFFFF : 0;
            } else if (id != null) {
                identify = null;
            }
            writeAndPreview(s, out, now);

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

    private void writeAndPreview(State s, int[] out, long now) {
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
                CompiledLayout.DeviceSlot slot = layout.devices.get(d);
                active.add(slot.controllerIndex);
                int[] data = buffers[d];
                for (int a = 0; a < data.length; a++) {
                    String order = slot.colorOrder[a];
                    if (order != null && !order.equals("RGB")) data[a] = Colors.reorder(data[a], order);
                }
                send(b, slot.controllerIndex, data, now);
            }
            // Geräte, die aus dem Layout geflogen sind, einmal dunkel schalten.
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
