package com.oleg.td.auth.web;


import com.oleg.td.integrations.tdlibs.AuthFlow;
import com.oleg.td.webauth.WebAuthService;
import com.oleg.td.webauth.dto.AuthStatusResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;


/**
 * Router for root and /app that decides which static page to forward to.
 *
 * Why: Use TDLib state if available, but also trust the already running AuthFlow.
 * This prevents unnecessary redirects to / when we are already authorized.
 */
@Controller
public class HomeController {
    private final AuthFlow authFlow;
    private final WebAuthService webAuth;


    public HomeController(AuthFlow authFlow, WebAuthService webAuth) {
        this.authFlow = authFlow;
        this.webAuth = webAuth;
    }


    private boolean isAuthorized() {
// Prefer WebAuthService READY, but OR with AuthFlow to avoid false negatives
        try {
            AuthStatusResponse st = webAuth.status();
            if (st.isOk() && "READY".equals(st.getState())) return true;
        } catch (Exception ignored) { /* fallback to AuthFlow below */ }
        return authFlow.isAuthorized();
    }


    @GetMapping("/")
    public String root() {
        return isAuthorized() ? "forward:/index.html" : "forward:/auth.html";
    }


    @GetMapping("/app")
    public String app() {
        return isAuthorized() ? "forward:/index.html" : "redirect:/";
    }
}