package com.oleg.td.auth.service;

import com.oleg.td.auth.model.AuthState;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * // Простой потокобезопасный стор для временных auth-данных.
 * // Можно заменить на сессию/Redis/БД — интерфейс останется прежним.
 */
@Service
public class AuthRuntimeStore {
    private final AtomicReference<AuthState> ref = new AtomicReference<>(new AuthState());

    // // Получить текущее состояние.
    public AuthState get() { return ref.get(); }

    // // Полностью заменить состояние.
    public void set(AuthState state) { ref.set(state); }

    // // Сбросить.
    public void clear() { ref.set(new AuthState()); }
}
