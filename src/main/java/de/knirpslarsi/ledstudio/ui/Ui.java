package de.knirpslarsi.ledstudio.ui;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Locale;
import java.util.function.DoubleConsumer;

/** Kleine Bausteine für einheitliche Formulare. */
final class Ui {
    private Ui() {
    }

    static Label label(String text, String... styles) {
        Label l = new Label(text);
        l.getStyleClass().addAll(styles);
        return l;
    }

    static Label section(String text) {
        return label(text, "section");
    }

    static Button button(String text, Runnable action) {
        Button b = new Button(text);
        b.setOnAction(e -> action.run());
        return b;
    }

    static Button button(String text, String tooltip, Runnable action) {
        Button b = button(text, action);
        b.setTooltip(new Tooltip(tooltip));
        return b;
    }

    static Button primary(String text, Runnable action) {
        Button b = button(text, action);
        b.getStyleClass().add("primary");
        return b;
    }

    static Region grow() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    static HBox row(Node... nodes) {
        HBox h = new HBox(8, nodes);
        h.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return h;
    }

    static VBox field(String label, Node control) {
        VBox v = new VBox(3, label(label, "field-label"), control);
        return v;
    }

    /** Regler mit Wertanzeige. */
    static VBox slider(String label, double min, double max, double value, double step, DoubleConsumer onChange) {
        Slider s = new Slider(min, Math.max(max, min + step), Math.max(min, value));
        s.setBlockIncrement(step);
        s.setMaxWidth(Double.MAX_VALUE);
        int decimals = decimals(step);
        Label shown = label(format(value, decimals), "value");
        s.valueProperty().addListener((o, a, b) -> {
            double v = step > 0 ? Math.round(b.doubleValue() / step) * step : b.doubleValue();
            shown.setText(format(v, decimals));
            onChange.accept(v);
        });
        HBox head = new HBox(label(label, "field-label"), grow(), shown);
        return new VBox(2, head, s);
    }

    static int decimals(double step) {
        if (step >= 1) return 0;
        return step >= 0.1 ? 1 : step >= 0.01 ? 2 : 3;
    }

    static String format(double v, int decimals) {
        return String.format(Locale.GERMANY, "%." + decimals + "f", v);
    }

    static boolean confirm(Window owner, String title, String text) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
        style(a, owner, title);
        return a.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    static void info(Window owner, String title, String text) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK);
        style(a, owner, title);
        a.showAndWait();
    }

    static void error(Window owner, String title, String text) {
        Alert a = new Alert(Alert.AlertType.ERROR, text, ButtonType.OK);
        style(a, owner, title);
        a.showAndWait();
    }

    private static void style(Alert a, Window owner, String title) {
        a.setTitle(title);
        a.setHeaderText(null);
        if (owner != null) a.initOwner(owner);
        a.getDialogPane().getStylesheets().add(Ui.class.getResource("/ledstudio/app.css").toExternalForm());
        a.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
    }

    /** Liefert die Farbe als CSS-Wert. */
    static javafx.scene.paint.Color fx(int rgb) {
        return javafx.scene.paint.Color.rgb((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
    }

    static int rgb(javafx.scene.paint.Color c) {
        return ((int) Math.round(c.getRed() * 255) << 16) | ((int) Math.round(c.getGreen() * 255) << 8) | (int) Math.round(c.getBlue() * 255);
    }
}
