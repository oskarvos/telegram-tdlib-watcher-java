package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.oleg.td.Utils.obj;

public class ChatDumpService {
    private final TdJsonClient client;
    private final Database db;
    private final Path filesDir;

    private final ConcurrentHashMap<Long, Object> locks = new ConcurrentHashMap<>();
    private final ExecutorService exec = Executors.newCachedThreadPool();

    private static final Pattern LINK_RE = Pattern.compile("(https?://\\S+)", Pattern.CASE_INSENSITIVE);

    public ChatDumpService(TdJsonClient client, Database db, Path filesDir) {
        this.client = client;
        this.db = db;
        this.filesDir = filesDir;
    }

    public void triggerDump(long chatId) {
        Object lock = locks.computeIfAbsent(chatId, k -> new Object());
        // не пускаем конкурирующие дампы одного чата
        exec.submit(() -> {
            synchronized (lock) {
                try { dumpOrSync(chatId); }
                catch (Exception e) { System.err.println("Dump error chat " + chatId + ": " + e); }
            }
        });
    }

    private JsonNode rq(ObjectNode o) throws Exception {
        return client.request(o).get(30, TimeUnit.SECONDS);
    }

    private void dumpOrSync(long chatId) throws Exception {
        System.out.printf("[%s] Dump started for chat %d%n", Instant.now(), chatId);

        // --- chat info
        JsonNode chat = rq(obj("getChat").put("chat_id", chatId));
        String title = chat.path("title").asText("");
        String chatType = chat.path("type").path("@type").asText("");
        String username = null;
        Long supergroupId = null;

        if ("chatTypeSupergroup".equals(chatType)) {
            supergroupId = chat.path("type").path("supergroup_id").asLong();
            // enrich supergroup info
            JsonNode sg = rq(obj("getSupergroup").put("supergroup_id", supergroupId));
            username = sg.path("usernames").path("editable_username").asText(null);
            // full info
            rq(obj("getSupergroupFullInfo").put("supergroup_id", supergroupId));
        } else if ("chatTypeBasicGroup".equals(chatType)) {
            // no usernames
        } else if ("chatTypePrivate".equals(chatType)) {
            // skip
        }

        db.upsertChat(chatId, title, username, chatType);

        // --- members (best-effort for supergroup)
        if (supergroupId != null) {
            int limit = 200;
            int offset = 0;
            while (true) {
                ObjectNode q = obj("getSupergroupMembers");
                q.put("supergroup_id", supergroupId);
                q.put("offset", offset);
                q.put("limit", limit);
                q.set("filter", obj("supergroupMembersFilterRecent")); // recent members
                JsonNode res = rq(q);
                JsonNode members = res.path("members");
                if (!members.isArray() || members.size() == 0) break;
                for (JsonNode mem : members) {
                    long userId = mem.path("member_id").path("@type").asText("").equals("messageSenderUser")
                            ? mem.path("member_id").path("user_id").asLong()
                            : 0;
                    String status = mem.path("status").path("@type").asText("");
                    db.upsertChatMember(chatId, userId, status, 0);
                    if (userId != 0) pullAndUpsertUser(userId);
                }
                offset += members.size();
                if (members.size() < limit) break;
            }
        }

        // --- messages
        long maxSaved = db.getMaxMessageId(chatId);
        long fromMessageId = 0;
        long lastOldest = -1;
        long maxSeenThisRun = maxSaved;

        final int PAGE = 200;
        boolean stop = false;

        while (!stop) {
            ObjectNode h = obj("getChatHistory");
            h.put("chat_id", chatId);
            h.put("from_message_id", fromMessageId);
            h.put("offset", 0);
            h.put("limit", PAGE);
            h.put("only_local", false);
            JsonNode res = rq(h);
            JsonNode msgs = res.path("messages");
            if (!msgs.isArray() || msgs.size() == 0) break;

            long oldest = Long.MAX_VALUE;

            for (JsonNode msg : msgs) {
                long mid = msg.path("id").asLong();
                if (mid <= maxSaved) { stop = true; break; }

                oldest = Math.min(oldest, mid);
                maxSeenThisRun = Math.max(maxSeenThisRun, mid);

                // author
                long authorId = 0;
                JsonNode snd = msg.path("sender_id");
                String senderType = snd.path("@type").asText("");
                if ("messageSenderUser".equals(senderType)) authorId = snd.path("user_id").asLong();
                else if ("messageSenderChat".equals(senderType)) authorId = -snd.path("chat_id").asLong(); // mark chat-sender as negative ID

                if (authorId > 0) pullAndUpsertUser(authorId);

                int date = msg.path("date").asInt(0);
                // content
                JsonNode content = msg.path("content");
                String ctype = content.path("@type").asText("");

                String text = null;
                if ("messageText".equals(ctype)) text = content.path("text").path("text").asText("");

                db.upsertMessage(chatId, mid, date, authorId, ctype, text, msg.toString());
                // links
                if (text != null && !text.isBlank()) {
                    Matcher m = LINK_RE.matcher(text);
                    while (m.find()) db.insertLink(chatId, mid, m.group(1));
                }

                // media
                handleMedia(chatId, mid, content);
            }

            if (oldest == Long.MAX_VALUE) break;
            if (lastOldest == oldest) break; // safety
            lastOldest = oldest;
            fromMessageId = oldest; // go older
        }

        // обновляем отметку
        if (maxSeenThisRun > maxSaved) db.setChatSyncPosition(chatId, maxSeenThisRun);

        System.out.printf("[%s] Dump finished for chat %d (new up to msg_id=%d)%n",
                Instant.now(), chatId, db.getMaxMessageId(chatId));
    }

    private void pullAndUpsertUser(long userId) throws Exception {
        JsonNode u = rq(obj("getUser").put("user_id", userId));
        String first = u.path("first_name").asText(null);
        String last = u.path("last_name").asText(null);
        String uname = u.path("usernames").path("editable_username").asText(null);
        String phone = u.path("phone_number").asText(null);
        boolean isBot = u.path("type").path("@type").asText("").equals("userTypeBot");
        db.upsertUser(userId, first, last, uname, phone, isBot);
    }

    private void handleMedia(long chatId, long messageId, JsonNode content) throws Exception {
        String t = content.path("@type").asText("");
        switch (t) {
            case "messagePhoto": {
                JsonNode sizes = content.path("photo").path("sizes");
                if (sizes.isArray() && sizes.size() > 0) {
                    // возьмём последнюю (обычно самая большая)
                    JsonNode last = sizes.get(sizes.size() - 1);
                    JsonNode file = last.path("photo");
                    saveFileRecord(chatId, messageId, "photo", file, last.path("width").asInt(), last.path("height").asInt(),
                            0, null);
                }
                break;
            }
            case "messageVideo": {
                JsonNode v = content.path("video");
                JsonNode file = v.path("video");
                saveFileRecord(chatId, messageId, "video", file,
                        v.path("width").asInt(), v.path("height").asInt(),
                        v.path("duration").asInt(), v.path("mime_type").asText(null));
                break;
            }
            case "messageAnimation": {
                JsonNode a = content.path("animation");
                JsonNode file = a.path("animation");
                saveFileRecord(chatId, messageId, "animation", file,
                        a.path("width").asInt(), a.path("height").asInt(),
                        a.path("duration").asInt(), a.path("mime_type").asText(null));
                break;
            }
            case "messageDocument": {
                JsonNode d = content.path("document");
                JsonNode file = d.path("document");
                saveFileRecord(chatId, messageId, "document", file,
                        0, 0, 0, d.path("mime_type").asText(null));
                break;
            }
            case "messageAudio": {
                JsonNode a = content.path("audio");
                JsonNode file = a.path("audio");
                saveFileRecord(chatId, messageId, "audio", file,
                        0, 0, a.path("duration").asInt(), a.path("mime_type").asText(null));
                break;
            }
            case "messageVoiceNote": {
                JsonNode v = content.path("voice_note");
                JsonNode file = v.path("voice");
                saveFileRecord(chatId, messageId, "voice", file,
                        0, 0, v.path("duration").asInt(), v.path("mime_type").asText(null));
                break;
            }
            default: /* ignore other types */ }
    }

    private void saveFileRecord(long chatId, long messageId, String kind, JsonNode fileNode,
                                int width, int height, int duration, String mime) throws Exception {
        if (fileNode == null || fileNode.isMissingNode()) return;
        int fileId = fileNode.path("id").asInt(0);
        if (fileId == 0) return;

        // стартуем скачивание (лучше фоново; путь появится по мере готовности)
        ObjectNode dl = obj("downloadFile");
        dl.put("file_id", fileId);
        dl.put("priority", 1);
        dl.put("synchronous", false);
        dl.put("offset", 0);
        dl.put("limit", 0);
        client.send(dl);

        // несколько попыток узнать локальный путь
        String remoteId = null;
        String localPath = null;
        long size = 0;

        for (int i = 0; i < 20; i++) { // ~10 секунд
            JsonNode f = rq(obj("getFile").put("file_id", fileId));
            remoteId = f.path("remote").path("id").asText(null);
            localPath = f.path("local").path("path").asText(null);
            size = f.path("size").asLong(0);
            boolean completed = f.path("local").path("is_downloading_completed").asBoolean(false);
            if (localPath != null && !localPath.isBlank() && (completed || size > 0)) break;
            Thread.sleep(500);
        }

        db.upsertMedia(chatId, messageId, kind, fileId, remoteId, localPath, width, height, duration, mime, size);
    }
}
