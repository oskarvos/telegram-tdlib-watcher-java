package com.oleg.td;

import org.springframework.stereotype.Component;

/**
 * Simple configuration bean storing TDLib parameters.
 * <p>
 * Values may be supplied via environment variables or system properties. If
 * a particular value is not provided, sensible defaults are used.
 */
@Component
public class Config {
    private final Tdlib tdlib = new Tdlib();
    private final Auth auth = new Auth();

    public Tdlib getTdlib() {
        return tdlib;
    }

    public Auth getAuth() {
        return auth;
    }

    /**
     * TDLib specific parameters required during client initialisation.
     */
    public static class Tdlib {
        private final int apiId = Integer.parseInt(System.getProperty("td.api_id", System.getenv().getOrDefault("TD_API_ID", "0")));
        private final String apiHash = System.getProperty("td.api_hash", System.getenv().getOrDefault("TD_API_HASH", ""));
        private final String systemLanguageCode = System.getProperty("td.system_language_code", System.getenv().getOrDefault("TD_SYSTEM_LANGUAGE_CODE", "en"));
        private final String deviceModel = System.getProperty("td.device_model", System.getenv().getOrDefault("TD_DEVICE_MODEL", "Java"));
        private final String systemVersion = System.getProperty("td.system_version", System.getProperty("os.name") + " " + System.getProperty("os.version", ""));
        private final String applicationVersion = System.getProperty("td.application_version", System.getenv().getOrDefault("TD_APPLICATION_VERSION", "1.0.4-debug"));
        private final String databaseDirectory = System.getProperty("td.database_directory", System.getenv().getOrDefault("TD_DATABASE_DIRECTORY", "tdlib"));
        private final String filesDirectory = System.getProperty("td.files_directory", System.getenv().getOrDefault("TD_FILES_DIRECTORY", "tdlib/files"));

        public int getApiId() {
            return apiId;
        }

        public String getApiHash() {
            return apiHash;
        }

        public String getSystemLanguageCode() {
            return systemLanguageCode;
        }

        public String getDeviceModel() {
            return deviceModel;
        }

        public String getSystemVersion() {
            return systemVersion;
        }

        public String getApplicationVersion() {
            return applicationVersion;
        }

        public String getDatabaseDirectory() {
            return databaseDirectory;
        }

        public String getFilesDirectory() {
            return filesDirectory;
        }
    }

    /**
     * Optional authentication values which may be provided from the
     * configuration to speed up manual authorisation steps.
     */
    public static class Auth {
        private final String phone = System.getProperty("td.auth.phone", System.getenv().getOrDefault("TG_PHONE", null));
        private final String code = System.getProperty("td.auth.code", System.getenv().getOrDefault("TG_CODE", null));
        private final String pass = System.getProperty("td.auth.pass", System.getenv().getOrDefault("TG_PASS", null));

        public String getPhone() {
            return phone;
        }

        public String getCode() {
            return code;
        }

        public String getPass() {
            return pass;
        }
    }
}