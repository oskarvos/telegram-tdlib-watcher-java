package com.oleg.td.integrations.tdlibs;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oleg.td.app.Config;
import com.oleg.td.common.Utils;
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

    /**
     * Подписывается на обновления авторизации.
     */
    public void wireInto() {
        if (wired) {
            log.info("AuthFlow уже подключен");
            return;
        }
        log.info("Подключение AuthFlow к UpdateRouter...");

        router.add(n -> {
            final String type = n.path("@type").asText();
            if ("updateAuthorizationState".equals(type)) {
                var authState = n.path("authorization_state");
                String state = authState.path("@type").asText();
                stateRef.set(state);
                log.info("ОТЛАДКА АВТОРИЗАЦИИ: состояние = {}", state);
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
        client.send(Utils.obj("getAuthorizationState"), TdJsonClient.Channel.AUTH);
    }

    /**
     * Блокирующая машина состояний. Однопоточная: мы обрабатываем получение данных внутри этого цикла.
     */
    public void authorizeBlocking() {
        if (!wired) throw new IllegalStateException("AuthFlow не инициализирован. Сначала вызовите wireInto()");

        log.info("Начало процесса авторизации...");
        client.send(Utils.obj("getAuthorizationState"), TdJsonClient.Channel.AUTH);

        String last = null;
        long startTime = System.currentTimeMillis();
        long timeoutMs = 30_000L;

        while (!authorized.get() && (System.currentTimeMillis() - startTime) < timeoutMs) {
            client.pumpOnce(1.5); // получает ровно одно обновление (если есть) в этом потоке

            String state = stateRef.get();
            if (state == null) {
                client.send(Utils.obj("getAuthorizationState"), TdJsonClient.Channel.AUTH);
                sleep(500);
                continue;
            }
            if (state.equals(last)) {
                sleep(100);
                continue;
            }
            log.info("Текущее состояние авторизации: {}", state);

            try {
                switch (state) {
                    case "authorizationStateWaitTdlibParameters" -> sendTdParams();
                    case "authorizationStateWaitPhoneNumber" -> sendPhoneNumber();
                    case "authorizationStateWaitCode" -> sendCodeWithRetry();
                    case "authorizationStateWaitPassword" -> sendPassword();
                    case "authorizationStateReady" -> authorized.set(true);
                    case "authorizationStateClosed" -> throw new RuntimeException("Авторизация закрыта");
                    case "authorizationStateLoggingOut" -> throw new RuntimeException("Выход из системы");
                    default -> sleep(300);
                }
            } catch (Exception e) {
                log.error("Ошибка обработки состояния {}: {}", state, e.getMessage(), e);
                throw new RuntimeException("Ошибка авторизации в состоянии: " + state, e);
            }
            last = state;
        }
        if (!authorized.get()) throw new RuntimeException("Таймаут авторизации после " + timeoutMs + " мс");
    }

    private void sendTdParams() {
        ObjectNode p = Utils.obj("setTdlibParameters");

        String osName = System.getProperty("os.name", "OS");
        String osVersion = System.getProperty("os.version", "");
        String systemVersion = osName + " " + osVersion;

        String phoneDigits = (cfg.getAuth() != null && cfg.getAuth().getPhone() != null)
                ? cfg.getAuth().getPhone().replaceAll("\\D", "")
                : "unknown";
        String suffix = cfg.getTdlib().getApiId() + "_" + phoneDigits;

        p.put("use_test_dc", cfg.getUseTestDc());
        p.put("database_directory", cfg.getTdlib().getDatabaseDirectory() + "/" + suffix);
        p.put("files_directory", cfg.getTdlib().getFilesDirectory() + "/" + suffix);
        p.put("use_file_database", true);
        p.put("use_chat_info_database", true);
        p.put("use_message_database", true);
        p.put("use_secret_chats", false);
        p.put("api_id", cfg.getTdlib().getApiId());
        p.put("api_hash", cfg.getTdlib().getApiHash());
        p.put("system_language_code", cfg.getTdlib().getSystemLanguageCode());
        p.put("device_model", cfg.getTdlib().getDeviceModel());
        p.put("system_version", systemVersion);
        p.put("application_version", cfg.getTdlib().getApplicationVersion());
        p.put("enable_storage_optimizer", true);
        p.put("ignore_file_names", true);
        p.put("database_encryption_key", "");

        log.info("ОТЛАДКА setTdlibParameters JSON --> {}", p.toString());
        client.send(p, TdJsonClient.Channel.AUTH);
        log.info("Параметры TDLib отправлены");
    }

    private void sendPhoneNumber() {
        String phone = cfg.getAuth() != null && cfg.getAuth().getPhone() != null
                ? cfg.getAuth().getPhone().trim()
                : readValue("Введите номер телефона (+xxxxxxxxxxx): ");

        ObjectNode req = Utils.obj("setAuthenticationPhoneNumber");
        req.put("phone_number", phone);
        ObjectNode settings = req.putObject("settings");
        settings.put("@type", "phoneNumberAuthenticationSettings");
        settings.put("allow_flash_call", false);
        settings.put("is_current_phone_number", true);
        settings.put("allow_sms_retriever_api", false);

        ObjectNode resp = client.requestWithFloodWaitSyncLimited(req, 60, TdJsonClient.Channel.AUTH);
        if ("error".equals(resp.path("@type").asText())) {
            int code = resp.path("code").asInt();
            String msg = resp.path("message").asText();
            log.error("ОШИБКА АВТОРИЗАЦИИ при отправке номера: код={} сообщение={}", code, msg);
        } else {
            log.info("ОТЛАДКА АВТОРИЗАЦИИ: номер телефона отправлен");
            log.info("Номер телефона отправлен: {}", phone);
        }
    }

    private void sendCodeWithRetry() {
        while (true) {
            String code = cfg.getAuth() != null && cfg.getAuth().getCode() != null
                    ? cfg.getAuth().getCode().trim()
                    : readValue("Введите код из Telegram: ");

            ObjectNode req = Utils.obj("checkAuthenticationCode");
            req.put("code", code);
            ObjectNode resp = client.requestWithFloodWaitSyncLimited(req, 60, TdJsonClient.Channel.AUTH);

            if ("error".equals(resp.path("@type").asText())
                    && resp.path("code").asInt() == 400
                    && resp.path("message").asText().toLowerCase().contains("code")) {
                if (cfg.getAuth() != null) cfg.getAuth().setCode(null);
                log.warn("Неверный код, попробуйте снова");
                continue;
            }
            log.info("Код отправлен");
            break;
        }
    }

    private void sendPassword() {
        String password = cfg.getAuth() != null && cfg.getAuth().getPass() != null
                ? cfg.getAuth().getPass().trim()
                : readValue("Введите пароль двухфакторной аутентификации: ");
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
            return input == null ? "" : input.trim();
        }
        System.out.print(prompt);
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Нет доступного ввода: " + e.getMessage());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isAuthorized() {
        return authorized.get();
    }
}