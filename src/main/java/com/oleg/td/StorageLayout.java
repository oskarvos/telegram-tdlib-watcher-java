package com.oleg.td;

import java.nio.file.Path;

public final class StorageLayout {
    private StorageLayout() {}

    /** Делаем безопасное имя для файла/папки. */
    public static String sanitize(String title) {
        if (title == null) title = "";
        String s = title.replaceAll("[\\\\/:*?\"<>|]", "_") // запретные символы
                .replaceAll("\\s+", " ")            // много пробелов -> один
                .trim()
                .replace(' ', '_')                  // пробелы -> _
                .replaceAll("[._]{2,}", "_");       // .. и __ -> _
        if (s.isEmpty()) s = "chat";
        if (s.length() > 80) s = s.substring(0, 80);        // не безумно длинно
        return s;
    }

    /** Путь к DB файла чата: <base>/<chat>.sqlite */
    public static Path chatDbPath(Path dbBaseDir, String chatTitle) {
        return dbBaseDir.resolve(sanitize(chatTitle) + ".sqlite");
    }

    /** Папка для медиа чата: <files>/<chat>/<kind-folder> */
    public static Path mediaDir(Path filesBaseDir, String chatTitle, String kind) {
        String folder = switch (kind) {
            case "photo" -> "photos";
            case "video", "animation", "video_note" -> "videos";
            case "voice_note", "audio" -> "voice";
            default -> "temp";
        };
        return filesBaseDir.resolve(sanitize(chatTitle)).resolve(folder);
    }
}
