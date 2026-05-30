package com.flexpop.engine.api;

import com.flexpop.engine.api.dto.SessionCreateRequest;
import com.flexpop.engine.api.dto.SessionResponse;
import com.flexpop.engine.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/sessions")
public class SessionController {

    private final SessionService service;

    public SessionController(SessionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<SessionResponse> create(@Valid @RequestBody SessionCreateRequest req,
                                                  HttpServletRequest http) {
        SessionResponse body = service.create(req, http);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
}
