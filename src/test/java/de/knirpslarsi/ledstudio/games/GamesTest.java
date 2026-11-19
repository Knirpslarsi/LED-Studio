package de.knirpslarsi.ledstudio.games;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.knirpslarsi.ledstudio.audio.AudioEngine;
import de.knirpslarsi.ledstudio.engine.Engine;
import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.LayoutTools;
import de.knirpslarsi.ledstudio.model.Games;
import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.openrgb.DemoBackend;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spielerkennung, CS2-Ereignisse und Animationen über allem. */
class GamesTest {
    private static final String ME = "7656";

    /** Ein CS2-Paket mit den Feldern, die zählen. */
    private static JsonObject packet(String roundPhase, String bomb, String team, int health, int kills, int hs, int flashed, String winTeam) {
        String round = "{\"phase\":\"" + roundPhase + "\"" + (bomb == null ? "" : ",\"bomb\":\"" + bomb + "\"")
                + (winTeam == null ? "" : ",\"win_team\":\"" + winTeam + "\"") + "}";
        String json = "{\"provider\":{\"steamid\":\"" + ME + "\"},\"map\":{\"phase\":\"live\"},\"round\":" + round
                + ",\"player\":{\"steamid\":\"" + ME + "\",\"team\":\"" + team + "\",\"state\":{\"health\":" + health
                + ",\"flashed\":" + flashed + ",\"burning\":0,\"round_kills\":" + kills + ",\"round_killhs\":" + hs + "}}}";
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static List<String> ids(List<Cs2Events.Action> actions) {
        List<String> out = new ArrayList<>();
        for (Cs2Events.Action a : actions) out.add(a.kind().name().toLowerCase() + ":" + a.id());
        return out;
    }

    @Test
    void cs2StatesBecomeEvents() {
        Cs2Events ev = new Cs2Events();
        assertEquals(List.of("play:team_ct"), ids(ev.update(packet("freezetime", null, "CT", 100, 0, 0, 0, null))));
        assertEquals(List.of("play:round_start"), ids(ev.update(packet("live", null, "CT", 100, 0, 0, 0, null))));
        assertEquals(List.of(), ids(ev.update(packet("live", null, "CT", 100, 0, 0, 0, null))), "Herzschlag ohne Änderung");
        assertEquals(List.of("play:kill"), ids(ev.update(packet("live", null, "CT", 100, 1, 0, 0, null))));
        assertEquals(List.of("play:headshot"), ids(ev.update(packet("live", null, "CT", 100, 2, 1, 0, null))));
        assertEquals(List.of("play:bomb_planted"), ids(ev.update(packet("live", "planted", "CT", 100, 2, 1, 0, null))));
        assertEquals(List.of("play:low_health"), ids(ev.update(packet("live", "planted", "CT", 20, 2, 1, 0, null))));
        List<Cs2Events.Action> flash = ev.update(packet("live", "planted", "CT", 20, 2, 1, 255, null));
        assertEquals(List.of("play:flashed", "level:flashed"), ids(flash));
        assertEquals(1.0, flash.get(1).value(), 1e-9);
        List<Cs2Events.Action> fading = ev.update(packet("live", "planted", "CT", 20, 2, 1, 51, null));
        assertEquals(0.2, fading.get(0).value(), 1e-9, "Blende lässt nach");
        assertEquals(List.of("release:bomb_planted", "play:bomb_defused", "release:flashed"),
                ids(ev.update(packet("live", "defused", "CT", 20, 2, 1, 0, null))));
        assertEquals(List.of("play:round_won"), ids(ev.update(packet("over", "defused", "CT", 20, 2, 1, 0, "CT"))));
        // Neue Runde, dann Tod: Gehaltenes endet.
        ev.update(packet("freezetime", null, "CT", 20, 0, 0, 0, null));
        assertEquals(List.of("play:round_start", "release:low_health", "play:death"), ids(ev.update(packet("live", null, "CT", 0, 0, 0, 0, null))));
    }

    @Test
    void cs2LostRoundExplosionAndLeavingTheGame() {
        Cs2Events ev = new Cs2Events();
        ev.update(packet("live", null, "T", 100, 0, 0, 0, null));
        ev.update(packet("live", "planted", "T", 100, 0, 0, 0, null));
        assertEquals(List.of("release:bomb_planted", "play:bomb_exploded"), ids(ev.update(packet("live", "exploded", "T", 100, 0, 0, 0, null))));
        assertEquals(List.of("play:round_lost", "release:team_t", "play:team_ct"),
                ids(ev.update(packet("over", "exploded", "CT", 100, 0, 0, 0, "T"))), "Teamwechsel zur Halbzeit");
        ev.update(packet("live", null, "CT", 25, 0, 0, 0, null));
        // Hauptmenü: kein map-Block mehr.
        List<String> left = ids(ev.update(new JsonObject()));
        assertTrue(left.contains("release:low_health") && left.contains("release:team_ct"), left.toString());
    }

    @Test
    void spectatingOthersDoesNotTriggerOwnEvents() {
        Cs2Events ev = new Cs2Events();
        ev.update(packet("live", null, "CT", 100, 0, 0, 0, null));
        JsonObject other = packet("live", null, "CT", 0, 3, 0, 0, null);
        other.getAsJsonObject("player").addProperty("steamid", "999");
        assertEquals(List.of(), ids(ev.update(other)), "kein Tod, keine Kills eines anderen Spielers");
    }

    @Test
    void configFileAndSteamLibraries() {
        String cfg = Cs2Setup.config(47800, "abc123");
        assertTrue(cfg.contains("\"uri\"       \"http://127.0.0.1:47800/\""), cfg);
        assertTrue(cfg.contains("\"token\" \"abc123\""));
        assertTrue(cfg.contains("\"player_state\""));
        String vdf = "\"libraryfolders\"\n{\n \"0\"\n {\n  \"path\"\t\t\"C:\\\\Program Files (x86)\\\\Steam\"\n }\n \"1\"\n {\n  \"path\"\t\t\"D:\\\\SteamLibrary\"\n }\n}";
        assertEquals(List.of(Path.of("C:\\Program Files (x86)\\Steam"), Path.of("D:\\SteamLibrary")), Cs2Setup.parseLibraries(vdf));
    }

    @Test
    void oldSettingsGetAllNewParts() {
        Games g = Project.GSON.fromJson("{\"detect\":false,\"cs2\":{\"port\":5000,\"events\":{\"kill\":{\"enabled\":false,"
                + "\"look\":{\"effect\":\"static\"},\"seconds\":1}}}}", Games.class);
        g.complete();
        assertFalse(g.detect);
        assertEquals(5000, g.cs2.port);
        assertFalse(g.cs2.events.get("kill").enabled, "Eigene Einstellungen bleiben");
        assertEquals(Games.CS2_EVENTS.size(), g.cs2.events.size(), "neue Ereignisse kommen dazu");
        assertFalse(g.cs2.token.isBlank());
        assertTrue(g.appIntro.enabled);
        assertTrue(g.games.stream().anyMatch(x -> x.exe.equals("cs2.exe")));
    }

    private static int red(int c) {
        return (c >> 16) & 0xFF;
    }

    private static int green(int c) {
        return (c >> 8) & 0xFF;
    }

    private record Rig(Project project, Engine engine, CompiledLayout layout) {
    }

    private static Rig rig() {
        DemoBackend b = new DemoBackend();
        Project p = new Project();
        p.fixtures = LayoutTools.legacyNzxtChain(b.controllers());
        p.fps = 120;
        p.smoothMs = 0;
        p.presets = new ArrayList<>();
        p.presets.add(new Project.Preset("Grün", "static", java.util.Map.of("use_color", 1, "color", "#00FF00")));
        p.presets.add(new Project.Preset("Blau", "static", java.util.Map.of("use_color", 1, "color", "#0000FF")));
        p.current = "Grün";
        CompiledLayout layout = CompiledLayout.compile(p.fixtures, b.controllers());
        Engine e = new Engine(new AudioEngine(() -> p.audio), x -> {
        });
        e.apply(p, layout);
        e.start();
        return new Rig(p, e, layout);
    }

    @Test
    void overlayLiesOnTopAndEndsByItself() throws Exception {
        Rig r = rig();
        Engine e = r.engine();
        try {
            Thread.sleep(80);
            assertEquals(255, green(e.preview()[0]), "Vorlage grün");
            e.play("x", Project.Layer.solid("#FF0000"), 0.4, 0, 0);
            Thread.sleep(150);
            int[] during = e.preview();
            int redLeds = 0;
            for (int c : during) {
                assertEquals(0, green(c), "nichts mehr grün");
                if ((c & 0xFFFFFF) == 0xFF0000) redLeds++;
            }
            assertTrue(redLeds > during.length / 2, "rot über der Vorlage: " + redLeds);
            Thread.sleep(450);
            assertEquals(255, green(e.preview()[0]), "danach wieder die Vorlage");
            assertFalse(e.playing("x"));

            e.play("hold", Project.Layer.solid("#FF0000"), 0, 0, 0.1);
            e.level("hold", 0.5);
            Thread.sleep(120);
            assertEquals(128, red(e.preview()[0]), 2, "Stärke halbiert");
            assertTrue(e.playing("hold"));
            e.release("hold");
            Thread.sleep(250);
            assertEquals(0, red(e.preview()[0]), "ausgeblendet");
        } finally {
            e.stop();
        }
    }

    @Test
    void gameStartPlaysIntroAndSwitchesPresetUntilItEnds() throws Exception {
        Rig r = rig();
        Project p = r.project();
        p.games = new Games();
        p.games.complete();
        Games.Game cs = p.games.games.get(0);
        cs.preset = "Blau";
        List<String> chosen = new ArrayList<>();
        GameLink link = new GameLink(p, r.engine(), () -> p.current, name -> {
            chosen.add(name);
            p.current = name;
        }, Runnable::run, () -> {
        });
        try {
            link.scan(Set.of("explorer.exe"));
            assertFalse(link.isRunning(cs));
            link.scan(Set.of("explorer.exe", "cs2.exe"));
            assertTrue(link.isRunning(cs));
            assertTrue(r.engine().playing("game"), "Spielstart-Animation läuft");
            assertEquals(List.of("Blau"), chosen);
            link.scan(Set.of("explorer.exe"));
            assertEquals(List.of("Blau", "Grün"), chosen, "vorherige Vorlage kommt zurück");

            // Läuft das Spiel schon beim Start der Software, gibt es keine Animation, aber die Vorlage.
            GameLink late = new GameLink(p, r.engine(), () -> p.current, name -> p.current = name, Runnable::run, () -> {
            });
            r.engine().stopOverlays();
            late.scan(Set.of("cs2.exe"));
            assertTrue(late.isRunning(cs));
            assertFalse(r.engine().playing("game"));
            assertEquals("Blau", p.current);
        } finally {
            r.engine().stop();
        }
    }

    @Test
    void cs2SendsOverHttpAndNeedsTheToken() throws Exception {
        Rig r = rig();
        Project p = r.project();
        p.games = new Games();
        p.games.complete();
        try (java.net.ServerSocket free = new java.net.ServerSocket(0)) {
            p.games.cs2.port = free.getLocalPort();
        }
        GameLink link = new GameLink(p, r.engine(), () -> p.current, name -> {
        }, Runnable::run, () -> {
        });
        link.settingsChanged();
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        java.util.function.BiConsumer<JsonObject, String> send = (json, token) -> {
            json.add("auth", JsonParser.parseString("{\"token\":\"" + token + "\"}"));
            try {
                http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + p.games.cs2.port + "/"))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.toString())).build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding());
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        };
        try {
            assertEquals("", link.serverError);
            send.accept(packet("freezetime", null, "CT", 100, 0, 0, 0, null), p.games.cs2.token);
            send.accept(packet("live", null, "CT", 100, 0, 0, 0, null), "falsch");
            assertFalse(r.engine().playing("cs2:round_start"), "ohne richtigen Token passiert nichts");
            send.accept(packet("live", null, "CT", 100, 0, 0, 0, null), p.games.cs2.token);
            assertTrue(r.engine().playing("cs2:round_start"));
            assertEquals("Runde beginnt", link.cs2LastEvent);
        } finally {
            link.stop();
            r.engine().stop();
        }
    }
}
