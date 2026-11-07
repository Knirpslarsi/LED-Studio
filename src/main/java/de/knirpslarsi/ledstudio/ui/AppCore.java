package de.knirpslarsi.ledstudio.ui;

import de.knirpslarsi.ledstudio.audio.AudioEngine;
import de.knirpslarsi.ledstudio.effects.ScriptEffects;
import de.knirpslarsi.ledstudio.engine.Connection;
import de.knirpslarsi.ledstudio.engine.Engine;
import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.LayoutTools;
import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.openrgb.Controller;
import de.knirpslarsi.ledstudio.openrgb.DemoBackend;
import de.knirpslarsi.ledstudio.openrgb.LedBackend;
import de.knirpslarsi.ledstudio.util.AppPaths;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Verbindet Oberfläche, Projekt, Bildschleife und OpenRGB. Alle Listener laufen im FX-Thread. */
public final class AppCore {
    private static final Logger LOG = Logger.getLogger("App");

    public final Project project;
    public final AudioEngine audio;
    public final Engine engine;
    public final Connection connection;
    public final boolean demo;
    /** Nur lesen: Geräte anzeigen, aber nie an die Hardware schreiben. */
    private final boolean readOnly;
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "background");
        t.setDaemon(true);
        return t;
    });

    private volatile CompiledLayout compiled;
    private final List<Runnable> layoutListeners = new ArrayList<>();
    private final List<Runnable> deviceListeners = new ArrayList<>();
    private final List<Runnable> settingsListeners = new ArrayList<>();
    private final List<Consumer<String>> statusListeners = new ArrayList<>();
    private final Deque<String> undo = new ArrayDeque<>();
    private final Deque<String> redo = new ArrayDeque<>();
    private final PauseTransition saveTimer = new PauseTransition(Duration.millis(400));

    public AppCore(boolean demo, boolean autostart, boolean readOnly) {
        this.demo = demo;
        this.readOnly = readOnly;
        project = Project.load();
        ScriptEffects.load(AppPaths.effectsDir());
        audio = new AudioEngine(() -> project.audio);
        engine = new Engine(audio, e -> {
            LOG.log(Level.INFO, "Schreibfehler", e);
            reconnectSoon();
        });
        connection = new Connection(project, autostart, demo ? DemoBackend::new : null,
                this::backendChanged, this::devicesChanged, this::statusChanged);
        compiled = CompiledLayout.compile(project.fixtures, List.of());
        engine.apply(project, compiled);
        saveTimer.setOnFinished(e -> project.save());
    }

    public void start() {
        engine.start();
        audio.start();
        connection.start();
    }

    /** Speichert und schaltet die LEDs aus. */
    public void shutdown() {
        project.save();
        audio.stop();
        engine.stop();
        connection.stop();
    }

    private void reconnectSoon() {
        connection.reconnect();
    }

    private void backendChanged(LedBackend b) {
        if (!readOnly) engine.setBackend(b);
    }

    private void devicesChanged(List<Controller> list) {
        Platform.runLater(() -> {
            recompile();
            deviceListeners.forEach(Runnable::run);
        });
    }

    private void statusChanged(String s) {
        Platform.runLater(() -> statusListeners.forEach(l -> l.accept(s)));
    }

    public CompiledLayout compiled() {
        return compiled;
    }

    public List<Controller> controllers() {
        return connection.controllers();
    }

    public List<LayoutTools.Issue> issues() {
        return LayoutTools.analyze(project.fixtures, controllers());
    }

    private void recompile() {
        compiled = CompiledLayout.compile(project.fixtures, controllers());
        engine.apply(project, compiled);
    }

    /** Nach jeder Änderung an den Bauteilen aufrufen. */
    public void layoutChanged() {
        recompile();
        saveTimer.playFromStart();
        layoutListeners.forEach(Runnable::run);
    }

    /** Nach Änderungen an Vorlagen und Einstellungen aufrufen. */
    public void settingsChanged() {
        engine.apply(project, compiled);
        saveTimer.playFromStart();
        settingsListeners.forEach(Runnable::run);
    }

    public void onLayout(Runnable r) {
        layoutListeners.add(r);
    }

    public void onDevices(Runnable r) {
        deviceListeners.add(r);
    }

    public void onSettings(Runnable r) {
        settingsListeners.add(r);
    }

    public void onStatus(Consumer<String> c) {
        statusListeners.add(c);
    }

    public void selectPreset(String name) {
        project.current = name;
        settingsChanged();
    }

    // ---- Rückgängig

    /** Vor einer Änderung am Layout aufrufen. */
    public void snapshot() {
        undo.push(project.fixturesJson());
        if (undo.size() > 100) undo.removeLast();
        redo.clear();
    }

    public void undo() {
        if (undo.isEmpty()) return;
        redo.push(project.fixturesJson());
        project.restoreFixtures(undo.pop());
        layoutChanged();
    }

    public void redo() {
        if (redo.isEmpty()) return;
        undo.push(project.fixturesJson());
        project.restoreFixtures(redo.pop());
        layoutChanged();
    }

    // ---- Hintergrundarbeit

    public interface Task {
        void run() throws Exception;
    }

    /** Führt OpenRGB-Aktionen außerhalb des FX-Threads aus; done/Fehler kommen im FX-Thread zurück. */
    public void background(Task task, Runnable done, Consumer<String> error) {
        background.submit(() -> {
            try {
                task.run();
                if (done != null) Platform.runLater(done);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Hintergrundaktion fehlgeschlagen", e);
                if (error != null) Platform.runLater(() -> error.accept(e.getMessage()));
            }
        });
    }

    public String diagnose() {
        StringBuilder b = new StringBuilder();
        b.append("Status: ").append(connection.status()).append('\n');
        b.append("OpenRGB-Pfad: ").append(project.openrgbPath.isBlank() ? "(nicht gesetzt)" : project.openrgbPath).append('\n');
        b.append("SDK-Server ").append(project.host).append(':').append(project.port).append(" erreichbar: ")
                .append(demo || connection.portOpen() ? "ja" : "nein").append('\n');
        for (Controller c : controllers()) {
            b.append("Gerät: ").append(c.displayName()).append(" – ").append(c.ledCount()).append(" LEDs; Zonen:");
            for (Controller.Zone z : c.zones()) b.append(' ').append(z.name()).append('=').append(z.ledCount());
            b.append('\n');
        }
        b.append("Bauteile im Layout: ").append(project.fixtures.size()).append(", LED-Positionen: ").append(compiled.leds.length).append('\n');
        b.append("Bildrate: ").append(String.format("%.1f", engine.fps())).append(" fps, gesendete Pakete: ").append(engine.framesSent()).append('\n');
        b.append("Audio: ").append(audio.status).append('\n');
        b.append("Letzter Fehler: ").append(connection.lastError().isBlank() ? "-" : connection.lastError()).append('\n');
        b.append("Daten: ").append(AppPaths.dataDir());
        return b.toString();
    }
}
