package com.waj.tool.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

/**
 * App-wide English/Japanese text lookup, backed by {@code messages_ja.properties}/{@code
 * messages_en.properties} on the classpath. Deliberately does NOT use {@code
 * ResourceBundle.getBundle()} - its default charset handling for {@code .properties} files has
 * shifted across JDK versions, so files are loaded here via an explicit {@code
 * InputStreamReader(..., UTF_8)} instead, guaranteeing correct decoding regardless of JDK version.
 *
 * <p>Missing keys return the key itself rather than throwing, so a forgotten translation shows up
 * as a visibly wrong dotted string during development instead of crashing the whole screen.
 *
 * <p>The language a user picks in {@code SettingsDialog} only takes effect on the next app launch
 * ({@link #setLocale(Locale)} is called once, in {@code App.start()}, before any view is
 * constructed) - re-rendering the ~200 already-built UI strings across every screen live would
 * mean converting every one of them to a bound/observable property, roughly doubling the size of
 * this change for a rarely-used setting.
 */
public final class Messages {

    private static volatile Properties bundle = load(Locale.JAPANESE);

    private Messages() {
    }

    public static void setLocale(Locale locale) {
        bundle = load(locale);
    }

    public static String get(String key) {
        return bundle.getProperty(key, key);
    }

    /**
     * Formats with {@link String#format} - placeholders must use explicit argument indices
     * ({@code %1$s}, {@code %2$d}, ...) rather than plain sequential {@code %s}/{@code %d}, so a
     * translation can freely reorder clauses (a real need between Japanese and English word order)
     * without silently swapping two same-typed arguments' meaning. {@link Locale#ROOT} keeps
     * numeric formatting stable; the app's language is already selected by the loaded bundle.
     */
    public static String get(String key, Object... args) {
        return String.format(Locale.ROOT, get(key), args);
    }

    private static Properties load(Locale locale) {
        String suffix = "en".equals(locale.getLanguage()) ? "en" : "ja";
        Properties props = new Properties();
        String resource = "/messages_" + suffix + ".properties";
        try (InputStream in = Messages.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing classpath resource: " + resource);
            }
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("failed to load message bundle: " + resource, e);
        }
        return props;
    }
}
