package com.oleg.td;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import java.io.Console;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
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
        p.put("database_directory", cfg.getTdlib().getDatabaseDirectory());
        p.put("files_directory", cfg.getTdlib().getFilesDirectory());
        p.put("use_file_database", true);
        p.put("use_chat_info_database", true);
        p.put("use_message_database", true);
        p.put("use_secret_chats", false);
        p.put("api_id", cfg.getTdlib().getApiId());
        p.put("api_hash", cfg.getTdlib().getApiHash());
        p.put("system_language_code", cfg.getTdlib().getSystemLanguageCode());
        p.put("device_model", cfg.getTdlib().getDeviceModel());
        p.put("system_version", cfg.getTdlib().getSystemVersion());
        p.put("application_version", cfg.getTdlib().getApplicationVersion());
        p.put("enable_storage_optimizer", true);
        p.put("ignore_file_names", true);
        p.put("database_encryption_key", "");
        System.out.println("DEBUG setTdlibParameters JSON --> " + p);
        client.send(p, TdJsonClient.Channel.AUTH);
    }

    private void sendPhoneLimited() {
        String phone = cfg.getAuth().getPhone() != null && !cfg.getAuth().getPhone().isBlank()
                ? cfg.getAuth().getPhone().trim()
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
        String code = cfg.getAuth().getCode() != null && !cfg.getAuth().getCode().isBlank()
                ? cfg.getAuth().getCode().trim()
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
        String pass = cfg.getAuth().getPass() != null && !cfg.getAuth().getPass().isBlank()
                ? cfg.getAuth().getPass()
                : readValue("Enter 2FA password: ", true,
                "TG_PASS", "TELEGRAM_PASS", "telegram.pass");
        ObjectNode r = Utils.obj("checkAuthenticationPassword");
        r.put("password", pass);
        client.send(r, TdJsonClient.Channel.AUTH);
        System.out.println("AUTH DEBUG: 2FA password submitted");
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    private static String readValue(String prompt, boolean secret, String... keys) {
        String envVal = EnvVars.get(keys);

        // 1) Есть реальная консоль — позволяем перебить ENV ручным вводом
        Console cons = System.console();
        if (cons != null) {
            if (envVal != null) {
                String full = prompt + " [value from env/sysprop; press Enter to use it or type a new one]: ";
                if (secret) {
                    char[] in = cons.readPassword(full);
                    String s = (in == null) ? "" : new String(in).trim();
                    return s.isEmpty() ? envVal : s;
                } else {
                    String s = cons.readLine(full);
                    return (s == null || s.isBlank()) ? envVal : s.trim();
                }
            } else {
                return secret ? new String(cons.readPassword(prompt)) : cons.readLine(prompt);
            }
        }

        // 2) Консоли нет (частый случай в Gradle). Тоже ждём ввод из STDIN.
        //    Если введено пусто — используем ENV (если он есть).
        String hint = (envVal != null)
                ? " [value from env/sysprop; press Enter to use it or type a new one]: "
                : "";
        System.out.print(prompt + hint);

        java.util.Scanner sc = new java.util.Scanner(System.in); // не закрываем System.in
        if (sc.hasNextLine()) {
            String line = sc.nextLine();
            String s = (line == null) ? "" : line.trim();
            if (s.isEmpty()) {
                if (envVal != null) {
                    System.out.println("(using value from env/sysprop)");
                    return envVal;
                }
                throw new IllegalStateException("Empty input and no env/sysprop value available.");
            }
            return s;
        }
        throw new IllegalStateException(
                "No input available on STDIN. Provide value via env/system property or run in a real terminal (e.g. `--console=plain`).");
    }
}
