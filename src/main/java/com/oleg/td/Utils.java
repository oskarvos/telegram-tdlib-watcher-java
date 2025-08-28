// ============================================================================
// File: src/main/java/com/oleg/td/Utils.java
// Назначение: Утилиты для сборки TDLib JSON-объектов.
// ============================================================================
package com.oleg.td;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Утилиты для формирования TDLib-запросов.
 */
public final class Utils {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private Utils() {}

    /** Создать JSON-объект TDLib с полем @type. */
    public static ObjectNode obj(String type) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("@type", type);
        return node;
    }

    /** {"@type":"formattedText","text": text} */
    public static ObjectNode formattedText(String text) {
        ObjectNode ft = MAPPER.createObjectNode();
        ft.put("@type", "formattedText");
        ft.put("text", text == null ? "" : text);
        return ft;
    }
}
