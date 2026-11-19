package de.knirpslarsi.ledstudio.games;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Richtet die Game State Integration in Counter-Strike 2 ein: eine kleine Textdatei im cfg-Ordner des Spiels, die
 * CS2 sagt, wohin es seinen Zustand schicken soll. Das ist die offizielle Schnittstelle von Valve.
 */
public final class Cs2Setup {
    public static final String FILE = "gamestate_integration_ledstudio.cfg";
    private static final String CS2_CFG = "steamapps/common/Counter-Strike Global Offensive/game/csgo/cfg";

    private Cs2Setup() {
    }

    /** Inhalt der Konfigurationsdatei. */
    public static String config(int port, String token) {
        return """
                "LED Studio"
                {
                    "uri"       "http://127.0.0.1:%d/"
                    "timeout"   "1.1"
                    "buffer"    "0.0"
                    "throttle"  "0.05"
                    "heartbeat" "10.0"
                    "auth"
                    {
                        "token" "%s"
                    }
                    "data"
                    {
                        "provider"           "1"
                        "map"                "1"
                        "round"              "1"
                        "player_id"          "1"
                        "player_state"       "1"
                        "player_match_stats" "1"
                    }
                }
                """.formatted(port, token);
    }

    /** cfg-Ordner aller gefundenen CS2-Installationen. */
    public static List<Path> findCfgDirs() {
        List<Path> out = new ArrayList<>();
        for (Path lib : libraries()) {
            Path cfg = lib.resolve(CS2_CFG);
            if (Files.isDirectory(cfg)) out.add(cfg);
        }
        return out;
    }

    /** Schreibt die Datei in jede gefundene Installation. Gibt die geschriebenen Dateien zurück. */
    public static List<Path> install(int port, String token) throws IOException {
        List<Path> written = new ArrayList<>();
        for (Path dir : findCfgDirs()) {
            Path f = dir.resolve(FILE);
            Files.writeString(f, config(port, token), StandardCharsets.UTF_8);
            written.add(f);
        }
        return written;
    }

    public static boolean installed() {
        for (Path dir : findCfgDirs()) if (Files.exists(dir.resolve(FILE))) return true;
        return false;
    }

    /** Steam-Bibliotheken: der Steam-Ordner selbst und alle Einträge aus libraryfolders.vdf. */
    static Set<Path> libraries() {
        Set<Path> libs = new LinkedHashSet<>();
        Path steam = steamPath();
        if (steam == null) return libs;
        libs.add(steam);
        Path vdf = steam.resolve("steamapps/libraryfolders.vdf");
        try {
            if (Files.exists(vdf)) libs.addAll(parseLibraries(Files.readString(vdf)));
        } catch (IOException ignored) {
        }
        return libs;
    }

    static List<Path> parseLibraries(String vdf) {
        List<Path> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"path\"\\s+\"([^\"]+)\"").matcher(vdf);
        while (m.find()) out.add(Path.of(m.group(1).replace("\\\\", "\\")));
        return out;
    }

    private static Path steamPath() {
        for (String key : List.of("HKCU\\Software\\Valve\\Steam", "HKLM\\SOFTWARE\\WOW6432Node\\Valve\\Steam")) {
            String value = regQuery(key, key.startsWith("HKCU") ? "SteamPath" : "InstallPath");
            if (value != null && Files.isDirectory(Path.of(value))) return Path.of(value);
        }
        Path fallback = Path.of("C:/Program Files (x86)/Steam");
        return Files.isDirectory(fallback) ? fallback : null;
    }

    private static String regQuery(String key, String value) {
        try {
            Process p = new ProcessBuilder("reg", "query", key, "/v", value).redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            Matcher m = Pattern.compile(value + "\\s+REG_\\w+\\s+(.+)").matcher(text);
            return m.find() ? m.group(1).trim() : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
