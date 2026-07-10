package com.waj.tool.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VendorLookupTest {

    @Test
    void resolvesKnownOuiRegardlessOfSeparatorStyle() {
        assertEquals("Cisco Systems, Inc", VendorLookup.vendorFor("00:00:0C:12:34:56"));
        assertEquals("Cisco Systems, Inc", VendorLookup.vendorFor("00-00-0C-12-34-56"));
        assertEquals("Cisco Systems, Inc", VendorLookup.vendorFor("00000c123456"));
    }

    @Test
    void returnsNullForUnassignedPrefixOrGarbage() {
        assertNull(VendorLookup.vendorFor("FF:FF:FF:00:00:00"));
        assertNull(VendorLookup.vendorFor(null));
        assertNull(VendorLookup.vendorFor("not-a-mac"));
    }
}
