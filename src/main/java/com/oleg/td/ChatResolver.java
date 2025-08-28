package com.oleg.td;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ChatResolver {
    private static final Logger log = LoggerFactory.getLogger(ChatResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TdJsonClient client;

    public ChatResolver(TdJsonClient client) {
        this.client = client;
    }

    // t.me/+abcdEFG,   t.me/joinchat/abcdEFG
    private static final Pattern P_INVITE_PLUS = Pattern.compile("^/?\\+([A-Za-z0-9_-]{4,})/?$");
    private static final Pattern P_INVITE_JOINCHAT = Pattern.compile("^/?joinchat/([A-Za-z0-9_-]{4,})/?$");
    // t.me/c/1234567890/42  -> приватный суперчат (id без префикса 100…)
    private static final Pattern P_PRIVATE_C = Pattern.compile("^/?c/(\\d+)(?:/.*)?$");
    // t.me/username
    private static final Pattern P_USERNAME = Pattern.compile("^/?([A-Za-z0-9_]{5,})/?$");

    /**
     * Принимает:
     * - числовой chat_id (включая -100…)
     * - t.me/<username>
     * - t.me/+<inviteHash> или t.me/joinchat/<inviteHash>
     * - t.me/c/<internalId>/...
     * Возвращает chat_id.
     */
    public long resolveOrJoin(String ref) {
        String s = ref.trim();

        // 1) уже число?
        try {
            if (s.startsWith("-") || Character.isDigit(s.charAt(0))) {
                return Long.parseLong(s);
            }
        } catch (Exception ignore) {
        }

        // 2) URL t.me/...
        URI uri;
        try {
            uri = URI.create(s);
        } catch (Exception e) {
            // невалидный URI: пробуем как «голый» юзернейм
            return resolveUsernameOrThrow(s);
        }
        if (uri.getHost() == null) {
            // возможно пришло "groop123" без схемы
            return resolveUsernameOrThrow(s);
        }

        if (!uri.getHost().equalsIgnoreCase("t.me") &&
                !uri.getHost().equalsIgnoreCase("telegram.me")) {
            throw new IllegalArgumentException("Неизвестный хост ссылки: " + uri.getHost());
        }

        String path = URLDecoder.decode(uri.getPath() == null ? "" : uri.getPath(), StandardCharsets.UTF_8);
        path = path.replaceAll("^/+", ""); // убрать ведущие /

        // --- инвайт через +hash
        Matcher mPlus = P_INVITE_PLUS.matcher(path);
        if (mPlus.matches()) {
            String inviteLink = buildOriginalLink(uri, "+" + mPlus.group(1));
            return resolveByInvite(inviteLink);
        }

        // --- инвайт через joinchat/hash
        Matcher mJoin = P_INVITE_JOINCHAT.matcher(path);
        if (mJoin.matches()) {
            String inviteLink = buildOriginalLink(uri, "joinchat/" + mJoin.group(1));
            return resolveByInvite(inviteLink);
        }

        // --- приватная ссылка t.me/c/<id>/...
        Matcher mC = P_PRIVATE_C.matcher(path);
        if (mC.matches()) {
            long internal = Long.parseLong(mC.group(1));
            // формула: chatId = -1000000000000L - internal
            long chatId = -1000000000000L - internal;
            log.info("ChatResolver: распознан приватный c-link: internal={}, chatId={}", internal, chatId);
            return chatId;
        }

        // --- обычный username
        Matcher mUser = P_USERNAME.matcher(path);
        if (mUser.matches()) {
            return resolveUsernameOrThrow(mUser.group(1));
        }

        throw new IllegalArgumentException("Не удалось распознать ссылку: " + ref);
    }

    /**
     * Собираем «чистую» invite-ссылку на основе исходного URI (схема/хост сохраняются).
     */
    private static String buildOriginalLink(URI original, String canonicalPath) {
        String scheme = (original.getScheme() == null ? "https" : original.getScheme());
        String host = (original.getHost() == null ? "t.me" : original.getHost());
        return scheme + "://" + host + "/" + canonicalPath;
    }

    /**
     * Разруливаем username через searchPublicChat.
     */
    private long resolveUsernameOrThrow(String usernameRaw) {
        String username = usernameRaw.replaceAll("^@+", "");
        ObjectNode req = MAPPER.createObjectNode();
        req.put("@type", "searchPublicChat");
        req.put("username", username);

        ObjectNode resp = client.requestWithFloodWaitSyncLimited(req, 60, TdJsonClient.Channel.MAIN);
        String t = resp.path("@type").asText();
        if ("chat".equals(t)) {
            long id = resp.path("id").asLong();
            log.info("ChatResolver: {} -> chat_id={}", username, id);
            return id;
        }
        throw new IllegalArgumentException("Пользователь/канал @" + username + " не найден (ответ: " + t + ")");
    }

    /**
     * Алгоритм по инвайт-ссылке:
     * 1) checkChatInviteLink(invite_link) — если вернулся chat_id (вы уже участник), берём его.
     * 2) иначе importChatInviteLink(invite_link) — присоединяемся и берём chat.id.
     * 3) если TDLib вернул error — кидаем понятную причину наверх.
     */
    private long resolveByInvite(String inviteLink) {
        // шаг 1: проверка инвайта
        ObjectNode check = MAPPER.createObjectNode();
        check.put("@type", "checkChatInviteLink");
        check.put("invite_link", inviteLink);

        ObjectNode checkResp = client.requestWithFloodWaitSyncLimited(check, 60, TdJsonClient.Channel.MAIN);
        String ct = checkResp.path("@type").asText();
        if ("chatInviteLinkInfo".equals(ct)) {
            long chatId = checkResp.path("chat_id").asLong(0);
            if (chatId != 0) {
                log.info("ChatResolver: checkChatInviteLink вернул chat_id={}, ссылка={}", chatId, inviteLink);
                return chatId;
            }
            // chat_id=0 — не участник, пробуем импорт
        } else if ("error".equals(ct)) {
            // иногда check падает, но import проходит — не останавливаемся
            log.warn("ChatResolver: checkChatInviteLink error: {} {}", checkResp.path("code").asInt(), checkResp.path("message").asText());
        }

        // шаг 2: импорт
        ObjectNode imp = MAPPER.createObjectNode();
        imp.put("@type", "importChatInviteLink");
        imp.put("invite_link", inviteLink);

        ObjectNode impResp = client.requestWithFloodWaitSyncLimited(imp, 60, TdJsonClient.Channel.MAIN);
        String it = impResp.path("@type").asText();

        if ("chat".equals(it)) {
            long id = impResp.path("id").asLong();
            log.info("ChatResolver: importChatInviteLink -> chat_id={} (ссылка={})", id, inviteLink);
            return id;
        }

        if ("error".equals(it)) {
            int code = impResp.path("code").asInt();
            String msg = impResp.path("message").asText();
            // популярные случаи: INVITE_HASH_EXPIRED, USER_ALREADY_PARTICIPANT, INVITE_REQUEST_SENT
            String lower = msg == null ? "" : msg.toLowerCase();
            // если уже участник — попробуем ещё раз через check (иногда именно после error появляется chat_id)
            if (lower.contains("already") || lower.contains("participant")) {
                ObjectNode recheck = MAPPER.createObjectNode();
                recheck.put("@type", "checkChatInviteLink");
                recheck.put("invite_link", inviteLink);
                ObjectNode rc = client.requestWithFloodWaitSyncLimited(recheck, 60, TdJsonClient.Channel.MAIN);
                if ("chatInviteLinkInfo".equals(rc.path("@type").asText())) {
                    long chatId = rc.path("chat_id").asLong(0);
                    if (chatId != 0) {
                        log.info("ChatResolver: повторный check дал chat_id={} после 'already participant'", chatId);
                        return chatId;
                    }
                }
            }
            throw new IllegalArgumentException("Не удалось присоединиться по инвайт-ссылке: " + inviteLink +
                    " (код " + code + ", сообщение: " + msg + ")");
        }

        throw new IllegalArgumentException("Не удалось присоединиться по инвайт-ссылке: " + inviteLink +
                " (ответ: " + it + ")");
    }
}
