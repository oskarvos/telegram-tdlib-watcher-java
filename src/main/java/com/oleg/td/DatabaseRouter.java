package com.oleg.td;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/** Выдаёт Database для нужного чата (по его названию), создаёт по требованию. */
public class DatabaseRouter implements Closeable {
    private final Path dbBaseDir;
    private final ChatTitleRegistry titles;
    private final ConcurrentHashMap<Long, Database> byChat = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> nameToChat = new ConcurrentHashMap<>();

    public DatabaseRouter(Path dbBaseDir, ChatTitleRegistry titles) {
        this.dbBaseDir = dbBaseDir;
        this.titles = titles;
        try { Files.createDirectories(dbBaseDir); } catch (IOException ignored) {}
    }

    public Database forChat(long chatId) {
        return byChat.computeIfAbsent(chatId, id -> {
            String base = StorageLayout.sanitize(titles.titleOf(id));
            // гарантируем уникальность имени файла между чатами
            String name = base;
            Long exists = nameToChat.putIfAbsent(name, id);
            if (exists != null && exists != id) {
                name = base + "_" + id; // коллизия — добавим id
                nameToChat.putIfAbsent(name, id);
            }
            Path path = StorageLayout.chatDbPath(dbBaseDir, name);
            return new Database(path.toString());
        });
    }

    @Override public void close() {
        byChat.values().forEach(db -> { try { db.close(); } catch (Exception ignored) {} });
    }
}
