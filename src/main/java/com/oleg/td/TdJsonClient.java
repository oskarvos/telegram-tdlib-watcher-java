// ============================================================================
// File: src/main/java/com/oleg/td/TdJsonClient.java
// Назначение: Обёртка JNA над TDLib. БЕЗ фоновых потоков: приём апдейтов
//              выполняется вызовом pumpOnce() из вызывающего кода.
// ============================================================================
package com.oleg.td;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * TDLib JSON клиент (JNA). Вся логика работы — синхронно в одном потоке.
 */
@Component
public class TdJsonClient {
    private static final Logger log = LoggerFactory.getLogger(TdJsonClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Канал используется лишь для логической группировки. */
    public enum Channel { AUTH, MAIN }

    /** Интерфейс TDLib (JNA). */
    private interface TdLib extends Library {
        Pointer td_json_client_create();
        void td_json_client_send(Pointer client, String request);
        String td_json_client_receive(Pointer client, double timeout);
        void td_json_client_destroy(Pointer client);
    }

    private final UpdateRouter router;
    private final Config config;
    private final AtomicLong extraId = new AtomicLong();
    private final Pointer client;
    private final TdLib tdLib;

    /** Загружаем TDLib (из указанной .so или из системной библиотеки). */
    public TdJsonClient(@Lazy UpdateRouter router, Config config) {
        this.router = router;
        this.config = config;

        String libPath = config.getLibPath();
        if (libPath != null && !libPath.isEmpty()) {
            log.info("Загрузка TDLib из: {}", libPath);
            this.tdLib = Native.load(libPath, TdLib.class);
        } else {
            log.info("Загрузка TDLib из системной библиотеки 'tdjson'");
            this.tdLib = Native.load("tdjson", TdLib.class);
        }
        this.client = tdLib.td_json_client_create();
        log.info("Создан TDLib-клиент {}", System.identityHashCode(this.client));
        initTdlibLogging();
        log.info("Приём обновлений TDLib выполняется в одном потоке (pumpOnce)");
    }

    /** Настраиваем уровень логирования TDLib и отключаем файловый лог. */
    private void initTdlibLogging() {
        ObjectNode verbosity = MAPPER.createObjectNode();
        verbosity.put("@type", "setLogVerbosityLevel");
        verbosity.put("new_verbosity_level", 3);
        send(verbosity);

        ObjectNode setLogStream = MAPPER.createObjectNode();
        setLogStream.put("@type", "setLogStream");
        ObjectNode empty = MAPPER.createObjectNode();
        empty.put("@type", "logStreamEmpty");
        setLogStream.set("log_stream", empty);
        send(setLogStream);
    }

    /** Отправка произвольной JSON-строки в TDLib. */
    public void send(String request) { tdLib.td_json_client_send(client, request); }

    /** Перегрузка с каналом (для читаемости логики). */
    public void send(String request, Channel channel) { send(request); }

    /** Отправка JSON-узла. */
    public void send(ObjectNode req) { send(req.toString()); }

    /** Отправка JSON-узла с каналом. */
    public void send(ObjectNode req, Channel channel) { send(req.toString(), channel); }

    /**
     * Разовый приём одного апдейта TDLib с таймаутом и маршрутизацией в UpdateRouter.
     * Ответы на запросы (с @extra) здесь игнорируются — их ждёт вызывающий метод.
     */
    public void pumpOnce(double timeoutSeconds) {
        String raw = tdLib.td_json_client_receive(client, timeoutSeconds);
        if (raw == null || raw.isBlank()) return;
        try {
            ObjectNode node = (ObjectNode) MAPPER.readTree(raw);
            if (node.has("@extra")) return; // это ответ на конкретный запрос
            router.handleUpdate(node);
        } catch (Exception e) {
            log.error("Ошибка при обработке апдейта TDLib", e);
        }
    }

    /**
     * Синхронное ожидание ответа на конкретный запрос (по полю @extra) с обработкой flood-wait (429).
     * Все "посторонние" апдейты без @extra немедленно передаются в UpdateRouter.
     */
    public ObjectNode requestWithFloodWaitSyncLimited(ObjectNode req, int limitSeconds, Channel channel) {
        int remaining = Math.min(Math.max(limitSeconds, 0), 60);
        String extra = "req-" + extraId.incrementAndGet();
        req.put("@extra", extra);

        while (true) {
            send(req, channel);
            long start = System.currentTimeMillis();
            while (true) {
                String raw = tdLib.td_json_client_receive(client, 2.0);
                if (raw == null || raw.isBlank()) {
                    if (System.currentTimeMillis() - start > 120_000L) {
                        ObjectNode timeout = MAPPER.createObjectNode();
                        timeout.put("@type", "error");
                        timeout.put("code", 408);
                        timeout.put("message", "Таймаут ожидания ответа @extra=" + extra);
                        return timeout;
                    }
                    continue;
                }
                try {
                    ObjectNode node = (ObjectNode) MAPPER.readTree(raw);
                    if (extra.equals(node.path("@extra").asText(null))) {
                        if ("error".equals(node.path("@type").asText()) && node.path("code").asInt() == 429) {
                            int waitSec = extractFloodWait(node.path("message").asText());
                            if (waitSec <= 0) return node;
                            if (waitSec > remaining) {
                                sleep(remaining * 1000L);
                                remaining = 0;
                                break; // сделаем ещё одну попытку
                            }
                            sleep(waitSec * 1000L);
                            remaining -= waitSec;
                            break; // повторим отправку
                        }
                        return node; // успех или другая ошибка
                    }
                    if (!node.has("@extra")) router.handleUpdate(node);
                } catch (Exception e) {
                    log.error("Ошибка парсинга TDLib JSON", e);
                }
            }
        }
    }

    /** Извлекает число секунд из сообщения об ошибке flood-wait. */
    public static int extractFloodWait(String message) {
        var m = Pattern.compile("(\\d+)").matcher(message == null ? "" : message);
        if (m.find()) { try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) { } }
        return -1;
    }

    /** Закрытие клиента TDLib. */
    public void close() {
        if (client != null) {
            tdLib.td_json_client_destroy(client);
            log.info("TDLib клиент закрыт");
        }
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); } }
}
