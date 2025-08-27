package com.oleg.td;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "td")
public class Config {

    private Tdlib tdlib = new Tdlib();
    private Auth auth = new Auth();
    private String groups;
    private String libPath;
    private String botUsername;
    private String welcomeMessage;

    // Геттеры и сеттеры для основных полей
    public Tdlib getTdlib() { return tdlib; }
    public void setTdlib(Tdlib tdlib) { this.tdlib = tdlib; }

    public Auth getAuth() { return auth; }
    public void setAuth(Auth auth) { this.auth = auth; }

    public List<String> getGroups() {
        return groups == null || groups.isEmpty() ? List.of() : Arrays.asList(groups.split(","));
    }
    public void setGroups(String groups) { this.groups = groups; }

    public String getLibPath() { return libPath; }
    public void setLibPath(String libPath) { this.libPath = libPath; }

    public String getBotUsername() { return botUsername; }
    public void setBotUsername(String botUsername) { this.botUsername = botUsername; }

    public String getWelcomeMessage() { return welcomeMessage; }
    public void setWelcomeMessage(String welcomeMessage) { this.welcomeMessage = welcomeMessage; }

    public static class Tdlib {
        private int apiId;
        private String apiHash;
        private String databaseDirectory = "tdlib";
        private String filesDirectory = "tdlib/files";
        private String systemLanguageCode = "en";
        private String deviceModel = "Java";
        private String systemVersion;
        private String applicationVersion = "1.0";

        // Геттеры и сеттеры
        public int getApiId() { return apiId; }
        public void setApiId(int apiId) { this.apiId = apiId; }

        public String getApiHash() { return apiHash; }
        public void setApiHash(String apiHash) { this.apiHash = apiHash; }

        public String getDatabaseDirectory() { return databaseDirectory; }
        public void setDatabaseDirectory(String databaseDirectory) { this.databaseDirectory = databaseDirectory; }

        public String getFilesDirectory() { return filesDirectory; }
        public void setFilesDirectory(String filesDirectory) { this.filesDirectory = filesDirectory; }

        public String getSystemLanguageCode() { return systemLanguageCode; }
        public void setSystemLanguageCode(String systemLanguageCode) { this.systemLanguageCode = systemLanguageCode; }

        public String getDeviceModel() { return deviceModel; }
        public void setDeviceModel(String deviceModel) { this.deviceModel = deviceModel; }

        public String getSystemVersion() { return systemVersion; }
        public void setSystemVersion(String systemVersion) { this.systemVersion = systemVersion; }

        public String getApplicationVersion() { return applicationVersion; }
        public void setApplicationVersion(String applicationVersion) { this.applicationVersion = applicationVersion; }
    }

    public static class Auth {
        private String phone;
        private String code;
        private String pass;

        // Геттеры и сеттеры
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }

        public String getPass() { return pass; }
        public void setPass(String pass) { this.pass = pass; }
    }
}