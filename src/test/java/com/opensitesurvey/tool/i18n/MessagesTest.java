package com.waj.tool.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against key-set drift between the two message bundles - since translations are edited by
 * hand (and, per the delivery plan, potentially by several independent passes), a key added to one
 * file but not the other would otherwise surface as a raw dotted key in production instead of
 * failing a build.
 */
class MessagesTest {

    @Test
    void jaAndEnBundlesDeclareExactlyTheSameKeys() throws IOException {
        Set<String> jaKeys = keysOf("/messages_ja.properties");
        Set<String> enKeys = keysOf("/messages_en.properties");

        Set<String> onlyInJa = new TreeSet<>(jaKeys);
        onlyInJa.removeAll(enKeys);
        Set<String> onlyInEn = new TreeSet<>(enKeys);
        onlyInEn.removeAll(jaKeys);

        assertTrue(onlyInJa.isEmpty(), "Keys present in messages_ja.properties but missing from messages_en.properties: " + onlyInJa);
        assertTrue(onlyInEn.isEmpty(), "Keys present in messages_en.properties but missing from messages_ja.properties: " + onlyInEn);
    }

    @Test
    void formattedNumbersDoNotDependOnJvmDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            Messages.setLocale(Locale.ENGLISH);

            assertEquals("1/2 (avg 3.5ms, max 4ms)",
                    Messages.get("report.message.pingSummary", 1, 2, 3.5, 4));
        } finally {
            Locale.setDefault(previous);
            Messages.setLocale(Locale.JAPANESE);
        }
    }

    private static Set<String> keysOf(String resource) throws IOException {
        Properties props = new Properties();
        try (InputStream in = MessagesTest.class.getResourceAsStream(resource)) {
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return new HashSet<>(props.stringPropertyNames());
    }
}
