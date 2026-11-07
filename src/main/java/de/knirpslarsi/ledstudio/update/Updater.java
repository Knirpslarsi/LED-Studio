package de.knirpslarsi.ledstudio.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.function.DoubleConsumer;

/** Prüft die GitHub-Releases, lädt das Setup, kontrolliert die SHA-256-Summe und startet die Installation. */
public final class Updater {
    public static final String REPO = "Knirpslarsi/LED-Studio";
    private static final String USER_AGENT = "LEDStudio-Updater";

    public record Release(String version, String notes, String setupUrl, String sha256, String pageUrl) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String apiBase;

    public Updater() {
        this("https://api.github.com");
    }

    public Updater(String apiBase) {
        this.apiBase = apiBase;
    }

    public static String currentVersion() {
        try (InputStream in = Updater.class.getResourceAsStream("/ledstudio/version.properties")) {
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("version", "0.0.0");
        } catch (IOException | NullPointerException e) {
            return "0.0.0";
        }
    }

    /** Nur die installierte EXE kann sich selbst ersetzen, nicht der Start aus IntelliJ. */
    public static boolean canInstall() {
        return System.getProperty("jpackage.app-path") != null;
    }

    /** Vergleicht x.y.z numerisch. */
    public static int compare(String a, String b) {
        int[] x = parts(a), y = parts(b);
        for (int i = 0; i < 3; i++) if (x[i] != y[i]) return Integer.compare(x[i], y[i]);
        return 0;
    }

    private static int[] parts(String v) {
        String[] s = v.strip().replaceFirst("^[vV]", "").split("[.-]");
        int[] out = new int[3];
        for (int i = 0; i < 3 && i < s.length; i++) {
            try {
                out[i] = Integer.parseInt(s[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** Gibt das neueste Release zurück, wenn es neuer als die laufende Version ist, sonst null. */
    public Release check(String current) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + "/repos/" + REPO + "/releases/latest"))
                .header("User-Agent", USER_AGENT).header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404) return null; // noch kein Release
        if (res.statusCode() != 200) throw new IOException("GitHub antwortet mit " + res.statusCode());
        Release r = parse(res.body());
        return r != null && compare(r.version, current) > 0 ? r : null;
    }

    static Release parse(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        String tag = o.get("tag_name").getAsString();
        String notes = o.has("body") && !o.get("body").isJsonNull() ? o.get("body").getAsString() : "";
        String page = o.has("html_url") ? o.get("html_url").getAsString() : "";
        String url = null, sha = null;
        JsonArray assets = o.getAsJsonArray("assets");
        if (assets == null) return null;
        for (JsonElement e : assets) {
            JsonObject a = e.getAsJsonObject();
            String name = a.get("name").getAsString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".exe") && name.contains("setup")) {
                url = a.get("browser_download_url").getAsString();
                if (a.has("digest") && !a.get("digest").isJsonNull()) {
                    String digest = a.get("digest").getAsString();
                    if (digest.startsWith("sha256:")) sha = digest.substring(7);
                }
            }
        }
        if (url == null) return null;
        return new Release(tag.replaceFirst("^[vV]", ""), notes, url, sha, page);
    }

    /** Lädt das Setup in den Temp-Ordner. Ohne passende Prüfsumme wird nichts ausgeführt. */
    public Path download(Release r, DoubleConsumer progress) throws IOException, InterruptedException {
        URI uri = URI.create(r.setupUrl);
        if (!"https".equals(uri.getScheme()) && !uri.getHost().equals("127.0.0.1"))
            throw new IOException("Unsichere Download-Adresse");
        String expected = r.sha256 != null ? r.sha256 : fetchSidecarSha(r);
        HttpRequest req = HttpRequest.newBuilder(uri).header("User-Agent", USER_AGENT).GET().build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) throw new IOException("Download fehlgeschlagen: " + res.statusCode());
        long total = res.headers().firstValueAsLong("Content-Length").orElse(-1);
        Path dir = Files.createTempDirectory("ledstudio-update");
        Path file = dir.resolve("LEDStudio-Setup-" + r.version + ".exe");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try (InputStream in = res.body(); var out = Files.newOutputStream(file)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                digest.update(buf, 0, n);
                done += n;
                if (total > 0) progress.accept((double) done / total);
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(expected)) {
            Files.deleteIfExists(file);
            throw new IOException("Prüfsumme des Updates stimmt nicht, Installation abgebrochen");
        }
        return file;
    }

    private String fetchSidecarSha(Release r) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(r.setupUrl + ".sha256")).header("User-Agent", USER_AGENT).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException("Prüfsumme des Updates nicht gefunden");
        return res.body().strip().split("\\s+")[0];
    }

    /** Startet das Setup still. Das Setup schließt die App und startet sie danach neu. */
    public static void install(Path setup) throws IOException {
        new ProcessBuilder(setup.toString(), "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS").start();
    }
}
