package com.oleg.td;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Utility helpers for constructing TDLib requests. */
public final class Utils {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Utils() {
        // no instances
    }

    /**
     * Create a new {@link ObjectNode} representing a TDLib request with the given type.
     *
     * @param type TDLib request type placed into the "@type" field
     * @return object node with the specified type
     */
    public static ObjectNode obj(String type) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("@type", type);
        return node;
    }
}