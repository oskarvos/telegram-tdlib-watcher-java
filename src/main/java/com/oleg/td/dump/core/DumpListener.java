package com.oleg.td.dump.core;

/**
 * Колбэки прогресса и сохранений по типам.
 * Все методы по умолчанию no-op, чтобы можно было передавать частичные реализации.
 */
public interface DumpListener {
    default void onProgress() {
    }

    default void onSavedMessage() {
    }

    default void onSavedPhoto() {
    }

    default void onSavedVideo() {
    }

    default void onSavedAudio() {
    }

    /**
     * @param ext расширение документа (lowercase, без точки) или "unknown"
     */
    default void onSavedDocument(String ext) {
    }

    /**
     * @param count сколько ссылок сохранено этой итерацией
     */
    default void onSavedLinks(int count) {
    }
}
