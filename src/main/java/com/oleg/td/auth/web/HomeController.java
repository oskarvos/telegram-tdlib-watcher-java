package com.oleg.td.auth.web;

import com.oleg.td.webauth.WebAuthService;
import com.oleg.td.webauth.dto.AuthStatusResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Маршрутизация стартовых точек.
 * Проверка авторизации синхронизирована с /api/webauth/status.
 */
@Controller
public class HomeController {
    private final WebAuthService web;

    public HomeController(WebAuthService web) {
        this.web = web;
    }

    private boolean isReady() {
        AuthStatusResponse st = web.status();
        return st != null && st.isOk() && "READY".equals(st.getState());
    }

    /** Корень: если авторизованы — приложение; иначе — мастер авторизации. */
    @GetMapping("/")
    public String root() {
        return isReady() ? "forward:/index.html" : "forward:/auth.html";
    }

    /** Прямой заход на /app — то же поведение. */
    @GetMapping("/app")
    public String app() {
        return isReady() ? "forward:/index.html" : "redirect:/auth.html";
    }
}
