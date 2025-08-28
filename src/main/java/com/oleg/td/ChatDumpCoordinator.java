package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ChatDumpCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ChatDumpCoordinator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final TdJsonClient client;
    private final ChatResolver resolver;
    private final DatabaseManager databaseManager;
    private volatile boolean stopRequested = false;

    public ChatDumpCoordinator(TdJsonClient client, ChatResolver resolver, DatabaseManager databaseManager) {
        this.client = client;
        this.resolver = resolver;
        this.databaseManager = databaseManager;
    }

    public void dumpChats(DumpRequest request, Runnable progressCallback) {
        stopRequested = false;
        for (String chat : request.getChats()) {
            if (stopRequested) {
                log.info("Dump cancelled");
                break;
            }
            long chatId = resolver.resolveOrJoin(chat);
            databaseManager.prepareSchema(chatId);

            long fromMessageId = 0;
            while (!stopRequested) {
                ObjectNode req = MAPPER.createObjectNode();
                req.put("@type", "getChatHistory");
                req.put("chat_id", chatId);
                req.put("from_message_id", fromMessageId);
                req.put("offset", 0);
                req.put("limit", 100);

                ObjectNode resp = client.requestWithFloodWaitSyncLimited(req, 60, TdJsonClient.Channel.MAIN);
                ArrayNode messages = (ArrayNode) resp.path("messages");
                if (messages == null || messages.size() == 0) {
                    break;
                }

                for (JsonNode msg : messages) {
                    long messageId = msg.path("id").asLong();
                    String content = msg.path("content").toString();
                    databaseManager.saveMessage(chatId, messageId, content);
                    if (progressCallback != null) {
                        progressCallback.run();
                    }
                    if (stopRequested) {
                        break;
                    }
                }

                fromMessageId = messages.get(messages.size() - 1).path("id").asLong();
            }
        }
    }

    public void stop() {
        stopRequested = true;
    }
}
