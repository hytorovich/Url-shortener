package ru.hytorovich.shortener.core;

/** Ошибка, которую нужно показать пользователю: ссылки нет, лимит исчерпан, нет прав и т. п. */
public class ShortenerException extends RuntimeException {

    public ShortenerException(String message) {
        super(message);
    }
}
