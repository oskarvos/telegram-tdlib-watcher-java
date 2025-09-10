package com.oleg.td.webauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oleg.td.app.Config;
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
    private final Config config;

    public WebAuthService(TdJsonClient client, Config config) {
        this.client = client;
        this.config = config;
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

            // 1) Сохраняем в конфиг
            config.getTdlib().setApiId(req.getApiId());
            config.getTdlib().setApiHash(req.getApiHash().trim());
            config.getAuth().setPhone(req.getPhone().trim());
            config.setUseTestDc(Boolean.TRUE.equals(req.getUseTestDc()));

            // 2) setTdlibParameters
            String dbBase = config.getTdlib().getDatabaseDirectory(); // напр. "tdlib"
            String filesBase = config.getTdlib().getFilesDirectory(); // напр. "tdlib/files"
            String suffix = String.format("%d_%s", req.getApiId(), digitsOnly(req.getPhone()));

            String dbDir = Paths.get(dbBase, suffix).toString();
            String filesDir = Paths.get(filesBase, suffix).toString();

            String osName = System.getProperty("os.name", "OS");
            String osVersion = System.getProperty("os.version", "");
            String systemVersion = osName + " " + osVersion;

            ObjectNode params = M.createObjectNode();
            params.put("@type", "setTdlibParameters");
            params.put("use_test_dc", config.getUseTestDc());
            params.put("database_directory", dbDir);
            params.put("files_directory", filesDir);
            params.put("use_file_database", true);
            params.put("use_chat_info_database", true);
            params.put("use_message_database", true);
            params.put("use_secret_chats", false);
            params.put("api_id", config.getTdlib().getApiId());
            params.put("api_hash", config.getTdlib().getApiHash());
            params.put("system_language_code", config.getTdlib().getSystemLanguageCode());
            params.put("device_model", config.getTdlib().getDeviceModel());
            params.put("system_version", systemVersion);
            params.put("application_version", config.getTdlib().getApplicationVersion());
            params.put("enable_storage_optimizer", true);
            params.put("ignore_file_names", true);
            // Если шифрование БД требуется, можно дополнительно вызвать checkDatabaseEncryptionKey.
            // TDLib допускает передачу ключа отдельной командой; здесь оставим без него.

            ObjectNode pResp = client.requestWithFloodWaitSyncLimited(params, 60, TdJsonClient.Channel.AUTH);
            if ("error".equals(pResp.path("@type").asText())) {
                return AuthStatusResponse.error("TDLib отказал в setTdlibParameters: " + pResp.path("message").asText());
            }

            // 3) setAuthenticationPhoneNumber
            ObjectNode reqPhone = M.createObjectNode();
            reqPhone.put("@type", "setAuthenticationPhoneNumber");
            reqPhone.put("phone_number", config.getAuth().getPhone());
            ObjectNode settings = reqPhone.putObject("settings");
            settings.put("@type", "phoneNumberAuthenticationSettings");
            settings.put("allow_flash_call", false);
            settings.put("is_current_phone_number", true);
            settings.put("allow_sms_retriever_api", false);

            ObjectNode phResp = client.requestWithFloodWaitSyncLimited(reqPhone, 60, TdJsonClient.Channel.AUTH);
            if ("error".equals(phResp.path("@type").asText())) {
                return AuthStatusResponse.error("Ошибка при отправке номера: " + phResp.path("message").asText());
            }

            // 4) Вернём текущее состояние
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

    private static String digitsOnly(String s) {
        return s == null ? "" : s.replaceAll("\\D+", "");
    }
}
