package com.oleg.td;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Точка входа. Только "склейка" блоков и жизненный цикл.
 */
public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        // 0) Конфиг
        Config cfg = Utils.loadJsonResource("/config.json", Config.class);
        log.info("CONFIG: api_id={}, has_api_hash={}, db_dir={}, files_dir={}, groups={}, case_insensitive={}",
                cfg.tdlib.api_id,
                (cfg.tdlib.api_hash != null && !cfg.tdlib.api_hash.isBlank()),
                cfg.tdlib.database_directory,
                cfg.tdlib.files_directory,
                cfg.groups, cfg.case_insensitive);

        // ★ Развести каталоги TDLib по api_id+телефон — чтобы не провоцировать re-auth и 429
        String phoneSan = (cfg.auth != null && cfg.auth.phone != null) ? cfg.auth.phone.replaceAll("\\D", "") : "unknown";
        cfg.tdlib.database_directory = Path.of(cfg.tdlib.database_directory, cfg.tdlib.api_id + "_" + phoneSan).toString();
        cfg.tdlib.files_directory = Path.of(cfg.tdlib.files_directory, cfg.tdlib.api_id + "_" + phoneSan).toString();

        // 1) Создать каталоги БД/файлов TDLib
        Files.createDirectories(Path.of(cfg.tdlib.database_directory));
        Files.createDirectories(Path.of(cfg.tdlib.files_directory));

        // 2) Single-instance lock
        try (LockManager lock = LockManager.tryLock(Path.of(cfg.tdlib.database_directory, ".app.lock"))) {
            if (!lock.isAcquired()) {
                log.error("Another instance is already running (lock file: {})", lock.lockFile().toAbsolutePath());
                return;
            }

            // 3) Загрузить TDLib (через JNA)
            TDLib td = TDLibLoader.loadFromEnvOrConfig(cfg);
            TDLibLoader.configureVerbosity(td, EnvVars.getInt("TDLIB_VERBOSITY", 1));

            // 4) Обёртка клиента
            try (TdJsonClient client = new TdJsonClient(td)) {
                // 4.1) Базовые настройки логов TDLib (опционально)
                {
                    var s = Utils.obj("setLogStream");
                    var ls = s.putObject("log_stream");
                    ls.put("@type", "logStreamEmpty");
                    client.send(s);
                    var v = Utils.obj("setLogVerbosityLevel");
                    v.put("new_verbosity_level", 0); // 0 — только критика
                    client.send(v);
                }

                // 5) Роутер апдейтов + кэш заголовков чатов
                ChatTitleRegistry titleRegistry = new ChatTitleRegistry();
                UpdateRouter router = new UpdateRouter(new CopyOnWriteArrayList<>());
                client.addUpdateHandler(router::dispatch);
                router.add(n -> TDLibErrors.logIfError(n));
                router.add(titleRegistry::onUpdateNewChat); // наполняем кэш заголовков

                // 6) Авторизация
                AuthFlow auth = new AuthFlow(client, cfg);
                auth.wireInto(router);      // подписать обработчики шагов авторизации
                auth.authorizeBlocking();   // пройти все состояния

                // 7) Компиляция паттернов
                int flags = cfg.case_insensitive ? Pattern.CASE_INSENSITIVE : 0;
                List<MessageMatcher.PatternEntry> patterns = cfg.patterns.stream()
                        .map(p -> new MessageMatcher.PatternEntry(p.name, Pattern.compile(p.regex, flags)))
                        .collect(Collectors.toList());
                MessageMatcher matcher = new MessageMatcher(patterns, titleRegistry);

                // 8) Подписать обработчик на новые сообщения
                router.add(n -> matcher.onUpdateNewMessage(n));

                // 9) Разрешить чаты/вступить
                ChatResolver chatResolver = new ChatResolver(client);
                Set<Long> chatIds = chatResolver.resolveGroups(cfg.groups);
                matcher.setAllowedChats(chatIds);
                log.info("Watching {} chats: {}", chatIds.size(), chatIds);

                // 9.1) SQLite
                DatabaseRouter dbRouter = new DatabaseRouter(Path.of(cfg.tdlib.database_directory), titleRegistry);

                // 9.2) Кэш пользователей + логгер
                UserDirectory userDirectory = new UserDirectory(client);
                MediaDownloader mediaDownloader = new MediaDownloader(client);
                MessageLogger messageLogger = new MessageLogger(
                        dbRouter, userDirectory, titleRegistry, mediaDownloader, Path.of(cfg.tdlib.files_directory));
                messageLogger.setAllowedChats(chatIds);

                // 9.3) Дампер истории
                ChatDumpCoordinator dumper = new ChatDumpCoordinator(client, messageLogger);

                // 9.4) Дамп по событию совпадения
                matcher.setOnMatchListener(chatId -> {
                    messageLogger.enableCapture(chatId);
                    dumper.onPatternMatch(chatId);
                });

                // ★ Автоматический дамп при первом подключении ★
                for (Long chatId : chatIds) {
                    Database dbForChat = dbRouter.forChat(chatId);   // ← берём БД конкретного чата
                    if (!dbForChat.isChatBootstrapped(chatId)) {
                        log.info("First-time bootstrap dump for chat {}", chatId);
                        messageLogger.enableCapture(chatId);
                        dumper.onPatternMatch(chatId);
                        dbForChat.markChatBootstrapped(chatId);
                    }
                }

                // 9.5) Закрытие БД на выходе
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        dbRouter.close();
                    } catch (Exception ignored) {
                    }
                }, "db-shutdown"));

                // небольшой «проброс» истории (как было)
                for (Long chatId : chatIds) {
                    ObjectNode o = Utils.obj("getChatHistory");
                    o.put("chat_id", chatId);
                    o.put("from_message_id", 0);
                    o.put("offset", 0);
                    o.put("limit", 1);
                    o.put("only_local", false);
                    client.request(o, TdJsonClient.Channel.HISTORY);
                }

                log.info("Ready. Listening...");
                //noinspection InfiniteLoopStatement
                while (true) {
                    Thread.sleep(10_000L);
                }
            }
        }
    }

    static final class TDLibErrors {
        static void logIfError(com.fasterxml.jackson.databind.JsonNode n) {
            if ("error".equals(n.path("@type").asText())) {
                int code = n.path("code").asInt();
                String msg = n.path("message").asText();
                System.err.println("TDLIB ERROR: code=" + code + " message=" + msg);
            }
        }
    }
}
