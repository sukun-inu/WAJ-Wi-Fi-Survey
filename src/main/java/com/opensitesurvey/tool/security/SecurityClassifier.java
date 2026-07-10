package com.opensitesurvey.tool.security;

/**
 * Classifies an AP's security as Open/WEP/WPA/WPA2/WPA3 by parsing the raw 802.11 information
 * elements (IEs) from its last Beacon/Probe Response, since {@code WLAN_BSS_ENTRY} only exposes
 * a single "Privacy" bit - not enough to tell WEP from WPA3.
 *
 * <p>Looks for the RSN element (tag 48) and the legacy WPA vendor element (tag 221, OUI
 * 00:50:F2, type 1), and inspects the AKM (authentication key management) suite list in the RSN
 * element to distinguish PSK/Enterprise (WPA2) from SAE (WPA3). This is a heuristic, not a full
 * 802.11 IE parser - it only decodes enough of the RSN structure to read the AKM suite list.
 */
public final class SecurityClassifier {

    private static final int EID_RSN = 48;
    private static final int EID_VENDOR = 221;
    private static final byte WPA_OUI_0 = (byte) 0x00;
    private static final byte WPA_OUI_1 = (byte) 0x50;
    private static final byte WPA_OUI_2 = (byte) 0xF2;
    private static final int WPA_OUI_TYPE = 1;

    // AKM suite selector types under the standard 00-0F-AC OUI (IEEE 802.11).
    private static final int AKM_8021X = 1;
    private static final int AKM_PSK = 2;
    private static final int AKM_FT_8021X = 3;
    private static final int AKM_FT_PSK = 4;
    private static final int AKM_8021X_SHA256 = 5;
    private static final int AKM_PSK_SHA256 = 6;
    private static final int AKM_SAE = 8;
    private static final int AKM_FT_SAE = 9;
    private static final int AKM_8021X_SUITE_B = 11;
    private static final int AKM_8021X_SUITE_B_192 = 12;
    private static final int AKM_FT_8021X_SHA384 = 13;

    private SecurityClassifier() {
    }

    public static SecurityType classify(byte[] ie, boolean privacyEnabled) {
        if (!privacyEnabled) {
            return SecurityType.OPEN;
        }
        if (ie == null || ie.length == 0) {
            // Privacy bit set but no IEs decoded at all: only WEP relies solely on that bit.
            return SecurityType.WEP;
        }

        boolean hasRsn = false;
        boolean hasLegacyWpa = false;
        boolean hasWpa2 = false;
        boolean hasWpa3 = false;

        int pos = 0;
        while (pos + 2 <= ie.length) {
            int eid = ie[pos] & 0xFF;
            int len = ie[pos + 1] & 0xFF;
            int contentStart = pos + 2;
            if (contentStart + len > ie.length) {
                break; // truncated/corrupt tail - stop parsing defensively
            }

            if (eid == EID_RSN) {
                hasRsn = true;
                for (int akm : parseAkmSuiteTypes(ie, contentStart, len)) {
                    if (akm == AKM_SAE || akm == AKM_FT_SAE || akm == AKM_8021X_SUITE_B
                            || akm == AKM_8021X_SUITE_B_192 || akm == AKM_FT_8021X_SHA384) {
                        hasWpa3 = true;
                    } else if (akm == AKM_PSK || akm == AKM_FT_PSK || akm == AKM_PSK_SHA256
                            || akm == AKM_8021X || akm == AKM_FT_8021X || akm == AKM_8021X_SHA256) {
                        hasWpa2 = true;
                    }
                }
            } else if (eid == EID_VENDOR && len >= 4
                    && ie[contentStart] == WPA_OUI_0 && ie[contentStart + 1] == WPA_OUI_1
                    && ie[contentStart + 2] == WPA_OUI_2 && (ie[contentStart + 3] & 0xFF) == WPA_OUI_TYPE) {
                hasLegacyWpa = true;
            }

            pos = contentStart + len;
        }

        if (hasWpa3 && hasWpa2) {
            return SecurityType.WPA2_WPA3_MIXED;
        }
        if (hasWpa3) {
            return SecurityType.WPA3;
        }
        if (hasWpa2) {
            return SecurityType.WPA2;
        }
        if (hasRsn) {
            return SecurityType.WPA2; // RSN present but AKM suite not one we specifically matched
        }
        if (hasLegacyWpa) {
            return SecurityType.WPA;
        }
        return SecurityType.WEP;
    }

    /**
     * RSN element body: Version(2) GroupCipher(4) PairwiseCount(2) PairwiseList(4*n)
     * AkmCount(2) AkmList(4*m) ... Returns the 4th byte (suite type) of each AKM suite.
     */
    private static int[] parseAkmSuiteTypes(byte[] ie, int rsnStart, int rsnLen) {
        int p = rsnStart;
        int end = rsnStart + rsnLen;
        if (p + 2 + 4 + 2 > end) {
            return new int[0];
        }
        p += 2; // version
        p += 4; // group cipher suite
        int pairwiseCount = readU16LE(ie, p);
        p += 2;
        p += pairwiseCount * 4;
        if (p + 2 > end) {
            return new int[0];
        }
        int akmCount = readU16LE(ie, p);
        p += 2;
        int[] result = new int[Math.max(0, akmCount)];
        for (int i = 0; i < akmCount; i++) {
            if (p + 4 > end) {
                break;
            }
            result[i] = ie[p + 3] & 0xFF;
            p += 4;
        }
        return result;
    }

    private static int readU16LE(byte[] b, int offset) {
        return (b[offset] & 0xFF) | ((b[offset + 1] & 0xFF) << 8);
    }
}
