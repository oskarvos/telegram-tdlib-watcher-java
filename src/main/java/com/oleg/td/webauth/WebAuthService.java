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

    public WebAuthService(TdJsonClient client, TdlibProperties td, AppProperties app, AuthRuntimeStore authStore) {
        this.client = client;
        this.td = td;
        this.app = app;
        this.authStore = authStore;
    }

    public AuthStatusResponse start(StartAuthRequest req) {
        try {
            if (req.getApiId() == null || req.getApiId() <= 0) return AuthStatusResponse.error("api_id не задан");
            if (req.getApiHash() == null || req.getApiHash().isBlank()) return AuthStatusResponse.error("api_hash не задан");
            if (req.getPhone() == null || req.getPhone().isBlank()) return AuthStatusResponse.error("phone не задан");

            // 1) Сохраняем в конфиг
            td.setApiId(req.getApiId());
            td.setApiHash(req.getApiHash().trim());
            authStore.get().setPhone(req.getPhone().trim());
            app.setUseTestDc(Boolean.TRUE.equals(req.getUseTestDc()));

            // 2) Параметры TDLib
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

            // 3) Состояния
            String state = currentAuthType();

            if ("authorizationStateWaitTdlibParameters".equals(state)) {
                ObjectNode pResp = client.requestWithFloodWaitSyncLimited(params, 60, TdJsonClient.Channel.AUTH);
                if ("error".equals(pResp.path("@type").asText())) {
                    return AuthStatusResponse.error("TDLib отказал в setTdlibParameters: " + pResp.path("message").asText());
                }
                state = currentAuthType();
            }

            if ("authorizationStateReady".equals(state)) {
                return AuthStatusResponse.ok("READY");
            }

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

            if ("authorizationStateWaitCode".equals(state))     return AuthStatusResponse.ok("WAIT_CODE");
            if ("authorizationStateWaitPassword".equals(state)) return AuthStatusResponse.ok("WAIT_PASSWORD");
            if ("authorizationStateLoggingOut".equals(state))   return AuthStatusResponse.ok("LOGGING_OUT");
            if ("authorizationStateClosed".equals(state))       return AuthStatusResponse.ok("CLOSED");

            return mapAuthState();
        } catch (Exception e) {
            log.error("webauth.start error", e);
            return AuthStatusResponse.error("Исключение: " + e.getMessage());
        }
    }

    public AuthStatusResponse verify(VerifyCodeRequest req) {
        try {
            String state = currentAuthType();
            log.info("Текущее состояние: {}", state);

            // Уже авторизованы — считаем успехом (идемпотентность)
            if ("authorizationStateReady".equals(state)) {
                return AuthStatusResponse.ok("READY");
            }

            // Ожидаем пароль 2FA
            if ("authorizationStateWaitPassword".equals(state)) {
                if (req.getPassword() == null || req.getPassword().isBlank()) {
                    return AuthStatusResponse.error("Требуется пароль 2FA");
                }
                ObjectNode pass = M.createObjectNode();
                pass.put("@type", "checkAuthenticationPassword");
                pass.put("password", req.getPassword().trim());

                ObjectNode resp = client.requestWithFloodWaitSyncLimited(pass, 60, TdJsonClient.Channel.AUTH);
                if ("error".equals(resp.path("@type").asText())) {
                    String errorMsg = resp.path("message").asText();
                    log.error("Ошибка от TDLib (password): {}", errorMsg);
                    return AuthStatusResponse.error("TDLib ошибка: " + errorMsg);
                }
                return mapAuthState();
            }

            // Ожидаем код
            if (!"authorizationStateWaitCode".equals(state)) {
                log.error("ОШИБКА: Неверное состояние для подтверждения: {}", state);
                return AuthStatusResponse.error("Неверное состояние: " + state);
            }
            if (req.getCode() == null || req.getCode().isBlank()) {
                return AuthStatusResponse.error("Код не задан");
            }

            ObjectNode code = M.createObjectNode();
            code.put("@type", "checkAuthenticationCode");
            code.put("code", req.getCode().trim());

            log.info("Отправляем код: {}", req.getCode());
            ObjectNode resp = client.requestWithFloodWaitSyncLimited(code, 60, TdJsonClient.Channel.AUTH);

            log.info("ОТВЕТ TDLib: {}", resp.toString());

            if ("error".equals(resp.path("@type").asText())) {
                String errorMsg = resp.path("message").asText();
                log.error("Ошибка от TDLib (code): {}", errorMsg);
                return AuthStatusResponse.error("TDLib ошибка: " + errorMsg);
            }

            return mapAuthState();
        } catch (Exception e) {
            log.error("Исключение в verify()", e);
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
                s.path("@type").asText()
        );

        switch (t) {
            case "authorizationStateReady":           return AuthStatusResponse.ok("READY");
            case "authorizationStateWaitCode":        return AuthStatusResponse.ok("WAIT_CODE");
            case "authorizationStateWaitPassword":    return AuthStatusResponse.ok("WAIT_PASSWORD");
            case "authorizationStateWaitPhoneNumber": return AuthStatusResponse.ok("WAIT_PHONE");
            case "authorizationStateWaitTdlibParameters": return AuthStatusResponse.ok("WAIT_TDLIB_PARAMETERS");
            case "authorizationStateLoggingOut":      return AuthStatusResponse.ok("LOGGING_OUT");
            case "authorizationStateClosed":          return AuthStatusResponse.ok("CLOSED");
            default:                                   return AuthStatusResponse.ok(t == null || t.isBlank() ? "UNKNOWN" : t);
        }
    }

    /** Публичный — может пригодиться и в других местах (напр., контроллеры). */
    public String currentAuthType() {
        ObjectNode get = M.createObjectNode();
        get.put("@type", "getAuthorizationState");
        ObjectNode s = client.requestWithFloodWaitSyncLimited(get, 30, TdJsonClient.Channel.AUTH);

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
