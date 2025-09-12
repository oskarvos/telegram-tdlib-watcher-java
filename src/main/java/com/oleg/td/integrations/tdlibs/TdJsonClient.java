package com.oleg.td.integrations.tdlibs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oleg.td.app.config.AppProperties;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Обёртка над tdjson (JNA).
 * Отправка запросов и синхронное ожидание ответов.
 */
@Component
public class TdJsonClient {

    private static final Logger log = LoggerFactory.getLogger(TdJsonClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum Channel { AUTH, MAIN }

    private interface TdLib extends Library {
        Pointer td_json_client_create();
        void td_json_client_send(Pointer client, String request);
        String td_json_client_receive(Pointer client, double timeout);
        void td_json_client_destroy(Pointer client);
    }

    private final UpdateRouter router;         // маршрутизатор обновлений
    private final AppProperties appProps;      // конфиг приложения
    private final AtomicLong extraId = new AtomicLong(); // счётчик @extra
    private final Pointer client;              // указатель TDLib клиента
    private final TdLib tdLib;                 // биндинги JNA
    private final Object recvLock = new Object(); // монитор приёма TDLib

    public TdJsonClient(@Lazy UpdateRouter router, AppProperties appProps) {
        this.router = router;
        this.appProps = appProps;

        String libPath = appProps.getLibPath();
        if (libPath != null && !libPath.isEmpty()) {
            log.info("Загрузка TDLib из: {}", libPath);
            this.tdLib = Native.load(libPath, TdLib.class);
        } else {
            log.info("Загрузка системной библиотеки 'tdjson'");
            this.tdLib = Native.load("tdjson", TdLib.class);
        }
        this.client = tdLib.td_json_client_create();
        log.info("TDLib клиент создан [{}]", System.identityHashCode(this.client));
        initTdlibLogging();
        log.info("Приём обновлений TDLib запущен (single-threaded pump)");
    }

    // инициализация логирования TDLib
    private void initTdlibLogging() {
        ObjectNode lvl = MAPPER.createObjectNode();
        lvl.put("@type", "setLogVerbosityLevel");
        lvl.put("new_verbosity_level", 1);
        send(lvl);

        ObjectNode setLogStream = MAPPER.createObjectNode();
        setLogStream.put("@type", "setLogStream");
        ObjectNode file = MAPPER.createObjectNode();
        file.put("@type", "logStreamFile");
        file.put("path", "tdlib/tdlib.log");
        file.put("max_file_size", 64 * 1024 * 1024);
        file.put("redirect_stderr", false);
        setLogStream.set("log_stream", file);
        send(setLogStream);
    }

    // отправка JSON-строкой
    public void send(String request) {
        tdLib.td_json_client_send(client, request);
    }

    // перегрузка с каналом (для совместимости)
    public void send(String request, Channel channel) {
        send(request);
    }

    // отправка ObjectNode
    public void send(ObjectNode req) { send(req.toString()); }

    // отправка ObjectNode c каналом
    public void send(ObjectNode req, Channel channel) { send(req.toString(), channel); }

    // однократный приём и маршрутизация обновления (без @extra)
    public void pumpOnce(double timeoutSeconds) {
        String raw;
        synchronized (recvLock) {
            raw = tdLib.td_json_client_receive(client, timeoutSeconds);
        }
        if (raw == null || raw.isBlank()) return;
        try {
            ObjectNode node = (ObjectNode) MAPPER.readTree(raw);
            if (node.has("@extra")) return; // ответ на запрос, обрабатывается в ожидании
            router.handleUpdate(node);
        } catch (Exception e) {
            log.error("Ошибка обработки обновления TDLib", e);
        }
    }

    // отправка запроса с ожиданием ответа по @extra и ограничением по FLOOD_WAIT
    public ObjectNode requestWithFloodWaitSyncLimited(ObjectNode req, int limitSeconds, Channel channel) {
        int remaining = Math.max(limitSeconds, 0);
        String extra = "req-" + extraId.incrementAndGet();
        req.put("@extra", extra);

        while (true) {
            send(req, channel);
            long start = System.currentTimeMillis();

            while (true) {
                String raw;
                synchronized (recvLock) {
                    raw = tdLib.td_json_client_receive(client, 2.0);
                }
                if (raw == null || raw.isBlank()) {
                    // ждём не дольше 120 секунд ответа конкретно на этот @extra
                    if (System.currentTimeMillis() - start > 120_000L) {
                        ObjectNode timeout = MAPPER.createObjectNode();
                        timeout.put("@type", "error");
                        timeout.put("code", 408);
                        timeout.put("message", "Таймаут ожидания ответа для @extra=" + extra);
                        return timeout;
                    }
                    continue;
                }

                try {
                    ObjectNode node = (ObjectNode) MAPPER.readTree(raw);

                    if (extra.equals(node.path("@extra").asText(null))) {
                        boolean floodWait = "error".equals(node.path("@type").asText())
                                && node.path("code").asInt() == 429;
                        if (floodWait) {
                            int waitSec = extractFloodWait(node.path("message").asText());
                            if (waitSec <= 0) return node; // нераспознанный FLOOD_WAIT
                            if (waitSec > remaining) {
                                sleep(remaining * 1000L);
                                remaining = 0;
                                break; // повторная отправка и возврат следующего ответа
                            }
                            sleep(waitSec * 1000L);
                            remaining -= waitSec;
                            break; // повторная отправка
                        }
                        return node; // успех или ошибка не 429
                    }

                    if (!node.has("@extra")) router.handleUpdate(node); // сторонние апдейты
                } catch (Exception e) {
                    log.error("Ошибка парсинга JSON TDLib", e);
                }
            }
        }
    }

    // извлекает секунды из сообщения FLOOD_WAIT
    public static int extractFloodWait(String message) {
        var m = Pattern.compile("(\\d+)").matcher(message == null ? "" : message);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    public void close() {
        if (client != null) {
            tdLib.td_json_client_destroy(client);
            log.info("TDLib клиент закрыт");
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
