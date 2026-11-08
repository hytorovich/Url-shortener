package ru.hytorovich.shortener.core;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Фоновый поток, который по расписанию удаляет истёкшие ссылки. */
public class ExpiredLinkCleaner {

    private final LinkService service;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "cleaner");
        thread.setDaemon(true); // не мешает программе завершиться
        return thread;
    });

    public ExpiredLinkCleaner(LinkService service) {
        this.service = service;
    }

    public void start() {
        long seconds = service.getConfig().cleanupInterval().toSeconds();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                service.removeExpired();
            } catch (RuntimeException e) {
                // если не поймать, расписание остановится навсегда
                System.err.println("Ошибка очистки: " + e.getMessage());
            }
        }, seconds, seconds, TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }
}
