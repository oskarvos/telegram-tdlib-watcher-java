package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.Console;
import java.io.IOException;

/**
 * Handles interactive authorization with TDLib.
 */
@Component
public class AuthFlow {
    private static final Logger log = LoggerFactory.getLogger(AuthFlow.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TdJsonClient client;

    public AuthFlow(TdJsonClient client) {
        this.client = client;
    }

    /**
     * Runs authorization flow using environment variables and console input.
     */
    public void authorize() {
        log.info("Запрос состояния авторизации");
        client.send(Utils.obj("getAuthorizationState"), TdJsonClient.Channel.AUTH);
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
        client.send(req, TdJsonClient.Channel.AUTH);
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
            ObjectNode resp = client.requestWithFloodWaitSyncLimited(req, 60, TdJsonClient.Channel.AUTH);
            if ("error".equals(resp.path("@type").asText())) {
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
            ObjectNode resp = client.requestWithFloodWaitSyncLimited(req, 60, TdJsonClient.Channel.AUTH);
            if ("error".equals(resp.path("@type").asText())) {
                log.warn("Неверный пароль: {}", resp.path("message").asText());
            } else {
                log.info("Пароль принят");
                break;
            }
        }
    }
}
