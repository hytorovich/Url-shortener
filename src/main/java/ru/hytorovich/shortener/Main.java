package ru.hytorovich.shortener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Clock;
import ru.hytorovich.shortener.cli.ConsoleApp;
import ru.hytorovich.shortener.config.AppConfig;
import ru.hytorovich.shortener.core.ExpiredLinkCleaner;
import ru.hytorovich.shortener.core.LinkService;

/** Точка входа: создаёт объекты и связывает их через конструкторы. */
public class Main {

    public static void main(String[] args) throws IOException {
        Path configFile = Path.of(args.length > 0 ? args[0] : "application.properties");
        AppConfig config;
        try {
            config = AppConfig.load(configFile);
        } catch (IllegalArgumentException e) {
            System.err.println("Ошибка в настройках: " + e.getMessage());
            return;
        }

        LinkService service = new LinkService(config, Clock.systemUTC());
        ExpiredLinkCleaner cleaner = new ExpiredLinkCleaner(service);
        cleaner.start();

        // Кодировка консоли, чтобы русский текст не превращался в знаки вопроса
        Charset charset = System.console() != null ? System.console().charset() : Charset.defaultCharset();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, charset));
        PrintStream out = new PrintStream(System.out, true, charset);
        new ConsoleApp(service, in, out, true).run();
        cleaner.stop();
    }
}
