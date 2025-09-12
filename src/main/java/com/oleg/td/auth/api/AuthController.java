package com.oleg.td.auth.api;

import com.oleg.td.app.config.TdlibProperties;
import com.oleg.td.auth.service.AuthRuntimeStore;
import com.oleg.td.integrations.tdlibs.AuthFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST-контроллер для управления процессом авторизации через TDLib.
 * Предоставляет эндпоинты статуса авторизации и запуска/остановки процесса.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthFlow authFlow;
    private final TdlibProperties td;
    private final AuthRuntimeStore authStore;

    public AuthController(AuthFlow authFlow, TdlibProperties td, AuthRuntimeStore authStore) {
        this.authFlow = authFlow;
        this.td = td;
        this.authStore = authStore;
    }

    /**
     * GET /api/auth/status
     * Возвращает текущий статус авторизации и наличие введённых параметров.
     */
    @GetMapping("/status")
    public AuthStatus status() {
        AuthStatus s = new AuthStatus();
        s.setAuthorized(authFlow.isAuthorized());
        s.setHasApiId(td.getApiId() > 0);
        s.setHasApiHash(notBlank(td.getApiHash()));
        s.setHasPhone(notBlank(authStore.get().getPhone()));
        s.setPhoneMasked(mask(authStore.get().getPhone()));
        return s;
    }

    /**
     * POST /api/auth/start
     * Принимает параметры авторизации, сохраняет их и запускает блокирующую процедуру авторизации.
     * Возвращает флаг успеха и сообщение.
     */
    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody AuthStartRequest req) {
        Map<String, Object> resp = new HashMap<>();
        try {
            if (req.getApiId() != null) td.setApiId(req.getApiId());
            if (req.getApiHash() != null) td.setApiHash(req.getApiHash().trim());
            if (req.getPhone() != null) authStore.get().setPhone(req.getPhone().trim());
            authStore.get().setCode(req.getCode());
            authStore.get().setPass(req.getPass());

            authFlow.wireInto();
            authFlow.authorizeBlocking();

            boolean ok = authFlow.isAuthorized();
            resp.put("authorized", ok);
            resp.put("message", ok ? "Авторизация выполнена" : "Авторизация не подтверждена");
        } catch (Exception e) {
            log.error("Ошибка запуска авторизации: {}", e.getMessage(), e);
            resp.put("authorized", false);
            resp.put("message", e.getMessage());
        }
        return resp;
    }

    // Вспомогательный метод: проверка строки на непустоту
    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // Вспомогательный метод: маскирование номера телефона для отображения
    private static String mask(String p) {
        if (!notBlank(p)) return null;
        String d = p.replaceAll("\\s+", "");
        if (d.length() <= 4) return "****";
        return d.substring(0, Math.min(2, d.length())) + "***" + d.substring(Math.max(0, d.length() - 2));
    }
}
