package com.oleg.td;

import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.Console;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.oleg.td.TdJsonClient.extractFloodWait;

@Component
public class AuthFlow {
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
     * Подписывает обработчики апдейтов, чтобы ловить состояния авторизации.
     */
    public void wireInto() {
        if (wired) {
            System.out.println("AuthFlow уже подключен");
            return;
        }

        System.out.println("Подключение AuthFlow к UpdateRouter...");

        router.add(n -> {
            String type = n.path("@type").asText();
            System.out.println("Получен апдейт: " + type);

            // Обработка ошибки Flood Wait
            if ("error".equals(type) && n.path("code").asInt() == 429) {
                handleFloodWait(n);
                return;
            }

            if ("updateAuthorizationState".equals(type)) {
                String state = n.path("authorization_state").path("@type").asText();
                System.out.println("Обновление состояния авторизации: " + state);
                stateRef.set(state);

                if ("authorizationStateReady".equals(state)) {
                    authorized.set(true);
                    System.out.println("Авторизация прошла успешно!");
                } else if ("authorizationStateClosed".equals(state)) {
                    authorized.set(false);
                    System.out.println("Авторизация закрыта");
                }
            }
        });

        wired = true;
        System.out.println("AuthFlow успешно подключен");
    }

    private void handleFloodWait(ObjectNode errorNode) {
        int waitTime = extractFloodWait(errorNode.path("message").asText());
        System.out.println("Обнаружен Flood wait: " + waitTime + " секунд");

        // Асинхронная обработка без блокировки основного потока
        new Thread(() -> {
            try {
                Thread.sleep(waitTime * 1000L);
                System.out.println("Flood wait завершен, возобновляем операции");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    /**
     * Блокирующая авторизация — проходит все шаги.
     */
    public void authorizeBlocking() {
        if (!wired) {
            throw new IllegalStateException("AuthFlow не инициализирован. Сначала вызовите wireInto()");
        }

        System.out.println("Начало процесса авторизации...");

        // Запрашиваем текущее состояние авторизации
        client.send(Utils.obj("getAuthorizationState"), TdJsonClient.Channel.AUTH);

        String last = null;
        long startTime = System.currentTimeMillis();
        long timeout = 100000; // Увеличьте таймаут до 5 минут

        while (!authorized.get() && (System.currentTimeMillis() - startTime) < timeout) {
            String state = stateRef.get();

            if (state == null) {
                System.out.println("Ожидание состояния авторизации...");
                sleep(500);
                continue;
            }

            if (state.equals(last)) {
                sleep(100);
                continue;
            }

            System.out.println("Обработка состояния авторизации: " + state);

            try {
                switch (state) {
                    case "authorizationStateWaitTdlibParameters":
                        sendTdParams();
                        break;
                    case "authorizationStateWaitPhoneNumber":
                        sendPhoneNumber();
                        break;
                    case "authorizationStateWaitCode":
                        sendCode();
                        break;
                    case "authorizationStateWaitPassword":
                        sendPassword();
                        break;
                    case "authorizationStateReady":
                        authorized.set(true);
                        System.out.println("Авторизация успешно завершена!");
                        break;
                    case "authorizationStateClosed":
                        System.err.println("Авторизация закрыта TDLib");
                        throw new RuntimeException("Авторизация закрыта");
                    case "authorizationStateLoggingOut":
                        System.err.println("Выход из системы");
                        throw new RuntimeException("Выход из системы");
                    default:
                        System.out.println("Ожидание в состоянии: " + state);
                        sleep(1000);
                        break;
                }
            } catch (Exception e) {
                System.err.println("Ошибка обработки состояния " + state + ": " + e.getMessage());
                throw new RuntimeException("Ошибка авторизации в состоянии: " + state, e);
            }

            last = state;
            sleep(100);
        }

        if (!authorized.get()) {
            throw new RuntimeException("Таймаут авторизации после " + timeout + "мс");
        }
    }

    private void sendTdParams() {
        ObjectNode params = Utils.obj("setTdlibParameters");
        params.put("use_test_dc", false);
        params.put("database_directory", cfg.getTdlib().getDatabaseDirectory());
        params.put("files_directory", cfg.getTdlib().getFilesDirectory());
        params.put("api_id", cfg.getTdlib().getApiId());
        params.put("api_hash", cfg.getTdlib().getApiHash());
        params.put("system_language_code", cfg.getTdlib().getSystemLanguageCode());
        params.put("device_model", cfg.getTdlib().getDeviceModel());
        params.put("system_version", cfg.getTdlib().getSystemVersion());
        params.put("application_version", cfg.getTdlib().getApplicationVersion());
        params.put("enable_storage_optimizer", true);
        params.put("ignore_file_names", false);

        client.send(params, TdJsonClient.Channel.AUTH);
        System.out.println("Параметры TDLib отправлены.");
    }

    private void sendPhoneNumber() {
        String phone = cfg.getAuth() != null && cfg.getAuth().getPhone() != null ?
                cfg.getAuth().getPhone().trim() : readValue("Введите номер телефона (+xxxxxxxxxxx): ");
        ObjectNode req = Utils.obj("setAuthenticationPhoneNumber");
        req.put("phone_number", phone);
        client.send(req, TdJsonClient.Channel.AUTH);
        System.out.println("Номер телефона отправлен: " + phone);
    }

    private void sendCode() {
        String code = cfg.getAuth() != null && cfg.getAuth().getCode() != null ?
                cfg.getAuth().getCode().trim() : readValue("Введите код из Telegram: ");
        ObjectNode req = Utils.obj("checkAuthenticationCode");
        req.put("code", code);
        client.send(req, TdJsonClient.Channel.AUTH);
        System.out.println("Код отправлен.");
    }

    private void sendPassword() {
        String password = cfg.getAuth() != null && cfg.getAuth().getPass() != null ?
                cfg.getAuth().getPass().trim() : readValue("Введите 2FA пароль: ");
        ObjectNode req = Utils.obj("checkAuthenticationPassword");
        req.put("password", password);
        client.send(req, TdJsonClient.Channel.AUTH);
        System.out.println("Пароль отправлен.");
    }

    private String readValue(String prompt) {
        Console console = System.console();
        if (console != null) {
            String input = console.readLine(prompt);
            if (input != null) {
                return input.trim();
            }
        }
        throw new IllegalStateException("Нет доступного ввода.");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isAuthorized() {
        return authorized.get();
    }


}
