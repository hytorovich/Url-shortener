package ru.hytorovich.shortener.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import ru.hytorovich.shortener.config.AppConfig;

/** Вся логика сервиса. Консоль только вызывает эти методы и печатает результат. */
public class LinkService {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int MAX_ATTEMPTS = 10;

    // ConcurrentHashMap, потому что с ссылками работают два потока: консоль и фоновая очистка
    private final Map<String, Link> links = new ConcurrentHashMap<>();
    // Непрочитанные уведомления по UUID владельца. Доступ только через synchronized-методы.
    private final Map<UUID, List<String>> notifications = new HashMap<>();

    private final AppConfig config;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public LinkService(AppConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    /** Новый код на каждый вызов, поэтому у разных пользователей ссылки на один адрес разные. */
    public Link create(UUID owner, String url, Integer limit) {
        String checkedUrl = checkUrl(url);
        int maxClicks = limit == null ? config.defaultLimit() : checkLimit(limit);
        Instant expiresAt = clock.instant().plus(config.ttl());

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Link link = new Link(generateCode(), checkedUrl, owner, expiresAt, maxClicks);
            // putIfAbsent вернёт null, только если такого кода ещё не было
            if (links.putIfAbsent(link.getCode(), link) == null) {
                return link;
            }
        }
        // при 6 символах кодов около 56 миллиардов, так что сюда на практике не попасть
        throw new ShortenerException("Не удалось подобрать свободный код, попробуйте ещё раз.");
    }

    /** Переход по ссылке: проверяем срок и лимит, засчитываем переход. */
    public Link open(String code) {
        Instant now = clock.instant();
        Link link = findOrThrow(code);
        if (link.isExpired(now)) {
            expire(link);
            throw new ShortenerException("Срок жизни ссылки " + code + " истёк, ссылка удалена.");
        }

        // Проверка лимита и +1 внутри computeIfPresent выполняются атомарно:
        // пока работает лямбда, другой поток эту ссылку не изменит.
        // throw внутри лямбды отменяет изменение: ссылка остаётся как была, а исключение уходит в консоль.
        Link result = links.computeIfPresent(code, (key, current) -> {
            if (current.isLimitReached()) {
                throw new ShortenerException("Лимит переходов по ссылке " + code + " исчерпан, ссылка недоступна.");
            }
            current.addClick();
            if (current.isLimitReached()) {
                notifyOwner(current.getOwner(), "Лимит переходов по ссылке " + code
                        + " исчерпан. Поднимите лимит командой edit или создайте новую: create " + current.getUrl());
            }
            return current;
        });
        if (result == null) {
            throw new ShortenerException("Ссылка " + code + " не найдена.");
        }
        return result;
    }

    public void changeLimit(UUID user, String code, int newLimit) {
        checkOwner(findOrThrow(code), user);
        checkLimit(newLimit);
        links.computeIfPresent(code, (key, link) -> {
            if (newLimit < link.getClicks()) {
                throw new ShortenerException("По ссылке уже " + link.getClicks()
                        + " переходов, лимит не может быть меньше.");
            }
            link.setMaxClicks(newLimit);
            return link;
        });
    }

    public void delete(UUID user, String code) {
        Link link = findOrThrow(code);
        checkOwner(link, user);
        links.remove(code);
    }

    public List<Link> linksOf(UUID owner) {
        return links.values().stream()
                .filter(link -> link.isOwnedBy(owner))
                .sorted(Comparator.comparing(Link::getExpiresAt))
                .toList();
    }

    /** Удалить истёкшие ссылки. Вызывается фоновым потоком. */
    public int removeExpired() {
        Instant now = clock.instant();
        int removed = 0;
        for (Link link : links.values()) {
            if (link.isExpired(now) && expire(link)) {
                removed++;
            }
        }
        return removed;
    }

    /** Отдать непрочитанные уведомления и очистить их. */
    public synchronized List<String> takeNotifications(UUID owner) {
        List<String> list = notifications.remove(owner);
        return list == null ? List.of() : list;
    }

    public AppConfig getConfig() {
        return config;
    }

    public Instant now() {
        return clock.instant();
    }

    /**
     * remove(code, link) удаляет, только если в карте всё ещё этот объект.
     * Если консоль и фоновый поток заметят истечение одновременно, удалит только один,
     * и уведомление придёт один раз.
     */
    private boolean expire(Link link) {
        if (links.remove(link.getCode(), link)) {
            notifyOwner(link.getOwner(), "Срок жизни ссылки " + link.getCode()
                    + " истёк, ссылка удалена. Создайте новую: create " + link.getUrl());
            return true;
        }
        return false;
    }

    private synchronized void notifyOwner(UUID owner, String message) {
        notifications.computeIfAbsent(owner, key -> new ArrayList<>()).add(message);
    }

    private Link findOrThrow(String code) {
        Link link = links.get(code);
        if (link == null) {
            throw new ShortenerException("Ссылка " + code + " не найдена.");
        }
        return link;
    }

    private static void checkOwner(Link link, UUID user) {
        if (!link.isOwnedBy(user)) {
            throw new ShortenerException("Нет прав: ссылка " + link.getCode() + " принадлежит другому пользователю.");
        }
    }

    private int checkLimit(int limit) {
        if (limit < 1 || limit > config.maxLimit()) {
            throw new ShortenerException("Лимит должен быть от 1 до " + config.maxLimit() + ".");
        }
        return limit;
    }

    private static String checkUrl(String url) {
        try {
            URI uri = new URI(url.strip());
            String scheme = uri.getScheme();
            boolean httpOrHttps = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            if (!httpOrHttps || uri.getHost() == null) {
                throw new ShortenerException("Нужен адрес вида https://example.com (только http или https).");
            }
            return url.strip();
        } catch (URISyntaxException e) {
            throw new ShortenerException("Некорректный адрес: " + url);
        }
    }

    private String generateCode() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < config.codeLength(); i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
