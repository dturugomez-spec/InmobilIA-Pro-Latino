package com.inmobilia.controller;

import com.inmobilia.service.BrandingService;
import com.inmobilia.service.NotificationService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.service.ValoracionService;
import com.inmobilia.service.WorkflowEngine;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 🏷️ Valoración instantánea (imán de captación).
 *  - Panel admin: /valoracion → link compartible + valoraciones recibidas
 *  - Público: /v/{token} → propietario valora su casa y queda captado como lead
 */
@Controller
@RequiredArgsConstructor
public class ValoracionController {

    private final SupabaseService supabase;
    private final ValoracionService valoracion;
    private final NotificationService notif;
    private final WorkflowEngine workflowEngine;
    private final BrandingService branding;

    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    // ─── PANEL ADMIN ───
    @GetMapping("/valoracion")
    public String panel(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        // Asegurar token público
        String token = obtenerOcrearToken(uid);

        List<Map<String, Object>> recibidas = supabase.selectFilter(
                "valoraciones", "user_id=eq." + uid + "&order=created_at.desc&limit=50");
        long conContacto = recibidas.stream().filter(v -> v.get("propietario_email") != null).count();
        long quierenVender = recibidas.stream().filter(v ->
                "ahora".equals(v.get("quiere_vender")) || "3-6m".equals(v.get("quiere_vender"))).count();

        model.addAttribute("token", token);
        model.addAttribute("urlPublica", BASE_URL + "/v/" + token);
        model.addAttribute("recibidas", recibidas);
        model.addAttribute("conContacto", conContacto);
        model.addAttribute("quierenVender", quierenVender);
        return "valoracion_admin";
    }

    private String obtenerOcrearToken(String uid) {
        List<Map<String, Object>> u = supabase.selectFilter("users", "id=eq." + uid);
        if (!u.isEmpty() && u.get(0).get("valoracion_token") != null
                && !String.valueOf(u.get(0).get("valoracion_token")).isBlank()
                && !"null".equals(String.valueOf(u.get(0).get("valoracion_token")))) {
            return String.valueOf(u.get(0).get("valoracion_token"));
        }
        String token = "val" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        supabase.update("users", uid, Map.of("valoracion_token", token), uid);
        return token;
    }

    // ─── PÚBLICO: formulario ───
    @GetMapping("/v/{token}")
    public String publico(@PathVariable String token, Model model) {
        List<Map<String, Object>> u = supabase.selectFilter("users", "valoracion_token=eq." + token);
        if (u.isEmpty()) { model.addAttribute("error", "Enlace no válido"); return "valoracion_publica"; }
        Map<String, Object> b = branding.branding(String.valueOf(u.get(0).get("id")));
        model.addAttribute("token", token);
        model.addAttribute("empresa", b.get("nombre"));
        model.addAttribute("branding", b);
        return "valoracion_publica";
    }

    // ─── PÚBLICO: calcular (AJAX) ───
    @PostMapping("/v/{token}/calcular")
    @ResponseBody
    public Map<String, Object> calcular(@PathVariable String token, @RequestBody Map<String, String> in) {
        List<Map<String, Object>> u = supabase.selectFilter("users", "valoracion_token=eq." + token);
        if (u.isEmpty()) return Map.of("ok", false);
        String uid = String.valueOf(u.get(0).get("id"));

        Map<String, Object> val = valoracion.valorar(uid, in);
        String comentario = valoracion.comentarioIA(val, in, uid);
        if (comentario != null) val.put("comentario", comentario);
        return val;
    }

    // ─── PÚBLICO: capturar propietario como lead ───
    @PostMapping("/v/{token}/contacto")
    @ResponseBody
    public Map<String, Object> contacto(@PathVariable String token,
                                        @RequestBody Map<String, String> in,
                                        HttpServletRequest req) {
        List<Map<String, Object>> u = supabase.selectFilter("users", "valoracion_token=eq." + token);
        if (u.isEmpty()) return Map.of("ok", false);
        String uid = String.valueOf(u.get(0).get("id"));

        // Recalcular para guardar la valoración completa
        Map<String, Object> val = valoracion.valorar(uid, in);

        // 1) Guardar valoración
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("user_id", uid);
        v.put("tipo", in.get("tipo"));
        v.put("municipio", in.get("municipio"));
        v.put("provincia", in.get("provincia"));
        v.put("metros", pd(in.get("metros")));
        v.put("habitaciones", (int) pd(in.get("habitaciones")));
        v.put("estado", in.get("estado"));
        v.put("precio_estimado", val.get("precio_estimado"));
        v.put("precio_min", val.get("precio_min"));
        v.put("precio_max", val.get("precio_max"));
        v.put("eur_m2", val.get("eur_m2"));
        v.put("confianza", val.get("confianza"));
        v.put("metodo", val.get("metodo"));
        v.put("propietario_nombre", in.get("nombre"));
        v.put("propietario_email", in.get("email") != null ? in.get("email").trim().toLowerCase() : null);
        v.put("propietario_telefono", in.get("telefono"));
        v.put("quiere_vender", in.get("quiere_vender"));
        v.put("ip", req.getRemoteAddr());
        v.put("created_at", OffsetDateTime.now().toString());
        Map<String, Object> savedVal = supabase.insert("valoraciones", v);

        // 2) Crear lead PROPIETARIO (el oro de la captación)
        Map<String, Object> lead = new LinkedHashMap<>();
        lead.put("user_id", uid);
        lead.put("nombre", in.get("nombre"));
        lead.put("email", v.get("propietario_email"));
        lead.put("telefono", in.get("telefono"));
        lead.put("interes", "Venta");
        lead.put("origen", "valoracion");
        lead.put("etapa", "Nuevo");
        lead.put("zona", in.get("municipio"));
        lead.put("notas", String.format("VALORACIÓN: %s de %s m² en %s. Estimado %s€. Quiere vender: %s",
                in.getOrDefault("tipo", "?"), in.getOrDefault("metros", "?"),
                in.getOrDefault("municipio", "?"), val.get("precio_estimado"),
                in.getOrDefault("quiere_vender", "?")));
        lead.put("created_at", OffsetDateTime.now().toString());
        Map<String, Object> savedLead = supabase.insert("leads", lead);

        // 3) Notificar + disparar workflow lead_creado (asignación, bienvenida...)
        notif.create(uid, "🏷️ Nuevo lead de valoración",
                in.get("nombre") + " valoró su " + in.getOrDefault("tipo", "inmueble") + " en " + in.getOrDefault("municipio", ""),
                "/valoracion", "alta");
        try { workflowEngine.triggerEvent(uid, "lead_creado", lead); } catch (Exception ignored) {}

        return Map.of("ok", true);
    }

    private static double pd(Object o) { try { return o == null ? 0 : Double.parseDouble(String.valueOf(o).replace(",", ".")); } catch (Exception e) { return 0; } }
}
