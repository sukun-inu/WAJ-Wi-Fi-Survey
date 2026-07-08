package com.waj.tool.alert;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * Windows notifications via {@code java.awt.SystemTray} - JDK-standard, no extra native
 * dependency. Cosmetically it's the legacy tray-balloon API, but Windows 10/11 surface these in
 * the Action Center like a modern toast. INFO-level alerts are intentionally not surfaced here to
 * avoid notification spam; only WARNING/CRITICAL raise a balloon.
 */
public final class NotificationService {

    private TrayIcon trayIcon;
    private boolean available;

    public NotificationService() {
        if (!SystemTray.isSupported()) {
            available = false;
            return;
        }
        try {
            trayIcon = new TrayIcon(createIconImage(), "WAJ Wi-Fi Survey");
            trayIcon.setImageAutoSize(true);
            SystemTray.getSystemTray().add(trayIcon);
            available = true;
        } catch (AWTException e) {
            available = false;
        }
    }

    public void notify(Alert alert, boolean enabled) {
        if (!available || !enabled || alert.severity() == AlertSeverity.INFO) {
            return;
        }
        TrayIcon.MessageType type = alert.severity() == AlertSeverity.CRITICAL
                ? TrayIcon.MessageType.ERROR
                : TrayIcon.MessageType.WARNING;
        trayIcon.displayMessage(alert.category(), alert.message(), type);
    }

    private static Image createIconImage() {
        int size = 16;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x2E, 0xCC, 0x71));
        g.fillOval(0, 0, size, size);
        g.dispose();
        return img;
    }
}
