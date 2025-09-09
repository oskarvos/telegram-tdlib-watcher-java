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
    private static final Pattern P_INVITE_PLUS = Pattern.compile("^/?\\+([A-Za-z0-9_-]{4,})/?$");
    private static final Pattern P_INVITE_JOINCHAT = Pattern.compile("^/?joinchat/([A-Za-z0-9_-]{4,})/?$");
    private static final Pattern P_PRIVATE_C = Pattern.compile("^/?c/(\\d+)(?:/.*)?$");
    private static final Pattern P_USERNAME = Pattern.compile("^/?([A-Za-z0-9_]{5,})/?$");

    private final TdJsonClient client;

    public ChatResolver(TdJsonClient client) {
        this.client = client;
    }

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

    public long resolveFlexible(String ref) {
        String s = ref == null ? "" : ref.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("Пустая ссылка/идентификатор");

        String lower = s.toLowerCase(Locale.ROOT);

        if (lower.startsWith("first:") || lower.startsWith("name:") || lower.startsWith("имя:")) {
            String q = s.substring(s.indexOf(':') + 1).trim();
            if (q.isEmpty()) throw new IllegalArgumentException("Не указано имя после 'First:'");
            return resolveByFirstName(q);
        }

        if (lower.startsWith("id:")) {
            String num = s.substring(s.indexOf(':') + 1).trim();
            return resolveByNumeric(num);
        }

        if (s.startsWith("@")) {
            return resolveUsernameOrThrow(s.substring(1));
        }

        if (s.startsWith("-") || Character.isDigit(s.charAt(0))) {
            return resolveByNumeric(s);
        }

        try {
            URI uri = URI.create(s);
            if (uri.getHost() != null &&
                    (uri.getHost().equalsIgnoreCase("t.me") || uri.getHost().equalsIgnoreCase("telegram.me"))) {
                return resolveOrJoin(s);
            }
        } catch (Exception ignore) {}

        return resolveUsernameOrThrow(s);
    }

    private long resolveByNumeric(String raw) {
        long n;
        try {
            n = Long.parseLong(raw.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Некорректный числовой идентификатор: " + raw);
        }

        ObjectNode get = MAPPER.createObjectNode();
        get.put("@type", "getChat");
        get.put("chat_id", n);
        ObjectNode getResp = client.requestWithFloodWaitSyncLimited(get, 60, TdJsonClient.Channel.MAIN);
        if ("chat".equals(getResp.path("@type").asText())) {
            log.info("ChatResolver: numeric '{}' распознан как chat_id={}", raw, n);
            return n;
        }

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

    private long resolveByFirstName(String nameQuery) {
        String q = nameQuery.trim();
        if (q.isEmpty()) throw new IllegalArgumentException("Пустое имя для поиска");

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

    public long resolveOrJoin(String ref) {
        String s = ref.trim();

        try {
            if (s.startsWith("-") || Character.isDigit(s.charAt(0))) {
                return Long.parseLong(s);
            }
        } catch (Exception ignore) {}

        URI uri;
        try {
            uri = URI.create(s);
        } catch (Exception e) {
            return resolveUsernameOrThrow(s);
        }
        if (uri.getHost() == null) {
            return resolveUsernameOrThrow(s);
        }

        if (!uri.getHost().equalsIgnoreCase("t.me") &&
                !uri.getHost().equalsIgnoreCase("telegram.me")) {
            throw new IllegalArgumentException("Неизвестный хост ссылки: " + uri.getHost());
        }

        String path = URLDecoder.decode(uri.getPath() == null ? "" : uri.getPath(), StandardCharsets.UTF_8);
        path = path.replaceAll("^/+", "");

        Matcher mPlus = P_INVITE_PLUS.matcher(path);
        if (mPlus.matches()) {
            String inviteLink = buildOriginalLink(uri, "+" + mPlus.group(1));
            return resolveByInvite(inviteLink);
        }

        Matcher mJoin = P_INVITE_JOINCHAT.matcher(path);
        if (mJoin.matches()) {
            String inviteLink = buildOriginalLink(uri, "joinchat/" + mJoin.group(1));
            return resolveByInvite(inviteLink);
        }

        Matcher mC = P_PRIVATE_C.matcher(path);
        if (mC.matches()) {
            long internal = Long.parseLong(mC.group(1));
            long chatId = -1000000000000L - internal;
            log.info("ChatResolver: распознан приватный c-link: internal={}, chatId={}", internal, chatId);
            return chatId;
        }

        Matcher mUser = P_USERNAME.matcher(path);
        if (mUser.matches()) {
            return resolveUsernameOrThrow(mUser.group(1));
        }

        throw new IllegalArgumentException("Не удалось распознать ссылку: " + ref);
    }

    private static String buildOriginalLink(URI original, String canonicalPath) {
        String scheme = (original.getScheme() == null ? "https" : original.getScheme());
        String host = (original.getHost() == null ? "t.me" : original.getHost());
        return scheme + "://" + host + "/" + canonicalPath;
    }

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

    private long resolveByInvite(String inviteLink) {
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
        } else if ("error".equals(ct)) {
            log.warn("ChatResolver: checkChatInviteLink error: {} {}", checkResp.path("code").asInt(), checkResp.path("message").asText());
        }

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