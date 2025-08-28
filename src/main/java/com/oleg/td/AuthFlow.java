package com.oleg.td;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.io.Console;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class AuthFlow {
    private static final Logger log = LoggerFactory.getLogger(AuthFlow.class);

    private final TdJsonClient client;
    private final Config cfg;
    private final UpdateRouter router;

    private final AtomicBoolean authorized = new AtomicBoolean(false);
    private final AtomicReference<String> stateRef = new AtomicReference<>(null);
    private boolean wired = false;

    public AuthFlow(TdJsonClient client, Config cfg, UpdateRouter router) {
        this.client = client;
        this.cfg = cfg;
        this.router = router;
    }

    /** Подписываемся на апдейты авторизации. */
    public void wireInto() {
        if (wired) {
            log.info("AuthFlow уже подключен");
            return;
        }

        log.info("Подключение AuthFlow к UpdateRouter...");

        router.add(n -> {
            String type = n.path("@type").asText();

            if ("error".equals(type)) {
                int errorCode = n.path("code").asInt();
                String errorMessage = n.path("message").asText();
                log.error("Ошибка TDLib: {} - {}", errorCode, errorMessage);
                if (errorCode == 429) {
                    int waitTime = TdJsonClient.extractFloodWait(errorMessage);
                    log.info("Flood wait: {} секунд", waitTime);
                    try { Thread.sleep(Math.max(0, waitTime) * 1000L); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                return;
            }

            if ("updateAuthorizationState".equals(type)) {
                com.fasterxml.jackson.databind.node.ObjectNode authState = (com.fasterxml.jackson.databind.node.ObjectNode) n.path("authorization_state");
                String state = authState.path("@type").asText();
                log.info("AUTH DEBUG: state = {}", state);
                stateRef.set(state);

                if ("authorizationStateReady".equals(state)) {
                    authorized.set(true);
                    log.info("Авторизация прошла успешно!");
                } else if ("authorizationStateClosed".equals(state)) {
                    authorized.set(false);
                    log.info("Авторизация закрыта");
                }
            }
        });

        wired = true;
        log.info("AuthFlow успешно подключен");

        // 👇 ДОБАВИТЬ ЭТО: не ждём первого апдейта, сразу спрашиваем текущее состояние
        client.send(Utils.obj("getAuthorizationState"), TdJsonClient.Channel.AUTH);
    }

    /** Блокирующая авторизация — проходит все шаги. */
    public void authorizeBlocking() {
        if (!wired) throw new IllegalStateException("AuthFlow не инициализирован. Сначала вызовите wireInto()");

        log.info("Начало процесса авторизации...");

        // маленькая страховка
        client.send(Utils.obj("getAuthorizationState"), TdJsonClient.Channel.AUTH);

        String last = null;
        long startTime = System.currentTimeMillis();
        long timeout = 300_000;

        while (!authorized.get() && (System.currentTimeMillis() - startTime) < timeout) {
            String state = stateRef.get();
            if (state == null) {
                // через секунду повторим запрос состояния, если ничего не пришло
                try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                client.send(Utils.obj("getAuthorizationState"), TdJsonClient.Channel.AUTH);
                continue;
            }

            if (state == null) {
                log.info("Ожидание состояния авторизации...");
                sleep(1000);
                continue;
            }

            if (state.equals(last)) {
                sleep(100);
                continue;
            }

            log.info("Текущее состояние авторизации: {}", state);

            try {
                switch (state) {
                    case "authorizationStateWaitTdlibParameters" -> {
                        log.info("Отправка параметров TDLib...");
                        sendTdParams();
                    }
                    case "authorizationStateWaitPhoneNumber" -> sendPhoneNumber();
                    case "authorizationStateWaitCode" -> sendCode();
                    case "authorizationStateWaitPassword" -> sendPassword();
                    case "authorizationStateReady" -> {
                        authorized.set(true);
                        log.info("Авторизация успешно завершена!");
                    }
                    case "authorizationStateClosed" -> {
                        throw new RuntimeException("Авторизация закрыта");
                    }
                    case "authorizationStateLoggingOut" -> {
                        throw new RuntimeException("Выход из системы");
                    }
                    default -> {
                        log.info("Ожидание в состоянии: {}", state);
                        sleep(1000);
                    }
                }
            } catch (Exception e) {
                log.error("Ошибка обработки состояния {}: {}", state, e.getMessage(), e);
                throw new RuntimeException("Ошибка авторизации в состоянии: " + state, e);
            }

            last = state;
            sleep(100);
        }

        if (!authorized.get()) {
            throw new RuntimeException("Таймаут авторизации после " + timeout + " мс");
        }
    }

    private void sendTdParams() {
        ObjectNode params = Utils.obj("setTdlibParameters");

        // Сбор system_version, как в примере (Linux <kernel>)
        String osName = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");
        String systemVersion = (osName != null ? osName : "OS") + " " + (osVersion != null ? osVersion : "");

        // Хвост вида apiId_phoneOnlyDigits
        String phoneDigits = (cfg.getAuth() != null && cfg.getAuth().getPhone() != null)
                ? cfg.getAuth().getPhone().replaceAll("\\D", "")
                : "unknown";
        String suffix = cfg.getTdlib().getApiId() + "_" + phoneDigits;

        String dbBase = cfg.getTdlib().getDatabaseDirectory();
        String filesBase = cfg.getTdlib().getFilesDirectory();

        params.put("use_test_dc", cfg.getUseTestDc());
        params.put("database_directory", dbBase + "/" + suffix);
        params.put("files_directory", filesBase + "/" + suffix);

        // Рекомендуемые флаги / как в образце
        params.put("use_file_database", true);
        params.put("use_chat_info_database", true);
        params.put("use_message_database", true);
        params.put("use_secret_chats", false);

        params.put("api_id", cfg.getTdlib().getApiId());
        params.put("api_hash", cfg.getTdlib().getApiHash());
        params.put("system_language_code", cfg.getTdlib().getSystemLanguageCode());
        params.put("device_model", cfg.getTdlib().getDeviceModel());
        params.put("system_version", systemVersion);
        params.put("application_version", cfg.getTdlib().getApplicationVersion());
        params.put("enable_storage_optimizer", true);
        params.put("ignore_file_names", true);
        params.put("database_encryption_key", ""); // при необходимости замените на реальный ключ

        log.info("DEBUG setTdlibParameters JSON --> {}", params.toString());
        client.send(params, TdJsonClient.Channel.AUTH);
        log.info("Параметры TDLib отправлены");
    }

    private void sendPhoneNumber() {
        String phone = cfg.getAuth() != null && cfg.getAuth().getPhone() != null
                ? cfg.getAuth().getPhone().trim()
                : readValue("Enter phone number (+xxxxxxxxxxx): ");
        ObjectNode req = Utils.obj("setAuthenticationPhoneNumber");
        req.put("phone_number", phone);
        client.send(req, TdJsonClient.Channel.AUTH);
        log.info("AUTH DEBUG: phone submitted");
    }

    private void sendCode() {
        String code = cfg.getAuth() != null && cfg.getAuth().getCode() != null
                ? cfg.getAuth().getCode().trim()
                : readValue("Enter code from Telegram: ");
        ObjectNode req = Utils.obj("checkAuthenticationCode");
        req.put("code", code);
        client.send(req, TdJsonClient.Channel.AUTH);
        log.info("Код отправлен");
    }

    private void sendPassword() {
        String password = cfg.getAuth() != null && cfg.getAuth().getPass() != null
                ? cfg.getAuth().getPass().trim()
                : readValue("Enter 2FA password: ");
        ObjectNode req = Utils.obj("checkAuthenticationPassword");
        req.put("password", password);
        client.send(req, TdJsonClient.Channel.AUTH);
        log.info("Пароль отправлен");
    }

    private String readValue(String prompt) {
        Console console = System.console();
        if (console != null) {
            System.out.print(prompt);
            String input = console.readLine();
            if (input != null) return input.trim();
        }
        System.out.print(prompt);
        try (java.io.BufferedReader reader =
                     new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Нет доступного ввода: " + e.getMessage());
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isAuthorized() {
        return authorized.get();
    }
}
