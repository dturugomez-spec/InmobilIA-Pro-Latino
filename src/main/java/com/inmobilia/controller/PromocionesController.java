package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/promociones")
public class PromocionesController {

    private final SupabaseService supabase;
    private final com.inmobilia.service.PaisService paises;

    @GetMapping
    public String list(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> promos = supabase.selectFilter("promociones", "user_id=eq." + uid + "&order=created_at.desc");
        for (Map<String, Object> p : promos) {
            List<Map<String, Object>> uds = supabase.selectFilter("promocion_unidades", "promocion_id=eq." + p.get("id"));
            p.put("total_unidades", uds.size());
            long vend = uds.stream().filter(u -> "vendida".equals(u.get("estado"))).count();
            p.put("vendidas", vend);
            p.put("porcentaje_vendido", uds.isEmpty() ? 0 : (vend * 100.0 / uds.size()));
        }
        model.addAttribute("promociones", promos);
        return "promociones_list";
    }

    @GetMapping("/nueva")
    public String form(HttpSession session, Model model) {
        if (SessionUtils.uid(session) == null) return "redirect:/login";
        model.addAttribute("promo", new LinkedHashMap<>());
        return "promociones_form";
    }

    @PostMapping("/guardar")
    public String save(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        for (String k : List.of("nombre", "promotora", "arquitecto", "constructora", "direccion",
                "municipio", "provincia", "codigo_postal", "tipo", "fase",
                "memoria_calidades", "render_principal_url", "tour_360_url", "video_url",
                "seo_descripcion", "landing_slug")) {
            if (form.get(k) != null) data.put(k, form.get(k));
        }
        // Campos numéricos opcionales: vacíos o no numéricos no se guardan (antes daban error)
        putSiHay(data, "num_unidades", entero(form.get("num_unidades")));
        putSiHay(data, "precio_desde", decimal(form.get("precio_desde")));
        putSiHay(data, "precio_hasta", decimal(form.get("precio_hasta")));
        if (form.get("fecha_entrega") != null && !form.get("fecha_entrega").isBlank()) data.put("fecha_entrega", form.get("fecha_entrega"));
        data.put("publicada", "on".equals(form.get("publicada")));
        String slug = form.get("landing_slug");
        data.put("slug", slug == null || slug.isBlank() ? "promo-" + System.currentTimeMillis() : slug.trim());
        data.put("created_at", OffsetDateTime.now().toString());
        data.put("updated_at", OffsetDateTime.now().toString());
        supabase.insert("promociones", data);
        SessionUtils.flash(session, "✅ Promoción creada");
        return "redirect:/promociones";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable String id, HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("promociones", "id=eq." + id + "&user_id=eq." + uid);
        if (rows.isEmpty()) return "redirect:/promociones";
        Map<String, Object> p = rows.get(0);
        List<Map<String, Object>> unidades = supabase.selectFilter("promocion_unidades", "promocion_id=eq." + id + "&order=bloque.asc,planta.asc,letra.asc");
        model.addAttribute("promo", p);
        model.addAttribute("unidades", unidades);
        return "promociones_detail";
    }

    @PostMapping("/{id}/unidad/crear")
    public String crearUnidad(@PathVariable String id, @RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        // La promoción tiene que ser de esta inmobiliaria (las unidades no llevan user_id propio)
        if (supabase.selectOwned("promociones", id, uid).isEmpty()) return "redirect:/promociones";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("promocion_id", id);
        for (String k : List.of("bloque", "letra", "portal", "tipologia", "orientacion")) {
            if (form.get(k) != null) data.put(k, form.get(k));
        }
        putSiHay(data, "planta", entero(form.get("planta")));
        putSiHay(data, "num_habitaciones", entero(form.get("num_habitaciones")));
        putSiHay(data, "num_banos", entero(form.get("num_banos")));
        putSiHay(data, "superficie_util", decimal(form.get("superficie_util")));
        putSiHay(data, "precio", decimal(form.get("precio")));
        data.put("plaza_garaje", "on".equals(form.get("plaza_garaje")));
        data.put("trastero", "on".equals(form.get("trastero")));
        data.put("estado", "disponible");
        data.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("promocion_unidades", data);
        return "redirect:/promociones/" + id;
    }

    /** Landing pública. */
    @GetMapping("/p/{slug}")
    public String landing(@PathVariable String slug, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("promociones", "landing_slug=eq." + slug + "&publicada=eq.true");
        if (rows.isEmpty()) { model.addAttribute("error", true); return "promociones_public"; }
        Map<String, Object> p = rows.get(0);
        paises.usar(String.valueOf(p.get("user_id")));  // moneda y formato del país de la inmobiliaria
        int v = ((Number) p.getOrDefault("visitas_count", 0)).intValue() + 1;
        supabase.update("promociones", String.valueOf(p.get("id")), Map.of("visitas_count", v));

        List<Map<String, Object>> unidades = supabase.selectFilter("promocion_unidades",
                "promocion_id=eq." + p.get("id") + "&estado=eq.disponible");

        model.addAttribute("promo", p);
        model.addAttribute("unidades", unidades);
        return "promociones_public";
    }

    /** Número entero de un campo opcional: vacío o no numérico → null (no se guarda). */
    static Integer entero(String s) {
        try { return s == null || s.isBlank() ? null : Integer.valueOf(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    /** Decimal de un campo opcional (admite coma): vacío o no numérico → null. */
    static Double decimal(String s) {
        try { return s == null || s.isBlank() ? null : Double.valueOf(s.trim().replace(",", ".")); }
        catch (NumberFormatException e) { return null; }
    }

    private static void putSiHay(Map<String, Object> data, String campo, Object valor) {
        if (valor != null) data.put(campo, valor);
    }
}
