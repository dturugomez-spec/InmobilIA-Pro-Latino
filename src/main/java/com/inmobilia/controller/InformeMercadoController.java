package com.inmobilia.controller;

import com.inmobilia.service.BrandingService;
import com.inmobilia.service.InformeMercadoService;
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
 * 📊 Informe de Mercado / CMA.
 *  - /informes: panel (lista + generar desde un inmueble de la cartera)
 *  - /informe/{token}: informe público de marca, compartible con el propietario
 */
@Controller
@RequiredArgsConstructor
public class InformeMercadoController {

    private final SupabaseService supabase;
    private final InformeMercadoService informes;
    private final BrandingService branding;

    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    @GetMapping("/informes")
    public String panel(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> lista = supabase.selectFilter(
                "informes_mercado", "user_id=eq." + uid + "&order=created_at.desc&limit=100");
        List<Map<String, Object>> props = supabase.selectFilter(
                "propiedades", "user_id=eq." + uid + "&order=created_at.desc&limit=200");

        model.addAttribute("informes", lista);
        model.addAttribute("propiedades", props);
        model.addAttribute("baseUrl", BASE_URL);
        return "informes";
    }

    @PostMapping("/informes/generar")
    public String generar(@RequestParam String propiedad_id,
                          @RequestParam(required = false) String propietario_nombre,
                          HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> rows = supabase.selectFilter("propiedades", "id=eq." + propiedad_id);
        if (rows.isEmpty() || !uid.equals(String.valueOf(rows.get(0).get("user_id")))) return "redirect:/informes";
        Map<String, Object> prop = rows.get(0);

        Map<String, Object> rec = informes.generarYGuardar(uid, prop, propietario_nombre);
        return "redirect:/informe/" + rec.get("token") + "?preview=1";
    }

    @PostMapping("/informes/{id}/eliminar")
    public String eliminar(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("informes_mercado", "id=eq." + id);
        if (!rows.isEmpty() && uid.equals(String.valueOf(rows.get(0).get("user_id")))) {
            supabase.delete("informes_mercado", id, uid);
        }
        return "redirect:/informes";
    }

    // ─── PÚBLICO ───
    @GetMapping("/informe/{token}")
    public String publico(@PathVariable String token,
                          @RequestParam(required = false) String preview,
                          Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("informes_mercado", "token=eq." + token);
        if (rows.isEmpty()) { model.addAttribute("error", "Informe no encontrado"); return "informe_publico"; }
        Map<String, Object> r = rows.get(0);
        String uid = String.valueOf(r.get("user_id"));

        // Contar vistas (salvo preview del propio agente)
        if (preview == null) {
            try {
                int vistas = r.get("vistas") == null ? 0 : (int) Double.parseDouble(String.valueOf(r.get("vistas")));
                supabase.update("informes_mercado", String.valueOf(r.get("id")), Map.of("vistas", vistas + 1), uid);
            } catch (Exception ignored) {}
        }

        model.addAttribute("r", r);
        model.addAttribute("comparables", informes.parseComparables(r.get("comparables_json")));
        model.addAttribute("agente", branding.branding(uid));
        return "informe_publico";
    }
}
