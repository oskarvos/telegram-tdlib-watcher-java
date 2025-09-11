package com.oleg.td.auth.api;

/**
 * // DTO для ответа статуса авторизации и наличия основных параметров.
 */
public class AuthStatus {
    private boolean authorized;
    private boolean hasApiId;
    private boolean hasApiHash;
    private boolean hasPhone;
    private String phoneMasked;

    public boolean isAuthorized() {
        return authorized;
    }

    public void setAuthorized(boolean authorized) {
        this.authorized = authorized;
    }

    public boolean isHasApiId() {
        return hasApiId;
    }

    public void setHasApiId(boolean hasApiId) {
        this.hasApiId = hasApiId;
    }

    public boolean isHasApiHash() {
        return hasApiHash;
    }

    public void setHasApiHash(boolean hasApiHash) {
        this.hasApiHash = hasApiHash;
    }

    public boolean isHasPhone() {
        return hasPhone;
    }

    public void setHasPhone(boolean hasPhone) {
        this.hasPhone = hasPhone;
    }

    public String getPhoneMasked() {
        return phoneMasked;
    }

    public void setPhoneMasked(String phoneMasked) {
        this.phoneMasked = phoneMasked;
    }
}
