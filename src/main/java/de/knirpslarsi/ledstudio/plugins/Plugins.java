package de.knirpslarsi.ledstudio.plugins;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.update.Updater;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Plugins: Pakete aus Effekten (JavaScript, läuft abgeschottet ohne Datei- oder Netzzugriff) und Vorlagen. Sie kommen aus
 * einem GitHub-Repo (neuestes Release, sonst der Hauptzweig), aus einer ZIP-Datei oder werden lokal selbst gebaut.
 * Jedes liegt in einem eigenen Ordner unter plugins/ mit seiner plugin.json.
 */
public final class Plugins {
    public static final String CATALOG_REPO = "Knirpslarsi/LED-Studio-Plugins";
    public static final String MANIFEST = "plugin.json";
    static final String SOURCE = ".source.json";
    private static final String USER_AGENT = "LEDStudio-Plugins";
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{1,39}");
    private static final Set<String> ALLOWED = Set.of("json", "js", "md", "txt", "png", "jpg", "jpeg");
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final int MAX_FILES = 500;

    /** Was in plugin.json steht. */
    public static class Manifest {
        public String id = "";
        public String name = "";
        public String version = "1.0.0";
        public String author = "";
        public String description = "";
        /** Mindestens nötige Version von LED Studio. */
        public String minAppVersion = "";
        /** Effekte, relativ zum Plugin-Ordner (.js). */
        public List<String> effects = new ArrayList<>();
        /** Datei mit Vorlagen (Liste wie in project.json), optional. */
        public String presets = "";
        public String homepage = "";
        /** Schaltet eine mitgelieferte Funktion frei ("games" oder "show"), leer bei normalen Plugins. */
        public String builtin = "";
    }

    /** Funktionen, die in LED Studio stecken, aber erst als Plugin installiert werden müssen. */
    /** offered: steht unter „Offizielle Plugins“; sonst kommt es als Plugin aus seinem eigenen GitHub-Repo. */
    public record Builtin(String feature, String id, String name, String description, boolean offered) {
    }

    public static final String GAMES = "games", SHOW = "show", WLED = "wled";
    /** Repo des WLED-Plugins; es schaltet die eingebaute WLED-Anbindung frei. */
    public static final String WLED_REPO = "Knirpslarsi/LED-Studio-WLED";

    public static final List<Builtin> BUILTINS = List.of(
            new Builtin(GAMES, "spiele", "Spiele & Counter-Strike 2",
                    "Erkennt den Spielstart, spielt eine Animation je Spiel, zeigt im Spiel eine eigene Vorlage und bringt "
                            + "CS2-Ereignisse wie Bombe, Kills und Treffer auf die LEDs.", true),
            new Builtin(SHOW, "show-editor", "Show-Editor",
                    "Lichtshows passend zu Songs: Zeitleiste mit Spuren, Clips, Markierungen, Sequenzen und zufälligen LEDs.", true),
            new Builtin(WLED, "wled", "WLED",
                    "Steuert WLED-Geräte im Netzwerk wie jedes andere Gerät: im Layout platzieren, Effekte, Musik und Shows.", false));

    public static Builtin builtin(String feature) {
        for (Builtin b : BUILTINS) if (b.feature.equals(feature)) return b;
        return null;
    }

    /** Herkunft und Zustand eines installierten Plugins. */
    public static class Source {
        /** github, zip oder local. */
        public String kind = "local";
        public String repo = "";
        public String ref = "";
        public boolean enabled = true;
        /** Vorlagen, die beim Installieren ins Projekt kamen. */
        public List<String> presets = new ArrayList<>();
    }

    public record Installed(Manifest manifest, Source source, Path dir) {
        public String id() {
            return manifest.id;
        }
    }

    /** Ein Eintrag im Plugin-Katalog. */
    public record CatalogEntry(String repo, String name, String description, String author) {
    }

    private final Path root;
    private final String apiBase;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public Plugins(Path root) {
        this(root, "https://api.github.com");
    }

    public Plugins(Path root, String apiBase) {
        this.root = root;
        this.apiBase = apiBase;
    }

    public Path root() {
        return root;
    }

    // ---- Installierte Plugins

    public List<Installed> installed() {
        List<Installed> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).sorted().forEach(d -> {
                try {
                    Manifest m = readManifest(d);
                    out.add(new Installed(m, readSource(d), d));
                } catch (IOException | RuntimeException ignored) {
                    // kaputtes Plugin: nicht anzeigen, nicht laden
                }
            });
        } catch (IOException ignored) {
        }
        return out;
    }

    public Installed find(String id) {
        for (Installed p : installed()) if (p.id().equals(id)) return p;
        return null;
    }

    /** Effekt-Dateien aller eingeschalteten Plugins. */
    public List<PluginEffect> effectFiles() {
        List<PluginEffect> out = new ArrayList<>();
        for (Installed p : installed()) {
            if (!p.source.enabled) continue;
            for (String e : p.manifest.effects) {
                Path f = p.dir.resolve(e).normalize();
                if (f.startsWith(p.dir) && Files.isRegularFile(f)) out.add(new PluginEffect(p.manifest, f));
            }
        }
        return out;
    }

    public record PluginEffect(Manifest plugin, Path file) {
        /** Feste ID, damit Vorlagen den Effekt nach einem Update wiederfinden. */
        public String effectId() {
            String name = file.getFileName().toString().replaceFirst("\\.js$", "");
            return "plugin_" + plugin.id + "_" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        }
    }

    /** Vorlagen aus der Datei des Plugins. */
    public List<Project.Preset> presets(Installed p) throws IOException {
        if (p.manifest.presets == null || p.manifest.presets.isBlank()) return List.of();
        Path f = p.dir.resolve(p.manifest.presets).normalize();
        if (!f.startsWith(p.dir) || !Files.isRegularFile(f)) return List.of();
        Project.Preset[] list = Project.GSON.fromJson(Files.readString(f), Project.Preset[].class);
        return list == null ? List.of() : List.of(list);
    }

    public void setEnabled(String id, boolean enabled) throws IOException {
        Installed p = find(id);
        if (p == null) return;
        p.source.enabled = enabled;
        writeSource(p.dir, p.source);
    }

    public void rememberPresets(String id, List<String> names) throws IOException {
        Installed p = find(id);
        if (p == null) return;
        p.source.presets = new ArrayList<>(names);
        writeSource(p.dir, p.source);
    }

    public void uninstall(String id) throws IOException {
        Installed p = find(id);
        if (p != null) deleteTree(p.dir);
    }

    // ---- Mitgelieferte Plugins

    /** Ob die Funktion als Plugin installiert und eingeschaltet ist. */
    public boolean featureActive(String feature) {
        for (Installed p : installed()) if (feature.equals(p.manifest.builtin) && p.source.enabled) return true;
        return false;
    }

    public boolean featureInstalled(String feature) {
        for (Installed p : installed()) if (feature.equals(p.manifest.builtin)) return true;
        return false;
    }

    /** Installiert eine mitgelieferte Funktion als Plugin (ohne Download). */
    public Installed installBuiltin(String feature) throws IOException {
        Builtin b = builtin(feature);
        if (b == null) throw new IOException("Unbekanntes Plugin: " + feature);
        for (Installed p : installed()) if (feature.equals(p.manifest.builtin)) return p;
        Path dir = root.resolve(b.id);
        Files.createDirectories(dir);
        Manifest m = new Manifest();
        m.id = b.id;
        m.name = b.name;
        m.version = Updater.currentVersion();
        m.author = "LED Studio";
        m.description = b.description;
        m.builtin = feature;
        Files.writeString(dir.resolve(MANIFEST), Project.GSON.toJson(m));
        Source s = new Source();
        s.kind = "builtin";
        writeSource(dir, s);
        return new Installed(m, s, dir);
    }

    // ---- Installieren

    /** "name/repo", "https://github.com/name/repo" oder mit .git/Unterpfad → "name/repo". */
    public static String parseRepo(String input) {
        if (input == null) throw new IllegalArgumentException("Kein Repo angegeben");
        String s = input.trim().replaceFirst("(?i)^https?://(www\\.)?github\\.com/", "").replaceFirst("\\.git$", "");
        Matcher m = Pattern.compile("^([A-Za-z0-9-]{1,39})/([A-Za-z0-9._-]{1,100})(/.*)?$").matcher(s);
        if (!m.matches()) throw new IllegalArgumentException("Bitte als name/repo oder GitHub-Link angeben");
        return m.group(1) + "/" + m.group(2);
    }

    /** Lädt das neueste Release (sonst den Hauptzweig) eines GitHub-Repos und installiert es. */
    public Installed installFromGitHub(String repoInput) throws IOException, InterruptedException {
        String repo = parseRepo(repoInput);
        Latest latest = latest(repo);
        Path zip = Files.createTempFile("ledstudio-plugin", ".zip");
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(latest.zipUrl)).header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(60)).GET().build();
            HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() == 404) throw new IOException("Repo " + repo + " nicht gefunden (oder privat)");
            if (res.statusCode() != 200) throw new IOException("GitHub antwortet mit " + res.statusCode());
            try (InputStream in = res.body()) {
                copyLimited(in, zip);
            }
            Source src = new Source();
            src.kind = "github";
            src.repo = repo;
            src.ref = latest.ref;
            return installZip(zip, src);
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    /** Installiert eine ZIP-Datei mit plugin.json (im Wurzelordner oder in genau einem Unterordner). */
    public Installed installFromZip(Path zip) throws IOException {
        Source src = new Source();
        src.kind = "zip";
        return installZip(zip, src);
    }

    private Installed installZip(Path zip, Source src) throws IOException {
        Path tmp = Files.createTempDirectory("ledstudio-plugin");
        try {
            unzip(zip, tmp);
            Path base = findManifest(tmp);
            if (base == null) throw new IOException("Keine plugin.json gefunden. Ist das wirklich ein LED-Studio-Plugin?");
            Manifest m = readManifest(base);
            if (!m.minAppVersion.isBlank() && Updater.compare(Updater.currentVersion(), m.minAppVersion) < 0)
                throw new IOException("„" + m.name + "“ braucht LED Studio " + m.minAppVersion + " oder neuer.");
            Path target = root.resolve(m.id);
            Installed before = find(m.id);
            if (before != null) {
                // Update: Schalter und gemerkte Vorlagen behalten.
                src.enabled = before.source.enabled;
                src.presets = before.source.presets;
                deleteTree(target);
            }
            Files.createDirectories(root);
            moveTree(base, target);
            writeSource(target, src);
            return new Installed(readManifest(target), src, target);
        } finally {
            deleteTree(tmp);
        }
    }

    // ---- Updates und Katalog

    private record Latest(String ref, String version, String zipUrl) {
    }

    /** Neuestes Release, sonst Hauptzweig mit der Version aus dessen plugin.json. */
    private Latest latest(String repo) throws IOException, InterruptedException {
        HttpResponse<String> rel = get(apiBase + "/repos/" + repo + "/releases/latest", "application/vnd.github+json");
        if (rel.statusCode() == 200) {
            JsonObject o = JsonParser.parseString(rel.body()).getAsJsonObject();
            String tag = o.get("tag_name").getAsString();
            return new Latest(tag, tag.replaceFirst("^[vV]", ""), o.get("zipball_url").getAsString());
        }
        HttpResponse<String> repoInfo = get(apiBase + "/repos/" + repo, "application/vnd.github+json");
        if (repoInfo.statusCode() == 404) throw new IOException("Repo " + repo + " nicht gefunden (oder privat)");
        if (repoInfo.statusCode() != 200) throw new IOException("GitHub antwortet mit " + repoInfo.statusCode());
        String branch = JsonParser.parseString(repoInfo.body()).getAsJsonObject().get("default_branch").getAsString();
        String version = "";
        HttpResponse<String> manifest = get(apiBase + "/repos/" + repo + "/contents/" + MANIFEST + "?ref=" + branch, "application/vnd.github.raw+json");
        if (manifest.statusCode() == 200) {
            try {
                version = Project.GSON.fromJson(manifest.body(), Manifest.class).version;
            } catch (RuntimeException ignored) {
            }
        }
        return new Latest(branch, version, apiBase + "/repos/" + repo + "/zipball/" + branch);
    }

    /** Neuere Version auf GitHub oder null. Nur für Plugins aus GitHub. */
    public String availableUpdate(Installed p) throws IOException, InterruptedException {
        if (!"github".equals(p.source.kind)) return null;
        Latest l = latest(p.source.repo);
        if (l.version == null || l.version.isBlank()) return null;
        return Updater.compare(l.version, p.manifest.version) > 0 ? l.version : null;
    }

    /** Plugins aus dem Katalog-Repo (plugins.json). Leer, wenn es ihn noch nicht gibt. */
    public List<CatalogEntry> catalog() throws IOException, InterruptedException {
        HttpResponse<String> res = get(apiBase + "/repos/" + CATALOG_REPO + "/contents/plugins.json", "application/vnd.github.raw+json");
        if (res.statusCode() == 404) return List.of();
        if (res.statusCode() != 200) throw new IOException("Katalog nicht erreichbar (" + res.statusCode() + ")");
        List<CatalogEntry> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(res.body());
        if (!root.isJsonArray()) return out;
        for (JsonElement e : root.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            try {
                out.add(new CatalogEntry(parseRepo(str(o, "repo")), str(o, "name"), str(o, "description"), str(o, "author")));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }

    private HttpResponse<String> get(String url, String accept) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).header("Accept", accept)
                .timeout(Duration.ofSeconds(20)).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    // ---- Eigenes Plugin

    /** Legt ein neues Plugin mit Beispiel-Effekt an, das sofort geladen wird und als Vorlage für GitHub dient. */
    public Installed create(String name, String author) throws IOException {
        String id = slug(name);
        if (find(id) != null || Files.exists(root.resolve(id))) throw new IOException("Ein Plugin mit der ID „" + id + "“ gibt es schon.");
        Path dir = root.resolve(id);
        Files.createDirectories(dir.resolve("effects"));
        Manifest m = new Manifest();
        m.id = id;
        m.name = name.trim();
        m.author = author == null ? "" : author.trim();
        m.description = "Meine Effekte für LED Studio.";
        m.minAppVersion = Updater.currentVersion();
        m.effects = new ArrayList<>(List.of("effects/beispiel.js"));
        Files.writeString(dir.resolve(MANIFEST), Project.GSON.toJson(m));
        Files.writeString(dir.resolve("effects/beispiel.js"), template("beispiel.js"));
        Files.writeString(dir.resolve("README.md"), template("README.md").replace("{{name}}", m.name).replace("{{id}}", id));
        Files.writeString(dir.resolve(".gitignore"), SOURCE + System.lineSeparator());
        writeSource(dir, new Source());
        return new Installed(m, readSource(dir), dir);
    }

    /** Schreibt Vorlagen in das Plugin (presets.json) und trägt die Datei in plugin.json ein. */
    public void exportPresets(Installed p, List<Project.Preset> presets) throws IOException {
        List<Project.Preset> copies = new ArrayList<>();
        for (Project.Preset preset : presets) copies.add(preset.copy(preset.name));
        Files.writeString(p.dir.resolve("presets.json"), Project.GSON.toJson(copies));
        p.manifest.presets = "presets.json";
        Files.writeString(p.dir.resolve(MANIFEST), Project.GSON.toJson(p.manifest));
    }

    static String slug(String name) {
        String s = name == null ? "" : name.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (s.length() > 40) s = s.substring(0, 40).replaceAll("-+$", "");
        if (!ID.matcher(s).matches()) throw new IllegalArgumentException("Bitte einen Namen mit Buchstaben oder Zahlen angeben");
        return s;
    }

    private static String template(String name) throws IOException {
        try (InputStream in = Plugins.class.getResourceAsStream("/ledstudio/plugin-template/" + name)) {
            if (in == null) throw new IOException("Vorlage fehlt: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---- Dateien

    static Manifest readManifest(Path dir) throws IOException {
        Path f = dir.resolve(MANIFEST);
        if (!Files.isRegularFile(f)) throw new IOException("plugin.json fehlt");
        Manifest m;
        try {
            m = Project.GSON.fromJson(Files.readString(f), Manifest.class);
        } catch (RuntimeException e) {
            throw new IOException("plugin.json ist kein gültiges JSON: " + e.getMessage());
        }
        if (m == null) throw new IOException("plugin.json ist leer");
        if (m.id == null || !ID.matcher(m.id).matches())
            throw new IOException("plugin.json: \"id\" muss aus Kleinbuchstaben, Zahlen und - bestehen (2–40 Zeichen)");
        if (m.name == null || m.name.isBlank()) m.name = m.id;
        if (m.version == null || m.version.isBlank()) m.version = "1.0.0";
        if (m.author == null) m.author = "";
        if (m.description == null) m.description = "";
        if (m.minAppVersion == null) m.minAppVersion = "";
        if (m.presets == null) m.presets = "";
        if (m.homepage == null) m.homepage = "";
        if (m.builtin == null) m.builtin = "";
        if (!m.builtin.isEmpty() && builtin(m.builtin) == null) throw new IOException("plugin.json: unbekannte Funktion " + m.builtin);
        if (m.effects == null) m.effects = new ArrayList<>();
        for (String e : m.effects) {
            Path p = dir.resolve(e).normalize();
            if (!p.startsWith(dir) || !e.endsWith(".js")) throw new IOException("plugin.json: ungültiger Effekt-Pfad " + e);
        }
        return m;
    }

    private static Source readSource(Path dir) {
        try {
            Source s = Project.GSON.fromJson(Files.readString(dir.resolve(SOURCE)), Source.class);
            if (s != null) {
                if (s.presets == null) s.presets = new ArrayList<>();
                return s;
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return new Source();
    }

    private static void writeSource(Path dir, Source s) throws IOException {
        Files.writeString(dir.resolve(SOURCE), Project.GSON.toJson(s));
    }

    private static Path findManifest(Path dir) throws IOException {
        if (Files.isRegularFile(dir.resolve(MANIFEST))) return dir;
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> sub = s.filter(Files::isDirectory).toList();
            if (sub.size() == 1 && Files.isRegularFile(sub.get(0).resolve(MANIFEST))) return sub.get(0);
        }
        return null;
    }

    private static void copyLimited(InputStream in, Path target) throws IOException {
        try (var out = Files.newOutputStream(target)) {
            byte[] buf = new byte[65536];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new IOException("Plugin ist zu groß (über 20 MB)");
                out.write(buf, 0, n);
            }
        }
    }

    /** Entpackt nur erlaubte Dateiarten und nie außerhalb des Zielordners. */
    static void unzip(Path zip, Path target) throws IOException {
        long total = 0;
        int files = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                int dot = name.lastIndexOf('.');
                String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (!ALLOWED.contains(ext)) continue;
                Path out = target.resolve(name).normalize();
                if (!out.startsWith(target)) throw new IOException("Ungültiger Pfad im Archiv: " + name);
                if (++files > MAX_FILES) throw new IOException("Zu viele Dateien im Plugin");
                Files.createDirectories(out.getParent());
                try (var os = Files.newOutputStream(out)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_BYTES) throw new IOException("Plugin ist entpackt zu groß (über 20 MB)");
                        os.write(buf, 0, n);
                    }
                }
            }
        }
    }

    private static void moveTree(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try (Stream<Path> s = Files.walk(from)) {
                for (Path p : s.toList()) {
                    Path dest = to.resolve(from.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(dest);
                    else Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
