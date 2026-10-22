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
    private final com.inmobilia.service.PaisService paises;
    private final com.inmobilia.service.WorkflowEngine workflows;

    /**
     * El inmueble público con esa referencia. La referencia la pone cada inmobiliaria y puede
     * repetirse entre ellas ("INM-0001"): antes se cogía la primera de toda la base de datos y se
     * podía ver el inmueble de otra agencia o mandarle el contacto. Si hay más de uno, no se adivina.
     */
    private Map<String, Object> inmueblePublico(String reference) {
        List<Map<String, Object>> publicos = supabase.findBy("propiedades", Map.of("reference", reference)).stream()
                .filter(p -> Boolean.TRUE.equals(p.get("publicada")) || "true".equalsIgnoreCase(String.valueOf(p.get("publicada"))))
                .toList();
        return publicos.size() == 1 ? publicos.get(0) : null;
    }

    @GetMapping("/p/{reference}")
    public String publicView(@PathVariable String reference, Model model) {
        Map<String, Object> prop = inmueblePublico(reference);
        if (prop == null) return "redirect:/";

        // Owner data (para el contacto)
        String orgId = String.valueOf(prop.get("user_id"));
        paises.usar(orgId);  // moneda y formato del país de la inmobiliaria
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
        Map<String, Object> prop = inmueblePublico(reference);
        if (prop == null) return Map.of("ok", false);
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
        if (saved != null && !saved.isEmpty()) {
            Map<String, Object> evento = new LinkedHashMap<>(saved);
            evento.put("propiedad_id", prop.get("id"));
            workflows.triggerEvent(orgId, "lead_creado", evento);
        }
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
