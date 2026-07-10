package com.opensitesurvey.tool.alert;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.net.URL;

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
            trayIcon = new TrayIcon(createIconImage(), "OpenSiteSurvey");
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
        URL iconUrl = NotificationService.class.getResource("/icons/app-16.png");
        return Toolkit.getDefaultToolkit().createImage(iconUrl);
    }
}
