package com.oleg.td.webauth.dto;

public class AuthStatusResponse {
    private boolean ok;
    private String state;   // READY / WAIT_CODE / WAIT_PASSWORD / ...
    private String message; // для ошибок

    public static AuthStatusResponse ok(String state) {
        AuthStatusResponse r = new AuthStatusResponse();
        r.ok = true; r.state = state; return r;
    }
    public static AuthStatusResponse error(String msg) {
        AuthStatusResponse r = new AuthStatusResponse();
        r.ok = false; r.message = msg; return r;
    }

    public boolean isOk() { return ok; }
    public void setOk(boolean ok) { this.ok = ok; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
