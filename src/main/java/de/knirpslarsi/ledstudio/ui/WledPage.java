package de.knirpslarsi.ledstudio.ui;

import de.knirpslarsi.ledstudio.model.WledDevice;
import de.knirpslarsi.ledstudio.wled.Wled;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Plugin „WLED“: Geräte im Netzwerk finden, hinzufügen und verwalten. Platziert werden sie im Layout. */
final class WledPage extends ScrollPane {
    private final AppCore core;
    private final VBox box = new VBox(12);
    private final TextField host = new TextField();
    private final Label status = AnimationEditor.muted("");
    private List<Wled.Info> found = List.of();
    private boolean searching;

    WledPage(AppCore core) {
        this.core = core;
        host.setPromptText("IP-Adresse, z. B. 192.168.178.50");
        host.setOnAction(e -> addHost(host.getText()));
        HBox.setHgrow(host, Priority.ALWAYS);
        box.getStyleClass().add("page-narrow");
        box.setMaxWidth(720);
        setContent(box);
        setFitToWidth(true);
        build();
        core.onStatus(s -> status.setText(s));
    }

    private void build() {
        box.getChildren().setAll(
                Ui.label("WLED", "page-title"),
                AnimationEditor.muted("WLED-Geräte im Netzwerk werden wie jedes andere Gerät gesteuert: im Layout platzieren, dann laufen "
                        + "Effekte, Musik, Shows und Spiel-Animationen auch darauf. LED Studio sendet per DDP; ohne Signal "
                        + "kehrt WLED nach kurzer Zeit zu seinem eigenen Effekt zurück."),
                status,
                Ui.section("Geräte"));
        if (core.project.wled.isEmpty()) box.getChildren().add(AnimationEditor.muted("Noch kein WLED-Gerät. Suche im Netzwerk oder gib die IP-Adresse ein."));
        for (WledDevice d : core.project.wled) box.getChildren().add(card(d));
        Button search = Ui.primary(searching ? "Suche läuft …" : "Im Netzwerk suchen", this::search);
        search.setDisable(searching);
        box.getChildren().addAll(
                Ui.section("Gerät hinzufügen"),
                Ui.row(search),
                Ui.row(host, Ui.button("Hinzufügen", () -> addHost(host.getText()))));
        List<Wled.Info> fresh = found.stream().filter(i -> find(i) == null).toList();
        if (!fresh.isEmpty()) {
            box.getChildren().add(Ui.label("Gefunden", "field-label"));
            for (Wled.Info i : fresh) {
                VBox card = card(i.name(), i.host() + " · " + i.leds() + " LEDs · WLED " + i.version());
                card.getChildren().add(Ui.row(Ui.grow(), Ui.button("Web-Oberfläche", () -> browse(i.host())), Ui.primary("Hinzufügen", () -> add(i))));
                box.getChildren().add(card);
            }
        }
        box.getChildren().addAll(Ui.section("Im Layout platzieren"),
                AnimationEditor.muted("Neue Geräte erscheinen unter Layout in der Geräteliste als „WLED …“. Dort wie einen LED-Streifen "
                        + "anlegen und hinziehen. Die LED-Zahl kommt aus WLED; änderst du sie dort, hier „Neu einlesen“ klicken."));
    }

    private Node card(WledDevice d) {
        CheckBox on = new CheckBox(d.name);
        on.setSelected(d.enabled);
        on.selectedProperty().addListener((o, a, b) -> {
            d.enabled = b;
            changed();
        });
        VBox card = card(null, d.host + " · " + d.leds + " LEDs" + (d.mac.isBlank() ? "" : " · " + d.mac));
        card.getChildren().add(0, Ui.row(on));
        card.getChildren().add(Ui.row(Ui.grow(),
                Ui.button("Web-Oberfläche", () -> browse(d.host)),
                Ui.button("Neu einlesen", "Name und LED-Zahl aus WLED holen", () -> reread(d)),
                Ui.button("Entfernen", () -> {
                    if (!Ui.confirm(getScene().getWindow(), "WLED-Gerät entfernen", "„" + d.name + "“ entfernen? Im Layout platzierte "
                            + "LEDs bleiben als Platzhalter stehen, bis du sie löschst.")) return;
                    core.project.wled.remove(d);
                    changed();
                    build();
                })));
        return card;
    }

    private static VBox card(String title, String text) {
        VBox card = new VBox(6);
        card.getStyleClass().add("layer-row");
        card.setStyle("-fx-padding: 10;");
        if (title != null) card.getChildren().add(Ui.label(title, "field-label"));
        card.getChildren().add(AnimationEditor.muted(text));
        return card;
    }

    private WledDevice find(Wled.Info i) {
        for (WledDevice d : core.project.wled) {
            if (!i.mac().isBlank() && i.mac().equalsIgnoreCase(d.mac)) return d;
            if (i.host().equals(d.host)) return d;
        }
        return null;
    }

    private void search() {
        searching = true;
        status.setText("Suche im Netzwerk …");
        build();
        core.background(() -> {
            List<Wled.Info> list = Wled.discover();
            Platform.runLater(() -> {
                searching = false;
                found = list;
                long fresh = list.stream().filter(i -> find(i) == null).count();
                status.setText(list.isEmpty() ? "Kein WLED-Gerät gefunden. Ist es im selben Netz? Sonst die IP-Adresse eingeben."
                        : list.size() + " WLED-Gerät(e) gefunden" + (fresh < list.size() ? ", " + (list.size() - fresh) + " davon schon hinzugefügt." : "."));
                // Geänderte IPs bekannter Geräte gleich übernehmen.
                boolean moved = false;
                for (Wled.Info i : list) {
                    WledDevice d = find(i);
                    if (d != null && !d.host.equals(i.host())) {
                        d.host = i.host();
                        moved = true;
                    }
                }
                if (moved) changed();
                build();
            });
        }, null, err -> {
            searching = false;
            status.setText("Suche fehlgeschlagen: " + err);
            build();
        });
    }

    private void addHost(String text) {
        String h = text == null ? "" : text.trim().replaceFirst("^https?://", "").replaceFirst("/.*$", "");
        if (h.isEmpty()) return;
        status.setText("Frage " + h + " …");
        core.background(() -> {
            Wled.Info i = Wled.info(h);
            Platform.runLater(() -> {
                host.clear();
                add(i);
            });
        }, null, err -> {
            status.setText(h + ": " + err);
            Ui.error(getScene().getWindow(), "WLED", "Kein WLED-Gerät unter " + h + " erreichbar.\n" + err);
        });
    }

    private void add(Wled.Info i) {
        WledDevice d = find(i);
        if (d == null) {
            d = new WledDevice();
            d.name = i.name(); // nur beim Anlegen: das Layout erkennt das Gerät auch am Namen
            core.project.wled.add(d);
        }
        d.host = i.host();
        d.mac = i.mac();
        d.leds = i.leds();
        status.setText("„" + d.name + "“ hinzugefügt (" + d.leds + " LEDs). Jetzt unter Layout platzieren.");
        changed();
        build();
    }

    private void reread(WledDevice d) {
        core.background(() -> {
            Wled.Info i = Wled.info(d.host);
            Platform.runLater(() -> add(i));
        }, null, err -> Ui.error(getScene().getWindow(), "WLED", d.host + " nicht erreichbar.\n" + err));
    }

    /** Speichern und neu verbinden, damit die Geräteliste stimmt. */
    private void changed() {
        core.settingsChanged();
        core.connection.reconnect();
    }

    private static void browse(String host) {
        new Thread(() -> {
            try {
                Desktop.getDesktop().browse(URI.create("http://" + host + "/"));
            } catch (Exception ignored) {
            }
        }, "browse").start();
    }

    /** Für Screenshots: ein Beispielgerät. */
    void demo() {
        found = new ArrayList<>(List.of(new Wled.Info("192.168.178.61", "Schreibtisch", 60, "a4cf12ab34cd", "0.15.0")));
        build();
    }
}
