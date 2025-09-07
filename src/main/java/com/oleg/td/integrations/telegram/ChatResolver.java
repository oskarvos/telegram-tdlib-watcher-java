package com.oleg.td.integrations.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oleg.td.integrations.tdlibs.TdJsonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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

    /**
     * Получает название чата по его ID
     */
    public String getChatTitle(long chatId) {
        try {
            ObjectNode req = MAPPER.createObjectNode();
            req.put("@type", "getChat");
            req.put("chat_id", chatId);

            ObjectNode resp = client.requestWithFloodWaitSyncLimited(req, 10, TdJsonClient.Channel.MAIN);
            if ("chat".equals(resp.path("@type").asText())) {
                return resp.path("title").asText();
            }
        } catch (Exception e) {
            log.warn("Не удалось получить название чата {}: {}", chatId, e.getMessage());
        }
        return null;
    }

    // t.me/+abcdEFG,   t.me/joinchat/abcdEFG
    private static final Pattern P_INVITE_PLUS = Pattern.compile("^/?\\+([A-Za-z0-9_-]{4,})/?$");
    private static final Pattern P_INVITE_JOINCHAT = Pattern.compile("^/?joinchat/([A-Za-z0-9_-]{4,})/?$");
    // t.me/c/1234567890/42  -> приватный суперчат (id без префикса 100…)
    private static final Pattern P_PRIVATE_C = Pattern.compile("^/?c/(\\d+)(?:/.*)?$");
    // t.me/username
    private static final Pattern P_USERNAME = Pattern.compile("^/?([A-Za-z0-9_]{5,})/?$");

    /**
     * Универсальное разрешение ввода пользователя:
     * - "Id: 7184569562" или просто "7184569562" (chat_id или user_id)
     * - "First: Sparky" (а также "name: ...", "имя: ...")
     * - "@username"
     * - t.me/* (включая joinchat/+hash/c/..)
     * - "username" (без @)
     */
    public long resolveFlexible(String ref) {
        String s = ref == null ? "" : ref.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("Пустая ссылка/идентификатор");

        String lower = s.toLowerCase(Locale.ROOT);

        // First: / Name: / Имя:
        if (lower.startsWith("first:") || lower.startsWith("name:") || lower.startsWith("имя:")) {
            String q = s.substring(s.indexOf(':') + 1).trim();
            if (q.isEmpty()) throw new IllegalArgumentException("Не указано имя после 'First:'");
            return resolveByFirstName(q);
        }

        // Id: <number>
        if (lower.startsWith("id:")) {
            String num = s.substring(s.indexOf(':') + 1).trim();
            return resolveByNumeric(num);
        }

        // @username
        if (s.startsWith("@")) {
            return resolveUsernameOrThrow(s.substring(1));
        }

        // Голое число -> пробуем как chat_id, затем как user_id
        if (s.startsWith("-") || Character.isDigit(s.charAt(0))) {
            return resolveByNumeric(s);
        }

        // URL t.me / telegram.me
        try {
            URI uri = URI.create(s);
            if (uri.getHost() != null &&
                    (uri.getHost().equalsIgnoreCase("t.me") || uri.getHost().equalsIgnoreCase("telegram.me"))) {
                return resolveOrJoin(s); // существующая логика
            }
        } catch (Exception ignore) {
            // упадем в username
        }

        // иначе — пробуем как username без @
        return resolveUsernameOrThrow(s);
    }

    /**
     * Числовой ввод:
     * - если это валидный chat_id — вернём его;
     * - если это user_id — создадим приватный чат через createPrivateChat и вернём chat.id.
     */
    private long resolveByNumeric(String raw) {
        long n;
        try {
            n = Long.parseLong(raw.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Некорректный числовой идентификатор: " + raw);
        }

        // Попытка получить чат напрямую
        ObjectNode get = MAPPER.createObjectNode();
        get.put("@type", "getChat");
        get.put("chat_id", n);
        ObjectNode getResp = client.requestWithFloodWaitSyncLimited(get, 60, TdJsonClient.Channel.MAIN);
        if ("chat".equals(getResp.path("@type").asText())) {
            log.info("ChatResolver: numeric '{}' распознан как chat_id={}", raw, n);
            return n;
        }

        // Если не chat — пробуем как user_id создать приватный чат
        ObjectNode cp = MAPPER.createObjectNode();
        cp.put("@type", "createPrivateChat");
        cp.put("user_id", n);
        cp.put("force", true);

        ObjectNode cpResp = client.requestWithFloodWaitSyncLimited(cp, 60, TdJsonClient.Channel.MAIN);
        if ("chat".equals(cpResp.path("@type").asText())) {
            long chatId = cpResp.path("id").asLong();
            log.info("ChatResolver: '{}' распознан как user_id, создан приватный chat_id={}", raw, chatId);
            return chatId;
        }

        String typ = cpResp.path("@type").asText();
        String msg = cpResp.path("message").asText();
        throw new IllegalArgumentException("Не удалось распознать '" + raw + "' как chat_id или user_id (ответ: " + typ + (msg == null ? "" : ", " + msg) + ")");
    }

    /**
     * Поиск по имени: сначала ищем пользователей (searchUsers), потом публичные чаты (searchPublicChats).
     * При нахождении пользователя — создаём приватный чат и проверяем title на включение строки поиска.
     */
    private long resolveByFirstName(String nameQuery) {
        String q = nameQuery.trim();
        if (q.isEmpty()) throw new IllegalArgumentException("Пустое имя для поиска");

        // 1) Пользователи
        ObjectNode su = MAPPER.createObjectNode();
        su.put("@type", "searchUsers");
        su.put("query", q);
        su.put("limit", 20);
        ObjectNode suResp = client.requestWithFloodWaitSyncLimited(su, 60, TdJsonClient.Channel.MAIN);
        if ("users".equals(suResp.path("@type").asText()) && suResp.path("user_ids").isArray()) {
            var arr = suResp.path("user_ids");
            for (var idNode : arr) {
                long uid = idNode.asLong(0);
                if (uid == 0) continue;

                ObjectNode cp = MAPPER.createObjectNode();
                cp.put("@type", "createPrivateChat");
                cp.put("user_id", uid);
                cp.put("force", true);
                ObjectNode cpResp = client.requestWithFloodWaitSyncLimited(cp, 60, TdJsonClient.Channel.MAIN);
                if ("chat".equals(cpResp.path("@type").asText())) {
                    String title = cpResp.path("title").asText("");
                    if (!title.isBlank() && title.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))) {
                        long chatId = cpResp.path("id").asLong();
                        log.info("ChatResolver: First:'{}' -> приватный чат '{}' ({})", q, title, chatId);
                        return chatId;
                    }
                }
            }
        }

        // 2) Публичные чаты/каналы
        ObjectNode spc = MAPPER.createObjectNode();
        spc.put("@type", "searchPublicChats");
        spc.put("query", q);
        ObjectNode spcResp = client.requestWithFloodWaitSyncLimited(spc, 60, TdJsonClient.Channel.MAIN);
        if ("chats".equals(spcResp.path("@type").asText()) && spcResp.path("chat_ids").isArray()) {
            var ids = spcResp.path("chat_ids");
            for (var cid : ids) {
                long chatId = cid.asLong(0);
                if (chatId == 0) continue;

                ObjectNode gc = MAPPER.createObjectNode();
                gc.put("@type", "getChat");
                gc.put("chat_id", chatId);
                ObjectNode gcResp = client.requestWithFloodWaitSyncLimited(gc, 60, TdJsonClient.Channel.MAIN);
                if ("chat".equals(gcResp.path("@type").asText())) {
                    String title = gcResp.path("title").asText("");
                    if (!title.isBlank() && title.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))) {
                        log.info("ChatResolver: First:'{}' -> публичный чат '{}' ({})", q, title, chatId);
                        return chatId;
                    }
                }
            }
        }

        throw new IllegalArgumentException("По запросу имени '" + q + "' не найден подходящий чат/пользователь");
    }

    /* ==================== ИМЕЮЩАЯСЯ ЛОГИКА (без изменений) ==================== */

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
    long resolveUsernameOrThrow(String usernameRaw) {
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
            String lower = msg == null ? "" : msg.toLowerCase(Locale.ROOT);
            // если уже участник — повторный check может вернуть chat_id
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
