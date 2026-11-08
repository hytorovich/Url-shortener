package ru.hytorovich.shortener.cli;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import ru.hytorovich.shortener.core.Link;
import ru.hytorovich.shortener.core.LinkService;
import ru.hytorovich.shortener.core.ShortenerException;

/** Консоль: читает команды, вызывает LinkService, печатает ответ. Логики здесь нет. */
public class ConsoleApp {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final LinkService service;
    private final String domain;
    private final BufferedReader in;
    private final PrintStream out;
    private final boolean openBrowser;

    private UUID user; // текущий пользователь; null, пока не создана первая ссылка

    /** openBrowser = false нужен для тестов, чтобы не открывать настоящий браузер. */
    public ConsoleApp(LinkService service, BufferedReader in, PrintStream out, boolean openBrowser) {
        this.service = service;
        this.domain = service.getConfig().domain();
        this.in = in;
        this.out = out;
        this.openBrowser = openBrowser;
    }

    public void run() throws IOException {
        out.println("Сервис коротких ссылок. Введите help, чтобы увидеть команды.");
        while (true) {
            printNotifications();
            out.print("> ");
            out.flush();
            String line = in.readLine();
            if (line == null || line.strip().equals("exit")) {
                out.println("До встречи.");
                return;
            }
            printNotifications(); // пока пользователь думал, фоновый поток мог удалить его ссылку
            execute(line.strip());
        }
    }

    void execute(String line) {
        if (line.isEmpty()) {
            return;
        }
        String[] args = line.split("\\s+");
        try {
            switch (args[0]) {
                case "help" -> printHelp();
                case "create" -> create(args);
                case "list" -> list();
                case "edit" -> edit(args);
                case "delete" -> delete(args);
                case "login" -> login(args);
                case "logout" -> logout();
                case "whoami" -> whoami();
                default -> {
                    if (isShortLink(args[0])) {
                        open(toCode(args[0]));
                    } else {
                        out.println("Неизвестная команда. Введите help, чтобы увидеть список команд.");
                    }
                }
            }
        } catch (ShortenerException e) {
            out.println(e.getMessage());
        } catch (NumberFormatException e) {
            out.println("Лимит должен быть целым числом.");
        }
    }

    private void create(String[] args) {
        if (args.length < 2 || args.length > 3) {
            out.println("Формат: create <url> [лимит]. Пример: create https://example.com 5");
            return;
        }
        Integer limit = args.length == 3 ? Integer.parseInt(args[2]) : null;
        // UUID выдаётся при первом создании ссылки
        UUID owner = user != null ? user : UUID.randomUUID();
        Link link = service.create(owner, args[1], limit);
        if (user == null) {
            user = owner;
            out.println("Ваш UUID: " + user + " (сохраните, чтобы вернуться командой login)");
        }
        out.println("Короткая ссылка: " + domain + "/" + link.getCode());
        out.println("Лимит переходов: " + link.getMaxClicks() + ", действует до " + TIME.format(link.getExpiresAt()));
    }

    private void open(String code) {
        Link link = service.open(code);
        out.println("Переход " + link.getClicks() + " из " + link.getMaxClicks() + ": " + link.getUrl());
        if (!openBrowser || !browse(link.getUrl())) {
            out.println("Откройте адрес вручную.");
        }
    }

    private void list() {
        List<Link> links = service.linksOf(requireUser());
        if (links.isEmpty()) {
            out.println("У вас нет ссылок.");
        }
        for (Link link : links) {
            String status = link.isLimitReached() ? "лимит исчерпан" : "активна";
            out.println(domain + "/" + link.getCode() + "  " + status + "  " + link.getClicks() + "/"
                    + link.getMaxClicks() + "  до " + TIME.format(link.getExpiresAt()) + "  " + link.getUrl());
        }
    }

    private void edit(String[] args) {
        if (args.length != 4 || !args[2].equals("limit")) {
            out.println("Формат: edit <код> limit <число>. Пример: edit aB3x9Q limit 20");
            return;
        }
        service.changeLimit(requireUser(), toCode(args[1]), Integer.parseInt(args[3]));
        out.println("Лимит изменён.");
    }

    private void delete(String[] args) {
        if (args.length != 2) {
            out.println("Формат: delete <код>");
            return;
        }
        service.delete(requireUser(), toCode(args[1]));
        out.println("Ссылка удалена.");
    }

    private void login(String[] args) {
        if (args.length != 2) {
            out.println("Формат: login <uuid>");
            return;
        }
        UUID parsed;
        try {
            parsed = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            out.println("Некорректный UUID. Пример: 3f2b8c1e-9a4d-4e7b-b6f1-2c8d0e5a7f93");
            return;
        }
        user = parsed;
        out.println("Вы вошли как " + user + ". Ссылок: " + service.linksOf(user).size());
    }

    private void whoami() {
        if (user == null) {
            out.println("Вы ещё не создали ни одной ссылки.");
        } else {
            out.println("Ваш UUID: " + user);
        }
    }

    private void logout() {
        user = null;
        out.println("Вы вышли. Следующий create создаст нового пользователя.");
    }

    private void printNotifications() {
        if (user != null) {
            for (String message : service.takeNotifications(user)) {
                out.println("[уведомление] " + message);
            }
        }
    }

    private void printHelp() {
        out.println("""
                create <url> [лимит]       сократить ссылку
                <короткая ссылка>          перейти, например %s/aB3x9Q
                list                       мои ссылки
                edit <код> limit <число>   изменить лимит (только владелец)
                delete <код>               удалить ссылку (только владелец)
                login <uuid> / logout      сменить пользователя
                whoami                     показать мой UUID
                exit                       выход
                Срок жизни ссылки: %d мин, лимит по умолчанию: %d, максимум: %d."""
                .formatted(domain, service.getConfig().ttl().toMinutes(),
                        service.getConfig().defaultLimit(), service.getConfig().maxLimit()));
    }

    private UUID requireUser() {
        if (user == null) {
            throw new ShortenerException("Сначала создайте ссылку командой create или войдите: login <uuid>");
        }
        return user;
    }

    /** "sh.rt/aB3x9Q" и "https://sh.rt/aB3x9Q" - короткие ссылки, "aB3x9Q" - нет. */
    private boolean isShortLink(String text) {
        return withoutScheme(text).startsWith(domain + "/");
    }

    /** Принимает и "sh.rt/aB3x9Q", и просто "aB3x9Q", возвращает "aB3x9Q". */
    private String toCode(String text) {
        String link = withoutScheme(text);
        if (link.startsWith(domain + "/")) {
            return link.substring(domain.length() + 1);
        }
        return link;
    }

    /** Убирает http:// или https:// в начале, если они есть. */
    private static String withoutScheme(String text) {
        if (text.startsWith("https://")) {
            return text.substring("https://".length());
        }
        if (text.startsWith("http://")) {
            return text.substring("http://".length());
        }
        return text;
    }

    private static boolean browse(String url) {
        try {
            if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
                return true;
            }
        } catch (Exception e) {
            // браузер не открылся: просто покажем адрес
        }
        return false;
    }
}
