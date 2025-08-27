package com.oleg.td;

import org.springframework.stereotype.Component;

@Component
public class ChatResolver {
    public long resolve(String identifier) {
        try {
            return Long.parseLong(identifier);
        } catch (NumberFormatException e) {
            // placeholder for resolving chat links
            return identifier.hashCode();
        }
    }
}
