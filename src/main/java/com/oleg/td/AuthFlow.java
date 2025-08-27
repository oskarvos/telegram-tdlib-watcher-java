package com.oleg.td;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.Console;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class AuthFlow {
    private final TdJsonClient client;
    private final Config cfg;
    private final UpdateRouter router;

    private final AtomicBoolean authorized = new AtomicBoolean(false);
    private final AtomicReference<String> stateRef = new AtomicReference<>(null);

    public AuthFlow(TdJsonClient client, Config cfg, UpdateRouter router) {
        this.client = client;
        this.cfg = cfg;
        this.router = router;
    }

    /** Подписывает обработчики апдейтов, чтобы ловить состояния авторизации. */
    public void wireInto() {
        router.add(n -> {
            String type = n.path("@type").asText();
            if ("updateAuthorizationState".equals(type)) {
                String state = n.path("authorization_state").path("@type").asText();
                stateRef.set(state);
                if ("authorizationStateReady".equals(state)) {
                    authorized.set(true);
                }
            }
        });
    }

    /** Блокирующая авторизация — проходит все шаги. */
    public void authorizeBlocking() {
        client.send(Utils.obj("getAuthorizationState"), TdJsonClient.Channel.AUTH);

        String last = null;
        while (!authorized.get()) {
            String state = stateRef.get();
            if (state == null || state.equals(last)) {
                sleep(100); // Пауза для предотвращения излишних запросов
                continue;
            }

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
                    System.out.println("Authorization completed.");
                    break;
                default:
                    System.err.println("Unexpected state: " + state);
                    break;
            }
            last = state;
        }
    }

    private void sendTdParams() {
        ObjectNode params = Utils.obj("setTdlibParameters");
        params.put("use_test_dc", false);
        params.put("database_directory", cfg.tdlib.database_directory);
        params.put("files_directory", cfg.tdlib.files_directory);
        params.put("api_id", cfg.tdlib.api_id);
        params.put("api_hash", cfg.tdlib.api_hash);
        params.put("system_language_code", "en");
        params.put("device_model", "Java");
        client.send(params, TdJsonClient.Channel.AUTH);
        System.out.println("TDLib parameters sent.");
    }

    private void sendPhoneNumber() {
        String phone = cfg.auth != null && cfg.auth.phone != null ? cfg.auth.phone.trim() : readValue("Enter phone number (+xxxxxxxxxxx): ");
        ObjectNode req = Utils.obj("setAuthenticationPhoneNumber");
        req.put("phone_number", phone);
        client.send(req, TdJsonClient.Channel.AUTH);
        System.out.println("Phone number submitted.");
    }

    private void sendCode() {
        String code = readValue("Enter code from Telegram: ");
        ObjectNode req = Utils.obj("checkAuthenticationCode");
        req.put("code", code);
        client.send(req, TdJsonClient.Channel.AUTH);
        System.out.println("Code submitted.");
    }

    private void sendPassword() {
        String password = readValue("Enter 2FA password: ");
        ObjectNode req = Utils.obj("checkAuthenticationPassword");
        req.put("password", password);
        client.send(req, TdJsonClient.Channel.AUTH);
        System.out.println("Password submitted.");
    }

    private String readValue(String prompt) {
        Console console = System.console();
        if (console != null) {
            String input = console.readLine(prompt);
            if (input != null) {
                return input.trim();
            }
        }
        throw new IllegalStateException("No input available.");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {}
    }
}
