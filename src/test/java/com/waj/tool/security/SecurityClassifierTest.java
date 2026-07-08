package com.waj.tool.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SecurityClassifierTest {

    private static final int AKM_PSK = 2;
    private static final int AKM_SAE = 8;
    private static final int AKM_8021X_SUITE_B_192 = 12;

    @Test
    void privacyBitClearIsOpenRegardlessOfIe() {
        assertEquals(SecurityType.OPEN, SecurityClassifier.classify(new byte[0], false));
        assertEquals(SecurityType.OPEN, SecurityClassifier.classify(buildRsnIe(AKM_PSK), false));
    }

    @Test
    void privacyBitSetWithNoIeIsWep() {
        assertEquals(SecurityType.WEP, SecurityClassifier.classify(new byte[0], true));
        assertEquals(SecurityType.WEP, SecurityClassifier.classify(null, true));
    }

    @Test
    void rsnWithPskAkmIsWpa2() {
        assertEquals(SecurityType.WPA2, SecurityClassifier.classify(buildRsnIe(AKM_PSK), true));
    }

    @Test
    void rsnWithSaeAkmIsWpa3() {
        assertEquals(SecurityType.WPA3, SecurityClassifier.classify(buildRsnIe(AKM_SAE), true));
    }

    @Test
    void rsnWithBothPskAndSaeAkmIsMixedMode() {
        assertEquals(SecurityType.WPA2_WPA3_MIXED, SecurityClassifier.classify(buildRsnIeMultiAkm(AKM_PSK, AKM_SAE), true));
    }

    @Test
    void rsnWithSuiteB192AkmIsWpa3NotWpa2() {
        // WPA3-Enterprise 192-bit (Suite-B-192, AKM type 12) - previously fell through to the
        // generic "RSN present but AKM not specifically matched -> WPA2" default, mislabeling a
        // *stronger* security mode as a weaker one.
        assertEquals(SecurityType.WPA3, SecurityClassifier.classify(buildRsnIe(AKM_8021X_SUITE_B_192), true));
    }

    @Test
    void legacyWpaVendorIeIsWpa() {
        byte[] ie = {(byte) 221, 4, 0x00, 0x50, (byte) 0xF2, 1};
        assertEquals(SecurityType.WPA, SecurityClassifier.classify(ie, true));
    }

    /** Builds a minimal RSN (tag 48) element with one AKM suite under the standard 00-0F-AC OUI. */
    private static byte[] buildRsnIe(int akmSuiteType) {
        return buildRsnIeMultiAkm(akmSuiteType);
    }

    private static byte[] buildRsnIeMultiAkm(int... akmTypes) {
        int bodyLen = 2 + 4 + 2 + 4 + 2 + akmTypes.length * 4;
        byte[] body = new byte[bodyLen];
        int p = 0;
        body[p++] = 1;
        body[p++] = 0; // version
        body[p++] = 0x00;
        body[p++] = 0x0F;
        body[p++] = (byte) 0xAC;
        body[p++] = 4; // group cipher: CCMP
        body[p++] = 1;
        body[p++] = 0; // pairwise count
        body[p++] = 0x00;
        body[p++] = 0x0F;
        body[p++] = (byte) 0xAC;
        body[p++] = 4; // pairwise cipher: CCMP
        body[p++] = (byte) akmTypes.length;
        body[p++] = 0; // akm count
        for (int t : akmTypes) {
            body[p++] = 0x00;
            body[p++] = 0x0F;
            body[p++] = (byte) 0xAC;
            body[p++] = (byte) t;
        }
        byte[] ie = new byte[2 + body.length];
        ie[0] = 48; // EID: RSN
        ie[1] = (byte) body.length;
        System.arraycopy(body, 0, ie, 2, body.length);
        return ie;
    }
}
