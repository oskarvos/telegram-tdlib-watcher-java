package com.oleg.td.auth.web;

import com.oleg.td.webauth.WebAuthService;
import com.oleg.td.webauth.dto.AuthStatusResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Фильтр, который не даёт открыть /index.html без авторизации.
 * Работает раньше раздачи статических ресурсов, поэтому покрывает прямые заходы.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatekeeperFilter extends OncePerRequestFilter {

    private final WebAuthService web;

    public GatekeeperFilter(WebAuthService web) {
        this.web = web;
    }

    private boolean isReady() {
        AuthStatusResponse st = web.status();
        return st != null && st.isOk() && "READY".equals(st.getState());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        // Фильтруем только ключевые входные страницы
        return !("/".equals(p) || "/index".equals(p) || "/index.html".equals(p) || "/app".equals(p) || "/auth.html".equals(p));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req,
            HttpServletResponse resp,
            FilterChain chain
    ) throws ServletException, IOException {

        String path = req.getRequestURI();
        boolean ready = isReady();

        // Неавторизованных с /, /index.html, /app отправляем на мастер
        if (!ready && ("/".equals(path) || "/index".equals(path) || "/index.html".equals(path) || "/app".equals(path))) {
            resp.sendRedirect("/auth.html");
            return;
        }

        // Авторизованных с /auth.html — сразу в приложение
        if (ready && "/auth.html".equals(path)) {
            resp.sendRedirect("/index.html");
            return;
        }

        chain.doFilter(req, resp);
    }
}
