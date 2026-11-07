package de.knirpslarsi.ledstudio;

import com.sun.net.httpserver.HttpServer;
import de.knirpslarsi.ledstudio.update.Updater;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prüft den Updater gegen einen lokalen Server, der die GitHub-API nachstellt. */
class UpdaterTest {
    private static final byte[] SETUP = "pretend-setup-binary".getBytes(StandardCharsets.UTF_8);
    private HttpServer server;
    private String base;
    private String digest;
    private int status = 200;

    @BeforeEach
    void start() throws Exception {
        digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(SETUP));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/repos/" + Updater.REPO + "/releases/latest", x -> {
            String agent = x.getRequestHeaders().getFirst("User-Agent");
            assertNotNull(agent);
            assertTrue(!agent.contains("@"), "keine persönlichen Daten im User-Agent");
            String body = """
                    {"tag_name":"v1.4.2","body":"Neu!","html_url":"https://example.invalid","assets":[
                      {"name":"notes.txt","browser_download_url":"%1$s/notes.txt"},
                      {"name":"LEDStudio-Setup-1.4.2.exe","browser_download_url":"%1$s/setup.exe","digest":"sha256:%2$s"}]}"""
                    .formatted(base, digest);
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(status, status == 200 ? b.length : -1);
            if (status == 200) x.getResponseBody().write(b);
            x.close();
        });
        server.createContext("/setup.exe", x -> {
            x.sendResponseHeaders(200, SETUP.length);
            x.getResponseBody().write(SETUP);
            x.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void comparesVersions() {
        assertTrue(Updater.compare("1.10.0", "1.9.9") > 0);
        assertTrue(Updater.compare("v2.0.0", "1.99.99") > 0);
        assertEquals(0, Updater.compare("v1.2.3", "1.2.3"));
        assertTrue(Updater.compare("1.2", "1.2.1") < 0);
    }

    @Test
    void findsNewerReleaseOnly() throws Exception {
        Updater u = new Updater(base);
        Updater.Release r = u.check("1.4.1");
        assertNotNull(r);
        assertEquals("1.4.2", r.version());
        assertTrue(r.setupUrl().endsWith("/setup.exe"));
        assertNull(u.check("1.4.2"));
        assertNull(u.check("2.0.0"));
    }

    @Test
    void noReleaseYetIsNotAnError() throws Exception {
        status = 404;
        assertNull(new Updater(base).check("1.0.0"));
    }

    @Test
    void downloadsAndVerifies() throws Exception {
        Updater u = new Updater(base);
        double[] progress = {0};
        Path file = u.download(u.check("1.0.0"), p -> progress[0] = p);
        assertEquals(new String(SETUP), Files.readString(file));
        assertEquals(1.0, progress[0], 1e-9);
        Files.delete(file);
    }

    @Test
    void rejectsTamperedDownload() {
        Updater u = new Updater(base);
        Updater.Release bad = new Updater.Release("9.9.9", "", base + "/setup.exe", "0".repeat(64), "");
        IOException e = assertThrows(IOException.class, () -> u.download(bad, p -> {
        }));
        assertTrue(e.getMessage().contains("Prüfsumme"));
    }

    @Test
    void rejectsInsecureUrl() {
        Updater u = new Updater(base);
        Updater.Release bad = new Updater.Release("9.9.9", "", "http://example.invalid/setup.exe", digest, "");
        assertThrows(IOException.class, () -> u.download(bad, p -> {
        }));
    }
}
