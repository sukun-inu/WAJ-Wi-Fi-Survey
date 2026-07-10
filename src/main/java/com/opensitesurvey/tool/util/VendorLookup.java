package com.waj.tool.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * MAC-address-prefix (OUI) to vendor-name lookup, backed by a bundled snapshot of the IEEE MA-L
 * (/24) public registry (see {@code /oui.tsv}: "PREFIX\tVendor Name" per line, one row per
 * assigned prefix - regenerated from https://standards-oui.ieee.org/oui/oui.csv). No network
 * access is made at runtime: the registry changes rarely enough, and this app otherwise has no
 * need for internet connectivity at all, that a periodically-refreshed static snapshot is a
 * better fit than a live lookup.
 *
 * <p>Locally-administered MACs (the U/L bit set, e.g. many virtual/randomized adapters) are
 * deliberately not special-cased here - they simply won't match any registry prefix and {@link
 * #vendorFor(String)} returns {@code null}, same as any other unrecognized prefix.
 */
public final class VendorLookup {

    private static final Map<String, String> VENDOR_BY_PREFIX = load();

    private VendorLookup() {
    }

    /** @param mac a MAC/BSSID in any of the usual separator styles (colon/hyphen/none), case-insensitive. */
    public static String vendorFor(String mac) {
        String prefix = normalize(mac);
        return prefix == null ? null : VENDOR_BY_PREFIX.get(prefix);
    }

    private static String normalize(String mac) {
        if (mac == null) {
            return null;
        }
        StringBuilder hex = new StringBuilder(6);
        for (int i = 0; i < mac.length() && hex.length() < 6; i++) {
            char c = mac.charAt(i);
            if (Character.digit(c, 16) >= 0) {
                hex.append(Character.toUpperCase(c));
            }
        }
        return hex.length() == 6 ? hex.toString() : null;
    }

    private static Map<String, String> load() {
        Map<String, String> map = new HashMap<>(45_000);
        try (InputStream in = VendorLookup.class.getResourceAsStream("/oui.tsv")) {
            if (in == null) {
                return map;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    if (tab <= 0) {
                        continue;
                    }
                    map.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        } catch (IOException e) {
            // best-effort: an app without vendor names is still fully functional
        }
        return map;
    }
}
