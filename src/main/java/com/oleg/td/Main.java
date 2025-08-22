package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.oleg.td.Utils.obj;

public class Main {
    private static final java.util.concurrent.ConcurrentHashMap<Long, String> chatTitles = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Boolean> chatExportStatus = new ConcurrentHashMap<>();
    private static FileChannel appLockCh;
    private static FileLock appLock;
    private static DatabaseManager dbManager;
    private static Config config;

    static {
        System.setProperty("jna.encoding", "UTF-8");
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("SQLite JDBC driver not found", e);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private static class PatternEntry {
        final String name;
        final Pattern pattern;

        PatternEntry(String n, Pattern p) {
            name = n;
            pattern = p;
        }
    }

    public static void main(String[] args) throws Exception {
        config = Utils.loadJsonResource("/config.json", Config.class);
        log.info("CONFIG: api_id={}, has_api_hash={}, db_dir={}, files_dir={}, groups={}, case_insensitive={}",
                config.tdlib.api_id, (config.tdlib.api_hash != null && !config.tdlib.api_hash.isBlank()),
                config.tdlib.database_directory, config.tdlib.files_directory, config.groups, config.case_insensitive);

        // Создаем директории перед инициализацией базы данных
        Files.createDirectories(Path.of(config.tdlib.database_directory));
        Files.createDirectories(Path.of(config.tdlib.files_directory));

        // Initialize database
        Path dbPath = Path.of(config.tdlib.database_directory, "telegram_data.db");
        dbManager = new DatabaseManager(dbPath.toString());
        log.info("Database initialized at: {}", dbPath.toAbsolutePath());

        String libPath = Optional.ofNullable(System.getenv("TDLIB_PATH")).orElse(Optional.ofNullable(config.tdlib.lib_path).orElse("/home/oleg/td/build/libtdjson.so"));
        log.info("Loading TDLib from: {}", libPath);
        TDLib lib = TDLib.load(libPath);
        int verb = Integer.parseInt(Optional.ofNullable(System.getenv("TDLIB_VERBOSITY")).orElse("1"));
        lib.td_set_log_message_callback(verb, (lvl, msg) -> {
            if (lvl <= 1) log.debug("TDLib[{}]: {}", lvl, msg);
        });

        int flags = config.case_insensitive ? Pattern.CASE_INSENSITIVE : 0;
        List<PatternEntry> patterns = new ArrayList<>();
        for (var p : config.patterns) {
            patterns.add(new PatternEntry(p.name, Pattern.compile(p.regex, flags)));
        }

        // Убрал дублирующее создание директорий (оно уже сделано выше)
        Path lockFile = Path.of(config.tdlib.database_directory, ".app.lock");
        appLockCh = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            appLock = appLockCh.tryLock();
        } catch (OverlappingFileLockException e) {
            appLock = null;
        }
        if (appLock == null) {
            log.error("Another instance is already running (lock file: {})", lockFile.toAbsolutePath());
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (appLock != null) appLock.release();
            } catch (Exception ignored) {
            }
            try {
                if (appLockCh != null) appLockCh.close();
            } catch (Exception ignored) {
            }
            if (dbManager != null) {
                dbManager.close();
            }
        }, "shutdown-hook"));

        try (TdJsonClient client = new TdJsonClient(lib)) {
            client.addUpdateHandler(n -> {
                if ("error".equals(n.path("@type").asText())) {
                    int code = n.path("code").asInt();
                    String msg = n.path("message").asText();
                    System.err.println("TDLIB ERROR: code=" + code + " message=" + msg);
                }
            });

            client.addUpdateHandler(n -> {
                if ("updateNewChat".equals(n.path("@type").asText())) {
                    long id = n.path("chat").path("id").asLong();
                    String title = n.path("chat").path("title").asText("");
                    String type = n.path("chat").path("type").path("@type").asText("");
                    if (!title.isEmpty()) {
                        chatTitles.put(id, title);
                        try {
                            dbManager.saveChat(id, title, type);
                        } catch (Exception e) {
                            log.error("Failed to save chat to database", e);
                        }
                    }
                }
            });

            client.addUpdateHandler(n -> handleUserUpdate(n, client));

            {
                var s = Utils.obj("setLogStream");
                var ls = s.putObject("log_stream");
                ls.put("@type", "logStreamEmpty");
                client.send(s);
            }

            {
                var v = Utils.obj("setLogVerbosityLevel");
                v.put("new_verbosity_level", 0);
                client.send(v);
            }

            client.addUpdateHandler(n -> handleUpdate(n, patterns, client));
            authorize(client, config);
            Set<Long> chats = resolveGroups(client, config.groups);
            log.info("Watching {} chats: {}", chats.size(), chats);

            for (Long chatId : chats) {
                // Check if chat already exported
                boolean isExported = false;
                try {
                    isExported = dbManager.isChatExported(chatId);
                } catch (Exception e) {
                    log.error("Failed to check export status for chat {}", chatId, e);
                }
                chatExportStatus.put(chatId, isExported);

                if (!isExported) {
                    log.info("Chat {} not yet exported, will export on trigger", chatId);
                } else {
                    log.info("Chat {} already exported", chatId);
                }
            }

            log.info("Ready. Listening...");
            while (true) {
                Thread.sleep(10000L);
            }
        }
    }

    private static void handleUserUpdate(JsonNode u, TdJsonClient client) {
        String type = u.path("@type").asText();
        if ("updateUser".equals(type)) {
            JsonNode user = u.path("user");
            long userId = user.path("id").asLong();
            String firstName = user.path("first_name").asText("");
            String lastName = user.path("last_name").asText("");
            String username = user.path("username").asText("");
            String phoneNumber = user.path("phone_number").asText("");

            try {
                dbManager.saveUser(userId, firstName, lastName, username, phoneNumber);
            } catch (Exception e) {
                log.error("Failed to save user to database", e);
            }
        } else if ("updateChatMember".equals(type)) {
            long chatId = u.path("chat_id").asLong();
            JsonNode member = u.path("new_chat_member").path("member");
            String memberType = member.path("@type").asText();

            if ("chatMember".equals(memberType)) {
                long userId = member.path("user_id").asLong();
                Instant joinedAt = Instant.ofEpochSecond(member.path("joined_chat_date").asLong());

                try {
                    dbManager.addChatUser(chatId, userId, joinedAt);
                } catch (Exception e) {
                    log.error("Failed to add chat user to database", e);
                }
            }
        }
    }

    private static void handleUpdate(JsonNode u, List<PatternEntry> patterns, TdJsonClient client) {
        String type = u.path("@type").asText();
        if (!"updateNewMessage".equals(type)) return;

        JsonNode m = u.path("message");
        long chatId = m.path("chat_id").asLong();
        Set<Long> targetChats = resolveGroups(client, config.groups);

        if (!targetChats.contains(chatId)) {
            return; // Игнорируем сообщения не из целевых чатов
        }
        long mid = m.path("id").asLong();
        JsonNode c = m.path("content");

        String text = "";
        String mediaType = "";
        String mediaPath = "";
        List<String> links = new ArrayList<>();

        if ("messageText".equals(c.path("@type").asText())) {
            text = c.path("text").path("text").asText("");
            // Extract links from text
            JsonNode entities = c.path("text").path("entities");
            if (entities.isArray()) {
                for (JsonNode entity : entities) {
                    if ("textEntityTypeUrl".equals(entity.path("type").path("@type").asText())) {
                        int offset = entity.path("offset").asInt();
                        int length = entity.path("length").asInt();
                        if (offset + length <= text.length()) {
                            links.add(text.substring(offset, offset + length));
                        }
                    }
                }
            }
        } else if ("messagePhoto".equals(c.path("@type").asText())) {
            mediaType = "photo";
            JsonNode photo = c.path("photo");
            text = c.path("caption").path("text").asText("");
            // Get largest photo
            JsonNode sizes = photo.path("sizes");
            if (sizes.isArray() && sizes.size() > 0) {
                JsonNode largest = sizes.get(sizes.size() - 1);
                mediaPath = largest.path("photo").path("local").path("path").asText("");
            }
        } else if ("messageVideo".equals(c.path("@type").asText())) {
            mediaType = "video";
            text = c.path("caption").path("text").asText("");
            mediaPath = c.path("video").path("video").path("local").path("path").asText("");
        }

        if (text.isEmpty() && mediaType.isEmpty()) return;

        // Создаем финальную копию для использования в лямбда-выражении
        final String finalText = text;

        // Save message to database
        long userId = m.path("sender_id").path("user_id").asLong();
        Instant timestamp = Instant.ofEpochSecond(m.path("date").asLong());

        try {
            dbManager.saveMessage(mid, chatId, userId, text, mediaType, mediaPath,
                    String.join(",", links), timestamp);
        } catch (Exception e) {
            log.error("Failed to save message to database", e);
        }

        // Check for patterns
        var hits = patterns.stream()
                .filter(p -> p.pattern.matcher(finalText).find()) // Используем finalText вместо text
                .map(p -> p.name)
                .collect(Collectors.toList());

        if (!hits.isEmpty()) {
            String title = chatTitles.getOrDefault(chatId, String.valueOf(chatId));
            System.out.printf("[%s] Match in chat %s (id=%d), msg %d, patterns=%s:%n%s%n----%n",
                    Instant.now(), title, chatId, mid, hits, text);

            // Export chat if not already exported
            if (!chatExportStatus.getOrDefault(chatId, false)) {
                log.info("Trigger word detected, exporting chat {}: {}", chatId, title);
                exportChat(client, chatId);
                chatExportStatus.put(chatId, true);
            } else {
                log.info("Trigger word detected, updating chat {}: {}", chatId, title);
                updateChat(client, chatId);
            }
        }
    }

    private static void exportChat(TdJsonClient client, long chatId) {
        try {
            // Get chat info
            ObjectNode getChat = obj("getChat");
            getChat.put("chat_id", chatId);
            JsonNode chatInfo = client.request(getChat).get();

            String title = chatInfo.path("title").asText("");
            String type = chatInfo.path("type").path("@type").asText("");
            dbManager.saveChat(chatId, title, type);

            // Get chat members
            ObjectNode getMembers = obj("getChatMembers");
            getMembers.put("chat_id", chatId);
            getMembers.put("limit", 200);
            JsonNode members = client.request(getMembers).get();

            if (members.has("members") && members.get("members").isArray()) {
                for (JsonNode member : members.get("members")) {
                    long userId = member.path("user_id").asLong();
                    Instant joinedAt = Instant.ofEpochSecond(member.path("joined_chat_date").asLong());
                    dbManager.addChatUser(chatId, userId, joinedAt);

                    // Get user info
                    ObjectNode getUser = obj("getUser");
                    getUser.put("user_id", userId);
                    JsonNode userInfo = client.request(getUser).get();

                    String firstName = userInfo.path("first_name").asText("");
                    String lastName = userInfo.path("last_name").asText("");
                    String username = userInfo.path("username").asText("");
                    String phoneNumber = userInfo.path("phone_number").asText("");

                    dbManager.saveUser(userId, firstName, lastName, username, phoneNumber);
                }
            }

            // Get all messages
            long fromMessageId = 0;
            boolean hasMore = true;

            while (hasMore) {
                ObjectNode getHistory = obj("getChatHistory");
                getHistory.put("chat_id", chatId);
                getHistory.put("from_message_id", fromMessageId);
                getHistory.put("offset", 0);
                getHistory.put("limit", 100);
                getHistory.put("only_local", false);

                JsonNode history = client.request(getHistory).get();
                JsonNode messages = history.path("messages");

                if (messages.isArray() && messages.size() > 0) {
                    for (JsonNode msg : messages) {
                        processMessage(msg);
                    }
                    fromMessageId = messages.get(messages.size() - 1).path("id").asLong();
                }

                hasMore = history.path("total_count").asInt() > 0 && messages.size() > 0;
                Thread.sleep(100); // Rate limiting
            }

            log.info("Finished exporting chat {}", chatId);

        } catch (Exception e) {
            log.error("Failed to export chat {}", chatId, e);
        }
    }

    private static void updateChat(TdJsonClient client, long chatId) {
        try {
            // Get only new messages since last export
            List<Long> existingMessageIds = dbManager.getMessageIds(chatId);
            long latestMessageId = existingMessageIds.stream().max(Long::compare).orElse(0L);

            ObjectNode getHistory = obj("getChatHistory");
            getHistory.put("chat_id", chatId);
            getHistory.put("from_message_id", latestMessageId);
            getHistory.put("offset", 0);
            getHistory.put("limit", 100);
            getHistory.put("only_local", false);

            JsonNode history = client.request(getHistory).get();
            JsonNode messages = history.path("messages");

            if (messages.isArray()) {
                for (JsonNode msg : messages) {
                    long msgId = msg.path("id").asLong();
                    if (msgId > latestMessageId) {
                        processMessage(msg);
                    }
                }
            }

            log.info("Updated chat {} with new messages", chatId);

        } catch (Exception e) {
            log.error("Failed to update chat {}", chatId, e);
        }
    }

    private static void processMessage(JsonNode msg) {
        long mid = msg.path("id").asLong();
        long chatId = msg.path("chat_id").asLong();
        long userId = msg.path("sender_id").path("user_id").asLong();
        JsonNode c = msg.path("content");

        String text = "";
        String mediaType = "";
        String mediaPath = "";
        List<String> links = new ArrayList<>();

        if ("messageText".equals(c.path("@type").asText())) {
            text = c.path("text").path("text").asText("");
            // Extract links
            JsonNode entities = c.path("text").path("entities");
            if (entities.isArray()) {
                for (JsonNode entity : entities) {
                    if ("textEntityTypeUrl".equals(entity.path("type").path("@type").asText())) {
                        int offset = entity.path("offset").asInt();
                        int length = entity.path("length").asInt();
                        if (offset + length <= text.length()) {
                            links.add(text.substring(offset, offset + length));
                        }
                    }
                }
            }
        } else if ("messagePhoto".equals(c.path("@type").asText())) {
            mediaType = "photo";
            text = c.path("caption").path("text").asText("");
            JsonNode photo = c.path("photo");
            JsonNode sizes = photo.path("sizes");
            if (sizes.isArray() && sizes.size() > 0) {
                JsonNode largest = sizes.get(sizes.size() - 1);
                mediaPath = largest.path("photo").path("local").path("path").asText("");
            }
        } else if ("messageVideo".equals(c.path("@type").asText())) {
            mediaType = "video";
            text = c.path("caption").path("text").asText("");
            mediaPath = c.path("video").path("video").path("local").path("path").asText("");
        }

        Instant timestamp = Instant.ofEpochSecond(msg.path("date").asLong());

        try {
            dbManager.saveMessage(mid, chatId, userId, text, mediaType, mediaPath,
                    String.join(",", links), timestamp);
        } catch (Exception e) {
            log.error("Failed to process message {}", mid, e);
        }
    }

    private static volatile boolean authorized = false;

    private static void authorize(TdJsonClient client, Config cfg) {
        final java.util.concurrent.atomic.AtomicReference<String> stateRef = new java.util.concurrent.atomic.AtomicReference<>(null);

        client.addUpdateHandler(n -> {
            String type = n.path("@type").asText();

            System.out.println("AUTH DEBUG: received node type = " + type);

            if ("updateAuthorizationState".equals(type)) {
                String s = n.path("authorization_state").path("@type").asText();
                System.out.println("AUTH DEBUG: state = " + s);
                stateRef.set(s);
                if ("authorizationStateReady".equals(s)) {
                    authorized = true;
                } else if ("authorizationStateClosed".equals(s)) {
                    // можно обработать как ошибку/выход
                }
            } else if (type.startsWith("authorizationState")) {
                // ответ на getAuthorizationState
                String s = n.path("@type").asText();
                System.out.println("AUTH DEBUG: state = " + s);
                stateRef.set(s);
                if ("authorizationStateReady".equals(s)) {
                    authorized = true;
                }
            }
        });

        client.send(Utils.obj("getAuthorizationState"));

        String lastHandledState = null;

        while (!authorized) {
            String s = stateRef.get();
            if (s == null || s.equals(lastHandledState)) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
                continue;
            }

            switch (s) {
                case "authorizationStateWaitTdlibParameters": {
                    com.fasterxml.jackson.databind.node.ObjectNode p = Utils.obj("setTdlibParameters");
                    p.put("use_test_dc", false);
                    p.put("database_directory", cfg.tdlib.database_directory);
                    p.put("files_directory", cfg.tdlib.files_directory);
                    p.put("use_file_database", true);
                    p.put("use_chat_info_database", true);
                    p.put("use_message_database", true);
                    p.put("use_secret_chats", false);
                    p.put("api_id", cfg.tdlib.api_id);
                    p.put("api_hash", cfg.tdlib.api_hash);
                    p.put("system_language_code", "en");
                    p.put("device_model", "Java");
                    p.put("system_version", System.getProperty("os.name") + " " + System.getProperty("os.version", ""));
                    p.put("application_version", "1.0.4-debug");
                    p.put("enable_storage_optimizer", true);
                    p.put("ignore_file_names", true);
                    p.put("database_encryption_key", ""); // опционально, для явного лога

                    System.out.println("DEBUG setTdlibParameters (inlined) JSON --> " + p.toString());
                    client.send(p); // ВАЖНО: только send(), execute тут нельзя
                    break;
                }
                case "authorizationStateWaitPhoneNumber": {
                    String phone = null;
                    if (cfg.auth != null && cfg.auth.phone != null && !cfg.auth.phone.isBlank()) {
                        phone = cfg.auth.phone.trim();
                        System.out.println("Enter phone number (+xxxxxxxxxxx): [value from config.auth.phone]");
                    } else {
                        phone = readValue("Enter phone number (+xxxxxxxxxxx): ",
                                false, "TG_PHONE", "TELEGRAM_PHONE", "telegram.phone");
                    }

                    ObjectNode r = Utils.obj("setAuthenticationPhoneNumber");
                    r.put("phone_number", phone);
                    r.put("allow_flash_call", false);
                    r.put("is_current_phone_number", false);
                    client.send(r);
                    System.out.println("AUTH DEBUG: phone submitted");
                    break;
                }

                case "authorizationStateWaitCode": {
                    String code = null;
                    if (cfg.auth != null && cfg.auth.code != null && !cfg.auth.code.isBlank()) {
                        code = cfg.auth.code.trim();
                        System.out.println("Enter code from Telegram: [value from config.auth.code]");
                    } else {
                        code = readValue("Enter code from Telegram: ",
                                false, "TG_CODE", "TELEGRAM_CODE", "telegram.code");
                    }
                    ObjectNode r = Utils.obj("checkAuthenticationCode");
                    r.put("code", code);
                    client.send(r);
                    System.out.println("AUTH DEBUG: code submitted");
                    break;
                }

                case "authorizationStateWaitPassword": {
                    String pass = null;
                    if (cfg.auth != null && cfg.auth.pass != null && !cfg.auth.pass.isBlank()) {
                        pass = cfg.auth.pass;
                        System.out.println("Enter 2FA password: [value from config.auth.pass]");
                    } else {
                        pass = readValue("Enter 2FA password: ",
                                true, "TG_PASS", "TELEGRAM_PASS", "telegram.pass");
                    }
                    ObjectNode r = Utils.obj("checkAuthenticationPassword");
                    r.put("password", pass);
                    client.send(r);
                    System.out.println("AUTH DEBUG: 2FA password submitted");
                    break;
                }

                case "authorizationStateReady": {
                    authorized = true;
                    System.out.println("Authorization completed.");
                    break;
                }
                case "authorizationStateClosed": {
                    System.err.println("Authorization closed.");
                    break;
                }
                default:
            }

            lastHandledState = s;
        }
    }

    private static java.util.Set<Long> resolveGroups(TdJsonClient client, java.util.List<String> links) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (String link : links) {
            String L = link.trim();
            if (L.isEmpty()) continue;
            try {
                if (L.matches("^https?://t.me/(joinchat/).+")) {
                    ObjectNode r = obj("joinChatByInviteLink");
                    r.put("invite_link", L);
                    JsonNode resp = client.request(r).get();
                    long id = resp.path("chat_id").asLong(0);
                    if (id != 0) ids.add(id);
                } else if (L.matches("^https?://t.me/[^/]+$")) {
                    String username = L.substring(L.lastIndexOf('/') + 1);
                    ObjectNode r = obj("searchPublicChat");
                    r.put("username", username);
                    JsonNode chat = client.request(r).get();
                    long id = chat.path("id").asLong(0);
                    if (id == 0) {
                        System.err.printf("Public chat not found for %s.%n", username);
                        continue;
                    }
                    ids.add(id);
                    ObjectNode join = obj("joinChat");
                    join.put("chat_id", id);
                    client.request(join).exceptionally(ex -> null);
                } else {
                    System.err.printf("Unsupported group link format: %s%n", link);
                }
            } catch (Exception e) {
                System.err.println("Group resolve error: " + e.getMessage());
            }
        }
        return ids;
    }

    private static String readValue(String prompt, boolean secret, String... keys) {

        for (String k : keys) {
            String v = System.getProperty(k);
            if (v != null && !v.isBlank()) {
                System.out.println(prompt + " [value from -D" + k + "]");
                return v.trim();
            }
            v = System.getenv(k);
            if (v != null && !v.isBlank()) {
                System.out.println(prompt + " [value from $" + k + "]");
                return v.trim();
            }
        }

        Console cons = System.console();
        if (cons != null) {
            return secret ? new String(cons.readPassword(prompt))
                    : cons.readLine(prompt);
        }

        System.out.print(prompt);
        try {
            java.util.Scanner sc = new java.util.Scanner(System.in);
            if (sc.hasNextLine()) {
                return sc.nextLine();
            }
            throw new IllegalStateException("STDIN is not interactive (EOF).");
        } catch (NoSuchElementException e) {
            throw new IllegalStateException("No input available on STDIN. " +
                    "Provide value via env/system property or run in a real terminal (e.g. `--console=plain`).", e);
        }
    }
}
