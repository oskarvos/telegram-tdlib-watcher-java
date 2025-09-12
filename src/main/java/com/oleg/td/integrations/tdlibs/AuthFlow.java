package com.oleg.td.integrations.tdlibs;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oleg.td.app.config.AppProperties;
import com.oleg.td.app.config.TdlibProperties;
import com.oleg.td.auth.service.AuthRuntimeStore;
import com.oleg.td.common.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.Console;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Процесс авторизации TDLib.
 * Машина состояний + подписка на обновления.
 */
@Component
public class AuthFlow {

    private static final Logger log = LoggerFactory.getLogger(AuthFlow.class);

    private final TdJsonClient client;             // TDLib клиент
    private final TdlibProperties td;              // настройки TDLib
    private final AppProperties app;               // настройки приложения
    private final AuthRuntimeStore authStore;      // хранилище ввода
    private final UpdateRouter router;             // маршрутизатор обновлений

    private final AtomicBoolean authorized = new AtomicBoolean(false);  // авторизован?
    private final AtomicReference<String> stateRef = new AtomicReference<>(null); // текущее состояние TDLib
    private boolean wired = false;                 // подключён к роутеру?

    public AuthFlow(TdJsonClient client, TdlibProperties td, AppProperties app, UpdateRouter router, AuthRuntimeStore authStore) {
        this.client = client;
        this.td = td;
        this.app = app;
        this.router = router;
        this.authStore = authStore;
    }

    // подключение слушателя к UpdateRouter
    public void wireInto() {
        if (wired) {
            log.info("AuthFlow уже подключён");
            return;
        }
        log.info("Подключаю AuthFlow к UpdateRouter...");

        router.add(n -> {
            String type = n.path("@type").asText();
            if ("updateAuthorizationState".equals(type)) {
                var authState = n.path("authorization_state");
                String state = authState.path("@type").asText();
                stateRef.set(state);
                log.info("Состояние авторизации: {}", state);

                if ("authorizationStateReady".equals(state)) {
                    authorized.set(true);
                    log.info("Авторизация завершена успешно");
                } else if ("authorizationStateClosed".equals(state)) {
                    authorized.set(false);
                    log.info("Авторизация закрыта");
                }
            }
        });

        wired = true;
        log.info("AuthFlow подключён");
        client.send(Utils.obj("getAuthorizationState"), TdJsonClient.Channel.AUTH);
    }

    // блокирующая авторизация по состояниям TDLib
    public void authorizeBlocking() {
        if (!wired) throw new IllegalStateException("AuthFlow не инициализирован. Вызовите wireInto()");

        log.info("Запуск процесса авторизации...");
        client.send(Utils.obj("getAuthorizationState"), TdJsonClient.Channel.AUTH);

        String last = null;
        long startTime = System.currentTimeMillis();
        long timeoutMs = 180_000L;

        while (!authorized.get() && (System.currentTimeMillis() - startTime) < timeoutMs) {
            client.pumpOnce(1.5); // один цикл получения обновлений

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
            log.info("Текущее состояние: {}", state);

            try {
                switch (state) {
                    case "authorizationStateWaitTdlibParameters" -> sendTdParams();
                    case "authorizationStateWaitPhoneNumber"     -> sendPhoneNumber();
                    case "authorizationStateWaitCode"            -> sendCodeWithRetry();
                    case "authorizationStateWaitPassword"        -> sendPassword();
                    case "authorizationStateReady"               -> authorized.set(true);
                    case "authorizationStateClosed"              -> throw new RuntimeException("Авторизация закрыта");
                    case "authorizationStateLoggingOut"          -> throw new RuntimeException("Выход из системы");
                    default                                      -> sleep(300);
                }
            } catch (Exception e) {
                log.error("Ошибка авторизации в состоянии {}: {}", state, e.getMessage(), e);
                throw new RuntimeException("Ошибка авторизации: " + state, e);
            }
            last = state;
        }
        if (!authorized.get()) throw new RuntimeException("Таймаут авторизации: " + timeoutMs + " мс");
    }

    // отправка параметров TDLib
    private void sendTdParams() {
        ObjectNode p = Utils.obj("setTdlibParameters");

        String osName = System.getProperty("os.name", "OS");
        String osVersion = System.getProperty("os.version", "");
        String systemVersion = osName + " " + osVersion;

        String phoneDigits = authStore.get().getPhone() != null
                ? authStore.get().getPhone().replaceAll("\\D", "")
                : "unknown"; // очищает от нецифр
        String suffix = td.getApiId() + "_" + phoneDigits;

        p.put("use_test_dc", app.getUseTestDc());
        p.put("database_directory", td.getDatabaseDirectory() + "/" + suffix);
        p.put("files_directory", td.getFilesDirectory() + "/" + suffix);
        p.put("use_file_database", true);
        p.put("use_chat_info_database", true);
        p.put("use_message_database", true);
        p.put("use_secret_chats", false);
        p.put("api_id", td.getApiId());
        p.put("api_hash", td.getApiHash());
        p.put("system_language_code", td.getSystemLanguageCode());
        p.put("device_model", td.getDeviceModel());
        p.put("system_version", systemVersion);
        p.put("application_version", td.getApplicationVersion());
        p.put("enable_storage_optimizer", true);
        p.put("ignore_file_names", true);
        p.put("database_encryption_key", "");

        log.info("Параметры TDLib подготовлены");
        client.send(p, TdJsonClient.Channel.AUTH);
        log.info("Параметры TDLib отправлены");
    }

    // отправка номера телефона
    private void sendPhoneNumber() {
        String phone = authStore.get().getPhone() != null
                ? authStore.get().getPhone().trim()
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
            log.error("Ошибка при отправке номера: код={} сообщение={}", code, msg);
        } else {
            log.info("Номер телефона отправлен");
        }
    }

    // отправка кода с повтором при неверном вводе
    private void sendCodeWithRetry() {
        while (true) {
            String code = authStore.get().getCode() != null
                    ? authStore.get().getCode().trim()
                    : readValue("Введите код из Telegram: ");

            ObjectNode req = Utils.obj("checkAuthenticationCode");
            req.put("code", code);
            ObjectNode resp = client.requestWithFloodWaitSyncLimited(req, 60, TdJsonClient.Channel.AUTH);

            boolean codeError = "error".equals(resp.path("@type").asText())
                    && resp.path("code").asInt() == 400
                    && resp.path("message").asText("").toLowerCase().contains("code"); // проверяет текст ошибки

            if (codeError) {
                authStore.get().setCode(null);
                log.warn("Неверный код, попробуйте снова");
                continue;
            }
            log.info("Код отправлен");
            break;
        }
    }

    // отправка 2FA пароля
    private void sendPassword() {
        String password = authStore.get().getPass() != null
                ? authStore.get().getPass().trim()
                : readValue("Введите пароль двухфакторной аутентификации: ");
        ObjectNode req = Utils.obj("checkAuthenticationPassword");
        req.put("password", password);
        client.send(req, TdJsonClient.Channel.AUTH);
        log.info("Пароль отправлен");
    }

    // чтение строки из консоли/STDIN
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
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    public boolean isAuthorized() { return authorized.get(); }
}
