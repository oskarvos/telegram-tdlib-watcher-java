package com.oleg.td.common;

import com.oleg.td.integrations.telegram.ChatResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

@Component
public class DbUtils {
    private static final Logger log = LoggerFactory.getLogger(DbUtils.class);
    private static final Pattern INVALID_FILENAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|]");

    private final Path dbDir = Paths.get("tdlib", "db");
    private final ChatResolver chatResolver;

    public DbUtils(ChatResolver chatResolver) {
        this.chatResolver = chatResolver;
    }

    public String qIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    public String getChatNameForDatabase(long chatId) {
        try {
            String chatTitle = chatResolver.getChatTitle(chatId);
            if (chatTitle != null && !chatTitle.trim().isEmpty()) {
                return chatTitle.trim();
            }
        } catch (Exception e) {
            log.warn("БД: не удалось получить название чата {}: {}", chatId, e.getMessage());
        }
        return "chat_" + Math.abs(chatId);
    }

    public String sanitizeFileName(String fileName, long chatId) {
        if (fileName == null || fileName.isEmpty()) return "unknown_chat";
        String sanitized = INVALID_FILENAME_CHARS.matcher(fileName).replaceAll("_").trim();
        while (sanitized.endsWith(".")) sanitized = sanitized.substring(0, sanitized.length() - 1).trim();
        if (sanitized.isEmpty()) return "chat_" + Math.abs(chatId);
        if (sanitized.length() > 100) sanitized = sanitized.substring(0, 100);
        return sanitized;
    }

    public Path getDbDir() {
        return dbDir;
    }
}