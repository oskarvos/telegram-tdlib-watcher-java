package com.oleg.td;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MessageService {
    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    private final TdJsonClient td;
    private final AuthFlow authFlow;
    private final ChatResolver chatResolver;

    public MessageService(TdJsonClient td, AuthFlow authFlow, ChatResolver chatResolver) {
        this.td = td;
        this.authFlow = authFlow;
        this.chatResolver = chatResolver;
    }

    /** Отправить простой текст в чат, указанный chatRef: chat_id | @username | t.me/... */
    public ObjectNode sendText(String chatRef, String text) {
        if (!authFlow.isAuthorized()) {
            throw new IllegalStateException("Не авторизованы в Telegram — отправка невозможна");
        }
        long chatId = chatResolver.resolveOrJoin(chatRef);

        ObjectNode content = Utils.obj("inputMessageText");
        content.set("text", Utils.formattedText(text)); // {"@type":"formattedText","text":...}

        ObjectNode req = Utils.obj("sendMessage");
        req.put("chat_id", chatId);
        req.set("input_message_content", content);

        ObjectNode resp = td.requestWithFloodWaitSyncLimited(req, 60, TdJsonClient.Channel.MAIN);
        String type = resp.path("@type").asText();
        if ("message".equals(type)) {
            long messageId = resp.path("id").asLong();
            log.info("✅ Сообщение отправлено: chat_id={}, message_id={}", chatId, messageId);
        } else if ("error".equals(type)) {
            int code = resp.path("code").asInt();
            String msg = resp.path("message").asText();
            log.error("❌ Ошибка отправки: {} {}", code, msg);
        } else {
            log.warn("Ответ TDLib на sendMessage: {}", type);
        }
        return resp;
    }
}
