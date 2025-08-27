package com.oleg.td;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TdJsonClient {
    private static final Logger log = LoggerFactory.getLogger(TdJsonClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern FLOOD_WAIT = Pattern.compile("(\\d+)");

    public enum Channel { AUTH, MAIN }

    private interface TdLib extends Library {
        TdLib INSTANCE = Native.load("tdjson", TdLib.class);
        Pointer td_json_client_create();
        void td_json_client_send(Pointer client, String request);
        String td_json_client_receive(Pointer client, double timeout);
        void td_json_client_destroy(Pointer client);
    }

    private final Pointer client = TdLib.INSTANCE.td_json_client_create();

    public void send(String request) {
        TdLib.INSTANCE.td_json_client_send(client, request);
    }

    public void send(String request, Channel channel) {
        send(request);
    }

    public void send(ObjectNode req) {
        send(req.toString());
    }

    public void send(ObjectNode req, Channel channel) {
        send(req.toString(), channel);
    }

    public ObjectNode requestWithFloodWaitSyncLimited(ObjectNode req, int limit, Channel channel) {
        int remaining = Math.min(limit, 60);
        while (true) {
            send(req, channel);
            ObjectNode resp = waitForResponse();
            if (resp == null) {
                return MAPPER.createObjectNode();
            }

            String type = resp.path("@type").asText();
            if ("error".equals(type) && resp.path("code").asInt() == 429) {
                int waitSec = extractFloodWait(resp.path("message").asText());
                if (waitSec <= 0) {
                    return resp;
                }
                if (waitSec > remaining) {
                    log.warn("Requested flood wait {}s exceeds remaining limit {}s; waiting only {}s", waitSec, remaining, remaining);
                    try { Thread.sleep(remaining * 1000L); } catch (InterruptedException ignored) {}
                    remaining = 0;
                    send(req, channel);
                    return waitForResponse();
                }
                try { Thread.sleep(waitSec * 1000L); } catch (InterruptedException ignored) {}
                remaining -= waitSec;
                continue; // resend the request after waiting
            }
            return resp;
        }
    }

    private ObjectNode waitForResponse() {
        try {
            while (true) {
                String respStr = receive(60);
                if (respStr == null) continue;
                ObjectNode node = (ObjectNode) MAPPER.readTree(respStr);
                String type = node.path("@type").asText();
                if (type.startsWith("update")) {
                    // Ignore updates; higher level components may handle them separately.
                    log.debug("Ignoring update: {}", respStr);
                    continue;
                }
                return node;
            }
        } catch (Exception e) {
            log.error("Failed to parse TDLib response", e);
            return null;
        }
    }

    private static int extractFloodWait(String message) {
        Matcher m = FLOOD_WAIT.matcher(message);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    public String receive(double timeout) {
        return TdLib.INSTANCE.td_json_client_receive(client, timeout);
    }

    public void close() {
        TdLib.INSTANCE.td_json_client_destroy(client);
    }
}
