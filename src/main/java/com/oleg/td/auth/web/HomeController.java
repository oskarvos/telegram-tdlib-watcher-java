package com.oleg.td.auth.web;

import com.oleg.td.integrations.tdlibs.AuthFlow;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * // Web-контроллер для маршрутизации стартовой страницы.
 * // Если авторизация выполнена — отдаём основной интерфейс, иначе — мастер авторизации.
 */
@Controller
public class HomeController {
    private final AuthFlow authFlow;

    public HomeController(AuthFlow authFlow) {
        this.authFlow = authFlow;
    }

    /**
     * // GET /
     * // Корневой маршрут: редирект на index.html (если авторизованы) или на auth.html (если нет).
     */
    @GetMapping("/")
    public String root() {
        // если уже авторизованы — сразу основной экран, иначе мастер авторизации
        return authFlow.isAuthorized() ? "forward:/index.html" : "forward:/auth.html";
    }

    /**
     * // GET /app
     * // При прямом заходе/обновлении /app: если не авторизованы — уходим на / (авторизация).
     */
    @GetMapping("/app")
    public String app() {
        return authFlow.isAuthorized() ? "forward:/index.html" : "redirect:/";
    }
}
