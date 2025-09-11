package com.oleg.td.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * // Свойства TDLib из префикса td.tdlib.*
 * // Хранит стабильную конфигурацию (ID, хэши, каталоги и версии).
 */
@Component
@ConfigurationProperties(prefix = "td.tdlib")
public class TdlibProperties {
    private int apiId;
    private String apiHash;
    private String databaseDirectory = "tdlib";
    private String filesDirectory = "tdlib/files";
    private String systemLanguageCode = "en";
    private String deviceModel = "Java";
    private String systemVersion;
    private String applicationVersion = "1.0.4-debug";

    // // Геттеры/сеттеры
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
