package ru.hytorovich.shortener.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppConfigTest {

    @TempDir
    Path dir;

    @Test
    void defaultsAreReadFromJar() throws IOException {
        AppConfig config = AppConfig.load(dir.resolve("no-such-file.properties"));

        assertEquals(Duration.ofHours(24), config.ttl());
        assertEquals(10, config.defaultLimit());
        assertEquals(1000, config.maxLimit());
        assertEquals("sh.rt", config.domain());
        assertEquals(Duration.ofSeconds(60), config.cleanupInterval());
    }

    @Test
    void fileOverridesDefaults() throws IOException {
        Path file = dir.resolve("app.properties");
        Files.writeString(file, "link.ttl-minutes=60\nlink.default-limit=3\n");

        AppConfig config = AppConfig.load(file);

        assertEquals(Duration.ofHours(1), config.ttl());
        assertEquals(3, config.defaultLimit());
    }

    @Test
    void wrongValueIsReported() throws IOException {
        Path file = dir.resolve("app.properties");
        Files.writeString(file, "link.max-limit=много\n");

        assertThrows(IllegalArgumentException.class, () -> AppConfig.load(file));
    }

    @Test
    void defaultLimitAboveMaximumIsRejected() throws IOException {
        Path file = dir.resolve("app.properties");
        Files.writeString(file, "link.default-limit=5000\n");

        assertThrows(IllegalArgumentException.class, () -> AppConfig.load(file));
    }
}
