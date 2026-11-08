package ru.hytorovich.shortener.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import ru.hytorovich.shortener.config.AppConfig;
import ru.hytorovich.shortener.core.LinkService;

/** Сценарии целиком: подаём команды на вход и проверяем, что напечатано. */
class ConsoleAppTest {

    private final LinkService service = new LinkService(
            new AppConfig(Duration.ofHours(24), 10, 1000, 6, "sh.rt", Duration.ofMinutes(1)), Clock.systemUTC());

    private String run(String... commands) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(new StringReader(String.join("\n", commands)));
        new ConsoleApp(service, in, out, false).run();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /** Код из строки "Короткая ссылка: sh.rt/XXXXXX". */
    private static String code(String output) {
        int start = output.lastIndexOf("Короткая ссылка: sh.rt/") + "Короткая ссылка: sh.rt/".length();
        return output.substring(start, start + 6);
    }

    /** UUID из строки "Ваш UUID: ...". */
    private static String uuid(String output) {
        int start = output.indexOf("Ваш UUID: ") + "Ваш UUID: ".length();
        return output.substring(start, start + 36);
    }

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }

    @Test
    void createGivesUuidOnceAndShortLinks() throws IOException {
        String out = run("create https://example.com", "create https://example.org 5");

        assertEquals(1, count(out, "Ваш UUID"));
        assertEquals(2, count(out, "Короткая ссылка: sh.rt/"));
        assertTrue(out.contains("Лимит переходов: 5"));
    }

    @Test
    void exhaustedLinkIsBlockedAndOwnerIsNotified() throws IOException {
        String created = run("create https://example.com 1");

        String out = run("login " + uuid(created), "sh.rt/" + code(created), "https://sh.rt/" + code(created));

        assertTrue(out.contains("Переход 1 из 1: https://example.com"));
        assertTrue(out.contains("Лимит переходов по ссылке " + code(created) + " исчерпан, ссылка недоступна"));
        assertTrue(out.contains("[уведомление] Лимит переходов по ссылке " + code(created)));
    }

    @Test
    void strangerCannotEditOrDeleteLink() throws IOException {
        String code = code(run("create https://example.com"));

        String out = run("create https://other.com", "edit " + code + " limit 50", "delete " + code);

        assertEquals(2, count(out, "Нет прав"));
    }

    @Test
    void ownerCanRaiseLimitAndDelete() throws IOException {
        String created = run("create https://example.com 1");
        String code = code(created);

        String out = run("login " + uuid(created), "sh.rt/" + code, "edit " + code + " limit 2",
                "sh.rt/" + code, "delete sh.rt/" + code, "sh.rt/" + code);

        assertTrue(out.contains("Лимит изменён."));
        assertTrue(out.contains("Переход 2 из 2"));
        assertTrue(out.contains("Ссылка удалена."));
        assertTrue(out.contains("Ссылка " + code + " не найдена."));
    }

    @Test
    void badInputGivesMessagesAndProgramContinues() throws IOException {
        String out = run("foo", "list", "create", "create notaurl", "create https://x.com abc",
                "edit x", "login 123", "exit", "list");

        assertTrue(out.contains("Неизвестная команда"));
        assertTrue(out.contains("Сначала создайте ссылку"));
        assertTrue(out.contains("Формат: create <url> [лимит]"));
        assertTrue(out.contains("Нужен адрес вида https://example.com"));
        assertTrue(out.contains("Лимит должен быть целым числом."));
        assertTrue(out.contains("Формат: edit <код> limit <число>"));
        assertTrue(out.contains("Некорректный UUID."));
        assertTrue(out.strip().endsWith("До встречи."), "после exit команды не выполняются");
    }

    @Test
    void helpShowsCommandsAndRules() throws IOException {
        String out = run("help");

        assertTrue(out.contains("create <url> [лимит]"));
        assertTrue(out.contains("Срок жизни ссылки: 1440 мин"));
    }
}
