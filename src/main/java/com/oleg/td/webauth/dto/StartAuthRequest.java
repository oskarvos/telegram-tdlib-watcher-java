package com.oleg.td.webauth.dto;

public class StartAuthRequest {
    private Integer apiId;
    private String apiHash;
    private String phone;
    private Boolean useTestDc; // опционально

    public Integer getApiId() { return apiId; }
    public void setApiId(Integer apiId) { this.apiId = apiId; }

    public String getApiHash() { return apiHash; }
    public void setApiHash(String apiHash) { this.apiHash = apiHash; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public Boolean getUseTestDc() { return useTestDc; }
    public void setUseTestDc(Boolean useTestDc) { this.useTestDc = useTestDc; }
}
