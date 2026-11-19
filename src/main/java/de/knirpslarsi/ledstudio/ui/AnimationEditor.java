package de.knirpslarsi.ledstudio.ui;

import de.knirpslarsi.ledstudio.effects.Effect;
import de.knirpslarsi.ledstudio.effects.Effects;
import de.knirpslarsi.ledstudio.effects.ParamSpec;
import de.knirpslarsi.ledstudio.effects.Params;
import de.knirpslarsi.ledstudio.model.Games;
import de.knirpslarsi.ledstudio.util.Colors;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Stellt eine Animation ein: Effekt, seine Farben und Einstellungen, Dauer, Ein- und Ausblenden. */
final class AnimationEditor extends VBox {
    private final Games.Animation anim;
    /** Ereignisse, die halten, solange ein Zustand anhält (z. B. geblendet): keine feste Dauer. */
    private final boolean hold;
    private final Runnable changed;

    AnimationEditor(Games.Animation anim, Runnable changed) {
        super(8);
        this.anim = anim;
        this.hold = anim.seconds <= 0;
        this.changed = changed;
        build();
    }

    private void build() {
        getChildren().clear();
        Map<String, String> effects = new LinkedHashMap<>();
        for (Effect e : Effects.catalog()) effects.put(e.id(), "static".equals(e.id()) ? "Farbe (fest)" : e.title());
        List<String> ids = new ArrayList<>(effects.keySet());
        ComboBox<String> effect = new ComboBox<>(FXCollections.observableArrayList(effects.values()));
        effect.setMaxWidth(Double.MAX_VALUE);
        effect.setValue(effects.getOrDefault(anim.look.effect, anim.look.effect));
        effect.setOnAction(e -> {
            int i = effect.getSelectionModel().getSelectedIndex();
            if (i < 0 || ids.get(i).equals(anim.look.effect)) return;
            String keepColor = anim.look.params.get("color") instanceof String s ? s : "#FFFFFF";
            anim.look.effect = ids.get(i);
            anim.look.params = new LinkedHashMap<>();
            if ("static".equals(anim.look.effect)) {
                anim.look.params.put("use_color", 1);
                anim.look.params.put("color", keepColor);
            }
            changed.run();
            build();
        });
        getChildren().add(Ui.field("Effekt", effect));
        Effect e = Effects.create(anim.look.effect);
        if (e != null) {
            Params values = new Params(e.params(), anim.look.params);
            for (ParamSpec spec : e.params()) {
                if (!spec.visible(values)) continue;
                if ("static".equals(anim.look.effect) && spec.name.equals("use_color")) continue;
                if ("static".equals(anim.look.effect) && values.b("use_color") && List.of("hue", "sat", "val").contains(spec.name)) continue;
                getChildren().add(control(spec, values));
            }
        }
        if (hold) {
            getChildren().add(muted("Läuft, solange der Zustand im Spiel anhält."));
        } else {
            getChildren().add(Ui.slider("Dauer (s)", 0.1, 60, anim.seconds, 0.05, v -> {
                anim.seconds = v;
                changed.run();
            }));
        }
        getChildren().addAll(
                Ui.slider("Einblenden (s)", 0, 5, anim.fadeIn, 0.01, v -> {
                    anim.fadeIn = v;
                    changed.run();
                }),
                Ui.slider("Ausblenden (s)", 0, 8, anim.fadeOut, 0.01, v -> {
                    anim.fadeOut = v;
                    changed.run();
                }),
                Ui.slider("Helligkeit", 0, 2, anim.look.brightness, 0.01, v -> {
                    anim.look.brightness = v;
                    changed.run();
                }),
                Ui.slider("Tempo insgesamt", 0, 5, anim.look.speed, 0.01, v -> {
                    anim.look.speed = v;
                    changed.run();
                }));
    }

    private Node control(ParamSpec s, Params values) {
        Consumer<Object> set = v -> {
            anim.look.params.put(s.name, v);
            changed.run();
        };
        Node n = switch (s.type) {
            case FLOAT, INT -> Ui.slider(s.label, s.min, s.max, values.d(s.name), s.step, set::accept);
            case HUE -> Ui.slider(s.label, 0, 1, values.d(s.name), 0.01, set::accept);
            case BOOL -> {
                CheckBox c = new CheckBox(s.label);
                c.setSelected(values.b(s.name));
                c.selectedProperty().addListener((o, a, b) -> {
                    set.accept(b ? 1 : 0);
                    build();
                });
                yield c;
            }
            case COLOR -> {
                ColorPicker picker = new ColorPicker(Ui.fx(values.color(s.name)));
                picker.setMaxWidth(Double.MAX_VALUE);
                picker.getStyleClass().add("button");
                picker.setOnAction(e -> set.accept(Colors.toHex(Ui.rgb(picker.getValue()))));
                yield Ui.field(s.label, picker);
            }
            case CHOICE -> {
                Map<String, String> ordered = new LinkedHashMap<>(s.choices);
                List<String> keys = new ArrayList<>(ordered.keySet());
                ComboBox<String> box = new ComboBox<>(FXCollections.observableArrayList(ordered.values()));
                box.setMaxWidth(Double.MAX_VALUE);
                box.setValue(ordered.getOrDefault(values.s(s.name), ordered.values().iterator().next()));
                box.setOnAction(e -> {
                    int i = box.getSelectionModel().getSelectedIndex();
                    if (i < 0) return;
                    set.accept(keys.get(i));
                    build();
                });
                yield Ui.field(s.label, box);
            }
        };
        if (s.help == null) return n;
        return new VBox(2, n, muted(s.help));
    }

    static Label muted(String text) {
        Label l = Ui.label(text, "muted");
        l.setWrapText(true);
        return l;
    }
}
