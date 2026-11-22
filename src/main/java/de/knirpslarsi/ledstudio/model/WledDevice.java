package de.knirpslarsi.ledstudio.model;

/** Ein WLED-Gerät im Netzwerk, gespeichert im Projekt. */
public class WledDevice {
    /** IP-Adresse oder Name im Netz, optional mit :Port für die Web-Oberfläche. */
    public String host = "";
    public String name = "WLED";
    /** Bleibt gleich, auch wenn sich die IP ändert; damit findet das Layout das Gerät wieder. */
    public String mac = "";
    public int leds = 30;
    public boolean enabled = true;
    public int ddpPort = 4048;

    /** Adresse ohne Port, für DDP. */
    public String address() {
        int colon = host.lastIndexOf(':');
        return colon > 0 && host.indexOf(':') == colon ? host.substring(0, colon) : host;
    }

    /** Stabile Kennung für das Layout. */
    public String id() {
        return mac == null || mac.isBlank() ? host : mac;
    }
}
