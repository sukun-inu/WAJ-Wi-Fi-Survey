package com.opensitesurvey.tool.util;

/** Shared RFC 4180-style CSV field escaping, used by every CSV exporter in the app. */
public final class CsvUtil {

    private CsvUtil() {
    }

    /**
     * Quotes {@code value} (doubling any embedded quotes) if it contains a comma, quote, or
     * newline (LF or CR) - any of which would otherwise corrupt the CSV's row/column structure,
     * e.g. a crafted SSID containing a raw newline splitting into extra rows.
     */
    public static String escapeField(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
