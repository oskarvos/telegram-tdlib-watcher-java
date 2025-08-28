package com.oleg.td;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class Utils {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private Utils() {}

    public static ObjectNode obj(String type) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("@type", type);
        return node;
    }

    public static ObjectNode formattedText(String text) {
        ObjectNode ft = MAPPER.createObjectNode();
        ft.put("@type", "formattedText");
        ft.put("text", text == null ? "" : text);
        return ft;
    }
}