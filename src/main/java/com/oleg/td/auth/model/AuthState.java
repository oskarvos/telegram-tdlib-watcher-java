package com.oleg.td.auth.model;

/**
 * // Временное состояние авторизации на рантайме.
 * // Хранит последние введённые пользователем телефон/код/пароль (при необходимости).
 * // По умолчанию это простой контейнер; стратегию хранения (в памяти/сессии/БД) выбирает сервис.
 */
public class AuthState {
    private String phone;
    private String code;
    private String pass;

    // // Геттеры/сеттеры
    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getPass() {
        return pass;
    }

    public void setPass(String pass) {
        this.pass = pass;
    }
}
