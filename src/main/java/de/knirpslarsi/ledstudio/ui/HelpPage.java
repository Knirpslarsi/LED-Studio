package de.knirpslarsi.ledstudio.ui;

import de.knirpslarsi.ledstudio.update.Updater;
import de.knirpslarsi.ledstudio.util.AppPaths;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

import java.awt.Desktop;
import java.net.URI;

/** Hilfe: Anleitung, Discord und E-Mail, dazu Version und Logdatei für Fehlerberichte. */
final class HelpPage extends ScrollPane {
    /** Ziele des Hilfe-Bereichs. */
    static final String REPO = "https://github.com/Knirpslarsi/LED-Studio";
    static final String DISCORD = "https://discord.gg/hHunMTsjfT";
    static final String EMAIL = "support@knirpslarsi.de";

    private final AppCore core;

    HelpPage(AppCore core) {
        this.core = core;
        VBox box = new VBox(12,
                Ui.label("Hilfe", "page-title"),
                muted("Fragen, Probleme oder Ideen? Hier erreichst du mich."),
                link(ICON_BOOK, "Anleitung und Downloads", "Einrichtung, Updates und neue Versionen", REPO),
                link(ICON_DISCORD, "Discord", "Fragen stellen und Hilfe bekommen", DISCORD),
                link(ICON_MAIL, "E-Mail", EMAIL, "mailto:" + EMAIL),
                Ui.section("Fehler melden"),
                muted("Hilfreich bei einem Fehlerbericht: die Version, die Diagnose und die Logdatei. Die Diagnose enthält "
                        + "Geräte, Verbindung und Einstellungen, aber keine persönlichen Daten."),
                Ui.label("LED Studio " + Updater.currentVersion(), "field-label"),
                Ui.row(Ui.button("Diagnose kopieren", this::copyDiagnosis), Ui.button("Logdatei öffnen", this::openLog)));
        box.getStyleClass().add("page-narrow");
        box.setMaxWidth(640);
        setContent(box);
        setFitToWidth(true);
        getStyleClass().add("panel");
    }

    private static Label muted(String text) {
        Label l = Ui.label(text, "muted");
        l.setWrapText(true);
        return l;
    }

    /** Eine Karte wie in Voxa: Symbol, Titel und Untertitel, Klick öffnet das Ziel. */
    private Button link(String icon, String title, String text, String target) {
        SVGPath svg = new SVGPath();
        svg.setContent(icon);
        svg.getStyleClass().add("help-icon");
        VBox labels = new VBox(2, Ui.label(title, "help-title"), Ui.label(text, "muted"));
        HBox.setHgrow(labels, Priority.ALWAYS);
        HBox content = new HBox(14, svg, labels, Ui.label("↗", "muted"));
        content.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Button b = new Button();
        b.setGraphic(content);
        b.setMaxWidth(Double.MAX_VALUE);
        b.getStyleClass().add("help-link");
        b.setOnAction(e -> open(target));
        return b;
    }

    private void open(String target) {
        try {
            Desktop.getDesktop().browse(URI.create(target));
        } catch (Exception e) {
            // Ohne Browser: Ziel in die Zwischenablage, damit man es einfügen kann.
            ClipboardContent c = new ClipboardContent();
            c.putString(target.startsWith("mailto:") ? target.substring(7) : target);
            Clipboard.getSystemClipboard().setContent(c);
            Ui.info(getScene().getWindow(), "Hilfe", "Konnte nicht direkt geöffnet werden. In die Zwischenablage kopiert:\n" + c.getString());
        }
    }

    private void copyDiagnosis() {
        ClipboardContent c = new ClipboardContent();
        c.putString("LED Studio " + Updater.currentVersion() + "\n" + core.diagnose());
        Clipboard.getSystemClipboard().setContent(c);
        Ui.info(getScene().getWindow(), "Diagnose", "Die Diagnose ist in der Zwischenablage. Füge sie in deine Nachricht ein.");
    }

    private void openLog() {
        try {
            Desktop.getDesktop().open(AppPaths.logFile().toFile());
        } catch (Exception e) {
            Ui.error(getScene().getWindow(), "Logdatei", "Logdatei nicht zu öffnen: " + AppPaths.logFile());
        }
    }

    private static final String ICON_BOOK = "M4 5.5C4 4.7 4.7 4 5.5 4H11v15H5.5C4.7 19 4 18.3 4 17.5Z M13 4h5.5c.8 0 1.5.7 1.5 1.5v12c0 .8-.7 1.5-1.5 1.5H13Z";
    private static final String ICON_DISCORD = "M8 7c-1.6.3-3 .9-4 1.6-.9 3.5-.7 6.6.4 9.4 1.2.8 2.5 1.4 3.9 1.7l.9-1.6c-.6-.2-1.2-.5-1.7-.9l.4-.3c3.1 1.4 6.9 1.4 10 0l.4.3c-.5.4-1.1.7-1.7.9l.9 1.6c1.4-.3 2.7-.9 3.9-1.7 1.2-3.2 1.3-6.3.4-9.4-1-.7-2.4-1.3-4-1.6l-.5.9c-1.8-.3-3.6-.3-5.4 0Z";
    private static final String ICON_MAIL = "M3 6h18v12H3Z M3 6.5l9 6.5 9-6.5";
}
