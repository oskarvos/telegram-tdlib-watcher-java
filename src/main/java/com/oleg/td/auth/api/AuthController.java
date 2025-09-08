package com.oleg.td.auth.api;

import com.oleg.td.app.Config;
import com.oleg.td.integrations.tdlibs.AuthFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthFlow authFlow;
    private final Config   config;

    public AuthController(AuthFlow authFlow, Config config) {
        this.authFlow = authFlow;
        this.config   = config;
    }

    @GetMapping("/status")
    public AuthStatus status() {
        AuthStatus s = new AuthStatus();
        s.setAuthorized(authFlow.isAuthorized());
        s.setHasApiId(config.getTdlib().getApiId() > 0);
        s.setHasApiHash(notBlank(config.getTdlib().getApiHash()));
        s.setHasPhone(notBlank(config.getAuth().getPhone()));
        s.setPhoneMasked(mask(config.getAuth().getPhone()));
        return s;
    }

    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody AuthStartRequest req) {
        Map<String, Object> resp = new HashMap<>();
        try {
            if (req.getApiId() != null) config.getTdlib().setApiId(req.getApiId());
            if (req.getApiHash() != null) config.getTdlib().setApiHash(req.getApiHash().trim());
            if (req.getPhone() != null) config.getAuth().setPhone(req.getPhone().trim());
            config.getAuth().setCode(req.getCode());
            config.getAuth().setPass(req.getPass());

            // Повторный вызов допускается: используем вашу существующую логику
            authFlow.wireInto();
            authFlow.authorizeBlocking();

            boolean ok = authFlow.isAuthorized();
            resp.put("authorized", ok);
            resp.put("message", ok ? "Авторизация выполнена" : "Авторизация не подтверждена");
        } catch (Exception e) {
            log.error("Auth start error: {}", e.getMessage(), e);
            resp.put("authorized", false);
            resp.put("message", e.getMessage());
        }
        return resp;
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static String mask(String p) {
        if (!notBlank(p)) return null;
        String d = p.replaceAll("\\s+", "");
        if (d.length() <= 4) return "****";
        return d.substring(0, Math.min(2, d.length())) + "***" + d.substring(Math.max(0, d.length() - 2));
    }
}
