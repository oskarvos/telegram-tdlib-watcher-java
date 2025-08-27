package com.oleg.td;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.Console;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * Handles interactive authorization with TDLib.
 */
@Component
public class AuthFlow {
    private static final Logger log = LoggerFactory.getLogger(AuthFlow.class);
    private static final Pattern FLOOD_WAIT = Pattern.compile("(\\d+)");

    private final TdJsonClient client;
    private final UpdateRouter router;
    private final Config config;

    public AuthFlow(TdJsonClient client, UpdateRouter router, Config config) {
        this.client = client;
        this.router = router;
        this.config = config;
    }

    /**
     * Runs authorization flow using environment variables and console input.
     */
    public void authorize() {
        BlockingQueue<ObjectNode> queue = new LinkedBlockingQueue<>();
        Consumer<ObjectNode> handler = node -> {
            if ("updateAuthorizationState".equals(node.path("@type").asText())) {
                queue.offer(node);
            }
        };
        router.add(handler);
        try {
            log.info("Запрос состояния авторизации");
            ObjectNode resp = callWithFloodWait(Utils.obj("getAuthorizationState"));
            if ("error".equals(resp.path("@type").asText())) {
                if (resp.path("code").asInt() == 429) {
                    log.warn("Превышено ожидание flood wait при запросе состояния авторизации");
                    return;
                }
                log.warn("Ошибка при запросе состояния авторизации: {}", resp.path("message").asText());
            }
            while (true) {
                ObjectNode update;
                try {
                    update = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                String state = update.path("authorization_state").path("@type").asText();
                log.info("Состояние авторизации: {}", state);
                switch (state) {
                    case "authorizationStateWaitTdlibParameters" -> handleTdlibParameters();
                    case "authorizationStateWaitPhoneNumber" -> handlePhoneNumber();
                    case "authorizationStateWaitCode" -> handleCode();
                    case "authorizationStateWaitPassword" -> handlePassword();
                    case "authorizationStateReady" -> {
                        log.info("Авторизация завершена");
                        sendWelcomeMessage();
                        return;
                    }
                    default -> {
                        // ignore other states
                    }
                }
            }
        } finally {
            router.remove(handler);
        }
    }

    private void sendWelcomeMessage() {
        String botUsername = config.getBotUsername();
        String welcome = config.getWelcomeMessage();
        if (botUsername == null || botUsername.isBlank() || welcome == null || welcome.isBlank()) {
            return;
        }

        ObjectNode searchReq = Utils.obj("searchPublicChat");
        searchReq.put("username", botUsername);
        ObjectNode searchResp = callWithFloodWait(searchReq);
        if ("error".equals(searchResp.path("@type").asText())) {
            log.warn("Не удалось найти чат {}: {}", botUsername, searchResp.path("message").asText());
            return;
        }

        long chatId = searchResp.path("id").asLong();
        ObjectNode sendReq = Utils.obj("sendMessage");
        sendReq.put("chat_id", chatId);
        ObjectNode content = Utils.obj("inputMessageText");
        ObjectNode text = Utils.obj("formattedText");
        text.put("text", welcome);
        content.set("text", text);
        sendReq.set("input_message_content", content);
        ObjectNode resp = callWithFloodWait(sendReq);
        if ("error".equals(resp.path("@type").asText())) {
            log.warn("Ошибка при отправке приветственного сообщения: {}", resp.path("message").asText());
        }
    }

    private void handlePhoneNumber() {
        String phone = System.getenv("TG_PHONE");
        if (phone == null || phone.isBlank()) {
            phone = System.getenv("TELEGRAM_PHONE");
        }
        if (phone == null || phone.isBlank()) {
            throw new IllegalStateException(
                    "Не задан номер телефона в переменных среды TG_PHONE или TELEGRAM_PHONE");
        }
        ObjectNode req = Utils.obj("setAuthenticationPhoneNumber");
        req.put("phone_number", phone);
        ObjectNode resp = callWithFloodWait(req);
        if ("error".equals(resp.path("@type").asText()) && resp.path("code").asInt() == 429) {
            log.warn("Превышено ожидание flood wait при отправке номера телефона");
            return;
        }
        log.info("Отправлен номер телефона");
    }

    private void handleCode() {
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("Консоль недоступна");
        }
        while (true) {
            String code = console.readLine("Введите код: ");
            ObjectNode req = Utils.obj("checkAuthenticationCode");
            req.put("code", code);
            ObjectNode resp = callWithFloodWait(req);
            if ("error".equals(resp.path("@type").asText())) {
                if (resp.path("code").asInt() == 429) {
                    log.warn("Превышено ожидание flood wait при проверке кода");
                    return;
                }
                log.warn("Неверный код: {}", resp.path("message").asText());
            } else {
                log.info("Код принят");
                break;
            }
        }
    }

    private void handleTdlibParameters() {
        Config.Tdlib td = config.getTdlib();
        ObjectNode req = Utils.obj("setTdlibParameters");
        ObjectNode params = Utils.obj("tdlibParameters");
        params.put("use_test_dc", false);
        params.put("database_directory", td.getDatabaseDirectory());
        params.put("files_directory", td.getFilesDirectory());
        params.put("use_file_database", true);
        params.put("use_chat_info_database", true);
        params.put("use_message_database", true);
        params.put("use_secret_chats", false);
        params.put("api_id", td.getApiId());
        params.put("api_hash", td.getApiHash());
        params.put("system_language_code", td.getSystemLanguageCode());
        params.put("device_model", td.getDeviceModel());
        params.put("system_version", td.getSystemVersion());
        params.put("application_version", td.getApplicationVersion());
        params.put("enable_storage_optimizer", true);
        params.put("ignore_file_names", false);
        req.set("parameters", params);
        ObjectNode resp = callWithFloodWait(req);
        if ("error".equals(resp.path("@type").asText()) && resp.path("code").asInt() == 429) {
            log.warn("Превышено ожидание flood wait при отправке параметров TDLib");
            return;
        }
        log.info("Отправлены параметры TDLib");
    }

    private void handlePassword() {
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("Консоль недоступна");
        }
        while (true) {
            char[] passChars = console.readPassword("Введите пароль: ");
            String pass = passChars == null ? "" : new String(passChars);
            ObjectNode req = Utils.obj("checkAuthenticationPassword");
            req.put("password", pass);
            ObjectNode resp = callWithFloodWait(req);
            if ("error".equals(resp.path("@type").asText())) {
                if (resp.path("code").asInt() == 429) {
                    log.warn("Превышено ожидание flood wait при проверке пароля");
                    return;
                }
                log.warn("Неверный пароль: {}", resp.path("message").asText());
            } else {
                log.info("Пароль принят");
                break;
            }
        }
    }

    private ObjectNode callWithFloodWait(ObjectNode req) {
        int waited = 0;
        ObjectNode resp;
        while (true) {
            resp = client.requestWithFloodWaitSyncLimited(req, 60 - waited, TdJsonClient.Channel.AUTH);
            if ("error".equals(resp.path("@type").asText()) && resp.path("code").asInt() == 429) {
                int waitSec = extractFloodWait(resp.path("message").asText());
                log.warn("Flood wait {}s for {}", waitSec, req.path("@type").asText());
                waited += waitSec;
                if (waited >= 60) {
                    return resp;
                }
                continue;
            }
            return resp;
        }
    }

    private static int extractFloodWait(String message) {
        Matcher m = FLOOD_WAIT.matcher(message);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }
}
