package com.toysystem.gateway.controller;

import com.toysystem.gateway.dto.LoginRequest;
import com.toysystem.gateway.dto.LoginResponse;
import com.toysystem.gateway.security.InMemoryUserStore;
import com.toysystem.gateway.security.JwtService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final InMemoryUserStore userStore;
    private final JwtService jwtService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        if (!userStore.authenticate(request.getUsername(), request.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        String token = jwtService.generateToken(request.getUsername());
        return ResponseEntity.ok(new LoginResponse(token, jwtService.getExpirationSeconds()));
    }
}
