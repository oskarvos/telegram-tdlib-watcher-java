package com.oleg.td.webauth.dto;

public class VerifyCodeRequest {
    private String code;
    private String password; // 2FA (если включён)

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
