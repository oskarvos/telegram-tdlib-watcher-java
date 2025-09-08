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
            // 1) сохраняем введённые значения в существующий Config
            config.getTdlib().setApiId(req.getApiId());
            config.getTdlib().setApiHash(req.getApiHash());
            config.getAuth().setPhone(req.getPhone());
            config.setUseTestDc(Boolean.TRUE.equals(req.getUseTestDc()));

            // 2) setTdlibParameters
            String dbBase = config.getTdlib().getDatabaseDirectory(); // напр. "tdlib"
            String filesBase = config.getTdlib().getFilesDirectory(); // напр. "tdlib/files"
            String suffix = String.format("%d_%s", req.getApiId(), digitsOnly(req.getPhone()));

            String dbDir = Paths.get(dbBase, suffix).toString();
            String filesDir = Paths.get(filesBase, suffix).toString();

            ObjectNode params = M.createObjectNode();
            params.put("@type", "setTdlibParameters");
            params.put("use_test_dc", config.getUseTestDc());
            params.put("database_directory", dbDir);
            params.put("files_directory", filesDir);
            params.put("use_file_database", true);
            params.put("use_chat_info_database", true);
            params.put("use_message_database", true);
            params.put("use_secret_chats", false);
            params.put("api_id", req.getApiId());
            params.put("api_hash", req.getApiHash());
            params.put("system_language_code", config.getTdlib().getSystemLanguageCode());
            params.put("device_model", config.getTdlib().getDeviceModel());
            params.put("system_version", config.getTdlib().getSystemVersion() == null ? "" : config.getTdlib().getSystemVersion());
            params.put("application_version", config.getTdlib().getApplicationVersion());
            params.put("enable_storage_optimizer", true);
            params.put("ignore_file_names", true);
            params.put("database_encryption_key", ""); // без шифрования

            client.requestWithFloodWaitSyncLimited(params, 30, TdJsonClient.Channel.MAIN);

            // 3) setAuthenticationPhoneNumber (отправит код)
            ObjectNode phone = M.createObjectNode();
            phone.put("@type", "setAuthenticationPhoneNumber");
            phone.put("phone_number", req.getPhone());
            ObjectNode settings = phone.putObject("settings");
            settings.put("@type", "phoneNumberAuthenticationSettings");
            settings.put("allow_flash_call", false);
            settings.put("is_current_phone_number", false);
            settings.put("allow_missed_call", false);

            client.requestWithFloodWaitSyncLimited(phone, 30, TdJsonClient.Channel.MAIN);

            return status();
        } catch (Exception e) {
            log.error("WebAuth start error: {}", e.getMessage(), e);
            return AuthStatusResponse.error("Ошибка старта авторизации: " + e.getMessage());
        }
    }

    public AuthStatusResponse verify(VerifyCodeRequest req) {
        try {
            if (req.getCode() != null && !req.getCode().isBlank()) {
                ObjectNode chk = M.createObjectNode();
                chk.put("@type", "checkAuthenticationCode");
                chk.put("code", req.getCode().trim());
                client.requestWithFloodWaitSyncLimited(chk, 30, TdJsonClient.Channel.MAIN);
            }

            // Если TDLib попросил пароль 2FA — отправим его
            var st = status();
            if ("WAIT_PASSWORD".equals(st.getState()) && req.getPassword() != null && !req.getPassword().isBlank()) {
                ObjectNode pwd = M.createObjectNode();
                pwd.put("@type", "checkAuthenticationPassword");
                pwd.put("password", req.getPassword());
                client.requestWithFloodWaitSyncLimited(pwd, 30, TdJsonClient.Channel.MAIN);
            }

            return status();
        } catch (Exception e) {
            log.error("WebAuth verify error: {}", e.getMessage(), e);
            return AuthStatusResponse.error("Ошибка подтверждения: " + e.getMessage());
        }
    }

    public AuthStatusResponse status() {
        try {
            ObjectNode get = M.createObjectNode();
            get.put("@type", "getAuthorizationState");
            var resp = client.requestWithFloodWaitSyncLimited(get, 10, TdJsonClient.Channel.MAIN);
            String t = resp.path("@type").asText();

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
                    return AuthStatusResponse.ok("WAIT_PARAMS");
                default:
                    return AuthStatusResponse.ok(t);
            }
        } catch (Exception e) {
            return AuthStatusResponse.error("state error: " + e.getMessage());
        }
    }

    private static String digitsOnly(String s) {
        return s == null ? "" : s.replaceAll("\\D+", "");
    }
}
