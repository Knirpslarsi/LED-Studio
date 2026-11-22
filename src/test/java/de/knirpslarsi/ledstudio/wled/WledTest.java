package de.knirpslarsi.ledstudio.wled;

import com.sun.net.httpserver.HttpServer;
import de.knirpslarsi.ledstudio.model.WledDevice;
import de.knirpslarsi.ledstudio.openrgb.Controller;
import de.knirpslarsi.ledstudio.openrgb.DemoBackend;
import de.knirpslarsi.ledstudio.openrgb.MultiBackend;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** WLED: DDP-Pakete, Senden per UDP, Geräte hinter OpenRGB einreihen, Geräte über die JSON-API erkennen. */
class WledTest {
    private static final String INFO = "{\"ver\": \"0.15.0\", \"name\": \"Schreibtisch\", \"mac\": \"a4cf12ab34cd\","
            + " \"leds\": {\"count\": 60, \"rgbw\": false}}";

    @Test
    void ddpPacketsFollowTheProtocol() {
        List<byte[]> one = Wled.ddp(new int[]{0xFF0000, 0x00FF00, 0x0000FF}, 3);
        assertEquals(1, one.size());
        byte[] p = one.get(0);
        assertEquals(10 + 9, p.length);
        assertEquals(0x41, p[0] & 0xFF, "Version 1 mit Push");
        assertEquals(3, p[1]);
        assertEquals(0x01, p[2], "RGB 8 Bit");
        assertEquals(0, p[4] | p[5] | p[6] | p[7], "Offset 0");
        assertEquals(9, ((p[8] & 0xFF) << 8) | (p[9] & 0xFF), "Länge");
        assertArrayEquals(new byte[]{(byte) 255, 0, 0, 0, (byte) 255, 0, 0, 0, (byte) 255}, Arrays.copyOfRange(p, 10, 19));

        // 600 LEDs: zwei Pakete, das zweite mit Offset 1440 und nur dieses mit Push.
        List<byte[]> two = Wled.ddp(new int[600], 1);
        assertEquals(2, two.size());
        assertEquals(0x40, two.get(0)[0] & 0xFF, "erstes ohne Push");
        assertEquals(0x41, two.get(1)[0] & 0xFF);
        byte[] q = two.get(1);
        assertEquals(1440, ((q[6] & 0xFF) << 8) | (q[7] & 0xFF));
        assertEquals(360, ((q[8] & 0xFF) << 8) | (q[9] & 0xFF));
    }

    @Test
    void sendsColorsOverUdp() throws Exception {
        try (DatagramSocket receiver = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            receiver.setSoTimeout(2000);
            WledDevice d = new WledDevice();
            d.host = "127.0.0.1";
            d.name = "Test";
            d.leds = 2;
            d.ddpPort = receiver.getLocalPort();
            try (WledBackend b = new WledBackend(List.of(d))) {
                assertEquals(1, b.controllers().size());
                Controller c = b.controllers().get(0);
                assertEquals("WLED Test", c.name());
                assertEquals(2, c.ledCount());
                assertEquals(WledBackend.LOCATION, c.location());
                b.setLeds(0, new int[]{0x102030, 0x405060});
                DatagramPacket in = new DatagramPacket(new byte[64], 64);
                receiver.receive(in);
                assertEquals(16, in.getLength());
                assertEquals(0x10, in.getData()[10]);
                assertEquals(0x60, in.getData()[15]);
            }
        }
    }

    @Test
    void wledDevicesComeAfterOpenRgb() throws Exception {
        DemoBackend demo = new DemoBackend();
        int openRgb = demo.controllers().size();
        WledDevice d = new WledDevice();
        d.host = "127.0.0.1";
        d.mac = "aabbccddeeff";
        d.leds = 10;
        try (MultiBackend m = new MultiBackend(demo, new WledBackend(List.of(d)))) {
            assertTrue(m.hasPrimary());
            assertEquals(openRgb + 1, m.controllers().size());
            Controller w = m.controllers().get(openRgb);
            assertEquals(openRgb, w.index(), "Nummer hinter OpenRGB");
            assertEquals("aabbccddeeff", w.serial(), "MAC als Seriennummer: Layout bleibt bei neuer IP");
            assertThrows(IOException.class, () -> m.resizeZone(openRgb, 0, 5));
        }
        try (MultiBackend only = new MultiBackend(null, new WledBackend(List.of(d)))) {
            assertFalse(only.hasPrimary());
            assertTrue(only.alive());
            assertEquals(0, only.controllers().get(0).index());
        }
    }

    @Test
    void findsWledByItsJsonApi() throws Exception {
        HttpServer wled = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        wled.createContext("/json/info", ex -> {
            byte[] body = INFO.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other.createContext("/", ex -> {
            byte[] body = "{\"hello\": 1}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        wled.start();
        other.start();
        try {
            String host = "127.0.0.1:" + wled.getAddress().getPort();
            Wled.Info i = Wled.info(host);
            assertEquals("Schreibtisch", i.name());
            assertEquals(60, i.leds());
            assertEquals("a4cf12ab34cd", i.mac());
            assertThrows(IOException.class, () -> Wled.info("127.0.0.1:" + other.getAddress().getPort()), "kein WLED");

            Set<String> hosts = new LinkedHashSet<>(List.of(host, "127.0.0.1:" + other.getAddress().getPort(), "127.0.0.1:1"));
            List<Wled.Info> found = Wled.probe(hosts);
            assertEquals(1, found.size(), "nur das WLED-Gerät");
            assertEquals(host, found.get(0).host());
        } finally {
            wled.stop(0);
            other.stop(0);
        }
    }

    @Test
    void addressWithoutPort() {
        WledDevice d = new WledDevice();
        d.host = "192.168.1.5:8080";
        assertEquals("192.168.1.5", d.address());
        d.host = "wled-kueche.local";
        assertEquals("wled-kueche.local", d.address());
        assertEquals("wled-kueche.local", d.id(), "ohne MAC zählt der Host");
    }
}
