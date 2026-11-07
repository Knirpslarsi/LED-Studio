package de.knirpslarsi.ledstudio;

import com.sun.jna.Library;
import com.sun.jna.Native;
import de.knirpslarsi.ledstudio.ui.LedStudioApp;
import de.knirpslarsi.ledstudio.util.AppPaths;
import javafx.application.Application;
import javafx.application.Platform;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Startklasse. Bewusst keine Application-Unterklasse, damit JavaFX vom Klassenpfad läuft. */
public final class Main {
    private static final int LOCK_PORT = 47652;
    private static ServerSocket lock;

    private interface WinMm extends Library {
        int timeBeginPeriod(int ms);
    }

    private Main() {
    }

    public static void main(String[] args) {
        List<String> flags = Arrays.asList(args);
        // --read-only zeigt echte Geräte, schreibt aber nichts an die Hardware (Prüfung neben der alten Version).
        boolean demo = flags.contains("--demo") || flags.contains("--read-only") || flags.stream().anyMatch(f -> f.startsWith("--screenshots="));
        if (demo && System.getProperty("ledstudio.data") == null) {
            String appdata = System.getenv("APPDATA");
            System.setProperty("ledstudio.data", Path.of(appdata != null ? appdata : System.getProperty("user.home"), "LEDStudio-Demo").toString());
        }
        AppPaths.initLogging();
        if (!demo && !acquireLock()) return; // läuft schon; das vorhandene Fenster wurde aufgerufen
        try {
            Native.load("winmm", WinMm.class).timeBeginPeriod(1); // gleichmäßigere Bildabstände
        } catch (Throwable ignored) {
        }
        Application.launch(LedStudioApp.class, args);
    }

    private static boolean acquireLock() {
        try {
            lock = new ServerSocket(LOCK_PORT, 4, InetAddress.getLoopbackAddress());
            return true;
        } catch (IOException e) {
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), LOCK_PORT)) {
                s.getOutputStream().write("SHOW".getBytes());
            } catch (IOException ignored) {
            }
            return false;
        }
    }

    /** Ein zweiter Start ruft das laufende Fenster nach vorne. */
    public static void listenForSecondInstance(Runnable show) {
        if (lock == null) return;
        Thread t = new Thread(() -> {
            while (!lock.isClosed()) {
                try (Socket peer = lock.accept()) {
                    Platform.runLater(show);
                } catch (IOException e) {
                    return;
                }
            }
        }, "single-instance");
        t.setDaemon(true);
        t.start();
    }
}
