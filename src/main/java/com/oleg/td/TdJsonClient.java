package com.oleg.td;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TdJsonClient {
    private static final Logger log = LoggerFactory.getLogger(TdJsonClient.class);

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

    public String receive(double timeout) {
        return TdLib.INSTANCE.td_json_client_receive(client, timeout);
    }

    public void close() {
        TdLib.INSTANCE.td_json_client_destroy(client);
    }
}
