package de.knirpslarsi.ledstudio.ui;

import de.knirpslarsi.ledstudio.model.Project;
import javafx.application.Platform;

import java.awt.AWTException;
import java.awt.CheckboxMenuItem;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Symbol neben der Uhr: Fenster öffnen, Vorlage wechseln, beenden. */
final class Tray {
    private static final Logger LOG = Logger.getLogger("Tray");
    private final AppCore core;
    private final Runnable show, quit;
    private TrayIcon icon;

    Tray(AppCore core, Runnable show, Runnable quit) {
        this.core = core;
        this.show = show;
        this.quit = quit;
    }

    boolean start() {
        if (!SystemTray.isSupported()) return false;
        EventQueue.invokeLater(() -> {
            try {
                icon = new TrayIcon(image(), "LED Studio", menu());
                icon.setImageAutoSize(true);
                icon.addActionListener(e -> Platform.runLater(show));
                SystemTray.getSystemTray().add(icon);
            } catch (AWTException | RuntimeException e) {
                LOG.log(Level.WARNING, "Tray nicht verfügbar", e);
            }
        });
        core.onSettings(this::refresh);
        return true;
    }

    private String shownMenu = "";

    private void refresh() {
        // Regler lösen oft Änderungen aus; das Menü nur neu bauen, wenn sich dafür etwas geändert hat.
        StringBuilder state = new StringBuilder(core.project.current);
        core.project.presets.forEach(p -> state.append('|').append(p.name));
        if (state.toString().equals(shownMenu)) return;
        shownMenu = state.toString();
        EventQueue.invokeLater(() -> {
            if (icon != null) icon.setPopupMenu(menu());
        });
    }

    void stop() {
        EventQueue.invokeLater(() -> {
            if (icon != null) SystemTray.getSystemTray().remove(icon);
        });
    }

    private PopupMenu menu() {
        PopupMenu m = new PopupMenu();
        MenuItem open = new MenuItem("LED Studio öffnen");
        open.addActionListener(e -> Platform.runLater(show));
        m.add(open);
        m.addSeparator();
        Menu presets = new Menu("Vorlagen");
        for (Project.Preset p : core.project.presets) {
            CheckboxMenuItem item = new CheckboxMenuItem(p.name, p.name.equals(core.project.current));
            item.addItemListener(e -> Platform.runLater(() -> core.selectPreset(p.name)));
            presets.add(item);
        }
        m.add(presets);
        CheckboxMenuItem off = new CheckboxMenuItem("Aus", Project.OFF.equals(core.project.current));
        off.addItemListener(e -> Platform.runLater(() -> core.selectPreset(Project.OFF)));
        m.add(off);
        m.addSeparator();
        MenuItem exit = new MenuItem("Beenden");
        exit.addActionListener(e -> Platform.runLater(quit));
        m.add(exit);
        return m;
    }

    private static BufferedImage image() {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(160, 102, 255));
        g.fillOval(4, 4, 56, 56);
        g.setColor(new Color(25, 25, 30));
        g.fillOval(14, 14, 36, 36);
        g.setColor(new Color(160, 102, 255));
        g.fillOval(26, 26, 12, 12);
        g.dispose();
        return img;
    }
}
