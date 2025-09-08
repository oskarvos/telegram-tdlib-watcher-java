package com.oleg.td.webauth;

import com.oleg.td.webauth.dto.AuthStatusResponse;
import com.oleg.td.webauth.dto.StartAuthRequest;
import com.oleg.td.webauth.dto.VerifyCodeRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/webauth")
public class WebAuthController {

    private final WebAuthService service;

    public WebAuthController(WebAuthService service) {
        this.service = service;
    }

    @PostMapping("/start")
    public AuthStatusResponse start(@RequestBody StartAuthRequest req) {
        return service.start(req);
    }

    @PostMapping("/verify")
    public AuthStatusResponse verify(@RequestBody VerifyCodeRequest req) {
        return service.verify(req);
    }

    @GetMapping("/status")
    public AuthStatusResponse status() {
        return service.status();
    }
}
