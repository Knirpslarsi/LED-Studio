package de.knirpslarsi.ledstudio.ui;

import de.knirpslarsi.ledstudio.update.Updater;
import javafx.animation.KeyFrame;
import javafx.animation.Animation;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Sucht beim Start und alle 6 Stunden nach Updates. Offenes Fenster: Hinweis; im Hintergrund: still installieren. */
final class UpdateController {
    private static final Logger LOG = Logger.getLogger("Update");
    final StringProperty status = new SimpleStringProperty("");
    final StringProperty bannerText = new SimpleStringProperty("");
    final BooleanProperty bannerVisible = new SimpleBooleanProperty(false);
    private final Updater updater = new Updater();
    private final AppCore core;
    private final BooleanSupplier windowVisible;
    private final Runnable quitForInstall;
    private volatile Updater.Release pending;
    private volatile boolean busy;

    UpdateController(AppCore core, BooleanSupplier windowVisible, Runnable quitForInstall) {
        this.core = core;
        this.windowVisible = windowVisible;
        this.quitForInstall = quitForInstall;
    }

    void start() {
        if (core.demo && System.getenv("LEDSTUDIO_TEST_UPDATE") == null) return;
        check(false);
        Timeline t = new Timeline(new KeyFrame(Duration.hours(6), e -> check(false)));
        t.setCycleCount(Animation.INDEFINITE);
        t.play();
    }

    void check(boolean manual) {
        if (busy) return;
        status.set("Suche nach Updates…");
        Thread t = new Thread(() -> {
            try {
                Updater.Release r = updater.check(Updater.currentVersion());
                Platform.runLater(() -> found(r));
            } catch (IOException | InterruptedException | RuntimeException e) {
                LOG.log(Level.INFO, "Update-Prüfung fehlgeschlagen: {0}", e.getMessage());
                Platform.runLater(() -> status.set(manual ? "Update-Prüfung fehlgeschlagen: " + e.getMessage() : ""));
            }
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    private void found(Updater.Release r) {
        pending = r;
        if (r == null) {
            status.set("Du hast die neueste Version.");
            bannerVisible.set(false);
            return;
        }
        status.set("Version " + r.version() + " ist verfügbar.");
        if (!Updater.canInstall()) {
            bannerText.set("Version " + r.version() + " ist verfügbar (Start aus der IDE: bitte über das Setup installieren).");
            bannerVisible.set(true);
            return;
        }
        if (core.project.autoUpdate && !windowVisible.getAsBoolean()) {
            install();
        } else {
            bannerText.set("Update auf Version " + r.version() + " ist verfügbar.");
            bannerVisible.set(true);
        }
    }

    void install() {
        Updater.Release r = pending;
        if (r == null || busy || !Updater.canInstall()) return;
        busy = true;
        Thread t = new Thread(() -> {
            try {
                Path setup = updater.download(r, p -> Platform.runLater(() -> bannerText.set(
                        "Lade Version " + r.version() + " … " + Math.round(p * 100) + " %")));
                Platform.runLater(() -> {
                    try {
                        LOG.info("Installiere Update " + r.version());
                        core.project.save();
                        Updater.install(setup);
                        quitForInstall.run();
                    } catch (IOException e) {
                        failed(e);
                    }
                });
            } catch (IOException | InterruptedException | RuntimeException e) {
                Platform.runLater(() -> failed(e));
            }
        }, "update-download");
        t.setDaemon(true);
        t.start();
    }

    private void failed(Exception e) {
        busy = false;
        LOG.log(Level.WARNING, "Update fehlgeschlagen", e);
        status.set("Update fehlgeschlagen: " + e.getMessage());
        bannerText.set("Update fehlgeschlagen: " + e.getMessage());
        bannerVisible.set(true);
    }
}
