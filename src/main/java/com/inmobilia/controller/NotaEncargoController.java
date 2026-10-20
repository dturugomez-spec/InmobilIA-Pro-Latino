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

/**
 * 📄 Nota de Encargo de venta (cierra la captación).
 * Genera el documento del encargo/exclusiva con los datos reales, listo para imprimir/firmar.
 * Plantilla estándar editable — no asesoramiento jurídico.
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/nota-encargo")
public class NotaEncargoController {

    private final SupabaseService supabase;

    @GetMapping
    public String panel(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        model.addAttribute("notas", supabase.selectFilter(
                "notas_encargo", "user_id=eq." + uid + "&order=created_at.desc&limit=100"));
        model.addAttribute("propiedades", supabase.selectFilter(
                "propiedades", "user_id=eq." + uid + "&order=created_at.desc&limit=200"));
        return "nota_encargo";
    }

    @PostMapping("/crear")
    public String crear(@RequestParam String propiedad_id,
                        @RequestParam(required = false) String propietario_nombre,
                        @RequestParam(required = false) String propietario_dni,
                        @RequestParam(required = false) String propietario_email,
                        @RequestParam(required = false) String propietario_telefono,
                        @RequestParam(required = false) String propietario_direccion,
                        @RequestParam(required = false, defaultValue = "3") String comision_pct,
                        @RequestParam(required = false, defaultValue = "6") String duracion_meses,
                        @RequestParam(required = false, defaultValue = "true") boolean exclusiva,
                        @RequestParam(required = false) String observaciones,
                        HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> pr = supabase.selectFilter("propiedades", "id=eq." + propiedad_id);
        if (pr.isEmpty() || !uid.equals(String.valueOf(pr.get(0).get("user_id")))) return "redirect:/nota-encargo";
        Map<String, Object> p = pr.get(0);

        Object precio = p.get("current_value") != null ? p.get("current_value") : p.get("purchase_price");
        String inmueble = String.valueOf(p.getOrDefault("name", p.getOrDefault("address", "Inmueble"))) +
                ", " + p.getOrDefault("city", "") +
                (p.get("area_m2") != null ? " · " + p.get("area_m2") + " m²" : "") +
                (p.get("reference") != null ? " · Ref. " + p.get("reference") : "");

        Map<String, Object> n = new LinkedHashMap<>();
        n.put("user_id", uid);
        n.put("propiedad_id", propiedad_id);
        n.put("propietario_nombre", propietario_nombre);
        n.put("propietario_dni", propietario_dni);
        n.put("propietario_email", propietario_email);
        n.put("propietario_telefono", propietario_telefono);
        n.put("propietario_direccion", propietario_direccion);
        n.put("inmueble_texto", inmueble);
        n.put("precio", precio);
        n.put("comision_pct", pd(comision_pct, 3));
        n.put("duracion_meses", (int) pd(duracion_meses, 6));
        n.put("exclusiva", exclusiva);
        n.put("observaciones", observaciones);
        n.put("estado", "borrador");
        n.put("created_at", OffsetDateTime.now().toString());
        Map<String, Object> saved = supabase.insert("notas_encargo", n);
        Object id = saved != null ? saved.get("id") : null;
        return id != null ? "redirect:/nota-encargo/" + id : "redirect:/nota-encargo";
    }

    @GetMapping("/{id}")
    public String documento(@PathVariable String id, HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("notas_encargo", "id=eq." + id);
        if (rows.isEmpty() || !uid.equals(String.valueOf(rows.get(0).get("user_id")))) return "redirect:/nota-encargo";

        // Datos legales de la agencia (business_profile)
        List<Map<String, Object>> bp = supabase.selectFilter("business_profile", "org_id=eq." + uid);
        model.addAttribute("n", rows.get(0));
        model.addAttribute("agencia", bp.isEmpty() ? Map.of() : bp.get(0));
        return "nota_encargo_doc";
    }

    @PostMapping("/{id}/eliminar")
    public String eliminar(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> rows = supabase.selectFilter("notas_encargo", "id=eq." + id);
        if (!rows.isEmpty() && uid.equals(String.valueOf(rows.get(0).get("user_id")))) {
            supabase.delete("notas_encargo", id, uid);
        }
        return "redirect:/nota-encargo";
    }

    private static double pd(String s, double def) {
        try { return Double.parseDouble(s.replace(",", ".")); } catch (Exception e) { return def; }
    }
}
