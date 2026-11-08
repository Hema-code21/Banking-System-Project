package com.yourname.bank.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Reads settings from db.properties (project root, not committed) or environment variables. */
public final class AppConfig {
    private static final Properties PROPS = load();

    private AppConfig() {}

    private static Properties load() {
        Properties p = new Properties();
        Path file = Path.of("db.properties");
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (IOException e) {
                System.err.println("Could not read db.properties: " + e.getMessage());
            }
        }
        return p;
    }

    public static String get(String propKey, String envKey, String fallback) {
        String v = PROPS.getProperty(propKey);
        if (v == null || v.isBlank()) v = System.getenv(envKey);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
