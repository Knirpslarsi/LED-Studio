package de.knirpslarsi.ledstudio.ui;

import de.knirpslarsi.ledstudio.audio.AudioEngine;
import de.knirpslarsi.ledstudio.audio.Music;
import de.knirpslarsi.ledstudio.audio.MusicAnalyzer;
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

        VBox reactiveBox = new VBox(12), classicBox = new VBox(12);
        Runnable showForMode = () -> {
            boolean reactive = Music.AUTO.equals(a.mode) || Music.stripModes().contains(a.mode);
            reactiveBox.setVisible(reactive);
            reactiveBox.setManaged(reactive);
            classicBox.setVisible(!reactive);
            classicBox.setManaged(!reactive);
        };
        ComboBox<String> mode = combo(Music.MODES, a.mode, v -> {
            a.mode = v;
            showForMode.run();
        });
        ComboBox<String> band = combo(Music.BANDS, a.band, v -> a.band = v);
        ComboBox<String> palette = combo(Music.PALETTES, a.palette, v -> a.palette = v);
        List<String> spreadIds = List.of("auto", "chain", "fixture", "x");
        List<String> spreadNames = List.of("Automatisch", "Entlang der Kette", "Jeder Lüfter für sich", "Von links nach rechts");
        ComboBox<String> spread = combo(spreadNames, spreadNames.get(Math.max(0, spreadIds.indexOf(a.spread))),
                v -> a.spread = spreadIds.get(spreadNames.indexOf(v)));

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

        // Was die Analyse gerade hört
        Label tempo = Ui.label("–", "field-label"), part = Ui.label("–", "field-label");
        ProgressBar tension = new ProgressBar(0), energy = new ProgressBar(0);
        tension.setPrefWidth(280);
        energy.setPrefWidth(280);
        Label[] lamps = new Label[MusicAnalyzer.EVENT_NAMES.size()];
        javafx.scene.layout.HBox lampRow = new javafx.scene.layout.HBox(8);
        for (int i = 0; i < lamps.length; i++) {
            lamps[i] = Ui.label(MusicAnalyzer.EVENT_NAMES.get(i), "muted");
            lamps[i].setStyle("-fx-padding: 3 8; -fx-background-radius: 10; -fx-background-color: rgba(128,128,128,0.15);");
            lampRow.getChildren().add(lamps[i]);
        }
        GridPane analysis = new GridPane();
        analysis.setHgap(10);
        analysis.setVgap(6);
        analysis.add(Ui.label("Tempo", "field-label"), 0, 0);
        analysis.add(tempo, 1, 0);
        analysis.add(Ui.label("Abschnitt", "field-label"), 0, 1);
        analysis.add(part, 1, 1);
        analysis.add(Ui.label("Spannung", "field-label"), 0, 2);
        analysis.add(tension, 1, 2);
        analysis.add(Ui.label("Energie", "field-label"), 0, 3);
        analysis.add(energy, 1, 3);
        int[] lastCounts = new int[lamps.length];
        double[] glow = new double[lamps.length];

        // Aufzeichnung zum Abstimmen der Erkennung
        Label recordStatus = wrapped("");
        javafx.scene.control.Button record = Ui.button("Aufzeichnung starten", () -> {
        });
        javafx.scene.control.TextField note = new javafx.scene.control.TextField();
        note.setPromptText("Notiz, z. B. „hier kommt der Drop“ oder „zu hell“");
        javafx.scene.control.Button mark = Ui.button("Markierung setzen", () -> {
            core.audio.recorder.mark(System.nanoTime() / 1e9, note.getText());
            recordStatus.setText("Markierung gesetzt" + (note.getText().isBlank() ? "." : ": " + note.getText()));
            note.clear();
        });
        mark.setDisable(true);
        record.setOnAction(e -> {
            if (core.audio.recorder.active()) {
                core.audio.recorder.stop();
                record.setText("Aufzeichnung starten");
                mark.setDisable(true);
                recordStatus.setText("Gespeichert: " + core.audio.recorder.file());
            } else {
                try {
                    java.nio.file.Path f = core.audio.recorder.start();
                    record.setText("Aufzeichnung beenden");
                    mark.setDisable(false);
                    recordStatus.setText("Läuft: " + f.getFileName() + ". Spiel jetzt deine Songs ab.");
                } catch (java.io.IOException ex) {
                    recordStatus.setText("Aufzeichnung nicht möglich: " + ex.getMessage());
                }
            }
        });
        javafx.scene.control.Button folder = Ui.button("Ordner öffnen", () -> {
            try {
                java.nio.file.Files.createDirectories(de.knirpslarsi.ledstudio.audio.AnalysisRecorder.folder());
                java.awt.Desktop.getDesktop().open(de.knirpslarsi.ledstudio.audio.AnalysisRecorder.folder().toFile());
            } catch (Exception ex) {
                recordStatus.setText("Ordner nicht zu öffnen: " + ex.getMessage());
            }
        });
        javafx.scene.layout.HBox recordRow = new javafx.scene.layout.HBox(8, record, mark, folder);
        javafx.scene.layout.HBox.setHgrow(note, javafx.scene.layout.Priority.ALWAYS);

        // Worauf die Musik-Effekte reagieren, wie das Signal geformt wird und wie es aussieht
        java.util.Map<String, String> sourceNames = de.knirpslarsi.ledstudio.audio.SoundSource.choices();
        List<String> sourceIdList = new ArrayList<>(sourceNames.keySet()), sourceLabels = new ArrayList<>(sourceNames.values());
        java.util.function.BiFunction<String, java.util.function.Consumer<String>, ComboBox<String>> reactCombo = (current, set) ->
                combo(sourceLabels, sourceNames.getOrDefault(current, sourceLabels.get(0)), v -> set.accept(sourceIdList.get(sourceLabels.indexOf(v))));
        reactiveBox.getChildren().addAll(
                Ui.field("Verteilung", spread),
                Ui.section("Worauf reagiert der Effekt?"),
                Ui.field("Kanal 1 (Hauptsignal)", reactCombo.apply(a.react1, v -> a.react1 = v)),
                Ui.field("Kanal 2", reactCombo.apply(a.react2, v -> a.react2 = v)),
                Ui.field("Kanal 3", reactCombo.apply(a.react3, v -> a.react3 = v)),
                wrapped("Energie zeigt alle drei Kanäle als Balken, Fluss als Farben. Power füllt mit Kanal 1 und sprüht Funken bei "
                        + "Kanal 2 und 3, Regen lässt je Kanal eigene Tropfen fallen. Pegel, Takt-Balken und Farbblöcke folgen Kanal 1."),
                Ui.slider("Eigener Bereich von (Hz)", 40, 16000, a.fromHz, 10, v -> save(() -> a.fromHz = v)),
                Ui.slider("Eigener Bereich bis (Hz)", 40, 16000, a.toHz, 10, v -> save(() -> a.toHz = v)),
                Ui.slider("Spektrum-Welle von (Hz)", 40, 16000, a.specFrom, 10, v -> save(() -> a.specFrom = v)),
                Ui.slider("Spektrum-Welle bis (Hz)", 40, 16000, a.specTo, 10, v -> save(() -> a.specTo = v)),
                Ui.section("Signal"),
                Ui.slider("Verstärkung", 0.2, 4, a.boost, 0.05, v -> save(() -> a.boost = v)),
                Ui.slider("Schwelle (Leises bleibt dunkel)", 0, 0.9, a.threshold, 0.01, v -> save(() -> a.threshold = v)),
                Ui.slider("Kontrast", 0.3, 4, a.contrast, 0.05, v -> save(() -> a.contrast = v)),
                Ui.slider("Anstieg (ms)", 1, 500, a.attackMs, 1, v -> save(() -> a.attackMs = v)),
                Ui.slider("Abfall (ms)", 20, 2000, a.releaseMs, 5, v -> save(() -> a.releaseMs = v)),
                Ui.section("Aussehen"),
                check("Zur Mitte spiegeln", a.mirror, v -> save(() -> a.mirror = v)),
                check("Farbverlauf umkehren", a.reverse, v -> save(() -> a.reverse = v)),
                Ui.slider("Weichzeichnen", 0, 4, a.blur, 1, v -> save(() -> a.blur = v)),
                Ui.slider("Hintergrundleuchten", 0, 0.4, a.background, 0.01, v -> save(() -> a.background = v)),
                Ui.slider("Geschwindigkeit (Fluss)", 0.2, 4, a.flowSpeed, 0.05, v -> save(() -> a.flowSpeed = v)),
                Ui.slider("Nachleuchten (Fluss, Funken, Tropfen)", 0.2, 4, a.afterglow, 0.05, v -> save(() -> a.afterglow = v)),
                Ui.slider("Leise Stellen dunkler", 0, 1, a.dynamics, 0.01, v -> save(() -> a.dynamics = v)));
        classicBox.getChildren().addAll(
                Ui.field("Reagiert auf", band),
                check("Jeden Beat zeigen (Läufe überlagern sich)", a.overlap, v -> save(() -> a.overlap = v)),
                wrapped("Aus: Während ein Lauf läuft, werden neue Beats ignoriert (wie früher)."),
                Ui.section("Lauf"),
                Ui.slider("Dauer / Tempo (Sek.)", 0.15, 4, a.duration, 0.05, v -> save(() -> a.duration = v)),
                Ui.slider("Effektlänge", 0.2, 4, a.length, 0.05, v -> save(() -> a.length = v)),
                Ui.slider("Mindestabstand zwischen Beats (Sek.)", 0, 3, a.pause, 0.01, v -> save(() -> a.pause = v)),
                Ui.section("Pegel-Anpassung"),
                Ui.slider("Verstärkung", 0.2, 20, a.gain, 0.1, v -> save(() -> a.gain = v)),
                Ui.slider("Rauschschwelle", 0, 0.3, a.floor, 0.005, v -> save(() -> a.floor = v)),
                Ui.slider("Glättung der Anzeige", 0, 1, a.smoothing, 0.01, v -> save(() -> a.smoothing = v)));
        showForMode.run();

        VBox box = new VBox(12,
                Ui.label("Musik", "page-title"),
                Ui.label("Ein Musikimpuls startet einen Lauf. Das Audio wird nur lokal ausgewertet, nie gespeichert oder übertragen.", "muted"),
                wrapped("Diese Soundreaktion gilt für alle Geräte und ersetzt alle Ebenen. Für Musik nur auf einzelnen Geräten: Unter „Effekte“ eine Ebene mit dem Effekt „Musik-Animation“ anlegen."),
                enabled, status,
                Ui.section("Animation"),
                Ui.field("Effekt", mode),
                Ui.field("Musikfarbe / Farbverlauf", palette),
                reactiveBox, classicBox,
                Ui.slider("Empfindlichkeit der Erkennung", 0.2, 3, a.sensitivity, 0.05, v -> save(() -> a.sensitivity = v)),
                Ui.slider("Grundhelligkeit", 0, 1, a.minimum, 0.01, v -> save(() -> a.minimum = v)),
                Ui.section("Analyse"),
                wrapped("„Auto-Regie“ hört dem Song zu: Tempo und Takt, Spannungsaufbau, Drops und ruhige Stellen. "
                        + "Passend dazu wechselt sie zwischen Effekten wie Energie, Fluss, Spektrum-Welle, Power und Regen."),
                analysis, lampRow,
                Ui.section("Erkennung abstimmen"),
                wrapped("Zeichnet beim Abspielen nur die Messwerte der Analyse auf (20 Pegel pro Messung, kein Ton). "
                        + "Daran lässt sich die Erkennung für deine Musik nachstellen und verbessern. Mit „Markierung "
                        + "setzen“ hältst du fest, wo etwas nicht passt."),
                recordRow, note, recordStatus,
                Ui.section("Quelle"),
                source, Ui.button("Quellen laden", this::scanSources),
                Ui.section("Pegel"), meters);
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
                MusicAnalyzer analyzer = core.audio.analyzer;
                MusicAnalyzer.Scene s = analyzer.scene();
                boolean live = System.nanoTime() / 1e9 - s.time() < 0.5;
                if (!live) s = s.silent(s.time());
                tempo.setText(live && s.confidence() > 0.25 ? String.format("%.0f BPM", s.bpm())
                        + (s.confidence() > 0.5 ? "" : " (unsicher)") : "–");
                part.setText(s.section().label);
                tension.setProgress(s.tension());
                energy.setProgress(s.energy());
                for (int i = 0; i < lamps.length; i++) {
                    int c = analyzer.counts[i];
                    if (c != lastCounts[i]) glow[i] = 1;
                    lastCounts[i] = c;
                    glow[i] *= 0.6;
                    lamps[i].setStyle("-fx-padding: 3 8; -fx-background-radius: 10; -fx-background-color: "
                            + String.format(java.util.Locale.ROOT, "rgba(160,32,255,%.2f);", 0.15 + 0.75 * glow[i]));
                }
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
