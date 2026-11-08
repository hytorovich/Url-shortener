package ru.hytorovich.shortener.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.hytorovich.shortener.config.AppConfig;

class LinkServiceTest {

    private static final String URL = "https://www.baeldung.com/java-9-http-client";
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private TestClock clock;
    private LinkService service;

    @BeforeEach
    void setUp() {
        service = createService(Duration.ofHours(24));
    }

    private LinkService createService(Duration ttl) {
        clock = new TestClock();
        AppConfig config = new AppConfig(ttl, 10, 1000, 6, "sh.rt", Duration.ofMinutes(1));
        return new LinkService(config, clock);
    }

    @Test
    void differentUsersGetDifferentLinksForSameUrl() {
        Link alices = service.create(ALICE, URL, null);
        Link bobs = service.create(BOB, URL, null);

        assertNotEquals(alices.getCode(), bobs.getCode());
        assertEquals(URL, service.open(alices.getCode()).getUrl());
        assertEquals(URL, service.open(bobs.getCode()).getUrl());
    }

    @Test
    void userSeesOnlyOwnLinks() {
        service.create(ALICE, URL, null);
        service.create(ALICE, "https://example.com", 3);
        service.create(BOB, URL, null);

        assertEquals(2, service.linksOf(ALICE).size());
        assertEquals(1, service.linksOf(BOB).size());
    }

    @Test
    void defaultLimitIsTakenFromConfig() {
        assertEquals(10, service.create(ALICE, URL, null).getMaxClicks());
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.com", "javascript:alert(1)", "file:///etc/passwd", "https://", "http://a b.com"})
    void badUrlsAreRejected(String url) {
        assertThrows(ShortenerException.class, () -> service.create(ALICE, url, null));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 1001})
    void limitOutsideBoundsIsRejected(int limit) {
        assertThrows(ShortenerException.class, () -> service.create(ALICE, URL, limit));
    }

    @Test
    void unknownCodeIsNotFound() {
        assertThrows(ShortenerException.class, () -> service.open("nope42"));
    }

    @Test
    void linkIsBlockedAfterLimit() {
        Link link = service.create(ALICE, URL, 3);

        for (int i = 1; i <= 3; i++) {
            assertEquals(i, service.open(link.getCode()).getClicks());
        }

        assertThrows(ShortenerException.class, () -> service.open(link.getCode()));
        assertEquals(3, link.getClicks());
    }

    @Test
    void ownerGetsOneNotificationWhenLimitIsReached() {
        Link link = service.create(ALICE, URL, 1);
        service.open(link.getCode());
        assertThrows(ShortenerException.class, () -> service.open(link.getCode()));

        List<String> messages = service.takeNotifications(ALICE);
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("Лимит переходов"));
        assertTrue(service.takeNotifications(ALICE).isEmpty(), "уведомление показывается один раз");
        assertTrue(service.takeNotifications(BOB).isEmpty());
    }

    @Test
    void hundredSimultaneousClicksCountExactlyLimit() throws Exception {
        Link link = service.create(ALICE, URL, 10);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger opened = new AtomicInteger();
        List<Future<?>> tasks = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            tasks.add(pool.submit(() -> {
                try {
                    service.open(link.getCode());
                    opened.incrementAndGet();
                } catch (ShortenerException e) {
                    // лимит исчерпан, это ожидаемо
                }
            }));
        }
        for (Future<?> task : tasks) {
            task.get();
        }
        pool.shutdown();

        assertEquals(10, opened.get());
        assertEquals(10, link.getClicks());
        assertEquals(1, service.takeNotifications(ALICE).size());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 24})
    void linkStopsWorkingAfterTtl(int hours) {
        Duration ttl = Duration.ofHours(hours);
        service = createService(ttl);
        Link link = service.create(ALICE, URL, 100);

        clock.advance(ttl.minusSeconds(1));
        service.open(link.getCode());

        clock.advance(Duration.ofSeconds(1));
        ShortenerException e = assertThrows(ShortenerException.class, () -> service.open(link.getCode()));
        assertTrue(e.getMessage().contains("истёк"));
        assertTrue(service.linksOf(ALICE).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 24})
    void cleanupRemovesOnlyExpiredLinks(int hours) {
        Duration ttl = Duration.ofHours(hours);
        service = createService(ttl);
        service.create(ALICE, URL, null);
        clock.advance(ttl.dividedBy(2));
        service.create(BOB, URL, null);

        clock.advance(ttl.dividedBy(2));

        assertEquals(1, service.removeExpired());
        assertTrue(service.linksOf(ALICE).isEmpty());
        assertEquals(1, service.linksOf(BOB).size());
    }

    @Test
    void ownerGetsOneNotificationWhenLinkExpires() {
        service.create(ALICE, URL, null);
        clock.advance(Duration.ofHours(25));

        service.removeExpired();
        service.removeExpired();

        List<String> messages = service.takeNotifications(ALICE);
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("истёк"));
    }

    @Test
    void onlyOwnerCanChangeLimit() {
        Link link = service.create(ALICE, URL, 5);

        assertThrows(ShortenerException.class, () -> service.changeLimit(BOB, link.getCode(), 50));
        assertEquals(5, link.getMaxClicks());

        service.changeLimit(ALICE, link.getCode(), 50);
        assertEquals(50, link.getMaxClicks());
    }

    @Test
    void onlyOwnerCanDelete() {
        Link link = service.create(ALICE, URL, null);

        assertThrows(ShortenerException.class, () -> service.delete(BOB, link.getCode()));
        assertEquals(1, service.linksOf(ALICE).size());

        service.delete(ALICE, link.getCode());
        assertTrue(service.linksOf(ALICE).isEmpty());
    }

    @Test
    void raisingLimitUnblocksLink() {
        Link link = service.create(ALICE, URL, 1);
        service.open(link.getCode());

        service.changeLimit(ALICE, link.getCode(), 2);

        assertEquals(2, service.open(link.getCode()).getClicks());
    }

    @Test
    void limitCannotBeLowerThanClicksMade() {
        Link link = service.create(ALICE, URL, 5);
        service.open(link.getCode());
        service.open(link.getCode());

        assertThrows(ShortenerException.class, () -> service.changeLimit(ALICE, link.getCode(), 1));
        assertEquals(5, link.getMaxClicks());
    }

    /** Часы, которые можно перемотать вперёд, вместо Thread.sleep. */
    private static class TestClock extends Clock {

        private Instant now = Instant.parse("2026-11-07T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
