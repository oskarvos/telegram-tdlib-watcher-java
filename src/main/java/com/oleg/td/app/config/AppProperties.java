package com.oleg.td.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * // Общие свойства приложения из префикса td.* (кроме td.tdlib.*).
 * // Используется для путей, флагов и текстов UI.
 */
@Component
@ConfigurationProperties(prefix = "td")
public class AppProperties {
    // // Дополнительные настройки интеграций и UI.
    private String libPath;
    private String botUsername;
    private String welcomeMessage;
    private boolean useTestDc = false;
    private boolean caseInsensitive = true;

    // // Геттеры/сеттеры
    public String getLibPath() { return libPath; }
    public void setLibPath(String libPath) { this.libPath = libPath; }
    public String getBotUsername() { return botUsername; }
    public void setBotUsername(String botUsername) { this.botUsername = botUsername; }
    public String getWelcomeMessage() { return welcomeMessage; }
    public void setWelcomeMessage(String welcomeMessage) { this.welcomeMessage = welcomeMessage; }
    public boolean getUseTestDc() { return useTestDc; }
    public void setUseTestDc(boolean useTestDc) { this.useTestDc = useTestDc; }
    public boolean isCaseInsensitive() { return caseInsensitive; }
    public void setCaseInsensitive(boolean caseInsensitive) { this.caseInsensitive = caseInsensitive; }
}
