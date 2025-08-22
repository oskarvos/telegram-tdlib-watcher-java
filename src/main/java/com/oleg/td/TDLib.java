package com.oleg.td;

import com.sun.jna.*;

public interface TDLib extends Library {
    Pointer td_json_client_create();

    void td_json_client_send(Pointer client, String request);

    String td_json_client_receive(Pointer client, double timeout);

    String td_json_client_execute(Pointer client, String request);

    void td_json_client_destroy(Pointer client);

    interface LogMessageCallback extends Callback {
        void invoke(int v, String m);
    }

    void td_set_log_message_callback(int maxLevel, LogMessageCallback cb);

    static TDLib load(String path) {
        return (path != null && !path.isBlank()) ? Native.load(path, TDLib.class) : Native.load("tdjson", TDLib.class);
    }
}
