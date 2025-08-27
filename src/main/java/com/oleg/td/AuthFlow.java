package com.oleg.td;

import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.Console;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
        if (wired) return;

        router.add(n -> {
            String type = n.path("@type").asText();
            if ("updateAuthorizationState".equals(type)) {
                String state = n.path("authorization_state").path("@type").asText();
                stateRef.set(state);
                System.out.println("Authorization state: " + state);

                if ("authorizationStateReady".equals(state)) {
                    authorized.set(true);
                } else if ("authorizationStateClosed".equals(state)) {
                    authorized.set(false);
                }
            }
        });
        wired = true;
    }

    /**
     * Блокирующая авторизация — проходит все шаги.
     */
    public void authorizeBlocking() {
        if (!wired) {
            throw new IllegalStateException("AuthFlow not wired. Call wireInto() first.");
        }

        System.out.println("Starting authorization process...");

        // Запрашиваем текущее состояние авторизации
        client.send(Utils.obj("getAuthorizationState"), TdJsonClient.Channel.AUTH);

        String last = null;
        long startTime = System.currentTimeMillis();
        long timeout = 120000; // 2 минуты таймаут

        while (!authorized.get() && (System.currentTimeMillis() - startTime) < timeout) {
            String state = stateRef.get();

            if (state == null) {
                System.out.println("Waiting for authorization state...");
                sleep(500);
                continue;
            }

            if (state.equals(last)) {
                sleep(100);
                continue;
            }

            System.out.println("Processing authorization state: " + state);

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
                        System.out.println("Authorization completed successfully!");
                        break;
                    case "authorizationStateClosed":
                        System.err.println("Authorization closed by TDLib");
                        throw new RuntimeException("Authorization closed");
                    case "authorizationStateLoggingOut":
                        System.err.println("Logging out");
                        throw new RuntimeException("Logging out");
                    default:
                        System.out.println("Waiting in state: " + state);
                        sleep(1000);
                        break;
                }
            } catch (Exception e) {
                System.err.println("Error processing state " + state + ": " + e.getMessage());
                throw new RuntimeException("Authorization failed in state: " + state, e);
            }

            last = state;
            sleep(100);
        }

        if (!authorized.get()) {
            throw new RuntimeException("Authorization timeout after " + timeout + "ms");
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
        System.out.println("TDLib parameters sent.");
    }

    private void sendPhoneNumber() {
        String phone = cfg.getAuth() != null && cfg.getAuth().getPhone() != null ?
                cfg.getAuth().getPhone().trim() : readValue("Enter phone number (+xxxxxxxxxxx): ");
        ObjectNode req = Utils.obj("setAuthenticationPhoneNumber");
        req.put("phone_number", phone);
        client.send(req, TdJsonClient.Channel.AUTH);
        System.out.println("Phone number submitted: " + phone);
    }

    private void sendCode() {
        String code = cfg.getAuth() != null && cfg.getAuth().getCode() != null ?
                cfg.getAuth().getCode().trim() : readValue("Enter code from Telegram: ");
        ObjectNode req = Utils.obj("checkAuthenticationCode");
        req.put("code", code);
        client.send(req, TdJsonClient.Channel.AUTH);
        System.out.println("Code submitted.");
    }

    private void sendPassword() {
        String password = cfg.getAuth() != null && cfg.getAuth().getPass() != null ?
                cfg.getAuth().getPass().trim() : readValue("Enter 2FA password: ");
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
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isAuthorized() {
        return authorized.get();
    }
}