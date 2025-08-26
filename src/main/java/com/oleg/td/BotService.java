package com.oleg.td;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class BotService implements AutoCloseable {

    public enum State { STOPPED, STARTING, RUNNING, STOPPING, FAILED }

    private volatile State state = State.STOPPED;
    private final Object stateLock = new Object();

    // Конфиг и компоненты
    private Config cfg;
    private LockManager lock;
    private TDLib td;
    private TdJsonClient client;
    private UpdateRouter router;
    private ChatTitleRegistry titleRegistry;
    private AuthFlow auth;
    private MessageMatcher matcher;
    private DatabaseRouter dbRouter;
    private UserDirectory users;
    private MediaDownloader media;
    private MessageLogger logger;
    private ChatDumpCoordinator dumper;

    private Set<Long> chatIds;

    public State getState() { return state; }
    public boolean isRunning() { return state == State.RUNNING; }

    public synchronized void start() throws Exception {
        if (state == State.RUNNING || state == State.STARTING) return;
        state = State.STARTING;

        try {
            // 0) конфиг
            cfg = Utils.loadJsonResource("/config.json", Config.class);

            // Развести каталоги по api_id+телефону
            String phoneSan = (cfg.auth != null && cfg.auth.phone != null) ? cfg.auth.phone.replaceAll("\\D","") : "unknown";
            cfg.tdlib.database_directory = Path.of(cfg.tdlib.database_directory, cfg.tdlib.api_id + "_" + phoneSan).toString();
            cfg.tdlib.files_directory    = Path.of(cfg.tdlib.files_directory,    cfg.tdlib.api_id + "_" + phoneSan).toString();
            Files.createDirectories(Path.of(cfg.tdlib.database_directory));
            Files.createDirectories(Path.of(cfg.tdlib.files_directory));

            // 1) single-instance lock
            lock = LockManager.tryLock(Path.of(cfg.tdlib.database_directory, ".app.lock"));
            if (!lock.isAcquired()) throw new IllegalStateException("Already running (lock acquired by another process)");

            // 2) TDLib загрузка и клиент
            td = TDLibLoader.loadFromEnvOrConfig(cfg);
            TDLibLoader.configureVerbosity(td, EnvVars.getInt("TDLIB_VERBOSITY", 1));

            client = new TdJsonClient(td);

            // Глушим лог TDLib
            {
                var s = Utils.obj("setLogStream");
                var ls = s.putObject("log_stream");
                ls.put("@type", "logStreamEmpty");
                client.send(s);
                var v = Utils.obj("setLogVerbosityLevel");
                v.put("new_verbosity_level", 0);
                client.send(v);
            }

            // 3) роутер, кеш названий
            titleRegistry = new ChatTitleRegistry();
            router = new UpdateRouter(new CopyOnWriteArrayList<>());
            client.addUpdateHandler(router::dispatch);
            router.add(n -> App.TDLibErrors.logIfError(n));
            router.add(titleRegistry::onUpdateNewChat);

            // 4) авторизация (блокирующая)
            auth = new AuthFlow(client, cfg);
            auth.wireInto(router);
            auth.authorizeBlocking();

            // 5) паттерны
            int flags = cfg.case_insensitive ? Pattern.CASE_INSENSITIVE : 0;
            List<MessageMatcher.PatternEntry> patterns = cfg.patterns.stream()
                    .map(p -> new MessageMatcher.PatternEntry(p.name, Pattern.compile(p.regex, flags)))
                    .collect(Collectors.toList());
            matcher = new MessageMatcher(patterns, titleRegistry);
            router.add(matcher::onUpdateNewMessage);

            // 6) резолвим чаты и входим
            ChatResolver resolver = new ChatResolver(client);
            chatIds = resolver.resolveGroups(cfg.groups);
            matcher.setAllowedChats(chatIds);

            // 7) БД + логгер + дампер
            dbRouter = new DatabaseRouter(Path.of(cfg.tdlib.database_directory), titleRegistry);
            users = new UserDirectory(client);
            media = new MediaDownloader(client);
            logger = new MessageLogger(dbRouter, users, titleRegistry, media, Path.of(cfg.tdlib.files_directory));
            logger.setAllowedChats(chatIds);
            dumper = new ChatDumpCoordinator(client, logger);

            // 8) по совпадению паттерна — включаем capture и дампим
            matcher.setOnMatchListener(chatId -> {
                logger.enableCapture(chatId);
                dumper.onPatternMatch(chatId);
            });

            // 9) начальный дамп (bootstrap) для каждого чата
            for (Long chatId : chatIds) {
                Database dbForChat = dbRouter.forChat(chatId);
                if (!dbForChat.isChatBootstrapped(chatId)) {
                    System.out.printf("First-time bootstrap dump for chat %d%n", chatId);
                    logger.enableCapture(chatId);
                    boolean ok = dumper.dumpWholeChatSync(chatId);
                    if (ok) {
                        dbForChat.markChatBootstrapped(chatId);
                        System.out.printf("Bootstrap dump finished for chat %d%n", chatId);
                    } else {
                        System.out.printf("Bootstrap dump did NOT finish for chat %d%n", chatId);
                    }
                }
            }

            // 10) лёгкий «проброс» истории (как было)
            for (Long chatId : chatIds) {
                ObjectNode o = Utils.obj("getChatHistory");
                o.put("chat_id", chatId);
                o.put("from_message_id", 0);
                o.put("offset", 0);
                o.put("limit", 1);
                o.put("only_local", false);
                client.request(o, TdJsonClient.Channel.HISTORY);
            }

            state = State.RUNNING;
            System.out.println("BotService: READY.");
        } catch (Exception e) {
            state = State.FAILED;
            e.printStackTrace();
            throw e;
        }
    }

    /** Принудительный дамп указанного чата (включает capture). */
    public synchronized boolean dumpNow(long chatId) {
        if (client == null || dumper == null) return false;
        logger.enableCapture(chatId);
        return dumper.dumpWholeChatSync(chatId);
    }

    public Set<Long> getWatchedChats() { return chatIds; }

    @Override public synchronized void close() {
        try { state = State.STOPPING; } catch (Exception ignored) {}
        try { if (dbRouter != null) dbRouter.close(); } catch (Exception ignored) {}
        try { if (client != null) client.close(); } catch (Exception ignored) {}
        try { if (lock != null) lock.close(); } catch (Exception ignored) {}
        state = State.STOPPED;
        System.out.println("BotService: stopped.");
    }
}
