package com.oleg.td;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.Console;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class AuthFlow {
    private final TdJsonClient client;
    private final Config cfg;

    private final AtomicBoolean authorized = new AtomicBoolean(false);
    private final AtomicReference<String> stateRef = new AtomicReference<>(null);

    public AuthFlow(TdJsonClient client, Config cfg) {
        this.client = client;
        this.cfg = cfg;
    }

    /** Подписывает обработчики апдейтов, чтобы ловить состояния авторизации. */
    public void wireInto(UpdateRouter router) {
        router.add(n -> {
            String type = n.path("@type").asText();
            if ("updateAuthorizationState".equals(type)) {
                String s = n.path("authorization_state").path("@type").asText();
                System.out.println("AUTH DEBUG: state = " + s);
                stateRef.set(s);
                if ("authorizationStateReady".equals(s)) authorized.set(true);
            } else if (type.startsWith("authorizationState")) {
                String s = n.path("@type").asText();
                System.out.println("AUTH DEBUG: state = " + s);
                stateRef.set(s);
                if ("authorizationStateReady".equals(s)) authorized.set(true);
            }
        });
    }

    /** Блокирующая авторизация — проходит все шаги. */
    public void authorizeBlocking() {
        client.send(Utils.obj("getAuthorizationState"), TdJsonClient.Channel.AUTH);

        String last = null;
        while (!authorized.get()) {
            String s = stateRef.get();
            if (s == null || s.equals(last)) { sleep(100); continue; }

            switch (s) {
                case "authorizationStateWaitTdlibParameters" -> sendTdParams();
                case "authorizationStateWaitPhoneNumber"     -> sendPhoneLimited();
                case "authorizationStateWaitCode"            -> sendCodeLimited();
                case "authorizationStateWaitPassword"        -> sendPassword();
                case "authorizationStateReady"               -> { authorized.set(true); System.out.println("Authorization completed."); }
                case "authorizationStateClosed"              -> System.err.println("Authorization closed.");
                default -> {}
            }
            last = s;
        }
    }

    private void sendTdParams() {
        ObjectNode p = Utils.obj("setTdlibParameters");
        p.put("use_test_dc", false);
        p.put("database_directory", cfg.tdlib.database_directory);
        p.put("files_directory", cfg.tdlib.files_directory);
        p.put("use_file_database", true);
        p.put("use_chat_info_database", true);
        p.put("use_message_database", true);
        p.put("use_secret_chats", false);
        p.put("api_id", cfg.tdlib.api_id);
        p.put("api_hash", cfg.tdlib.api_hash);
        p.put("system_language_code", "en");
        p.put("device_model", "Java");
        p.put("system_version", System.getProperty("os.name") + " " + System.getProperty("os.version", ""));
        p.put("application_version", "1.0.4-debug");
        p.put("enable_storage_optimizer", true);
        p.put("ignore_file_names", true);
        p.put("database_encryption_key", "");
        System.out.println("DEBUG setTdlibParameters JSON --> " + p);
        client.send(p, TdJsonClient.Channel.AUTH);
    }

    private void sendPhoneLimited() {
        String phone = (cfg.auth != null && cfg.auth.phone != null && !cfg.auth.phone.isBlank())
                ? cfg.auth.phone.trim()
                : readValue("Enter phone number (+xxxxxxxxxxx): ", false,
                "TG_PHONE", "TELEGRAM_PHONE", "telegram.phone");

        ObjectNode r = Utils.obj("setAuthenticationPhoneNumber");
        r.put("phone_number", phone);

        // В TDLib settings — это вложенный объект:
        ObjectNode settings = r.putObject("settings");
        settings.put("@type", "phoneNumberAuthenticationSettings");
        settings.put("allow_flash_call", false);
        settings.put("is_current_phone_number", true);
        settings.put("allow_sms_retriever_api", false);

        // Жёстко ограничиваем ожидание 429 одной минутой.
        var resp = client.requestWithFloodWaitSyncLimited(r, 60, TdJsonClient.Channel.AUTH);
        if ("error".equals(resp.path("@type").asText())) {
            System.err.printf("AUTH ERROR on phone: code=%d msg=%s%n",
                    resp.path("code").asInt(), resp.path("message").asText());
        } else {
            System.out.println("AUTH DEBUG: phone submitted");
        }
    }

    private void sendCodeLimited() {
        String code = (cfg.auth != null && cfg.auth.code != null && !cfg.auth.code.isBlank())
                ? cfg.auth.code.trim()
                : readValue("Enter code from Telegram: ", false,
                "TG_CODE", "TELEGRAM_CODE", "telegram.code");

        ObjectNode r = Utils.obj("checkAuthenticationCode");
        r.put("code", code);

        var resp = client.requestWithFloodWaitSyncLimited(r, 60, TdJsonClient.Channel.AUTH);
        if ("error".equals(resp.path("@type").asText())) {
            System.err.printf("AUTH ERROR on code: code=%d msg=%s%n",
                    resp.path("code").asInt(), resp.path("message").asText());
        } else {
            System.out.println("AUTH DEBUG: code submitted");
        }
    }

    private void sendPassword() {
        String pass = (cfg.auth != null && cfg.auth.pass != null && !cfg.auth.pass.isBlank())
                ? cfg.auth.pass
                : readValue("Enter 2FA password: ", true,
                "TG_PASS", "TELEGRAM_PASS", "telegram.pass");
        ObjectNode r = Utils.obj("checkAuthenticationPassword");
        r.put("password", pass);
        client.send(r, TdJsonClient.Channel.AUTH);
        System.out.println("AUTH DEBUG: 2FA password submitted");
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    private static String readValue(String prompt, boolean secret, String... keys) {
        String v = EnvVars.get(keys);
        if (v != null) { System.out.println(prompt + " [value from env/sysprop]"); return v; }
        Console cons = System.console();
        if (cons != null) return secret ? new String(cons.readPassword(prompt)) : cons.readLine(prompt);
        System.out.print(prompt);
        try (java.util.Scanner sc = new java.util.Scanner(System.in)) {
            if (sc.hasNextLine()) return sc.nextLine();
            throw new IllegalStateException("STDIN is not interactive (EOF).");
        } catch (NoSuchElementException e) {
            throw new IllegalStateException(
                    "No input available on STDIN. Provide value via env/sysprop or use real TTY (--console=plain).", e);
        }
    }
}
