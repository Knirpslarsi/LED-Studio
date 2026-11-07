package de.knirpslarsi.ledstudio.ui;

import de.knirpslarsi.ledstudio.layout.CompiledLayout;
import de.knirpslarsi.ledstudio.layout.LayoutTools;
import de.knirpslarsi.ledstudio.model.Fixture;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

import java.util.List;
import java.util.Set;

/** Der Layout-Editor. */
final class LayoutPage extends SplitPane {
    private final AppCore core;
    private final LayoutCanvas canvas;
    private final Label status = Ui.label("", "muted");
    private Timeline walk;
    private int walkIndex;

    LayoutPage(AppCore core) {
        this.core = core;
        canvas = new LayoutCanvas(core, true);
        BorderPane center = new BorderPane(canvas);
        center.setTop(toolbar());
        HBox foot = new HBox(status);
        foot.getStyleClass().add("statusline");
        center.setBottom(foot);
        DevicesPanel devices = new DevicesPanel(core, canvas);
        Inspector inspector = new Inspector(core, canvas);
        getItems().addAll(devices, center, inspector);
        setDividerPositions(0.25, 0.77);
        SplitPane.setResizableWithParent(devices, false);
        SplitPane.setResizableWithParent(inspector, false);
        devices.setMinWidth(300);
        inspector.setMinWidth(270);
        canvas.onSelection(this::updateStatus);
        core.onLayout(this::updateStatus);
        updateStatus();
    }

    private FlowPane toolbar() {
        ToggleButton led = new ToggleButton("LED-Modus");
        led.setTooltip(new javafx.scene.control.Tooltip("Einzelne LEDs anklicken und verschieben"));
        led.selectedProperty().addListener((o, a, b) -> canvas.setLedMode(b));
        ToggleButton walkButton = new ToggleButton("Durchlauf");
        walkButton.setTooltip(new javafx.scene.control.Tooltip("Lässt die Bauteile nacheinander real leuchten; so findest du Lage und doppelte Adressen"));
        walkButton.selectedProperty().addListener((o, a, b) -> toggleWalk(b));
        CheckBox numbers = new CheckBox("Nummern");
        numbers.setSelected(true);
        numbers.selectedProperty().addListener((o, a, b) -> canvas.setShowNumbers(b));
        CheckBox click = new CheckBox("Klick lässt real leuchten");
        click.setSelected(true);
        click.selectedProperty().addListener((o, a, b) -> canvas.setClickIdentifies(b));
        CheckBox snap = new CheckBox("Raster");
        snap.setSelected(true);
        snap.selectedProperty().addListener((o, a, b) -> canvas.setSnap(b));
        FlowPane bar = new FlowPane(8, 6,
                Ui.button("↶", "Rückgängig (Strg+Z)", () -> {
                    core.undo();
                    canvas.select(null, true);
                }),
                Ui.button("↷", "Wiederholen (Strg+Y)", () -> {
                    core.redo();
                    canvas.select(null, true);
                }),
                led, walkButton,
                Ui.button("Ansicht einpassen", canvas::fit),
                Ui.button("Kette sortieren", "Effekte laufen danach von links nach rechts durch den Aufbau", () -> {
                    core.snapshot();
                    LayoutTools.sortChainByPosition(core.project.fixtures);
                    core.layoutChanged();
                }),
                Ui.button("NZXT wie bisher", "Stellt die Reihenfolge der alten Software her: Channel 5 (gespiegelt) → 1 → 2", this::legacyChain),
                numbers, click, snap);
        bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        bar.getStyleClass().add("toolbar-row");
        return bar;
    }

    /** Für Screenshots: erstes Bauteil auswählen. */
    void selectFirst() {
        if (!core.project.fixtures.isEmpty()) canvas.select(core.project.fixtures.get(0), false);
    }

    private void legacyChain() {
        List<Fixture> chain = LayoutTools.legacyNzxtChain(core.controllers());
        if (chain.isEmpty()) {
            Ui.info(getScene().getWindow(), "NZXT-Kette", "Kein NZXT-Controller mit Channel 1, 2 oder 5 gefunden.");
            return;
        }
        if (!core.project.fixtures.isEmpty() && !Ui.confirm(getScene().getWindow(), "NZXT-Kette",
                "Das ersetzt dein aktuelles Layout durch " + chain.size() + " Lüfter in der alten Reihenfolge. Rückgängig geht mit Strg+Z.")) return;
        core.snapshot();
        core.project.fixtures.clear();
        core.project.fixtures.addAll(chain);
        canvas.select(null, false);
        core.layoutChanged();
        canvas.fit();
    }

    private void toggleWalk(boolean on) {
        if (walk != null) walk.stop();
        walk = null;
        if (!on) {
            core.engine.identify(Set.of(), 0);
            updateStatus();
            return;
        }
        walkIndex = 0;
        walk = new Timeline(new KeyFrame(Duration.millis(900), e -> walkStep()));
        walk.setCycleCount(Animation.INDEFINITE);
        walk.playFromStart();
        walkStep();
    }

    private void walkStep() {
        List<Fixture> list = canvas.selectedFixtures().size() > 1 ? canvas.selectedFixtures() : core.project.fixtures;
        if (list.isEmpty()) return;
        Fixture f = list.get(walkIndex++ % list.size());
        canvas.identifyFixture(f);
        status.setText("Durchlauf: " + f.name + " leuchtet jetzt. Leuchtet in echt noch etwas anderes mit, teilen sie sich die Adressen.");
    }

    private void updateStatus() {
        if (walk != null) return;
        CompiledLayout l = core.compiled();
        status.setText(core.project.fixtures.size() + " Bauteile · " + l.leds.length + " LEDs · " + l.devices.size() + (l.devices.size() == 1 ? " Gerät" : " Geräte") + " angesteuert"
                + (canvas.selection.isEmpty() ? "" : " · " + canvas.selection.size() + " gewählt"));
    }
}
