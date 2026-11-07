package de.knirpslarsi.ledstudio.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/** Daten liegen unter %APPDATA%, damit Updates sie nie überschreiben. */
public final class AppPaths {
    private static volatile Path dataDir;

    private AppPaths() {
    }

    public static synchronized Path dataDir() {
        if (dataDir == null) {
            String override = System.getProperty("ledstudio.data");
            String appdata = System.getenv("APPDATA");
            dataDir = override != null ? Path.of(override)
                    : Path.of(appdata != null ? appdata : System.getProperty("user.home"), "LEDStudio");
            try {
                Files.createDirectories(dataDir);
                Files.createDirectories(dataDir.resolve("effects"));
            } catch (IOException e) {
                throw new IllegalStateException("Datenordner nicht anlegbar: " + dataDir, e);
            }
        }
        return dataDir;
    }

    public static Path projectFile() {
        return dataDir().resolve("project.json");
    }

    public static Path effectsDir() {
        return dataDir().resolve("effects");
    }

    public static Path logFile() {
        return dataDir().resolve("ledstudio.log");
    }

    public static void initLogging() {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$s %2$s: %5$s%6$s%n");
        Logger root = Logger.getLogger("");
        try {
            FileHandler file = new FileHandler(logFile().toString().replace("%", "%%"), 500_000, 1, true);
            file.setEncoding("UTF-8");
            file.setFormatter(new SimpleFormatter());
            root.addHandler(file);
            root.setLevel(Level.INFO);
        } catch (IOException e) {
            root.log(Level.WARNING, "Log-Datei nicht verfügbar", e);
        }
    }
}
