package com.opensitesurvey.tool.alert;

import com.opensitesurvey.tool.model.ApSnapshot;
import com.opensitesurvey.tool.model.ScanSnapshot;
import com.opensitesurvey.tool.persistence.AppConfig;
import com.opensitesurvey.tool.security.SecurityType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertEngineTest {

    private static ApSnapshot ap(String ssid, String bssid, int channel, String band, int rssi) {
        return new ApSnapshot(ssid, bssid, channel, 0, band, rssi, 90, "n/a", true, SecurityType.WPA2, null, Instant.now());
    }

    private static ScanSnapshot snapshot(ApSnapshot... aps) {
        return new ScanSnapshot(Instant.now(), List.of(aps));
    }

    @Test
    void rssiRuleFiresOnceOnCrossingThenFiresRecoveryWhenBackAbove() {
        AppConfig config = new AppConfig();
        config.rssiThresholdDbm = -75;
        AlertEngine engine = new AlertEngine(new AlertContext(config));

        List<Alert> first = engine.onSnapshot(snapshot(ap("S1", "AA:AA:AA:AA:AA:01", 1, "2.4GHz", -80)));
        assertEquals(1, first.stream().filter(a -> a.category().equals("RSSI低下")).count());

        // still below threshold on the next cycle - must not re-fire
        List<Alert> second = engine.onSnapshot(snapshot(ap("S1", "AA:AA:AA:AA:AA:01", 1, "2.4GHz", -82)));
        assertEquals(0, second.stream().filter(a -> a.category().equals("RSSI低下")).count());

        // recovers above threshold - should fire a recovery INFO
        List<Alert> third = engine.onSnapshot(snapshot(ap("S1", "AA:AA:AA:AA:AA:01", 1, "2.4GHz", -60)));
        assertEquals(1, third.stream().filter(a -> a.category().equals("RSSI回復")).count());
    }

    @Test
    void unknownApRuleFlagsUntrustedBssidUnderTrustedSsidOnce() {
        AppConfig config = new AppConfig();
        AlertContext context = new AlertContext(config);
        context.trustedApRegistry.trust("Office-WiFi", "AA:AA:AA:AA:AA:01");
        AlertEngine engine = new AlertEngine(context);

        List<Alert> first = engine.onSnapshot(snapshot(ap("Office-WiFi", "BB:BB:BB:BB:BB:02", 1, "2.4GHz", -50)));
        assertEquals(1, first.stream().filter(a -> a.category().startsWith("未信頼AP")).count());

        // same rogue BSSID seen again - must not re-fire
        List<Alert> second = engine.onSnapshot(snapshot(ap("Office-WiFi", "BB:BB:BB:BB:BB:02", 1, "2.4GHz", -50)));
        assertEquals(0, second.stream().filter(a -> a.category().startsWith("未信頼AP")).count());
    }

    @Test
    void unknownApRuleIgnoresUntrustedSsidsEntirely() {
        AppConfig config = new AppConfig();
        AlertEngine engine = new AlertEngine(new AlertContext(config));
        List<Alert> alerts = engine.onSnapshot(snapshot(ap("RandomCafeWifi", "CC:CC:CC:CC:CC:03", 6, "2.4GHz", -50)));
        assertEquals(0, alerts.stream().filter(a -> a.category().startsWith("未信頼AP")).count());
    }

    @Test
    void newSsidRuleSkipsBaselineButFlagsLaterAdditions() {
        AppConfig config = new AppConfig();
        AlertEngine engine = new AlertEngine(new AlertContext(config));

        List<Alert> baseline = engine.onSnapshot(snapshot(ap("Existing", "AA:AA:AA:AA:AA:01", 1, "2.4GHz", -50)));
        assertEquals(0, baseline.stream().filter(a -> a.category().equals("新規SSID検知")).count());

        List<Alert> withNewOne = engine.onSnapshot(snapshot(
                ap("Existing", "AA:AA:AA:AA:AA:01", 1, "2.4GHz", -50),
                ap("BrandNew", "DD:DD:DD:DD:DD:04", 11, "2.4GHz", -60)));
        assertEquals(1, withNewOne.stream().filter(a -> a.category().equals("新規SSID検知")).count());
    }

    @Test
    void channelCongestionRuleFiresWhenScoreCrossesThreshold() {
        AppConfig config = new AppConfig();
        config.channelCongestionThreshold = 50.0;
        AlertEngine engine = new AlertEngine(new AlertContext(config));

        // Strong AP on channel 1 (strength ~ -30+100 = 70, above the 50.0 threshold)
        List<Alert> alerts = engine.onSnapshot(snapshot(ap("S1", "AA:AA:AA:AA:AA:01", 1, "2.4GHz", -30)));
        assertTrue(alerts.stream().anyMatch(a -> a.category().equals("チャネル混雑")));
    }
}
