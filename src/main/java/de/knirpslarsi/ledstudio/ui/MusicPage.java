package de.knirpslarsi.ledstudio.ui;

import de.knirpslarsi.ledstudio.audio.AudioEngine;
import de.knirpslarsi.ledstudio.audio.Music;
import de.knirpslarsi.ledstudio.model.Project;
import javafx.animation.AnimationTimer;
import javafx.collections.FXCollections;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Soundreaktion. */
final class MusicPage extends ScrollPane {
    private final AppCore core;
    private final ComboBox<String> source = new ComboBox<>();
    private final Map<String, String> sources = new LinkedHashMap<>();
    private final List<String> sourceIds = new ArrayList<>();

    MusicPage(AppCore core) {
        this.core = core;
        Project.Audio a = core.project.audio;
        CheckBox enabled = new CheckBox("Soundreaktion aktivieren");
        enabled.setSelected(a.enabled);
        enabled.selectedProperty().addListener((o, x, b) -> {
            a.enabled = b;
            core.settingsChanged();
        });
        Label status = Ui.label("", "muted");
        status.setWrapText(true);

        ComboBox<String> mode = combo(Music.MODES, a.mode, v -> a.mode = v);
        ComboBox<String> band = combo(Music.BANDS, a.band, v -> a.band = v);
        ComboBox<String> palette = combo(Music.PALETTES, a.palette, v -> a.palette = v);

        sources.put("default", "PC-Sound · Standardausgabe");
        source.setMaxWidth(Double.MAX_VALUE);
        fillSources();
        source.setOnAction(e -> {
            int i = source.getSelectionModel().getSelectedIndex();
            if (i >= 0 && !sourceIds.get(i).equals(a.device)) {
                a.device = sourceIds.get(i);
                core.settingsChanged();
                core.audio.restart();
            }
        });

        GridPane meters = new GridPane();
        meters.setHgap(10);
        meters.setVgap(6);
        ProgressBar[] bars = new ProgressBar[4];
        for (int i = 0; i < 4; i++) {
            bars[i] = new ProgressBar(0);
            bars[i].setPrefWidth(280);
            meters.add(Ui.label(Music.BANDS.get(i), "field-label"), 0, i);
            meters.add(bars[i], 1, i);
        }

        VBox box = new VBox(12,
                Ui.label("Musik", "page-title"),
                Ui.label("Ein Musikimpuls startet einen Lauf. Das Audio wird nur lokal ausgewertet, nie gespeichert oder übertragen.", "muted"),
                wrapped("Diese Soundreaktion gilt für alle Geräte und ersetzt alle Ebenen. Für Musik nur auf einzelnen Geräten: Unter „Effekte“ eine Ebene mit dem Effekt „Musik-Animation“ anlegen."),
                enabled, status,
                Ui.section("Animation"),
                Ui.field("Effekt", mode),
                Ui.field("Reagiert auf", band),
                Ui.field("Musikfarbe", palette),
                check("Jeden Beat zeigen (Läufe überlagern sich)", a.overlap, v -> save(() -> a.overlap = v)),
                wrapped("Aus: Während ein Lauf läuft, werden neue Beats ignoriert (wie früher)."),
                Ui.section("Quelle"),
                source, Ui.button("Quellen laden", this::scanSources),
                Ui.section("Pegel"), meters,
                Ui.section("Lauf"),
                Ui.slider("Dauer / Tempo (Sek.)", 0.15, 4, a.duration, 0.05, v -> save(() -> a.duration = v)),
                Ui.slider("Effektlänge", 0.2, 4, a.length, 0.05, v -> save(() -> a.length = v)),
                Ui.slider("Mindestabstand zwischen Beats (Sek.)", 0, 3, a.pause, 0.01, v -> save(() -> a.pause = v)),
                Ui.slider("Beat-Empfindlichkeit", 0.2, 3, a.sensitivity, 0.05, v -> save(() -> a.sensitivity = v)),
                Ui.section("Empfindlichkeit"),
                Ui.slider("Verstärkung", 0.2, 20, a.gain, 0.1, v -> save(() -> a.gain = v)),
                Ui.slider("Rauschschwelle", 0, 0.3, a.floor, 0.005, v -> save(() -> a.floor = v)),
                Ui.slider("Grundhelligkeit", 0, 1, a.minimum, 0.01, v -> save(() -> a.minimum = v)),
                Ui.slider("Glättung der Anzeige", 0, 1, a.smoothing, 0.01, v -> save(() -> a.smoothing = v)));
        box.getStyleClass().add("page-narrow");
        box.setMaxWidth(640);
        setContent(box);
        setFitToWidth(true);
        getStyleClass().add("panel");

        new AnimationTimer() {
            private long last;

            @Override
            public void handle(long now) {
                if (now - last < 50_000_000L || !isVisible()) return;
                last = now;
                status.setText(core.audio.status);
                for (int i = 0; i < 4; i++) bars[i].setProgress(core.engine.music.levels[i]);
            }
        }.start();
    }

    private static CheckBox check(String text, boolean value, java.util.function.Consumer<Boolean> onChange) {
        CheckBox c = new CheckBox(text);
        c.setSelected(value);
        c.selectedProperty().addListener((o, x, b) -> onChange.accept(b));
        return c;
    }

    private static Label wrapped(String text) {
        Label l = Ui.label(text, "muted");
        l.setWrapText(true);
        return l;
    }

    private void save(Runnable r) {
        r.run();
        core.settingsChanged();
    }

    private ComboBox<String> combo(List<String> values, String current, java.util.function.Consumer<String> onChange) {
        ComboBox<String> c = new ComboBox<>(FXCollections.observableArrayList(values));
        c.setMaxWidth(Double.MAX_VALUE);
        c.setValue(values.contains(current) ? current : values.get(0));
        c.setOnAction(e -> {
            if (c.getValue() != null) save(() -> onChange.accept(c.getValue()));
        });
        return c;
    }

    private void fillSources() {
        sourceIds.clear();
        sourceIds.addAll(sources.keySet());
        if (!sourceIds.contains(core.project.audio.device)) {
            sources.put(core.project.audio.device, "Gespeicherte Quelle");
            sourceIds.add(core.project.audio.device);
        }
        source.setItems(FXCollections.observableArrayList(sources.values()));
        source.getSelectionModel().select(sourceIds.indexOf(core.project.audio.device));
    }

    private void scanSources() {
        Map<String, String> found = new LinkedHashMap<>();
        core.background(() -> found.putAll(AudioEngine.devices()), () -> {
            sources.clear();
            sources.putAll(found);
            fillSources();
        }, null);
    }
}
