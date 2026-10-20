package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gestión de API keys: crear, listar, revocar.
 */
@Controller
@RequiredArgsConstructor
public class ApiKeyController {

    private final SupabaseService supabase;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @GetMapping("/api-keys")
    public String list(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String orgId = SessionUtils.uid(session);
        var keys = supabase.findBy("api_keys", Map.of("org_id", orgId));
        model.addAttribute("page", "API Keys");
        model.addAttribute("keys", keys);
        return "api_keys";
    }

    @PostMapping("/api-keys")
    public String create(@RequestParam String name, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";

        String token = generateToken();
        String prefix = token.substring(0, 8);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", SessionUtils.realUid(session));
        data.put("org_id", SessionUtils.uid(session));
        data.put("name", name);
        data.put("token_hash", encoder.encode(token));
        data.put("prefix", prefix);
        data.put("created_at", OffsetDateTime.now().toString());

        supabase.insert("api_keys", data);
        SessionUtils.flash(session, "✅ API key creada: " + token + " (cópiala AHORA, no la verás de nuevo)");
        return "redirect:/api-keys";
    }

    @PostMapping("/api-keys/{id}/revoke")
    public String revoke(@PathVariable String id, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String orgId = SessionUtils.uid(session);
        supabase.update("api_keys", id,
                Map.of("revoked_at", OffsetDateTime.now().toString()),
                orgId);
        SessionUtils.flash(session, "API key revocada");
        return "redirect:/api-keys";
    }

    private static String generateToken() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder("ipro_");
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
