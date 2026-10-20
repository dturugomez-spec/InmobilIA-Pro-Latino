package com.inmobilia.controller;

import com.inmobilia.service.BrandingService;
import com.inmobilia.service.NotificationService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 🛒 Portal del Comprador (selección curada compartible).
 *  - Admin: /portal-comprador → selecciones; /portal-comprador/{id} → gestionar items
 *  - Comprador (público): /c/{token} → ve su selección y reacciona
 */
@Controller
@RequiredArgsConstructor
public class PortalCompradorController {

    private final SupabaseService supabase;
    private final com.inmobilia.service.PaisService paises;
    private final NotificationService notif;
    private final BrandingService branding;

    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    // ─── ADMIN: lista ───
    @GetMapping("/portal-comprador")
    public String panel(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> selecciones = supabase.selectFilter(
                "selecciones_comprador", "user_id=eq." + uid + "&order=created_at.desc&limit=100");
        List<Map<String, Object>> leads = supabase.selectFilter(
                "leads", "user_id=eq." + uid + "&order=created_at.desc&limit=200");

        model.addAttribute("selecciones", selecciones);
        model.addAttribute("leads", leads);
        model.addAttribute("baseUrl", BASE_URL);
        return "portal_comprador";
    }

    @PostMapping("/portal-comprador/nueva")
    public String nueva(@RequestParam String titulo,
                        @RequestParam(required = false) String lead_id,
                        @RequestParam(required = false) String mensaje_agente,
                        HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        String token = "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 15);
        Map<String, Object> sel = new LinkedHashMap<>();
        sel.put("user_id", uid);
        sel.put("token", token);
        sel.put("titulo", titulo);
        sel.put("mensaje_agente", mensaje_agente);
        sel.put("activo", true);

        if (lead_id != null && !lead_id.isBlank()) {
            sel.put("lead_id", lead_id);
            List<Map<String, Object>> l = supabase.selectFilter("leads", "id=eq." + lead_id);
            if (!l.isEmpty()) sel.put("comprador_nombre", l.get(0).get("nombre"));
        }
        sel.put("created_at", OffsetDateTime.now().toString());
        Map<String, Object> saved = supabase.insert("selecciones_comprador", sel);
        Object id = saved != null ? saved.get("id") : null;
        return id != null ? "redirect:/portal-comprador/" + id : "redirect:/portal-comprador";
    }

    // ─── ADMIN: detalle ───
    @GetMapping("/portal-comprador/{id}")
    public String detalle(@PathVariable String id, HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("selecciones_comprador", "id=eq." + id);
        if (rows.isEmpty() || !uid.equals(String.valueOf(rows.get(0).get("user_id")))) return "redirect:/portal-comprador";

        List<Map<String, Object>> items = supabase.selectFilter(
                "seleccion_items", "seleccion_id=eq." + id + "&order=orden.asc&limit=100");
        enriquecerConPropiedades(items);

        // Cartera disponible para añadir
        List<Map<String, Object>> cartera = supabase.selectFilter(
                "propiedades", "user_id=eq." + uid + "&order=created_at.desc&limit=300");

        model.addAttribute("sel", rows.get(0));
        model.addAttribute("items", items);
        model.addAttribute("cartera", cartera);
        model.addAttribute("urlPublica", BASE_URL + "/c/" + rows.get(0).get("token"));
        return "portal_comprador_detail";
    }

    @PostMapping("/portal-comprador/{id}/item")
    public String addItem(@PathVariable String id, @RequestParam String propiedad_id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        if (!owns(uid, id)) return "redirect:/portal-comprador";

        List<Map<String, Object>> existentes = supabase.selectFilter("seleccion_items", "seleccion_id=eq." + id);
        boolean yaEsta = existentes.stream().anyMatch(it -> propiedad_id.equals(String.valueOf(it.get("propiedad_id"))));
        if (!yaEsta) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("user_id", uid);
            it.put("seleccion_id", id);
            it.put("propiedad_id", propiedad_id);
            it.put("orden", existentes.size());
            it.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("seleccion_items", it);
        }
        return "redirect:/portal-comprador/" + id;
    }

    /** Auto-sugerir inmuebles según el presupuesto/zona del lead. */
    @PostMapping("/portal-comprador/{id}/sugerir")
    public String sugerir(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("selecciones_comprador", "id=eq." + id);
        if (rows.isEmpty() || !uid.equals(String.valueOf(rows.get(0).get("user_id")))) return "redirect:/portal-comprador";
        Map<String, Object> sel = rows.get(0);

        double presupuesto = 0; String zona = "";
        if (sel.get("lead_id") != null) {
            List<Map<String, Object>> l = supabase.selectFilter("leads", "id=eq." + sel.get("lead_id"));
            if (!l.isEmpty()) {
                presupuesto = nd(l.get(0).get("presupuesto"));
                zona = String.valueOf(l.get(0).getOrDefault("zona", ""));
            }
        }
        final double max = presupuesto > 0 ? presupuesto * 1.05 : Double.MAX_VALUE;
        final String z = zona == null ? "" : zona.toLowerCase().trim();

        List<Map<String, Object>> existentes = supabase.selectFilter("seleccion_items", "seleccion_id=eq." + id);
        Set<String> ya = new HashSet<>();
        for (Map<String, Object> it : existentes) ya.add(String.valueOf(it.get("propiedad_id")));

        List<Map<String, Object>> cartera = supabase.selectFilter("propiedades", "user_id=eq." + uid + "&limit=300");
        int orden = existentes.size(), added = 0;
        for (Map<String, Object> p : cartera) {
            if (added >= 6) break;
            String pid = String.valueOf(p.get("id"));
            if (ya.contains(pid)) continue;
            double precio = nd(p.get("current_value")); if (precio <= 0) precio = nd(p.get("purchase_price"));
            if (precio <= 0 || precio > max) continue;
            if (!z.isBlank() && p.get("city") != null && !String.valueOf(p.get("city")).toLowerCase().contains(z)
                    && !z.contains(String.valueOf(p.get("city")).toLowerCase())) continue;

            Map<String, Object> it = new LinkedHashMap<>();
            it.put("user_id", uid);
            it.put("seleccion_id", id);
            it.put("propiedad_id", pid);
            it.put("orden", orden++);
            it.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("seleccion_items", it);
            added++;
        }
        return "redirect:/portal-comprador/" + id;
    }

    @PostMapping("/portal-comprador/item/{itemId}/eliminar")
    public String delItem(@PathVariable String itemId, @RequestParam String selId, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("seleccion_items", "id=eq." + itemId);
        if (!rows.isEmpty() && uid.equals(String.valueOf(rows.get(0).get("user_id")))) {
            supabase.delete("seleccion_items", itemId, uid);
        }
        return "redirect:/portal-comprador/" + selId;
    }

    // ─── PÚBLICO ───
    @GetMapping("/c/{token}")
    public String publico(@PathVariable String token, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("selecciones_comprador", "token=eq." + token);
        if (rows.isEmpty()) { model.addAttribute("error", "Enlace no válido"); return "portal_comprador_publico"; }
        Map<String, Object> sel = rows.get(0);
        if (Boolean.FALSE.equals(sel.get("activo"))) {
            model.addAttribute("error", "Esta selección ya no está disponible. Contacta con tu agente.");
            return "portal_comprador_publico";
        }
        String uid = String.valueOf(sel.get("user_id"));
        paises.usar(uid);  // moneda y formato del país de la inmobiliaria

        try {
            int vistas = sel.get("vistas") == null ? 0 : (int) Double.parseDouble(String.valueOf(sel.get("vistas")));
            supabase.update("selecciones_comprador", String.valueOf(sel.get("id")), Map.of("vistas", vistas + 1), uid);
        } catch (Exception ignored) {}

        List<Map<String, Object>> items = supabase.selectFilter(
                "seleccion_items", "seleccion_id=eq." + sel.get("id") + "&order=orden.asc&limit=100");
        enriquecerConPropiedades(items);

        model.addAttribute("sel", sel);
        model.addAttribute("items", items);
        model.addAttribute("token", token);
        model.addAttribute("branding", branding.branding(uid));
        return "portal_comprador_publico";
    }

    @PostMapping("/c/{token}/reaccion")
    @ResponseBody
    public Map<String, Object> reaccion(@PathVariable String token, @RequestBody Map<String, String> in) {
        List<Map<String, Object>> rows = supabase.selectFilter("selecciones_comprador", "token=eq." + token);
        if (rows.isEmpty()) return Map.of("ok", false);
        Map<String, Object> sel = rows.get(0);
        String uid = String.valueOf(sel.get("user_id"));
        String itemId = in.get("item_id");
        String reaccion = in.get("reaccion");

        List<Map<String, Object>> it = supabase.selectFilter("seleccion_items", "id=eq." + itemId);
        if (it.isEmpty() || !String.valueOf(sel.get("id")).equals(String.valueOf(it.get(0).get("seleccion_id")))) return Map.of("ok", false);

        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("reaccion", reaccion);
        if (in.get("comentario") != null) upd.put("comentario", in.get("comentario"));
        upd.put("reaccion_at", OffsetDateTime.now().toString());
        supabase.update("seleccion_items", itemId, upd, uid);

        String emoji = "favorito".equals(reaccion) ? "❤️" : ("visita".equals(reaccion) ? "🗓️" : "👎");
        notif.create(uid, emoji + " Reacción del comprador",
                String.valueOf(sel.getOrDefault("comprador_nombre", "Un comprador")) + " marcó " + reaccion +
                        ("visita".equals(reaccion) ? " (¡quiere visitar!)" : ""),
                "/portal-comprador/" + sel.get("id"), "visita".equals(reaccion) ? "alta" : "normal");
        return Map.of("ok", true);
    }

    // ── helpers ──
    /** Enriquece los items con su propiedad en UNA sola query (evita N+1). */
    private void enriquecerConPropiedades(List<Map<String, Object>> items) {
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> it : items) {
            String pid = String.valueOf(it.get("propiedad_id"));
            if (!pid.isBlank() && !"null".equals(pid)) ids.add(pid);
        }
        Map<String, Map<String, Object>> porId = new HashMap<>();
        if (!ids.isEmpty()) {
            try {
                List<Map<String, Object>> props = supabase.selectFilter(
                        "propiedades", "id=in.(" + String.join(",", ids) + ")");
                for (Map<String, Object> p : props) porId.put(String.valueOf(p.get("id")), p);
            } catch (Exception ignored) {}
        }
        for (Map<String, Object> it : items) {
            it.put("prop", porId.getOrDefault(String.valueOf(it.get("propiedad_id")), Map.of()));
        }
    }
    private boolean owns(String uid, String selId) {
        List<Map<String, Object>> rows = supabase.selectFilter("selecciones_comprador", "id=eq." + selId);
        return !rows.isEmpty() && uid.equals(String.valueOf(rows.get(0).get("user_id")));
    }
    private static double nd(Object o) { try { return o == null ? 0 : Double.parseDouble(String.valueOf(o).replace(",", ".")); } catch (Exception e) { return 0; } }
}
