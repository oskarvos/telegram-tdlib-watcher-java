package com.oleg.td;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ChatResolver {
    private static final Pattern TME_USERNAME = Pattern.compile("^(?:https?://)?t\\.me/(@?([A-Za-z0-9_]{5,}))/?$");
    private static final Pattern TME_INVITE = Pattern.compile("^(?:https?://)?t\\.me/(?:joinchat/|\\+)([A-Za-z0-9_-]{10,})$");

    private final TdJsonClient td;

    public ChatResolver(TdJsonClient td) {
        this.td = td;
    }

    /** Принимает: числовой chat_id | @username | t.me/username | t.me/+invite */
    public long resolveOrJoin(String ref) {
        String s = ref.trim();

        // 1) Явно chat_id (число)
        try {
            if (s.matches("^-?\\d+$")) {
                return Long.parseLong(s);
            }
        } catch (NumberFormatException ignored) {}

        // 2) @username или t.me/username → searchPublicChat
        String username = null;
        if (s.startsWith("@")) {
            username = s.substring(1);
        } else {
            Matcher mU = TME_USERNAME.matcher(s);
            if (mU.matches()) {
                username = mU.group(2) != null ? mU.group(2) : mU.group(1).replaceFirst("^@", "");
            }
        }
        if (username != null && !username.isBlank()) {
            ObjectNode req = Utils.obj("searchPublicChat");
            req.put("username", username);
            ObjectNode resp = td.requestWithFloodWaitSyncLimited(req, 60, TdJsonClient.Channel.MAIN);
            if ("chat".equals(resp.path("@type").asText())) {
                return resp.path("id").asLong();
            }
            throw new IllegalArgumentException("Не удалось найти публичный чат @"+username+" (ответ: "+resp.path("@type").asText()+")");
        }

        // 3) t.me/+invite → joinChatByInviteLink
        Matcher mI = TME_INVITE.matcher(s);
        if (mI.matches()) {
            ObjectNode join = Utils.obj("joinChatByInviteLink");
            join.put("invite_link", s);
            ObjectNode resp = td.requestWithFloodWaitSyncLimited(join, 60, TdJsonClient.Channel.MAIN);
            if ("chat".equals(resp.path("@type").asText())) {
                return resp.path("id").asLong();
            }
            // Некоторые инвайты возвращают ok/сервисные ответы — попробуем сразу вытянуть info из ссылки (TDLib сам кэширует)
            throw new IllegalArgumentException("Не удалось присоединиться по инвайт-ссылке: "+s+" (ответ: "+resp.path("@type").asText()+")");
        }

        throw new IllegalArgumentException("Неподдерживаемый идентификатор чата: "+ref+" (ожидаю chat_id | @username | t.me/...)");
    }
}
