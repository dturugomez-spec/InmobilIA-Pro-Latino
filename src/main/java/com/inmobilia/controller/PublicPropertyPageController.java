package com.inmobilia.controller;

import com.inmobilia.service.BrandingService;
import com.inmobilia.service.LeadAutoResponderService;
import com.inmobilia.service.SupabaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Página pública SEO por propiedad. URL: /propiedad/{id} (sin login).
 * Incluye OpenGraph, Schema.org RealEstateListing y formulario de contacto.
 */
@Controller
@RequiredArgsConstructor
public class PublicPropertyPageController {

    private final SupabaseService supabase;
    private final com.inmobilia.service.PaisService paises;
    private final BrandingService branding;
    private final LeadAutoResponderService autoResponder;

    @org.springframework.beans.factory.annotation.Value("${app.base-url:https://inmobiliapro.es}")
    private String baseUrl;

    @GetMapping("/propiedad/{id}")
    public String publica(@PathVariable String id, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("propiedades", "id=eq." + id);
        if (rows.isEmpty()) {
            model.addAttribute("error", "Propiedad no encontrada");
            return "property_public_page";
        }
        Map<String, Object> p = rows.get(0);

        // Datos de la agencia (branding real) para contacto/cabecera
        String uid = String.valueOf(p.get("user_id"));
        paises.usar(uid);  // moneda y formato del país de la inmobiliaria
        Map<String, Object> agente = branding.branding(uid);

        // Microsite del agente para branding
        List<Map<String, Object>> microsites = supabase.selectFilter("microsites", "user_id=eq." + uid);
        Map<String, Object> ms = microsites.isEmpty() ? null : microsites.get(0);

        // Propiedades similares (mismo tipo, misma agencia)
        StringBuilder qs = new StringBuilder("user_id=eq.").append(uid)
                .append("&id=neq.").append(id).append("&limit=4");
        if (p.get("type") != null) qs.append("&type=eq.").append(p.get("type"));
        List<Map<String, Object>> similares = supabase.selectFilter("propiedades", qs.toString());

        // Galería de fotos del inmueble (subidas por el cliente) — visibles para buscadores/portales.
        // Solo imágenes de la propia inmobiliaria: antes salían también los demás adjuntos del inmueble
        // (enlaces a escrituras, contratos...) y los que otra cuenta colgara de este id.
        List<Map<String, Object>> fotos = supabase.selectFilter("attachments",
                "entity_type=eq.propiedad&entity_id=eq." + id + "&org_id=eq." + uid
                + "&file_type=eq.image&order=created_at.asc&limit=40");

        model.addAttribute("p", p);
        model.addAttribute("fotos", fotos);
        model.addAttribute("agente", agente);
        model.addAttribute("ms", ms);
        model.addAttribute("similares", similares);
        model.addAttribute("canonical", publicBase(baseUrl) + "/propiedad/" + id);
        return "property_public_page";
    }

    @PostMapping("/propiedad/{id}/contactar")
    public String contactar(@PathVariable String id, @RequestParam Map<String, String> form, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("propiedades", "id=eq." + id);
        if (rows.isEmpty()) return "redirect:/";
        Map<String, Object> p = rows.get(0);

        // Crear lead
        Map<String, Object> lead = new LinkedHashMap<>();
        lead.put("user_id", p.get("user_id"));
        lead.put("nombre", form.get("nombre"));
        lead.put("email", form.get("email"));
        lead.put("telefono", form.get("telefono"));
        lead.put("notas", "Interesado en: " + p.getOrDefault("name", p.getOrDefault("address", "")) + "\n\n" + form.getOrDefault("mensaje", ""));
        lead.put("origen", "pagina_propiedad");
        lead.put("etapa", "Nuevo");
        lead.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("leads", lead);

        // ⚡ Speed-to-lead: respuesta instantánea (si la agencia lo tiene activado)
        try { autoResponder.responder(String.valueOf(p.get("user_id")), lead, p); } catch (Exception ignored) {}

        model.addAttribute("p", p);
        model.addAttribute("gracias", true);
        return "property_public_page";
    }

    private static double nd(Object o) { try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }

    /**
     * Dominio público canónico para SEO. Ignora URLs internas (railway.app/localhost)
     * aunque estén en APP_BASE_URL, para no emitir un canonical que apunte fuera del
     * dominio real. Respeta app.base-url si es un dominio público válido.
     */
    static String publicBase(String configured) {
        String b = configured == null ? "" : configured.trim().replaceAll("/+$", "");
        if (b.isEmpty() || b.contains("railway.app") || b.contains("localhost") || b.contains("127.0.0.1")) {
            return "https://www.inmobiliapro.es";
        }
        return b;
    }
}
