package de.knirpslarsi.ledstudio.wled;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * WLED-Geräte im Netzwerk: Infos über die JSON-API abfragen, im eigenen Netz suchen und Farben per DDP (UDP 4048)
 * senden. DDP ist das Echtzeit-Protokoll, das WLED von sich aus versteht; danach kehrt das Gerät nach kurzer Zeit
 * selbst zu seinem eigenen Effekt zurück.
 */
public final class Wled {
    public static final int DDP_PORT = 4048;
    /** Nutzdaten je DDP-Paket: 480 LEDs × 3 Byte. */
    static final int MAX_DATA = 1440;

    private Wled() {
    }

    /** Was ein Gerät über sich verrät. */
    public record Info(String host, String name, int leds, String mac, String version) {
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1500)).build();

    /** Fragt /json/info ab. host darf "ip" oder "ip:port" sein. */
    public static Info info(String host) throws IOException, InterruptedException {
        HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create("http://" + host + "/json/info"))
                .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException(host + " antwortet mit " + res.statusCode());
        return parseInfo(host, res.body());
    }

    static Info parseInfo(String host, String json) throws IOException {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (!o.has("leds") || !o.has("ver")) throw new IOException(host + " ist kein WLED-Gerät");
            int leds = o.getAsJsonObject("leds").get("count").getAsInt();
            String name = o.has("name") ? o.get("name").getAsString() : "WLED";
            String mac = o.has("mac") ? o.get("mac").getAsString() : "";
            return new Info(host, name, leds, mac, o.get("ver").getAsString());
        } catch (RuntimeException e) {
            throw new IOException(host + " ist kein WLED-Gerät");
        }
    }

    /** Sucht im eigenen Netz (jedes /24-Netz der Netzwerkkarten) nach WLED. Dauert ein paar Sekunden. */
    public static List<Info> discover() {
        Set<String> hosts = new LinkedHashSet<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
                for (InterfaceAddress a : nif.getInterfaceAddresses()) {
                    if (!(a.getAddress() instanceof Inet4Address v4) || !v4.isSiteLocalAddress()) continue;
                    byte[] b = v4.getAddress();
                    for (int i = 1; i < 255; i++) hosts.add((b[0] & 255) + "." + (b[1] & 255) + "." + (b[2] & 255) + "." + i);
                }
            }
        } catch (IOException ignored) {
        }
        return probe(hosts);
    }

    /** Fragt viele Adressen gleichzeitig ab und behält die WLED-Geräte. */
    static List<Info> probe(Set<String> hosts) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(900)).build();
        List<CompletableFuture<Info>> all = new ArrayList<>();
        for (String h : hosts) {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://" + h + "/json/info")).timeout(Duration.ofMillis(1800)).GET().build();
            all.add(http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(res -> {
                if (res.statusCode() != 200) return null;
                try {
                    return parseInfo(h, res.body());
                } catch (IOException e) {
                    return null;
                }
            }).exceptionally(e -> null));
        }
        List<Info> found = new ArrayList<>();
        for (CompletableFuture<Info> f : all) {
            Info i = f.join();
            if (i != null) found.add(i);
        }
        return found;
    }

    /** DDP-Pakete für alle LEDs: je Paket höchstens 480 LEDs, nur das letzte mit „Push“ (jetzt anzeigen). */
    static List<byte[]> ddp(int[] colors, int sequence) {
        byte[] data = new byte[colors.length * 3];
        for (int i = 0; i < colors.length; i++) {
            data[i * 3] = (byte) (colors[i] >> 16);
            data[i * 3 + 1] = (byte) (colors[i] >> 8);
            data[i * 3 + 2] = (byte) colors[i];
        }
        List<byte[]> packets = new ArrayList<>();
        int offset = 0;
        do {
            int len = Math.min(MAX_DATA, data.length - offset);
            boolean last = offset + len >= data.length;
            byte[] p = new byte[10 + len];
            p[0] = (byte) (0x40 | (last ? 0x01 : 0)); // Version 1, Push beim letzten Paket
            p[1] = (byte) (sequence & 0x0F);
            p[2] = 0x01; // RGB, 8 Bit je Kanal
            p[3] = 0x01; // Ausgabe des Geräts
            p[4] = (byte) (offset >>> 24);
            p[5] = (byte) (offset >>> 16);
            p[6] = (byte) (offset >>> 8);
            p[7] = (byte) offset;
            p[8] = (byte) (len >>> 8);
            p[9] = (byte) len;
            System.arraycopy(data, offset, p, 10, len);
            packets.add(p);
            offset += len;
        } while (offset < data.length);
        return packets;
    }

    /** Sendet Farben an ein Gerät. */
    static void send(DatagramSocket socket, InetAddress address, int port, int[] colors, int sequence) throws IOException {
        for (byte[] p : ddp(colors, sequence)) socket.send(new DatagramPacket(p, p.length, address, port));
    }
}
