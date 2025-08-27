package com.oleg.td;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class Utils {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Utils() {
        // no instances
    }

    public static ObjectNode obj(String type) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("@type", type);
        return node;
    }
}