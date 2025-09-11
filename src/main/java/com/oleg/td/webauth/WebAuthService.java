package com.oleg.td.webauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oleg.td.app.config.AppProperties;
import com.oleg.td.app.config.TdlibProperties;
import com.oleg.td.auth.service.AuthRuntimeStore;
import com.oleg.td.integrations.tdlibs.TdJsonClient;
import com.oleg.td.webauth.dto.AuthStatusResponse;
import com.oleg.td.webauth.dto.StartAuthRequest;
import com.oleg.td.webauth.dto.VerifyCodeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;

@Service
public class WebAuthService {
    private static final Logger log = LoggerFactory.getLogger(WebAuthService.class);
    private static final ObjectMapper M = new ObjectMapper();

    private final TdJsonClient client;
    private final TdlibProperties td;
    private final AppProperties app;
    private final AuthRuntimeStore authStore;
    ;

    public WebAuthService(TdJsonClient client, TdlibProperties td, AppProperties app, AuthRuntimeStore authStore) {
        this.client = client;
        this.td = td;
        this.app = app;
        this.authStore = authStore;
    }

    public AuthStatusResponse start(StartAuthRequest req) {
        try {
            if (req.getApiId() == null || req.getApiId() <= 0) {
                return AuthStatusResponse.error("api_id не задан");
            }
            if (req.getApiHash() == null || req.getApiHash().isBlank()) {
                return AuthStatusResponse.error("api_hash не задан");
            }
            if (req.getPhone() == null || req.getPhone().isBlank()) {
                return AuthStatusResponse.error("phone не задан");
            }

            // 1) Сохраняем в конфиг (как было)
            td.setApiId(req.getApiId());
            td.setApiHash(req.getApiHash().trim());
            authStore.get().setPhone(req.getPhone().trim());
            app.setUseTestDc(Boolean.TRUE.equals(req.getUseTestDc()));

            // 2) Готовим каталоги и параметры (как было)
            String dbBase = td.getDatabaseDirectory();
            String filesBase = td.getFilesDirectory();
            String suffix = String.format("%d_%s%s",
                    req.getApiId(),
                    digitsOnly(req.getPhone()),
                    Boolean.TRUE.equals(req.getUseTestDc()) ? "_testdc" : ""
            );
            String dbDir = Paths.get(dbBase, suffix).toString();
            String filesDir = Paths.get(filesBase, suffix).toString();

            String osName = System.getProperty("os.name", "OS");
            String osVersion = System.getProperty("os.version", "");
            String systemVersion = osName + " " + osVersion;

            ObjectNode params = M.createObjectNode();
            params.put("@type", "setTdlibParameters");
            params.put("use_test_dc", app.getUseTestDc());
            params.put("database_directory", dbDir);
            params.put("files_directory", filesDir);
            params.put("use_file_database", true);
            params.put("use_chat_info_database", true);
            params.put("use_message_database", true);
            params.put("use_secret_chats", false);
            params.put("api_id", td.getApiId());
            params.put("api_hash", td.getApiHash());
            params.put("system_language_code", td.getSystemLanguageCode());
            params.put("device_model", td.getDeviceModel());
            params.put("system_version", systemVersion);
            params.put("application_version", td.getApplicationVersion());
            params.put("enable_storage_optimizer", true);
            params.put("ignore_file_names", true);


            // 3) Идём по состояниям — НИКАКИХ «вслепую» вызовов
            String state = currentAuthType();

            // a) если ждём параметры — установим их
            if ("authorizationStateWaitTdlibParameters".equals(state)) {
                ObjectNode pResp = client.requestWithFloodWaitSyncLimited(params, 60, TdJsonClient.Channel.AUTH);
                if ("error".equals(pResp.path("@type").asText())) {
                    return AuthStatusResponse.error("TDLib отказал в setTdlibParameters: " + pResp.path("message").asText());
                }
                state = currentAuthType(); // обновляем
            }

            // b) если уже авторизованы — сразу READY
            if ("authorizationStateReady".equals(state)) {
                return AuthStatusResponse.ok("READY");
            }

            // c) если ждём телефон — отправим телефон
            if ("authorizationStateWaitPhoneNumber".equals(state)) {
                ObjectNode reqPhone = M.createObjectNode();
                reqPhone.put("@type", "setAuthenticationPhoneNumber");
                reqPhone.put("phone_number", authStore.get().getPhone());
                ObjectNode settings = reqPhone.putObject("settings");
                settings.put("@type", "phoneNumberAuthenticationSettings");
                settings.put("allow_flash_call", false);
                settings.put("is_current_phone_number", true);
                settings.put("allow_sms_retriever_api", false);

                ObjectNode phResp = client.requestWithFloodWaitSyncLimited(reqPhone, 60, TdJsonClient.Channel.AUTH);
                if ("error".equals(phResp.path("@type").asText())) {
                    return AuthStatusResponse.error("Ошибка при отправке номера: " + phResp.path("message").asText());
                }
                state = currentAuthType();
            }

            // d) если ждём код/пароль — сообщаем фронту соответствующий статус
            if ("authorizationStateWaitCode".equals(state)) {
                return AuthStatusResponse.ok("WAIT_CODE");
            }
            if ("authorizationStateWaitPassword".equals(state)) {
                return AuthStatusResponse.ok("WAIT_PASSWORD");
            }

            // e) если выходим/закрыто — отразим это
            if ("authorizationStateLoggingOut".equals(state)) {
                return AuthStatusResponse.ok("LOGGING_OUT");
            }
            if ("authorizationStateClosed".equals(state)) {
                return AuthStatusResponse.ok("CLOSED");
            }

            // f) финально — маппинг неизвестного состояния
            return mapAuthState();

        } catch (Exception e) {
            log.error("webauth.start error", e);
            return AuthStatusResponse.error("Исключение: " + e.getMessage());
        }
    }


    public AuthStatusResponse verify(VerifyCodeRequest req) {
        try {
            if (req.getPassword() != null && !req.getPassword().isBlank()) {
                ObjectNode pass = M.createObjectNode();
                pass.put("@type", "checkAuthenticationPassword");
                pass.put("password", req.getPassword().trim());
                ObjectNode resp = client.requestWithFloodWaitSyncLimited(pass, 60, TdJsonClient.Channel.AUTH);
                if ("error".equals(resp.path("@type").asText())) {
                    return AuthStatusResponse.error("Неверный пароль 2FA: " + resp.path("message").asText());
                }
                return mapAuthState();
            }

            if (req.getCode() == null || req.getCode().isBlank()) {
                return AuthStatusResponse.error("Не задан ни код, ни пароль");
            }

            ObjectNode code = M.createObjectNode();
            code.put("@type", "checkAuthenticationCode");
            code.put("code", req.getCode().trim());
            ObjectNode resp = client.requestWithFloodWaitSyncLimited(code, 60, TdJsonClient.Channel.AUTH);
            if ("error".equals(resp.path("@type").asText())) {
                return AuthStatusResponse.error("Ошибка кода: " + resp.path("message").asText());
            }
            return mapAuthState();
        } catch (Exception e) {
            log.error("webauth.verify error", e);
            return AuthStatusResponse.error("Исключение: " + e.getMessage());
        }
    }

    public AuthStatusResponse status() {
        try {
            return mapAuthState();
        } catch (Exception e) {
            log.error("webauth.status error", e);
            return AuthStatusResponse.error("Исключение: " + e.getMessage());
        }
    }

    // --- helpers ----

    private AuthStatusResponse mapAuthState() {
        ObjectNode get = M.createObjectNode();
        get.put("@type", "getAuthorizationState");
        ObjectNode s = client.requestWithFloodWaitSyncLimited(get, 30, TdJsonClient.Channel.AUTH);
        String t = s.path("authorization_state").path("@type").asText(
                s.path("@type").asText() // иногда TDLib даёт ответ сразу как state
        );

        switch (t) {
            case "authorizationStateReady":
                return AuthStatusResponse.ok("READY");
            case "authorizationStateWaitCode":
                return AuthStatusResponse.ok("WAIT_CODE");
            case "authorizationStateWaitPassword":
                return AuthStatusResponse.ok("WAIT_PASSWORD");
            case "authorizationStateWaitPhoneNumber":
                return AuthStatusResponse.ok("WAIT_PHONE");
            case "authorizationStateWaitTdlibParameters":
                return AuthStatusResponse.ok("WAIT_TDLIB_PARAMETERS");
            case "authorizationStateLoggingOut":
                return AuthStatusResponse.ok("LOGGING_OUT");
            case "authorizationStateClosed":
                return AuthStatusResponse.ok("CLOSED");
            default:
                return AuthStatusResponse.ok(t == null || t.isBlank() ? "UNKNOWN" : t);
        }
    }

    /**
     * Возвращает строку вида authorizationStateWaitCode / authorizationStateReady и т.п.
     */
    private String currentAuthType() {
        ObjectNode get = M.createObjectNode();
        get.put("@type", "getAuthorizationState");
        ObjectNode s = client.requestWithFloodWaitSyncLimited(get, 30, TdJsonClient.Channel.AUTH);

        // TDLib может вернуть либо {"@type":"authorizationStateXXX"} напрямую,
        // либо {"@type":"authorizationState", "authorization_state":{"@type":"authorizationStateXXX"}}
        String direct = s.path("@type").asText();
        if (direct != null && direct.startsWith("authorizationState")) {
            return direct;
        }
        String nested = s.path("authorization_state").path("@type").asText();
        return (nested != null && !nested.isBlank()) ? nested : "UNKNOWN";
    }


    private static String digitsOnly(String s) {
        return s == null ? "" : s.replaceAll("\\D+", "");
    }
}
