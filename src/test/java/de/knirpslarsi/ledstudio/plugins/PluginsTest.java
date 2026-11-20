package de.knirpslarsi.ledstudio.plugins;

import com.sun.net.httpserver.HttpServer;
import de.knirpslarsi.ledstudio.effects.Effect;
import de.knirpslarsi.ledstudio.effects.Effects;
import de.knirpslarsi.ledstudio.effects.Params;
import de.knirpslarsi.ledstudio.effects.ScriptEffects;
import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.LayoutTools;
import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.openrgb.DemoBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Plugins aus GitHub (nachgebaut), ZIP und selbst gebaut; Schutz vor kaputten und bösen Paketen. */
class PluginsTest {
    @TempDir
    Path dir;
    private HttpServer server;
    private String base;
    /** Pfad → Antwort (Status, Inhalt). */
    private final Map<String, Object[]> routes = new HashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath() + (ex.getRequestURI().getQuery() == null ? "" : "?" + ex.getRequestURI().getQuery());
            Object[] r = routes.getOrDefault(path, new Object[]{404, "{}".getBytes()});
            byte[] body = r[1] instanceof byte[] b ? b : r[1].toString().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders((int) r[0], body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        Effects.setCustom(Map.of());
    }

    private static byte[] zip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> f : files.entrySet()) {
                z.putNextEntry(new ZipEntry(f.getKey()));
                z.write(f.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static final String EFFECT = "var INFO = {title: \"Neon\", params: [{name: \"level\", label: \"Stärke\", def: 1, min: 0, max: 1, step: 0.1}]};\n"
            + "function render(led, t, p) { return [0, 255 * p.level, 0]; }";

    private static Map<String, String> pluginFiles(String top, String version) {
        Map<String, String> f = new java.util.LinkedHashMap<>();
        f.put(top + "plugin.json", "{\"id\": \"neon-pack\", \"name\": \"Neon Pack\", \"version\": \"" + version + "\", \"author\": \"Tester\","
                + "\"effects\": [\"effects/neon.js\"], \"presets\": \"presets.json\"}");
        f.put(top + "effects/neon.js", EFFECT);
        f.put(top + "presets.json", "[{\"name\": \"Neon grün\", \"layers\": [{\"effect\": \"plugin_neon-pack_neon\"}]}]");
        f.put(top + "README.md", "# Neon");
        f.put(top + "evil.exe", "MZ");
        return f;
    }

    private static int[] render(String id) {
        DemoBackend b = new DemoBackend();
        CompiledLayout l = CompiledLayout.compile(LayoutTools.legacyNzxtChain(b.controllers()), b.controllers());
        Effect e = Effects.create(id);
        assertNotNull(e, id);
        Effect.Frame f = new Effect.Frame();
        f.layout = l;
        f.p = new Params(e.params(), Map.of());
        f.out = new int[l.leds.length];
        e.render(f);
        return f.out;
    }

    private static List<ScriptEffects.Extra> extras(Plugins p) {
        List<ScriptEffects.Extra> out = new ArrayList<>();
        for (Plugins.PluginEffect e : p.effectFiles()) out.add(new ScriptEffects.Extra(e.effectId(), e.file(), e.plugin().name));
        return out;
    }

    @Test
    void repoNamesAreUnderstood() {
        assertEquals("knirps/neon", Plugins.parseRepo("knirps/neon"));
        assertEquals("knirps/neon", Plugins.parseRepo("https://github.com/knirps/neon"));
        assertEquals("knirps/neon.fx", Plugins.parseRepo("https://github.com/knirps/neon.fx.git"));
        assertEquals("knirps/neon", Plugins.parseRepo(" https://github.com/knirps/neon/tree/main/effects "));
        assertThrows(IllegalArgumentException.class, () -> Plugins.parseRepo("nur-ein-name"));
        assertThrows(IllegalArgumentException.class, () -> Plugins.parseRepo("https://evil.com/a/b/c?x"));
        assertEquals("meine-neon-effekte", Plugins.slug("Meine Neon-Effekte!"));
        assertEquals("gruene-welle", Plugins.slug("Grüne Welle"));
    }

    @Test
    void installsLatestReleaseFromGitHubAndFindsUpdates() throws Exception {
        routes.put("/repos/tester/neon/releases/latest", new Object[]{200,
                "{\"tag_name\": \"v1.2.0\", \"zipball_url\": \"" + base + "/zip/v1.2.0\"}"});
        routes.put("/zip/v1.2.0", new Object[]{200, zip(pluginFiles("tester-neon-abc123/", "1.2.0"))});
        Plugins plugins = new Plugins(dir, base);
        Plugins.Installed p = plugins.installFromGitHub("https://github.com/tester/neon");
        assertEquals("neon-pack", p.id());
        assertEquals("1.2.0", p.manifest().version);
        assertEquals("github", p.source().kind);
        assertEquals("tester/neon", p.source().repo);
        assertEquals("v1.2.0", p.source().ref);
        assertTrue(Files.exists(dir.resolve("neon-pack/effects/neon.js")));
        assertFalse(Files.exists(dir.resolve("neon-pack/evil.exe")), "nur erlaubte Dateiarten");
        assertEquals(1, plugins.presets(p).size());

        List<String> errors = ScriptEffects.load(dir.resolve("none"), extras(plugins));
        assertEquals(List.of(), errors);
        assertEquals(0x00FF00, render("plugin_neon-pack_neon")[0] & 0xFFFFFF, "Effekt aus dem Plugin rechnet");
        assertEquals("Neon", Effects.create("plugin_neon-pack_neon").title());

        assertNull(plugins.availableUpdate(plugins.find("neon-pack")), "aktuell");
        routes.put("/repos/tester/neon/releases/latest", new Object[]{200,
                "{\"tag_name\": \"v1.3.0\", \"zipball_url\": \"" + base + "/zip/v1.3.0\"}"});
        assertEquals("1.3.0", plugins.availableUpdate(plugins.find("neon-pack")));

        plugins.setEnabled("neon-pack", false);
        plugins.rememberPresets("neon-pack", List.of("Neon grün"));
        routes.put("/zip/v1.3.0", new Object[]{200, zip(pluginFiles("tester-neon-def456/", "1.3.0"))});
        Plugins.Installed updated = plugins.installFromGitHub("tester/neon");
        assertEquals("1.3.0", updated.manifest().version);
        assertFalse(updated.source().enabled, "Schalter bleibt nach dem Update");
        assertEquals(List.of("Neon grün"), updated.source().presets);
        assertEquals(List.of(), plugins.effectFiles(), "ausgeschaltet: keine Effekte");

        plugins.uninstall("neon-pack");
        assertTrue(plugins.installed().isEmpty());
    }

    @Test
    void withoutReleaseTheMainBranchIsUsed() throws Exception {
        routes.put("/repos/tester/neon", new Object[]{200, "{\"default_branch\": \"main\"}"});
        routes.put("/repos/tester/neon/contents/plugin.json?ref=main", new Object[]{200, "{\"id\": \"neon-pack\", \"version\": \"0.9.0\"}"});
        routes.put("/repos/tester/neon/zipball/main", new Object[]{200, zip(pluginFiles("tester-neon-main/", "0.9.0"))});
        Plugins plugins = new Plugins(dir, base);
        Plugins.Installed p = plugins.installFromGitHub("tester/neon");
        assertEquals("main", p.source().ref);
        assertEquals("0.9.0", p.manifest().version);
    }

    @Test
    void missingRepoAndBrokenPackagesGiveClearErrors() throws Exception {
        Plugins plugins = new Plugins(dir, base);
        IOException missing = assertThrows(IOException.class, () -> plugins.installFromGitHub("tester/gibtsnicht"));
        assertTrue(missing.getMessage().contains("nicht gefunden"), missing.getMessage());

        Path noManifest = dir.resolve("x.zip");
        Files.write(noManifest, zip(Map.of("a/readme.md", "hi")));
        assertTrue(assertThrows(IOException.class, () -> plugins.installFromZip(noManifest)).getMessage().contains("plugin.json"));

        Path badId = dir.resolve("y.zip");
        Files.write(badId, zip(Map.of("plugin.json", "{\"id\": \"Böse ID/..\"}")));
        assertThrows(IOException.class, () -> plugins.installFromZip(badId));

        Path escape = dir.resolve("z.zip");
        Files.write(escape, zip(Map.of("plugin.json", "{\"id\": \"ok-id\"}", "../../ausbruch.js", "x")));
        assertThrows(IOException.class, () -> plugins.installFromZip(escape), "Zip-Slip wird abgewiesen");
        assertFalse(Files.exists(dir.getParent().resolve("ausbruch.js")));

        Path escapeEffect = dir.resolve("w.zip");
        Files.write(escapeEffect, zip(Map.of("plugin.json", "{\"id\": \"ok-id\", \"effects\": [\"../../x.js\"]}")));
        assertThrows(IOException.class, () -> plugins.installFromZip(escapeEffect));

        Path tooNew = dir.resolve("v.zip");
        Files.write(tooNew, zip(Map.of("plugin.json", "{\"id\": \"future\", \"minAppVersion\": \"99.0.0\"}")));
        assertTrue(assertThrows(IOException.class, () -> plugins.installFromZip(tooNew)).getMessage().contains("99.0.0"));
        assertTrue(plugins.installed().isEmpty());
    }

    @Test
    void catalogListsPluginsAndToleratesMissingRepo() throws Exception {
        Plugins plugins = new Plugins(dir, base);
        assertEquals(List.of(), plugins.catalog(), "Katalog gibt es noch nicht");
        routes.put("/repos/" + Plugins.CATALOG_REPO + "/contents/plugins.json", new Object[]{200,
                "[{\"repo\": \"tester/neon\", \"name\": \"Neon Pack\", \"description\": \"Grün\", \"author\": \"Tester\"},"
                        + "{\"repo\": \"kaputt\"}, {\"name\": \"ohne repo\"}]"});
        List<Plugins.CatalogEntry> list = plugins.catalog();
        assertEquals(1, list.size());
        assertEquals("tester/neon", list.get(0).repo());
        assertEquals("Neon Pack", list.get(0).name());
    }

    @Test
    void ownPluginWorksRightAwayAndTakesPresets() throws Exception {
        Plugins plugins = new Plugins(dir, base);
        Plugins.Installed p = plugins.create("Meine Effekte", "Larsi");
        assertEquals("meine-effekte", p.id());
        assertEquals("local", p.source().kind);
        assertTrue(Files.readString(p.dir().resolve(".gitignore")).contains(".source.json"));
        assertTrue(Files.readString(p.dir().resolve("README.md")).contains("Meine Effekte"));
        assertEquals(List.of(), ScriptEffects.load(dir.resolve("none"), extras(plugins)));
        int[] out = render("plugin_meine-effekte_beispiel");
        assertTrue(java.util.Arrays.stream(out).anyMatch(c -> c != 0), "Beispiel-Effekt leuchtet");
        assertThrows(IOException.class, () -> plugins.create("Meine Effekte", ""), "gleiche ID");

        plugins.exportPresets(plugins.find("meine-effekte"), List.of(new Project.Preset("Rot", "static", Map.of("color", "#FF0000"))));
        Plugins.Installed again = plugins.find("meine-effekte");
        assertEquals("presets.json", again.manifest().presets);
        assertEquals("Rot", plugins.presets(again).get(0).name);
    }

    @Test
    void endlessLoopInAPluginIsStopped() throws Exception {
        Path f = dir.resolve("loop.js");
        Files.writeString(f, "function render(led, t, p) { while (true) {} }");
        assertEquals(List.of(), ScriptEffects.load(dir.resolve("none"), List.of(new ScriptEffects.Extra("plugin_x_loop", f, "x"))));
        long start = System.nanoTime();
        assertThrows(RuntimeException.class, () -> render("plugin_x_loop"));
        assertTrue(System.nanoTime() - start < 3_000_000_000L, "bricht schnell ab");

        Path g = dir.resolve("java.js");
        Files.writeString(g, "var x = java.lang.System.exit(1); function render() { return [0,0,0]; }");
        List<String> errors = ScriptEffects.load(dir.resolve("none"), List.of(new ScriptEffects.Extra("plugin_x_java", g, "x")));
        assertEquals(1, errors.size(), "kein Zugriff auf Java: " + errors);
    }

    @Test
    void gamesAndShowAreBuiltinPlugins() throws Exception {
        Plugins plugins = new Plugins(dir, base);
        assertFalse(plugins.featureActive(Plugins.GAMES), "ohne Plugin aus");
        assertFalse(plugins.featureActive(Plugins.SHOW));
        Plugins.Installed games = plugins.installBuiltin(Plugins.GAMES);
        assertEquals("builtin", games.source().kind);
        assertEquals(Plugins.GAMES, games.manifest().builtin);
        assertTrue(plugins.featureActive(Plugins.GAMES));
        assertFalse(plugins.featureActive(Plugins.SHOW), "nur das installierte");
        assertEquals(games.dir(), plugins.installBuiltin(Plugins.GAMES).dir(), "zweimal installieren schadet nicht");
        plugins.setEnabled(games.id(), false);
        assertFalse(plugins.featureActive(Plugins.GAMES), "ausgeschaltet");
        assertTrue(plugins.featureInstalled(Plugins.GAMES));
        plugins.uninstall(games.id());
        assertFalse(plugins.featureInstalled(Plugins.GAMES));

        Path unknown = dir.resolve("u.zip");
        Files.write(unknown, zip(Map.of("plugin.json", "{\"id\": \"hack\", \"builtin\": \"root-access\"}")));
        assertThrows(IOException.class, () -> plugins.installFromZip(unknown), "nur bekannte Funktionen");
    }
}
