package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.Console;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles interactive authorization with TDLib.
 */
@Component
public class AuthFlow {
    private static final Logger log = LoggerFactory.getLogger(AuthFlow.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern FLOOD_WAIT = Pattern.compile("(\\d+)");

    private final TdJsonClient client;

    public AuthFlow(TdJsonClient client) {
        this.client = client;
    }

    /**
     * Runs authorization flow using environment variables and console input.
     */
    public void authorize() {
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
            String updateStr = client.receive(60);
            if (updateStr == null) {
                continue;
            }
            try {
                JsonNode update = MAPPER.readTree(updateStr);
                String type = update.path("@type").asText();
                if (!"updateAuthorizationState".equals(type)) {
                    continue;
                }
                String state = update.path("authorization_state").path("@type").asText();
                log.info("Состояние авторизации: {}", state);
                switch (state) {
                    case "authorizationStateWaitPhoneNumber" -> handlePhoneNumber();
                    case "authorizationStateWaitCode" -> handleCode();
                    case "authorizationStateWaitPassword" -> handlePassword();
                    case "authorizationStateReady" -> {
                        log.info("Авторизация завершена");
                        return;
                    }
                    default -> {
                        // ignore other states
                    }
                }
            } catch (IOException e) {
                log.error("Не удалось разобрать ответ TDLib", e);
            }
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
