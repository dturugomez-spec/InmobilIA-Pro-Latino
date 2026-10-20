package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * API pública REST · /api/v1/*
 * Auth: header `Authorization: Bearer <token>`
 *
 * Endpoints públicos para que clientes Enterprise integren con CRM externos.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class PublicApiController {

    private final SupabaseService supabase;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private String authenticate(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        String token = auth.substring(7).trim();
        if (token.length() < 32) return null;

        // Buscar por prefix (primeros 8 chars) y verificar bcrypt
        String prefix = token.substring(0, 8);
        try {
            var keys = supabase.findBy("api_keys", Map.of("prefix", prefix));
            for (var k : keys) {
                if (k.get("revoked_at") != null && !"null".equals(String.valueOf(k.get("revoked_at")))) continue;
                String hash = String.valueOf(k.get("token_hash"));
                if (encoder.matches(token, hash)) {
                    // Update last_used (async-ish)
                    String orgId = String.valueOf(k.get("org_id"));
                    supabase.update("api_keys", String.valueOf(k.get("id")),
                            Map.of("last_used", OffsetDateTime.now().toString()), orgId);
                    return orgId;
                }
            }
        } catch (Exception e) {
            log.warn("API auth error: {}", e.getMessage());
        }
        return null;
    }

    @GetMapping("/properties")
    public ResponseEntity<?> properties(HttpServletRequest req) {
        String orgId = authenticate(req);
        if (orgId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        return ResponseEntity.ok(Map.of("data", supabase.query("propiedades", orgId)));
    }

    @GetMapping("/tenants")
    public ResponseEntity<?> tenants(HttpServletRequest req) {
        String orgId = authenticate(req);
        if (orgId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        return ResponseEntity.ok(Map.of("data", supabase.query("inquilinos", orgId)));
    }

    @GetMapping("/contracts")
    public ResponseEntity<?> contracts(HttpServletRequest req) {
        String orgId = authenticate(req);
        if (orgId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        return ResponseEntity.ok(Map.of("data", supabase.query("contratos", orgId)));
    }

    @GetMapping("/transactions")
    public ResponseEntity<?> transactions(HttpServletRequest req) {
        String orgId = authenticate(req);
        if (orgId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        return ResponseEntity.ok(Map.of("data", supabase.query("transacciones", orgId)));
    }

    @GetMapping("/invoices")
    public ResponseEntity<?> invoices(HttpServletRequest req) {
        String orgId = authenticate(req);
        if (orgId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        return ResponseEntity.ok(Map.of("data", supabase.query("invoices", orgId)));
    }

    @GetMapping("/leads")
    public ResponseEntity<?> leads(HttpServletRequest req) {
        String orgId = authenticate(req);
        if (orgId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        return ResponseEntity.ok(Map.of("data", supabase.query("leads", orgId)));
    }

    @PostMapping("/leads")
    public ResponseEntity<?> createLead(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        String orgId = authenticate(req);
        if (orgId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        body.put("user_id", orgId);
        body.putIfAbsent("etapa", "Nuevo");
        body.put("created_at", OffsetDateTime.now().toString());
        Map<String, Object> saved = supabase.insert("leads", body);
        return saved != null ? ResponseEntity.ok(Map.of("data", saved)) : ResponseEntity.status(500).body(Map.of("error","Insert failed"));
    }
}
