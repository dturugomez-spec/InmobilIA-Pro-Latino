package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Páginas públicas de propiedades — sin login.
 * URL: /p/{reference} → ficha pública compartible.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class PublicPropertyController {

    private final SupabaseService supabase;

    @GetMapping("/p/{reference}")
    public String publicView(@PathVariable String reference, Model model) {
        // Buscar por referencia (en TODA la BBDD, no por org)
        var rows = supabase.findBy("propiedades", Map.of("reference", reference));
        if (rows.isEmpty()) return "redirect:/";

        Map<String, Object> prop = rows.get(0);
        // Solo mostrar si está marcada como pública
        Object pub = prop.get("publicada");
        if (pub == null || !(Boolean.TRUE.equals(pub) || "true".equalsIgnoreCase(String.valueOf(pub)))) {
            return "redirect:/";
        }

        // Owner data (para el contacto)
        String orgId = String.valueOf(prop.get("user_id"));
        var owners = supabase.findBy("users", Map.of("id", orgId));
        Map<String, Object> owner = owners.isEmpty() ? Map.of() : owners.get(0);

        // Incrementar visita (async)
        registerVisit(String.valueOf(prop.get("id")), prop);

        model.addAttribute("prop", prop);
        model.addAttribute("owner", owner);
        return "public_property";
    }

    /** Formulario de contacto desde la página pública → genera lead. */
    @PostMapping("/p/{reference}/contact")
    @ResponseBody
    public Map<String, Object> contact(@PathVariable String reference,
                                        @RequestBody Map<String, String> body) {
        var rows = supabase.findBy("propiedades", Map.of("reference", reference));
        if (rows.isEmpty()) return Map.of("ok", false);
        Map<String, Object> prop = rows.get(0);
        String orgId = String.valueOf(prop.get("user_id"));

        Map<String, Object> lead = new LinkedHashMap<>();
        lead.put("user_id", orgId);
        lead.put("nombre", body.get("name"));
        lead.put("email", body.get("email"));
        lead.put("telefono", body.get("phone"));
        lead.put("interes", "Interesado/a en " + prop.get("name") + ". Mensaje: " + body.getOrDefault("message", ""));
        lead.put("origen", "Web pública");
        lead.put("etapa", "Nuevo");
        lead.put("zona", prop.get("city"));
        lead.put("created_at", OffsetDateTime.now().toString());

        Map<String, Object> saved = supabase.insert("leads", lead);
        return Map.of("ok", saved != null && !saved.isEmpty());
    }

    @Async
    void registerVisit(String propId, Map<String, Object> prop) {
        try {
            int current = 0;
            Object c = prop.get("visitas_count");
            if (c != null) try { current = Integer.parseInt(String.valueOf(c)); } catch (Exception ignored) {}
            supabase.update("propiedades", propId,
                    Map.of("visitas_count", current + 1),
                    String.valueOf(prop.get("user_id")));
        } catch (Exception ignored) {}
    }
}
