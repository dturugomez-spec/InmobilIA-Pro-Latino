package com.inmobilia.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Autenticación de la API pública con las claves de /api-keys (prefijo + hash bcrypt).
 * La usan /api/v1 y /api/v2: antes la v2 buscaba la clave en claro en una columna que nunca se
 * rellena y respondía siempre 401.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiKeyAuth {

    private final SupabaseService supabase;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** Id de la inmobiliaria dueña de la clave del "Authorization: Bearer ...", o null. */
    public String organizacion(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        String token = auth.substring(7).trim();
        if (token.length() < 32 || !token.matches("[A-Za-z0-9_-]+")) return null;
        try {
            for (Map<String, Object> k : supabase.findBy("api_keys", Map.of("prefix", token.substring(0, 8)))) {
                if (k.get("revoked_at") != null && !"null".equals(String.valueOf(k.get("revoked_at")))) continue;
                if (encoder.matches(token, String.valueOf(k.get("token_hash")))) {
                    String orgId = String.valueOf(k.get("org_id"));
                    supabase.update("api_keys", String.valueOf(k.get("id")),
                            Map.of("last_used", OffsetDateTime.now().toString()), null);
                    return orgId;
                }
            }
        } catch (Exception e) {
            log.warn("API auth error: {}", e.getMessage());
        }
        return null;
    }
}
