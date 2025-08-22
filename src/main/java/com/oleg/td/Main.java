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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.oleg.td.Utils.obj;

public class Main {
    private static final java.util.concurrent.ConcurrentHashMap<Long, String> chatTitles = new java.util.concurrent.ConcurrentHashMap<>();
    private static FileChannel appLockCh;
    private static FileLock appLock;

    static {
        System.setProperty("jna.encoding", "UTF-8");
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
        Config cfg = Utils.loadJsonResource("/config.json", Config.class);
        log.info("CONFIG: api_id={}, has_api_hash={}, db_dir={}, files_dir={}, groups={}, case_insensitive={}", cfg.tdlib.api_id, (cfg.tdlib.api_hash != null && !cfg.tdlib.api_hash.isBlank()), cfg.tdlib.database_directory, cfg.tdlib.files_directory, cfg.groups, cfg.case_insensitive);
        String libPath = Optional.ofNullable(System.getenv("TDLIB_PATH")).orElse(Optional.ofNullable(cfg.tdlib.lib_path).orElse("/home/oleg/td/build/libtdjson.so"));
        log.info("Loading TDLib from: {}", libPath);
        TDLib lib = TDLib.load(libPath);
        int verb = Integer.parseInt(Optional.ofNullable(System.getenv("TDLIB_VERBOSITY")).orElse("1"));
        lib.td_set_log_message_callback(verb, (lvl, msg) -> {
            if (lvl <= 1) log.debug("TDLib[{}]: {}", lvl, msg);
        });
        int flags = cfg.case_insensitive ? Pattern.CASE_INSENSITIVE : 0;
        List<PatternEntry> patterns = new ArrayList<>();
        for (var p : cfg.patterns) {
            patterns.add(new PatternEntry(p.name, Pattern.compile(p.regex, flags)));
        }
        Files.createDirectories(Path.of(cfg.tdlib.database_directory));
        Files.createDirectories(Path.of(cfg.tdlib.files_directory));
        Path lockFile = Path.of(cfg.tdlib.database_directory, ".app.lock");
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
            try { if (appLock != null) appLock.release(); } catch (Exception ignored) {}
            try { if (appLockCh != null) appLockCh.close(); } catch (Exception ignored) {}
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
                    if (!title.isEmpty()) chatTitles.put(id, title);
                }
            });

            // УБРАН setLogStream(logStreamEmpty) — он не нужен и у вас вызывает 400
            {
                var v = Utils.obj("setLogVerbosityLevel");
                v.put("new_verbosity_level", 0); // 0 — только критика
                client.send(v);
            }

            client.addUpdateHandler(n -> handleUpdate(n, patterns));
            authorize(client, cfg);

            Set<Long> chats = resolveGroups(client, cfg.groups);
            log.info("Watching {} chats: {}", chats.size(), chats);

            for (Long chatId : chats) {
                ObjectNode o = obj("getChatHistory");
                o.put("chat_id", chatId);
                o.put("from_message_id", 0);
                o.put("offset", 0);
                o.put("limit", 1);
                o.put("only_local", false);
                client.request(o);
            }
            log.info("Ready. Listening...");
            while (true) {
                Thread.sleep(10000L);
            }
        }
    }

    // ====== авторизация (обновлено) ======

    private static volatile boolean authorized = false;

    private static void authorize(TdJsonClient client, Config cfg) {
        final java.util.concurrent.atomic.AtomicReference<String> stateRef = new java.util.concurrent.atomic.AtomicReference<>(null);
        final java.util.concurrent.atomic.AtomicReference<String> authErrorRef = new java.util.concurrent.atomic.AtomicReference<>(null);

        client.addUpdateHandler(n -> {
            String type = n.path("@type").asText();
            System.out.println("AUTH DEBUG: received node type = " + type);

            if ("updateAuthorizationState".equals(type)) {
                String s = n.path("authorization_state").path("@type").asText();
                System.out.println("AUTH DEBUG: state = " + s);
                stateRef.set(s);
                if ("authorizationStateReady".equals(s)) authorized = true;
            } else if (type.startsWith("authorizationState")) {
                String s = n.path("@type").asText();
                System.out.println("AUTH DEBUG: state = " + s);
                stateRef.set(s);
                if ("authorizationStateReady".equals(s)) authorized = true;
            } else if ("error".equals(type)) {
                int code = n.path("code").asInt();
                String msg = n.path("message").asText("");
                System.out.println("AUTH DEBUG: TDLib ERROR: " + code + " " + msg);

                if (msg.contains("PHONE_CODE_INVALID") || msg.contains("PHONE_CODE_EXPIRED")
                        || msg.contains("PHONE_NUMBER_INVALID") || msg.contains("PASSWORD_HASH_INVALID")
                        || msg.contains("SESSION_PASSWORD_NEEDED")) {
                    authErrorRef.set(code + " " + msg);
                }
                if (code == 429) {
                    int secs = parseRetryAfterSeconds(msg);
                    if (secs > 0) {
                        System.out.println("AUTH DEBUG: backoff " + secs + "s due to rate limit");
                        try { Thread.sleep(secs * 1000L); } catch (InterruptedException ignored) {}
                    }
                }
            }
        });

        client.send(Utils.obj("getAuthorizationState"));

        String lastState = null;
        boolean paramsSent = false, phoneSent = false, codeSent = false, passSent = false;

        while (!authorized) {
            String s = stateRef.get();
            if (s == null) { sleep(100); continue; }

            boolean stateChanged = !s.equals(lastState);
            String authErr = authErrorRef.getAndSet(null);

            if (stateChanged) {
                lastState = s;
                paramsSent = phoneSent = codeSent = passSent = false;
            }

            switch (s) {
                case "authorizationStateWaitTdlibParameters": {
                    if (!paramsSent) {
                        var p = Utils.obj("setTdlibParameters");
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
                        p.put("database_encryption_key", "");
                        System.out.println("DEBUG setTdlibParameters (inlined) JSON --> " + p.toString());
                        client.send(p);
                        paramsSent = true;
                    }
                    break;
                }
                case "authorizationStateWaitPhoneNumber": {
                    if (!phoneSent || authErr != null) {
                        String phone = (cfg.auth != null && cfg.auth.phone != null && !cfg.auth.phone.isBlank())
                                ? cfg.auth.phone.trim()
                                : readValue("Enter phone number (+xxxxxxxxxxx): ",
                                false, "TG_PHONE", "TELEGRAM_PHONE", "telegram.phone");
                        if (cfg.auth != null && cfg.auth.phone != null) {
                            System.out.println("Enter phone number (+xxxxxxxxxxx): [value from config.auth.phone]");
                        }
                        ObjectNode r = Utils.obj("setAuthenticationPhoneNumber");
                        r.put("phone_number", phone);
                        r.put("allow_flash_call", false);
                        r.put("is_current_phone_number", false);
                        client.send(r);
                        System.out.println("AUTH DEBUG: phone submitted");
                        phoneSent = true;
                    }
                    break;
                }
                case "authorizationStateWaitCode": {
                    if (!codeSent || authErr != null) {
                        String code = (cfg.auth != null && cfg.auth.code != null && !cfg.auth.code.isBlank())
                                ? cfg.auth.code.trim()
                                : readValue("Enter code from Telegram: ",
                                false, "TG_CODE", "TELEGRAM_CODE", "telegram.code");
                        if (cfg.auth != null && cfg.auth.code != null) {
                            System.out.println("Enter code from Telegram: [value from config.auth.code]");
                        }
                        ObjectNode r = Utils.obj("checkAuthenticationCode");
                        r.put("code", code);
                        client.send(r);
                        System.out.println("AUTH DEBUG: code submitted");
                        codeSent = true;
                    }
                    break;
                }
                case "authorizationStateWaitPassword": {
                    if (!passSent || authErr != null) {
                        String pass = (cfg.auth != null && cfg.auth.pass != null && !cfg.auth.pass.isBlank())
                                ? cfg.auth.pass
                                : readValue("Enter 2FA password: ",
                                true, "TG_PASS", "TELEGRAM_PASS", "telegram.pass");
                        if (cfg.auth != null && cfg.auth.pass != null) {
                            System.out.println("Enter 2FA password: [value from config.auth.pass]");
                        }
                        ObjectNode r = Utils.obj("checkAuthenticationPassword");
                        r.put("password", pass);
                        client.send(r);
                        System.out.println("AUTH DEBUG: 2FA password submitted");
                        passSent = true;
                    }
                    break;
                }
                case "authorizationStateReady": {
                    authorized = true;
                    System.out.println("Authorization completed.");
                    break;
                }
                case "authorizationStateClosed": {
                    System.err.println("Authorization closed.");
                    return;
                }
                default: { /* ignore */ }
            }

            sleep(100);
        }
    }

    // ====== остальная логика ======

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

    private static void handleUpdate(JsonNode u, java.util.List<PatternEntry> patterns) {
        String type = u.path("@type").asText();
        if (!"updateNewMessage".equals(type)) return;
        JsonNode m = u.path("message");
        long chatId = m.path("chat_id").asLong();
        long mid = m.path("id").asLong();
        JsonNode c = m.path("content");
        if (!"messageText".equals(c.path("@type").asText())) return;
        String text = c.path("text").path("text").asText("");
        if (text.isEmpty()) return;
        var hits = patterns.stream().filter(p -> p.pattern.matcher(text).find()).map(p -> p.name).collect(Collectors.toList());
        if (!hits.isEmpty()) {
            String title = chatTitles.getOrDefault(chatId, String.valueOf(chatId));
            System.out.printf("[%s] Match in chat %s (id=%d), msg %d, patterns=%s:%n%s%n----%n",
                    Instant.now(), title, chatId, mid, hits, text);
        }
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

    private static int parseRetryAfterSeconds(String msg) {
        try {
            String m = msg.toLowerCase();
            int i = m.indexOf("retry after");
            if (i >= 0) {
                String tail = m.substring(i + "retry after".length()).trim();
                String num = tail.split("[^0-9]")[0];
                return Integer.parseInt(num);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
