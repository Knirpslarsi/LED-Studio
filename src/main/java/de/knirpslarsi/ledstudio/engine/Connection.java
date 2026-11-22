package de.knirpslarsi.ledstudio.engine;

import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.openrgb.Controller;
import de.knirpslarsi.ledstudio.openrgb.LedBackend;
import de.knirpslarsi.ledstudio.openrgb.OpenRgbClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Hält die Verbindung zu OpenRGB: startet es bei Bedarf, verbindet neu und meldet Geräteänderungen. */
public final class Connection {
    private static final Logger LOG = Logger.getLogger("Connection");
    public static final String CLIENT_NAME = "LED Studio";

    private final Project project;
    private final Supplier<LedBackend> demo;
    private final Consumer<LedBackend> onBackend;
    private final Consumer<List<Controller>> onDevices;
    private final Consumer<String> onStatus;
    private final boolean autostart;
    private volatile boolean running = true;
    private volatile boolean reconnect;
    private volatile LedBackend backend;
    private volatile List<Controller> controllers = List.of();
    private volatile String status = "Starte…";
    private volatile String lastError = "";
    private long launchedAt;
    private Thread thread;
    /** Zusätzliche WLED-Geräte (leer ohne Plugin). */
    private volatile Supplier<List<de.knirpslarsi.ledstudio.model.WledDevice>> wled = List::of;

    public Connection(Project project, boolean autostart, Supplier<LedBackend> demo, Consumer<LedBackend> onBackend,
                      Consumer<List<Controller>> onDevices, Consumer<String> onStatus) {
        this.project = project;
        this.autostart = autostart;
        this.demo = demo;
        this.onBackend = onBackend;
        this.onDevices = onDevices;
        this.onStatus = onStatus;
    }

    public void setWled(Supplier<List<de.knirpslarsi.ledstudio.model.WledDevice>> devices) {
        wled = devices == null ? List::of : devices;
    }

    public void start() {
        thread = new Thread(this::run, "openrgb-connection");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        LedBackend b = backend;
        if (b != null) b.close();
    }

    public LedBackend backend() {
        return backend;
    }

    public List<Controller> controllers() {
        return controllers;
    }

    public String status() {
        return status;
    }

    public String lastError() {
        return lastError;
    }

    public boolean connected() {
        return backend != null;
    }

    /** Verbindung neu aufbauen (auch nach einem Schreibfehler der Bildschleife). */
    public void reconnect() {
        reconnect = true;
    }

    private void setStatus(String s) {
        if (!s.equals(status)) LOG.info("Status: " + s);
        status = s;
        onStatus.accept(s);
    }

    private void run() {
        if (autostart && demo == null) {
            setStatus("Warte auf Systemstart…");
            sleep(project.startDelay * 1000L);
        }
        while (running) {
            LedBackend b = null;
            try {
                b = connect();
                if (b == null) continue;
                reconnect = false;
                backend = b;
                publish(b.controllers());
                onBackend.accept(b);
                setStatus(statusText(b));
                watch(b);
            } catch (IOException e) {
                lastError = e.getMessage();
                LOG.log(Level.INFO, "Keine Verbindung: {0}", e.getMessage());
                setStatus("Verbindung verloren – neuer Versuch…");
            } finally {
                backend = null;
                onBackend.accept(null);
                if (b != null) b.close();
            }
            if (running) sleep(2000);
        }
    }

    private void publish(List<Controller> list) {
        controllers = list;
        onDevices.accept(list);
    }

    private String statusText(LedBackend b) {
        if (demo != null) return "Demo-Modus (keine echte Hardware)";
        if (b instanceof de.knirpslarsi.ledstudio.openrgb.MultiBackend m && !m.hasPrimary())
            return "Nur WLED: " + b.controllers().size() + " Geräte (OpenRGB nicht erreichbar)";
        return "Verbunden: " + b.controllers().size() + " Geräte";
    }

    private LedBackend connect() throws IOException {
        List<de.knirpslarsi.ledstudio.model.WledDevice> extra = wled.get();
        LedBackend primary;
        if (demo != null) {
            primary = demo.get();
        } else if (extra.isEmpty()) {
            setStatus("Warte auf OpenRGB…");
            if (!ensureOpenRgb()) return null;
            setStatus("Verbinde mit OpenRGB…");
            primary = new OpenRgbClient(project.host, project.port, CLIENT_NAME);
        } else {
            // Mit WLED nicht auf OpenRGB warten: WLED läuft sofort, OpenRGB kommt dazu, sobald es erreichbar ist.
            primary = null;
            if (portOpen()) {
                setStatus("Verbinde mit OpenRGB…");
                try {
                    primary = new OpenRgbClient(project.host, project.port, CLIENT_NAME);
                } catch (IOException e) {
                    lastError = e.getMessage();
                }
            } else {
                launchOpenRgb();
            }
        }
        if (extra.isEmpty()) return primary;
        try {
            return new de.knirpslarsi.ledstudio.openrgb.MultiBackend(primary, new de.knirpslarsi.ledstudio.wled.WledBackend(extra));
        } catch (IOException e) {
            if (primary != null) primary.close();
            throw e;
        }
    }

    /** Startet OpenRGB, ohne darauf zu warten. */
    private void launchOpenRgb() {
        String path = project.openrgbPath == null || project.openrgbPath.isBlank() ? findOpenRgb() : project.openrgbPath;
        if (!project.startOpenrgb || path.isBlank() || !Files.exists(Path.of(path)) || openRgbRunning()) return;
        if (System.currentTimeMillis() - launchedAt < 120_000) return;
        try {
            new ProcessBuilder(path, "--server", "--startminimized").directory(Path.of(path).getParent().toFile()).start();
            launchedAt = System.currentTimeMillis();
        } catch (IOException e) {
            lastError = "OpenRGB-Start: " + e.getMessage();
        }
    }

    private void watch(LedBackend b) throws IOException {
        long checked = System.currentTimeMillis();
        while (running && !reconnect) {
            sleep(400);
            // Nur WLED lief, jetzt ist OpenRGB da: neu verbinden, damit beides läuft.
            if (b instanceof de.knirpslarsi.ledstudio.openrgb.MultiBackend m && !m.hasPrimary() && demo == null
                    && System.currentTimeMillis() - checked > 5000) {
                checked = System.currentTimeMillis();
                if (portOpen()) {
                    sleep(3000);
                    return;
                }
            }
            if (!b.alive()) throw new IOException("OpenRGB-Verbindung abgerissen");
            if (b.consumeDeviceListChanged()) {
                sleep(500); // OpenRGB meldet während der Erkennung oft mehrfach
                b.consumeDeviceListChanged();
                publish(b.refresh());
                setStatus(statusText(b));
            }
        }
    }

    /** Liest die Geräte neu ein, z. B. nach einer Größenänderung. */
    public void refreshNow() throws IOException {
        LedBackend b = backend;
        if (b != null) publish(b.refresh());
    }

    private boolean ensureOpenRgb() {
        if (portOpen()) return true;
        String path = project.openrgbPath;
        if (path == null || path.isBlank()) {
            path = findOpenRgb();
            if (!path.isEmpty()) project.openrgbPath = path;
        }
        if (openRgbRunning()) {
            setStatus("OpenRGB läuft, aber der SDK-Server ist aus: in OpenRGB unter „SDK Server“ auf „Start Server“");
        } else if (project.startOpenrgb && !path.isBlank() && Files.exists(Path.of(path))) {
            if (System.currentTimeMillis() - launchedAt > 120_000) {
                setStatus("Starte OpenRGB…");
                try {
                    new ProcessBuilder(path, "--server", "--startminimized").directory(Path.of(path).getParent().toFile()).start();
                    launchedAt = System.currentTimeMillis();
                } catch (IOException e) {
                    lastError = "OpenRGB-Start: " + e.getMessage();
                    LOG.log(Level.WARNING, "OpenRGB konnte nicht gestartet werden", e);
                }
            }
        } else {
            setStatus("OpenRGB nicht gefunden: Einstellungen → OpenRGB-Pfad wählen");
        }
        for (int i = 0; i < 20 && running && !reconnect; i++) {
            if (portOpen()) {
                sleep(3000); // OpenRGB braucht kurz, um die Geräte zu erkennen
                return true;
            }
            sleep(1000);
        }
        return false;
    }

    public boolean portOpen() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(project.host, project.port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean openRgbRunning() {
        try {
            Process p = new ProcessBuilder("tasklist", "/FI", "IMAGENAME eq OpenRGB.exe", "/NH").redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes());
            return text.toLowerCase().contains("openrgb.exe");
        } catch (IOException e) {
            return false;
        }
    }

    public static String findOpenRgb() {
        List<Path> candidates = new ArrayList<>();
        candidates.add(Path.of("C:/OpenRGB/OpenRGB.exe"));
        for (String env : new String[]{"ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"}) {
            String base = System.getenv(env);
            if (base != null) {
                candidates.add(Path.of(base, "OpenRGB", "OpenRGB.exe"));
                candidates.add(Path.of(base, "Programs", "OpenRGB", "OpenRGB.exe"));
            }
        }
        for (Path c : candidates) if (Files.exists(c)) return c.toString();
        return "";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
