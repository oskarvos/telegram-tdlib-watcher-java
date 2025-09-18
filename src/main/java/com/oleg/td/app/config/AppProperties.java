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

    private String libPath;                // Путь к директории с библиотекой TDLib
    private String botUsername;            // Имя бота для регистронезависимого сравнения
    private String welcomeMessage;         // Приветственное сообщение для пользователей
    private boolean useTestDc = false;     // Флаг использования тестового датацентра Telegram
    private boolean caseInsensitive = true;// Флаг регистронезависимой обработки команд

    public String getLibPath() {
        return libPath;
    }

    public void setLibPath(String libPath) {
        this.libPath = libPath;
    }

    public String getBotUsername() {
        return botUsername;
    }

    public void setBotUsername(String botUsername) {
        this.botUsername = botUsername;
    }

    public String getWelcomeMessage() {
        return welcomeMessage;
    }

    public void setWelcomeMessage(String welcomeMessage) {
        this.welcomeMessage = welcomeMessage;
    }

    public boolean getUseTestDc() {
        return useTestDc;
    }

    public void setUseTestDc(boolean useTestDc) {
        this.useTestDc = useTestDc;
    }

    public boolean isCaseInsensitive() {
        return caseInsensitive;
    }

    public void setCaseInsensitive(boolean caseInsensitive) {
        this.caseInsensitive = caseInsensitive;
    }
}
