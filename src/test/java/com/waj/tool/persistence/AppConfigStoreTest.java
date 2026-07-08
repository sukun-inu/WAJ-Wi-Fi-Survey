package com.waj.tool.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigStoreTest {

    @Test
    void missingFileYieldsDefaults(@TempDir Path tempDir) {
        AppConfig config = AppConfigStore.load(tempDir.resolve("does-not-exist.json").toFile());
        assertEquals(-75, config.rssiThresholdDbm);
        assertTrue(config.rssiAlertEnabled);
    }

    @Test
    void roundTripsCustomSettingsAndTrustedAps(@TempDir Path tempDir) {
        AppConfig original = new AppConfig();
        original.rssiThresholdDbm = -80;
        original.channelCongestionThreshold = 42.0;
        original.rogueApAlertEnabled = false;
        original.defaultPingHost = "8.8.8.8";
        original.trustedAps.add(new AppConfig.TrustedAp("Office-WiFi", "AA:BB:CC:DD:EE:FF"));

        java.io.File file = tempDir.resolve("settings.json").toFile();
        AppConfigStore.save(original, file);
        AppConfig loaded = AppConfigStore.load(file);

        assertEquals(original.rssiThresholdDbm, loaded.rssiThresholdDbm);
        assertEquals(original.channelCongestionThreshold, loaded.channelCongestionThreshold);
        assertFalse(loaded.rogueApAlertEnabled);
        assertEquals(original.defaultPingHost, loaded.defaultPingHost);
        assertEquals(1, loaded.trustedAps.size());
        assertEquals("Office-WiFi", loaded.trustedAps.get(0).ssid);
        assertEquals("AA:BB:CC:DD:EE:FF", loaded.trustedAps.get(0).bssid);
    }
}
