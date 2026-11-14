package de.knirpslarsi.ledstudio.ui;

import de.knirpslarsi.ledstudio.model.Project;
import de.knirpslarsi.ledstudio.model.SpeedCurve;
import javafx.collections.FXCollections;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/** Einstellungen für „Tempo schwankt“: Form, langsamstes und schnellstes Tempo, Dauer eines Wechsels. */
final class SpeedControls {
    private SpeedControls() {
    }

    /** clip: true für Show-Clips (Beschleunigen und Abbremsen laufen über die ganze Cliplänge). */
    static VBox build(Project.Layer l, boolean clip, Runnable changed) {
        List<String> ids = new ArrayList<>(SpeedCurve.MODES.keySet());
        ComboBox<String> mode = new ComboBox<>(FXCollections.observableArrayList(SpeedCurve.MODES.values()));
        mode.setMaxWidth(Double.MAX_VALUE);
        mode.setValue(SpeedCurve.MODES.getOrDefault(l.speedMode, SpeedCurve.MODES.get("off")));
        VBox details = new VBox(10);
        Label period = Ui.label("", "muted");
        period.setWrapText(true);
        Runnable show = () -> {
            boolean on = !"off".equals(l.speedMode);
            details.setVisible(on);
            details.setManaged(on);
            boolean ramp = clip && ("up".equals(l.speedMode) || "down".equals(l.speedMode));
            period.setText(ramp ? "Beschleunigt bzw. bremst über die ganze Länge des Clips." : "");
            period.setVisible(ramp);
            period.setManaged(ramp);
        };
        mode.setOnAction(e -> {
            int i = mode.getSelectionModel().getSelectedIndex();
            if (i < 0) return;
            l.speedMode = ids.get(i);
            show.run();
            changed.run();
        });
        details.getChildren().addAll(
                Ui.slider("Langsamstes Tempo", 0.05, 5, l.speedMin, 0.05, v -> {
                    l.speedMin = v;
                    changed.run();
                }),
                Ui.slider("Schnellstes Tempo", 0.05, 8, l.speedMax, 0.05, v -> {
                    l.speedMax = v;
                    changed.run();
                }),
                Ui.slider("Dauer eines Wechsels (s)", 0.2, 30, l.speedPeriod, 0.1, v -> {
                    l.speedPeriod = v;
                    changed.run();
                }),
                period);
        show.run();
        Label hint = Ui.label("Der Effekt läuft mal schneller, mal langsamer. 1 = normales Tempo.", "muted");
        hint.setWrapText(true);
        return new VBox(10, Ui.field("Tempo schwankt", mode), hint, details);
    }
}
