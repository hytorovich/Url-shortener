package ru.hytorovich.shortener.core;

import java.time.Instant;
import java.util.UUID;

/** Короткая ссылка. Менять счётчик и лимит можно только через LinkService. */
public class Link {

    private final String code;
    private final String url;
    private final UUID owner;
    private final Instant expiresAt;
    private int maxClicks;
    private int clicks;

    public Link(String code, String url, UUID owner, Instant expiresAt, int maxClicks) {
        this.code = code;
        this.url = url;
        this.owner = owner;
        this.expiresAt = expiresAt;
        this.maxClicks = maxClicks;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isLimitReached() {
        return clicks >= maxClicks;
    }

    public boolean isOwnedBy(UUID user) {
        return owner.equals(user);
    }

    void addClick() {
        clicks++;
    }

    void setMaxClicks(int maxClicks) {
        this.maxClicks = maxClicks;
    }

    public String getCode() {
        return code;
    }

    public String getUrl() {
        return url;
    }

    public UUID getOwner() {
        return owner;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public int getMaxClicks() {
        return maxClicks;
    }

    public int getClicks() {
        return clicks;
    }
}
