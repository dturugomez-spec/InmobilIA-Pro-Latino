package com.inmobilia.controller;

import com.inmobilia.service.MarketplaceService;
import com.inmobilia.service.PlanService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 💞 "Tinder de pisos".
 *  - Público: /descubre (swipe), /descubre/pool, /descubre/swipe
 *  - Admin: /marketplace (activar add-on +25% y ver resultados)
 */
@Controller
@RequiredArgsConstructor
public class MarketplaceController {

    private final SupabaseService supabase;
    private final MarketplaceService marketplace;
    private final PlanService planService;

    @org.springframework.beans.factory.annotation.Value("${app.base-url:https://www.inmobiliapro.es}")
    private String baseUrl;

    private static final Set<String> PLANES_PAGO = Set.of("pro", "agency", "enterprise");
    private static final double ADD_ON_PCT = 0.25;

    // ─── PÚBLICO ───
    /** Buscador GLOBAL (red compartida de todas las agencias). */
    @GetMapping("/descubre")
    public String descubre(Model model) {
        model.addAttribute("agenciaSlug", "");
        model.addAttribute("agenciaNombre", "");
        return "descubre";
    }

    /** Buscador PROPIO de una agencia (en su microsite): solo sus pisos y su marca. */
    @GetMapping("/descubre/a/{slug}")
    public String descubreAgencia(@PathVariable String slug, Model model) {
        List<Map<String, Object>> ms = supabase.selectFilter("microsites", "slug=eq." + slug);
        String nombre = "";
        if (!ms.isEmpty()) {
            Object np = ms.get(0).get("nombre_publico");
            if (np != null && !String.valueOf(np).isBlank()) {
                nombre = String.valueOf(np);
            } else {
                List<Map<String, Object>> u = supabase.selectFilter("users", "id=eq." + ms.get(0).get("user_id"));
                if (!u.isEmpty()) nombre = String.valueOf(u.get(0).getOrDefault("name", ""));
            }
        }
        model.addAttribute("agenciaSlug", slug);
        model.addAttribute("agenciaNombre", nombre);
        return "descubre";
    }

    @PostMapping("/descubre/pool")
    @ResponseBody
    public Map<String, Object> pool(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, String> filtros = body.get("filtros") instanceof Map ? (Map<String, String>) body.get("filtros") : Map.of();
        Set<String> excluir = new HashSet<>();
        if (body.get("excluir") instanceof List<?> l) for (Object o : l) excluir.add(String.valueOf(o));
        String agenciaSlug = body.get("agencia") != null ? String.valueOf(body.get("agencia")).trim() : "";
        List<Map<String, Object>> cards;
        if (!agenciaSlug.isBlank()) {
            // Buscador por agencia: resolver el slug de microsite → user_id de esa agencia
            List<Map<String, Object>> ms = supabase.selectFilter("microsites", "slug=eq." + agenciaSlug);
            String agencyId = ms.isEmpty() ? null : String.valueOf(ms.get(0).get("user_id"));
            cards = marketplace.poolAgencia(agencyId, filtros, excluir, 15);
        } else {
            cards = marketplace.pool(filtros, excluir, 15);
        }
        return Map.of("ok", true, "cards", cards);
    }

    @PostMapping("/descubre/swipe")
    @ResponseBody
    public Map<String, Object> swipe(@RequestBody Map<String, String> in) {
        String propiedadId = in.get("propiedad_id");
        String accion = in.getOrDefault("accion", "pass");
        if (propiedadId == null) return Map.of("ok", false);
        Map<String, String> buyer = new HashMap<>();
        for (String k : List.of("device", "nombre", "email", "telefono", "zona")) if (in.get(k) != null) buyer.put(k, in.get(k));
        marketplace.registrarSwipe(propiedadId, accion, buyer);
        return Map.of("ok", true);
    }

    // ─── ADMIN ───
    @GetMapping("/marketplace")
    public String admin(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> u = supabase.selectFilter("users", "id=eq." + uid);
        Map<String, Object> user = u.isEmpty() ? Map.of() : u.get(0);
        String plan = String.valueOf(user.getOrDefault("plan", "")).toLowerCase();
        boolean activo = Boolean.TRUE.equals(user.get("tinder_activo"));
        boolean puede = PLANES_PAGO.contains(plan);

        List<Map<String, Object>> swipes = supabase.selectFilter(
                "tinder_swipes", "agency_id=eq." + uid + "&order=created_at.desc&limit=200");
        long likes = swipes.stream().filter(s -> "like".equals(s.get("accion")) || "superlike".equals(s.get("accion"))).count();
        long leads = swipes.stream().filter(s -> s.get("lead_id") != null).count();

        // Precio del add-on = 25% del precio del plan
        PlanService.PlanLimits limits = PlanService.PLANS.get(plan);
        int planPrice = limits != null ? limits.monthlyPriceEur() : 0;
        double addonPrice = Math.round(planPrice * ADD_ON_PCT * 100.0) / 100.0;

        model.addAttribute("plan", plan);
        model.addAttribute("planPrice", planPrice);
        model.addAttribute("addonPrice", addonPrice);
        model.addAttribute("activo", activo);
        model.addAttribute("puede", puede);
        model.addAttribute("totalSwipes", swipes.size());
        model.addAttribute("likes", likes);
        model.addAttribute("leadsGenerados", leads);
        model.addAttribute("swipesRecientes", swipes.stream().limit(20).toList());

        // Buscador PROPIO de la agencia (solo sus pisos) — enlace listo para su microsite/redes.
        List<Map<String, Object>> ms = supabase.selectFilter("microsites", "user_id=eq." + uid);
        String slug = ms.isEmpty() ? null : String.valueOf(ms.get(0).get("slug"));
        model.addAttribute("micrositeSlug", slug);
        model.addAttribute("buscadorUrl", slug != null ? (baseUrl + "/descubre/a/" + slug) : null);
        return "marketplace_admin";
    }

    @PostMapping("/marketplace/match")
    public String match(@RequestParam String swipeId, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        marketplace.hacerMatch(swipeId, uid);
        return "redirect:/marketplace";
    }

    @PostMapping("/marketplace/toggle")
    public String toggle(@RequestParam(required = false, defaultValue = "false") boolean activar,
                         HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> u = supabase.selectFilter("users", "id=eq." + uid);
        if (u.isEmpty()) return "redirect:/marketplace";
        String plan = String.valueOf(u.get(0).getOrDefault("plan", "")).toLowerCase();
        // Solo se puede activar con un plan de pago
        boolean nuevo = activar && PLANES_PAGO.contains(plan);
        supabase.update("users", uid, Map.of("tinder_activo", nuevo), uid);
        return "redirect:/marketplace";
    }
}
