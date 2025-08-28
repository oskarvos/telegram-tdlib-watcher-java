// ============================================================================
// File: src/main/java/com/oleg/td/HomeController.java
// Назначение: Отдаёт стартовую страницу (templates/index.html).
// ============================================================================
package com.oleg.td;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Контроллер домашней страницы.
 */
@Controller
public class HomeController {
    /** Возвращает шаблон index.html (Thymeleaf не обязателен — чистый HTML). */
    @GetMapping("/")
    public String index() { return "index"; }
}