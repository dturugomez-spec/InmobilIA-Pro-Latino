package com.inmobilia.controller;

import com.inmobilia.service.PermissionService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recibos de alquiler (vivienda habitual: sin IVA, con IRPF si arrendador es persona física).
 */
@Controller
@RequestMapping("/receipts")
@RequiredArgsConstructor
@Slf4j
public class ReceiptController {

    private final SupabaseService supabase;
    private final PermissionService permissions;

    @GetMapping
    public String list(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canRead(role, "transacciones")) return "redirect:/dashboard";

        String uid = SessionUtils.uid(session);
        var rows = supabase.query("receipts", uid).stream()
                .sorted((a, b) -> String.valueOf(b.get("fecha_emision")).compareTo(String.valueOf(a.get("fecha_emision"))))
                .toList();

        model.addAttribute("page", "Recibos de alquiler");
        model.addAttribute("rows", rows);
        model.addAttribute("canWrite", permissions.canWrite(role, "transacciones"));
        return "receipt_list";
    }

    @GetMapping("/new")
    public String formNew(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canWrite(role, "transacciones")) return "redirect:/receipts";

        String uid = SessionUtils.uid(session);
        // Pre-rellenar con datos del business profile
        var profileRows = supabase.findBy("business_profile", Map.of("org_id", uid));
        Map<String, Object> profile = profileRows.isEmpty() ? Map.of() : profileRows.get(0);

        model.addAttribute("page", "Nuevo recibo de alquiler");
        model.addAttribute("rec", new java.util.HashMap<>());
        model.addAttribute("editing", false);
        model.addAttribute("profile", profile);
        model.addAttribute("inquilinos", supabase.query("inquilinos", uid));
        model.addAttribute("propiedades", supabase.query("propiedades", uid));
        return "receipt_form";
    }

    @GetMapping("/{id}")
    public String view(@PathVariable String id, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.uid(session);
        var rows = supabase.findBy("receipts", Map.of("id", id, "user_id", uid));
        if (rows.isEmpty()) return "redirect:/receipts";

        var profileRows = supabase.findBy("business_profile", Map.of("org_id", uid));
        Map<String, Object> profile = profileRows.isEmpty() ? Map.of() : profileRows.get(0);

        model.addAttribute("page", "Recibo " + rows.get(0).get("full_number"));
        model.addAttribute("rec", rows.get(0));
        model.addAttribute("profile", profile);
        return "receipt_view";
    }

    @PostMapping
    public String save(HttpServletRequest req, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canWrite(role, "transacciones")) return "redirect:/receipts";

        String uid = SessionUtils.uid(session);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);

        for (String f : List.of("serie","fecha_emision","fecha_pago","period_year","period_month",
                "emisor_nombre","emisor_nif","emisor_direccion",
                "inquilino_nombre","inquilino_nif","inquilino_direccion",
                "property_name","property_ref","catastro_ref","concepto",
                "metodo_pago","status","notas")) {
            String v = req.getParameter(f);
            if (v != null && !v.isBlank()) data.put(f, v);
        }

        double importe = parseD(req.getParameter("importe"));
        double irpfPct = parseD(req.getParameter("irpf_pct"));
        if (irpfPct == 0) irpfPct = 19; // default arrendador persona física
        double irpfAmount = round(importe * irpfPct / 100);
        double totalPagado = round(importe - irpfAmount);

        data.put("importe", importe);
        data.put("irpf_pct", irpfPct);
        data.put("irpf_amount", irpfAmount);
        data.put("total_pagado", totalPagado);
        data.put("created_at", OffsetDateTime.now().toString());

        Map<String, Object> saved = supabase.insert("receipts", data);
        if (saved == null || saved.isEmpty()) {
            SessionUtils.flash(session, "ERROR creando recibo: " + supabase.lastError());
            return "redirect:/receipts/new";
        }
        SessionUtils.flash(session, "Recibo " + saved.get("full_number") + " creado");
        return "redirect:/receipts/" + saved.get("id");
    }

    @PostMapping("/generate-monthly")
    public String generateMonthly(HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.uid(session);
        int y = LocalDate.now().getYear();
        int m = LocalDate.now().getMonthValue();

        var profileRows = supabase.findBy("business_profile", Map.of("org_id", uid));
        Map<String, Object> profile = profileRows.isEmpty() ? Map.of() : profileRows.get(0);

        var inquilinos = supabase.query("inquilinos", uid).stream()
                .filter(i -> {
                    String s = String.valueOf(i.getOrDefault("status", ""));
                    return !"Inactivo".equalsIgnoreCase(s) && !"Cerrado".equalsIgnoreCase(s);
                })
                .filter(i -> parseD(i.get("rent_amount")) > 0)
                .toList();

        int created = 0;
        for (var inq : inquilinos) {
            // No duplicar si ya existe recibo del mes para este inquilino
            var existing = supabase.findBy("receipts", Map.of(
                    "user_id", uid,
                    "inquilino_nombre", String.valueOf(inq.get("name")),
                    "period_year", String.valueOf(y),
                    "period_month", String.valueOf(m)
            ));
            if (!existing.isEmpty()) continue;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("user_id", uid);
            data.put("serie", "R");
            data.put("fecha_emision", LocalDate.now().toString());
            data.put("period_year", y);
            data.put("period_month", m);
            data.put("emisor_nombre", profile.getOrDefault("legal_name", ""));
            data.put("emisor_nif", profile.getOrDefault("tax_id", ""));
            data.put("emisor_direccion", profile.getOrDefault("address", ""));
            data.put("inquilino_nombre", String.valueOf(inq.getOrDefault("name", "")));
            data.put("inquilino_nif", String.valueOf(inq.getOrDefault("id_number", "")));
            data.put("property_name", String.valueOf(inq.getOrDefault("property_name", "")));
            data.put("concepto", "Alquiler vivienda · " + monthName(m) + " " + y);
            double importe = parseD(inq.get("rent_amount"));
            double irpfPct = 19;
            data.put("importe", importe);
            data.put("irpf_pct", irpfPct);
            data.put("irpf_amount", round(importe * irpfPct / 100));
            data.put("total_pagado", round(importe * (1 - irpfPct / 100)));
            data.put("status", "Emitido");
            data.put("created_at", OffsetDateTime.now().toString());

            if (supabase.insert("receipts", data) != null) created++;
        }
        SessionUtils.flash(session, "Generados " + created + " recibo(s) del mes " + m + "/" + y);
        return "redirect:/receipts";
    }

    private static double parseD(Object o) { if (o == null) return 0; try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
    private static String monthName(int m) {
        return new String[]{"enero","febrero","marzo","abril","mayo","junio","julio","agosto","septiembre","octubre","noviembre","diciembre"}[m - 1];
    }
}
