package de.knirpslarsi.ledstudio.games;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.knirpslarsi.ledstudio.engine.Engine;
import de.knirpslarsi.ledstudio.model.Games;
import de.knirpslarsi.ledstudio.model.Project;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Verbindung zu Spielen: erkennt laufende Spiele (Startanimation, eigene Vorlage im Spiel) und empfängt den Zustand
 * von Counter-Strike 2, um Ereignisse auf die LEDs zu bringen.
 */
public final class GameLink {
    private static final Logger LOG = Logger.getLogger("Games");

    private final Project project;
    private final Engine engine;
    private final Supplier<String> currentPreset;
    private final Consumer<String> selectPreset;
    /** Führt etwas im FX-Thread aus. */
    private final Consumer<Runnable> fx;
    private final Runnable changed;

    private ScheduledExecutorService timer;
    private HttpServer server;
    private int serverPort = -1;
    private final Cs2Events cs2 = new Cs2Events();
    private final Map<String, Long> running = new ConcurrentHashMap<>();
    private boolean firstScan = true;
    private String presetBefore, presetGame;

    public volatile long cs2LastPacket;
    public volatile String cs2LastEvent = "";
    public volatile String serverError = "";

    public GameLink(Project project, Engine engine, Supplier<String> currentPreset, Consumer<String> selectPreset,
                    Consumer<Runnable> fx, Runnable changed) {
        this.project = project;
        this.engine = engine;
        this.currentPreset = currentPreset;
        this.selectPreset = selectPreset;
        this.fx = fx;
        this.changed = changed;
    }

    public void start() {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "games");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(this::scanSafely, 1, 2, TimeUnit.SECONDS);
        settingsChanged();
    }

    public void stop() {
        if (timer != null) timer.shutdownNow();
        stopServer();
    }

    /** Nach Änderungen an den Einstellungen: Server an/aus, Port wechseln. */
    public synchronized void settingsChanged() {
        Games.Cs2 c = project.games.cs2;
        if (!c.enabled) {
            stopServer();
            serverError = "";
            return;
        }
        if (server != null && serverPort == c.port) return;
        stopServer();
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), c.port), 0);
            s.createContext("/", this::handle);
            s.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cs2");
                t.setDaemon(true);
                return t;
            }));
            s.start();
            server = s;
            serverPort = c.port;
            serverError = "";
        } catch (IOException e) {
            serverError = "Port " + c.port + " ist belegt. Wähle einen anderen und richte CS2 neu ein.";
            LOG.log(Level.WARNING, "CS2-Empfang konnte nicht starten", e);
        }
    }

    private void stopServer() {
        if (server != null) server.stop(0);
        server = null;
        serverPort = -1;
    }

    // ---- Animationen

    /** Spielt eine Animation, wenn sie eingeschaltet ist. */
    public void play(String key, Games.Animation a) {
        if (a == null || !a.enabled || a.look == null) return;
        engine.play(key, a.look, a.seconds, a.fadeIn, a.fadeOut);
    }

    /** Zum Ausprobieren im Einstellungsfenster, auch wenn sie ausgeschaltet ist. Gehaltene laufen 3 Sekunden. */
    public void preview(Games.Animation a) {
        if (a == null || a.look == null) return;
        engine.play("preview", a.look, a.seconds <= 0 ? 3 : a.seconds, a.fadeIn, a.fadeOut);
    }

    public void playAppIntro() {
        play("app", project.games.appIntro);
    }

    // ---- Spielerkennung

    public boolean isRunning(Games.Game g) {
        return running.containsKey(g.id);
    }

    /** Laufende erkannte Spiele, für die Anzeige. */
    public List<String> runningNames() {
        return project.games.games.stream().filter(this::isRunning).map(g -> g.name).toList();
    }

    private void scanSafely() {
        try {
            scan(processNames());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Spielerkennung", e);
        }
    }

    /** Dateinamen aller laufenden Programme, klein geschrieben. */
    static Set<String> processNames() {
        Set<String> names = new HashSet<>();
        ProcessHandle.allProcesses().forEach(p -> p.info().command().ifPresent(c -> {
            Path f = Path.of(c).getFileName();
            if (f != null) names.add(f.toString().toLowerCase(Locale.ROOT));
        }));
        return names;
    }

    synchronized void scan(Set<String> processes) {
        Games gs = project.games;
        boolean any = false;
        Set<String> known = new HashSet<>();
        for (Games.Game g : gs.games) {
            known.add(g.id);
            boolean on = gs.detect && g.enabled && !g.exe.isBlank() && processes.contains(g.exe.trim().toLowerCase(Locale.ROOT));
            boolean was = running.containsKey(g.id);
            if (on && !was) {
                running.put(g.id, System.currentTimeMillis());
                started(g, !firstScan);
                any = true;
            } else if (!on && was) {
                running.remove(g.id);
                stopped(g);
                any = true;
            }
        }
        running.keySet().removeIf(id -> !known.contains(id));
        firstScan = false;
        if (any) fx.accept(changed);
    }

    private void started(Games.Game g, boolean intro) {
        LOG.info("Spiel erkannt: " + g.name);
        if (intro) play("game", g.intro);
        String preset = g.preset == null ? "" : g.preset.trim();
        if (!preset.isEmpty() && project.preset(preset) != null) {
            fx.accept(() -> {
                if (presetBefore == null) presetBefore = currentPreset.get();
                presetGame = g.id;
                selectPreset.accept(preset);
            });
        }
    }

    private void stopped(Games.Game g) {
        LOG.info("Spiel beendet: " + g.name);
        if ("cs2.exe".equalsIgnoreCase(g.exe.trim())) apply(cs2Update(new JsonObject()));
        fx.accept(() -> {
            if (g.id.equals(presetGame) && presetBefore != null) {
                selectPreset.accept(presetBefore);
                presetBefore = null;
                presetGame = null;
            }
        });
    }

    // ---- Counter-Strike 2

    private void handle(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Games.Cs2 c = project.games.cs2;
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject auth = root.has("auth") && root.get("auth").isJsonObject() ? root.getAsJsonObject("auth") : null;
            String token = auth != null && auth.has("token") ? auth.get("token").getAsString() : "";
            if (c.enabled && c.token.equals(token)) {
                cs2LastPacket = System.currentTimeMillis();
                apply(cs2Update(root));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "CS2-Paket nicht lesbar", e);
        }
        ex.sendResponseHeaders(200, -1);
        ex.close();
    }

    private synchronized List<Cs2Events.Action> cs2Update(JsonObject root) {
        return cs2.update(root);
    }

    /** Für Tests und den Test-Knopf: ein Ereignis so auslösen, als käme es aus CS2. */
    public void apply(List<Cs2Events.Action> actions) {
        Map<String, Games.Animation> events = project.games.cs2.events;
        for (Cs2Events.Action a : actions) {
            String key = "cs2:" + a.id();
            switch (a.kind()) {
                case PLAY -> {
                    Games.Animation anim = events.get(a.id());
                    if (anim == null || !anim.enabled) continue;
                    play(key, anim);
                    cs2LastEvent = Games.CS2_EVENTS.stream().filter(e -> e.id().equals(a.id())).map(Games.Cs2Event::title)
                            .findFirst().orElse(a.id());
                }
                case RELEASE -> engine.release(key);
                case LEVEL -> engine.level(key, a.value());
            }
        }
    }
}
