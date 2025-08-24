package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.function.LongConsumer;

/**
 * Ищет совпадения по regex-паттернам в новых текстовых сообщениях.
 */
public class MessageMatcher {

    public static class PatternEntry {
        public final String name;
        public final Pattern pattern;

        public PatternEntry(String name, Pattern pattern) {
            this.name = name;
            this.pattern = pattern;
        }
    }

    private final List<PatternEntry> patterns;
    private final ChatTitleRegistry titles;
    private final AtomicReference<Set<Long>> allowedChats = new AtomicReference<>(null);
    private LongConsumer onMatchListener = null;

    public void setOnMatchListener(LongConsumer listener) {
        this.onMatchListener = listener;
    }

    public MessageMatcher(List<PatternEntry> patterns, ChatTitleRegistry titles) {
        this.patterns = patterns;
        this.titles = titles;
    }

    public void setAllowedChats(Set<Long> chatIds) {
        this.allowedChats.set(chatIds);
    }

    public void onUpdateNewMessage(JsonNode u) {
        if (!"updateNewMessage".equals(u.path("@type").asText())) return;
        JsonNode m = u.path("message");
        long chatId = m.path("chat_id").asLong();

        // +++ ФИЛЬТР ВАЖНО: пропускаем только разрешённые чаты
        Set<Long> allow = allowedChats.get();
        if (allow != null && !allow.contains(chatId)) return;

        long mid = m.path("id").asLong();
        JsonNode c = m.path("content");
        if (!"messageText".equals(c.path("@type").asText())) return;
        String text = c.path("text").path("text").asText("");
        if (text.isEmpty()) return;

        var hits = patterns.stream()
                .filter(p -> p.pattern.matcher(text).find())
                .map(p -> p.name)
                .collect(java.util.stream.Collectors.toList());

        if (!hits.isEmpty()) {
            String title = titles.titleOf(chatId);
            System.out.printf("[%s] Match in chat %s (id=%d), msg %d, patterns=%s:%n%s%n----%n",
                    java.time.Instant.now(), title, chatId, mid, hits, text);
            if (onMatchListener != null) onMatchListener.accept(chatId);
        }

    }
}