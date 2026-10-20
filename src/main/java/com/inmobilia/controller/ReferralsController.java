package com.inmobilia.controller;

import com.inmobilia.util.PaisActual;

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
@RequestMapping("/referidos")
public class ReferralsController {

    private final SupabaseService supabase;
    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    @GetMapping
    public String dashboard(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        // Config
        List<Map<String, Object>> cfgs = supabase.selectFilter("referidos_config", "user_id=eq." + uid);
        Map<String, Object> cfg;
        if (cfgs.isEmpty()) {
            Map<String, Object> nueva = new LinkedHashMap<>();
            nueva.put("user_id", uid);
            nueva.put("premio_referente", ("200 " + PaisActual.moneda() + " por cada venta cerrada"));
            nueva.put("premio_referido", "0,5% de descuento en comisión");
            nueva.put("created_at", OffsetDateTime.now().toString());
            cfg = supabase.insert("referidos_config", nueva);
        } else {
            cfg = cfgs.get(0);
        }

        List<Map<String, Object>> refs = supabase.selectFilter("referidos", "user_id=eq." + uid + "&order=fecha_referencia.desc");

        long cerrados = refs.stream().filter(r -> "cerrado_ok".equals(r.get("estado"))).count();
        long activos = refs.stream().filter(r -> List.of("pendiente", "contactado", "calificado").contains(r.get("estado"))).count();
        double valorTotal = refs.stream().filter(r -> "cerrado_ok".equals(r.get("estado")))
                .mapToDouble(r -> nd(r.get("valor_operacion"))).sum();
        double premiosPendientes = refs.stream().filter(r -> "cerrado_ok".equals(r.get("estado")) && !Boolean.TRUE.equals(r.get("premio_pagado_at"))).count()
                * nd(cfg.get("premio_valor"));

        model.addAttribute("cfg", cfg);
        model.addAttribute("referidos", refs);
        model.addAttribute("cerrados", cerrados);
        model.addAttribute("activos", activos);
        model.addAttribute("valorTotal", valorTotal);
        model.addAttribute("premiosPendientes", premiosPendientes);
        model.addAttribute("baseUrl", BASE_URL);
        return "referrals_dashboard";
    }

    @PostMapping("/config")
    public String saveConfig(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activo", "on".equals(form.get("activo")));
        data.put("premio_referente", form.get("premio_referente"));
        data.put("premio_referido", form.get("premio_referido"));
        data.put("premio_valor", com.inmobilia.util.Numeros.decimal(form.get("premio_valor")));
        data.put("requiere_cierre", "on".equals(form.getOrDefault("requiere_cierre", "on")));
        data.put("mensaje_landing", form.get("mensaje_landing"));
        List<Map<String, Object>> rows = supabase.selectFilter("referidos_config", "user_id=eq." + uid);
        if (!rows.isEmpty()) supabase.update("referidos_config", String.valueOf(rows.get(0).get("id")), data);
        return "redirect:/referidos";
    }

    @PostMapping("/generar")
    public String generarCodigo(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        String codigo = generarCodigoCorto(form.get("referente_nombre"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("referente_nombre", form.get("referente_nombre"));
        data.put("referente_email", form.get("referente_email"));
        data.put("referente_telefono", form.get("referente_telefono"));
        data.put("codigo", codigo);
        data.put("estado", "pendiente");
        data.put("fecha_referencia", OffsetDateTime.now().toString());
        supabase.insert("referidos", data);

        SessionUtils.flash(session, "✅ Código generado: " + codigo);
        return "redirect:/referidos";
    }

    @PostMapping("/{id}/cerrar")
    public String cerrarOk(@PathVariable String id, @RequestParam(required = false) Double valor, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("estado", "cerrado_ok");
        upd.put("fecha_cierre", OffsetDateTime.now().toString());
        if (valor != null) upd.put("valor_operacion", valor);
        supabase.update("referidos", id, upd, uid);
        return "redirect:/referidos";
    }

    @PostMapping("/{id}/pagar-premio")
    public String pagarPremio(@PathVariable String id, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        supabase.update("referidos", id, Map.of(
                "premio_concedido", true,
                "premio_pagado_at", OffsetDateTime.now().toString()
        ), uid);
        return "redirect:/referidos";
    }

    /** Landing pública con código. */
    @GetMapping("/codigo/{codigo}")
    public String landing(@PathVariable String codigo, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("referidos", "codigo=eq." + codigo);
        if (rows.isEmpty()) {
            model.addAttribute("error", "Código no válido");
            return "referrals_landing";
        }
        Map<String, Object> ref = rows.get(0);
        List<Map<String, Object>> cfgs = supabase.selectFilter("referidos_config", "user_id=eq." + ref.get("user_id"));
        if (!cfgs.isEmpty()) model.addAttribute("cfg", cfgs.get(0));
        model.addAttribute("ref", ref);
        return "referrals_landing";
    }

    @PostMapping("/codigo/{codigo}/usar")
    public String usar(@PathVariable String codigo, @RequestParam Map<String, String> form, Model model) {
        List<Map<String, Object>> rows = supabase.selectFilter("referidos", "codigo=eq." + codigo);
        if (rows.isEmpty()) return "redirect:/";
        Map<String, Object> ref = rows.get(0);

        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("referido_nombre", form.get("nombre"));
        upd.put("referido_email", form.get("email"));
        upd.put("referido_telefono", form.get("telefono"));
        upd.put("estado", "contactado");
        supabase.update("referidos", String.valueOf(ref.get("id")), upd);

        // Crear lead
        Map<String, Object> lead = new LinkedHashMap<>();
        lead.put("user_id", ref.get("user_id"));
        lead.put("nombre", form.get("nombre"));
        lead.put("email", form.get("email"));
        lead.put("telefono", form.get("telefono"));
        lead.put("origen", "referido");
        lead.put("notas", "Referido por: " + ref.get("referente_nombre") + " (código " + codigo + ")");
        lead.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("leads", lead);

        model.addAttribute("registrado", true);
        model.addAttribute("ref", ref);
        return "referrals_landing";
    }

    private String generarCodigoCorto(String nombre) {
        String base = nombre != null ? nombre.substring(0, Math.min(4, nombre.length())).toUpperCase() : "REF";
        return base + new Random().nextInt(9000 + 1000);
    }

    private static double nd(Object o) { try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
