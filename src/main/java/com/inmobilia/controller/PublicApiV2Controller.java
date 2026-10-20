package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * API REST extendida v2 con stats, leads y contratos.
 * Auth: Bearer ipro_xxx
 */
@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class PublicApiV2Controller {

    private final SupabaseService supabase;

    private String auth(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        String token = auth.substring(7);
        List<Map<String, Object>> keys = supabase.selectFilter("api_keys", "token=eq." + token + "&revoked_at=is.null");
        if (keys.isEmpty()) return null;
        supabase.update("api_keys", String.valueOf(keys.get(0).get("id")), Map.of("last_used", OffsetDateTime.now().toString()));
        return String.valueOf(keys.get(0).get("user_id"));
    }

    @GetMapping("/properties")
    public ResponseEntity<?> properties(HttpServletRequest req,
                                         @RequestParam(defaultValue = "20") int limit,
                                         @RequestParam(required = false) String tipo) {
        String uid = auth(req);
        if (uid == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        StringBuilder q = new StringBuilder("user_id=eq.").append(uid).append("&limit=").append(limit);
        if (tipo != null) q.append("&tipo=eq.").append(tipo);
        return ResponseEntity.ok(Map.of(
                "data", supabase.selectFilter("propiedades", q.toString()),
                "version", "v2"
        ));
    }

    @PostMapping("/leads")
    public ResponseEntity<?> createLead(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        String uid = auth(req);
        if (uid == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        body.put("user_id", uid);
        body.put("origen", body.getOrDefault("origen", "api_v2"));
        body.put("created_at", OffsetDateTime.now().toString());
        return ResponseEntity.ok(supabase.insert("leads", body));
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats(HttpServletRequest req) {
        String uid = auth(req);
        if (uid == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        return ResponseEntity.ok(Map.of(
                "properties", supabase.selectFilter("propiedades", "user_id=eq." + uid).size(),
                "leads", supabase.selectFilter("leads", "user_id=eq." + uid).size(),
                "contracts", supabase.selectFilter("contratos", "user_id=eq." + uid).size(),
                "visits", supabase.selectFilter("visitas", "user_id=eq." + uid).size(),
                "ofertas", supabase.selectFilter("ofertas", "user_id=eq." + uid).size()
        ));
    }

    /** Documentación Swagger/OpenAPI básica. */
    @GetMapping("/openapi.json")
    public ResponseEntity<?> openapi() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("openapi", "3.0.0");
        spec.put("info", Map.of(
                "title", "InmobilIA Pro API",
                "version", "2.0.0",
                "description", "REST API for property management"
        ));
        spec.put("servers", List.of(Map.of("url", com.inmobilia.util.UrlBase.BASE + "/api/v2")));
        spec.put("components", Map.of(
                "securitySchemes", Map.of("bearerAuth", Map.of(
                        "type", "http", "scheme", "bearer", "bearerFormat", "ipro_*"
                ))
        ));
        spec.put("security", List.of(Map.of("bearerAuth", List.of())));
        Map<String, Object> paths = new LinkedHashMap<>();
        paths.put("/properties", Map.of("get", Map.of("summary", "List properties", "responses", Map.of("200", Map.of("description", "OK")))));
        paths.put("/leads", Map.of("post", Map.of("summary", "Create lead", "responses", Map.of("200", Map.of("description", "Created")))));
        paths.put("/stats", Map.of("get", Map.of("summary", "Account statistics", "responses", Map.of("200", Map.of("description", "OK")))));
        spec.put("paths", paths);
        return ResponseEntity.ok(spec);
    }
}
