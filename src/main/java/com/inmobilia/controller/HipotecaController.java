package com.inmobilia.controller;

import com.inmobilia.util.PaisActual;

import com.inmobilia.service.BrandingService;
import com.inmobilia.service.HipotecaService;
import com.inmobilia.service.NotificationService;
import com.inmobilia.service.SupabaseService;
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
 * 🏦 Simulador de hipoteca (imán de captación de compradores).
 *  - Admin: /simulador → enlace compartible + leads compradores captados
 *  - Público: /h/{token} → el comprador calcula su cuota/capacidad y queda captado
 */
@Controller
@RequiredArgsConstructor
public class HipotecaController {

    private final SupabaseService supabase;
    private final com.inmobilia.service.PaisService paises;
    private final HipotecaService hipoteca;
    private final NotificationService notif;
    private final WorkflowEngine workflowEngine;
    private final BrandingService branding;

    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    // ─── ADMIN ───
    @GetMapping("/simulador")
    public String panel(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        String token = obtenerOcrearToken(uid);
        List<Map<String, Object>> recibidas = supabase.selectFilter(
                "simulaciones_hipoteca", "user_id=eq." + uid + "&order=created_at.desc&limit=50");
        long conContacto = recibidas.stream().filter(v -> v.get("comprador_email") != null).count();
        long viables = recibidas.stream().filter(v -> Boolean.TRUE.equals(v.get("viable"))).count();

        model.addAttribute("token", token);
        model.addAttribute("urlPublica", BASE_URL + "/h/" + token);
        model.addAttribute("recibidas", recibidas);
        model.addAttribute("conContacto", conContacto);
        model.addAttribute("viables", viables);
        return "simulador_admin";
    }

    private String obtenerOcrearToken(String uid) {
        List<Map<String, Object>> u = supabase.selectFilter("users", "id=eq." + uid);
        if (!u.isEmpty() && u.get(0).get("simulador_token") != null
                && !String.valueOf(u.get(0).get("simulador_token")).isBlank()
                && !"null".equals(String.valueOf(u.get(0).get("simulador_token")))) {
            return String.valueOf(u.get(0).get("simulador_token"));
        }
        String token = "sim" + UUID.randomUUID().toString().replace("-", "").substring(0, 13);
        supabase.update("users", uid, Map.of("simulador_token", token), uid);
        return token;
    }

    // ─── PÚBLICO: formulario ───
    @GetMapping("/h/{token}")
    public String publico(@PathVariable String token, Model model) {
        List<Map<String, Object>> u = supabase.selectFilter("users", "simulador_token=eq." + token);
        if (u.isEmpty()) { model.addAttribute("error", "Enlace no válido"); return "simulador_publico"; }
        paises.usar(String.valueOf(u.get(0).get("id")));  // moneda y formato del país de la inmobiliaria
        model.addAttribute("token", token);
        model.addAttribute("empresa", branding.branding(String.valueOf(u.get(0).get("id"))).get("nombre"));
        return "simulador_publico";
    }

    // ─── PÚBLICO: calcular (AJAX) ───
    @PostMapping("/h/{token}/calcular")
    @ResponseBody
    public Map<String, Object> calcular(@PathVariable String token, @RequestBody Map<String, String> in) {
        List<Map<String, Object>> u = supabase.selectFilter("users", "simulador_token=eq." + token);
        if (u.isEmpty()) return Map.of("ok", false);
        return hipoteca.calcular(in);
    }

    // ─── PÚBLICO: capturar comprador como lead ───
    @PostMapping("/h/{token}/contacto")
    @ResponseBody
    public Map<String, Object> contacto(@PathVariable String token,
                                        @RequestBody Map<String, String> in,
                                        HttpServletRequest req) {
        List<Map<String, Object>> u = supabase.selectFilter("users", "simulador_token=eq." + token);
        if (u.isEmpty()) return Map.of("ok", false);
        String uid = String.valueOf(u.get(0).get("id"));

        Map<String, Object> calc = hipoteca.calcular(in);

        // 1) Guardar simulación
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("user_id", uid);
        s.put("precio_objetivo", pd(in.get("precio_objetivo")));
        s.put("ahorro", pd(in.get("ahorro")));
        s.put("ingresos_mensuales", pd(in.get("ingresos_mensuales")));
        s.put("plazo_anos", (int) pd(in.get("plazo_anos")));
        s.put("tipo_interes", calc.get("tipo_interes"));
        s.put("cuota_estimada", calc.get("cuota_estimada"));
        s.put("capital_prestamo", calc.get("capital_prestamo"));
        s.put("capacidad_max", calc.get("capacidad_max"));
        s.put("esfuerzo_pct", calc.get("esfuerzo_pct"));
        s.put("viable", calc.get("viable"));
        s.put("comprador_nombre", in.get("nombre"));
        s.put("comprador_email", in.get("email") != null ? in.get("email").trim().toLowerCase() : null);
        s.put("comprador_telefono", in.get("telefono"));
        s.put("zona_interes", in.get("zona_interes"));
        s.put("ip", req.getRemoteAddr());
        s.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("simulaciones_hipoteca", s);

        // 2) Crear lead COMPRADOR
        Map<String, Object> lead = new LinkedHashMap<>();
        lead.put("user_id", uid);
        lead.put("nombre", in.get("nombre"));
        lead.put("email", s.get("comprador_email"));
        lead.put("telefono", in.get("telefono"));
        lead.put("interes", "Compra");
        lead.put("origen", "simulador");
        lead.put("etapa", "Nuevo");
        lead.put("zona", in.get("zona_interes"));
        lead.put("presupuesto", calc.get("capacidad_max"));
        lead.put("notas", String.format(("SIMULADOR HIPOTECA: capacidad ~%s" + PaisActual.moneda() + ", cuota ~%s" + PaisActual.moneda() + "/mes (esfuerzo %s%%). Busca en: %s"),
                calc.get("capacidad_max"), calc.get("cuota_estimada"), calc.get("esfuerzo_pct"),
                in.getOrDefault("zona_interes", "?")));
        lead.put("created_at", OffsetDateTime.now().toString());
        Map<String, Object> savedLead = supabase.insert("leads", lead);

        // 2b) Preferencia de búsqueda con el presupuesto → alimenta el motor de matching
        if (savedLead != null && savedLead.get("id") != null) {
            try {
                Map<String, Object> pref = new LinkedHashMap<>();
                pref.put("user_id", uid);
                pref.put("lead_id", savedLead.get("id"));
                pref.put("operacion", "venta");
                pref.put("presupuesto_max", calc.get("capacidad_max"));
                pref.put("created_at", OffsetDateTime.now().toString());
                supabase.insert("preferencias_busqueda", pref);
            } catch (Exception ignored) {}
        }

        // 3) Notificar + workflow
        notif.create(uid, "🏦 Nuevo comprador (simulador)",
                in.get("nombre") + " puede comprar hasta ~" + calc.get("capacidad_max") + ("" + PaisActual.moneda() + " en ") + in.getOrDefault("zona_interes", ""),
                "/simulador", "alta");
        try { workflowEngine.triggerEvent(uid, "lead_creado", lead); } catch (Exception ignored) {}

        return Map.of("ok", true);
    }

    private static double pd(Object o) { try { return o == null ? 0 : Double.parseDouble(String.valueOf(o).replace(",", ".")); } catch (Exception e) { return 0; } }
}
