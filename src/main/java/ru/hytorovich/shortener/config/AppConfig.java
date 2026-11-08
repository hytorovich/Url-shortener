package ru.hytorovich.shortener.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

/**
 * Настройки из application.properties.
 * Значения по умолчанию лежат внутри jar, файл рядом с программой может их переопределить.
 */
public record AppConfig(
        Duration ttl,
        int defaultLimit,
        int maxLimit,
        int codeLength,
        String domain,
        Duration cleanupInterval) {

    public static AppConfig load(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream defaults = AppConfig.class.getResourceAsStream("/application.properties")) {
            if (defaults != null) {
                p.load(new InputStreamReader(defaults, StandardCharsets.UTF_8));
            }
        }
        if (file != null && Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(reader);
            }
        }

        AppConfig config = new AppConfig(
                Duration.ofMinutes(readInt(p, "link.ttl-minutes")),
                readInt(p, "link.default-limit"),
                readInt(p, "link.max-limit"),
                readInt(p, "link.code-length"),
                p.getProperty("link.domain", "sh.rt").strip(),
                Duration.ofSeconds(readInt(p, "cleanup.interval-seconds")));

        if (config.defaultLimit() > config.maxLimit()) {
            throw new IllegalArgumentException("link.default-limit не может быть больше link.max-limit");
        }
        return config;
    }

    private static int readInt(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("В настройках нет параметра " + key);
        }
        try {
            int number = Integer.parseInt(value.strip());
            if (number <= 0) {
                throw new IllegalArgumentException(key + " должен быть больше нуля");
            }
            return number;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " должен быть целым числом, а не " + value);
        }
    }
}
