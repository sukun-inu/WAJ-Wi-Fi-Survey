package com.waj.tool.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvUtilTest {

    @Test
    void leavesPlainValuesUnquoted() {
        assertEquals("S-AP", CsvUtil.escapeField("S-AP"));
    }

    @Test
    void nullBecomesEmptyString() {
        assertEquals("", CsvUtil.escapeField(null));
    }

    @Test
    void quotesAndDoublesEmbeddedQuotesWhenCommaPresent() {
        assertEquals("\"a,b\"", CsvUtil.escapeField("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CsvUtil.escapeField("say \"hi\""));
    }

    @Test
    void quotesValuesContainingANewlineOrCarriageReturn() {
        // A crafted SSID containing a raw newline must not be allowed to split the export into
        // extra rows - this was previously only checked in one of the two duplicated csv()
        // helpers this class replaces (HistoryView's was missing the newline check entirely).
        assertEquals("\"line1\nline2\"", CsvUtil.escapeField("line1\nline2"));
        assertEquals("\"a\rb\"", CsvUtil.escapeField("a\rb"));
    }
}
