package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
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
 * Gestión de comisiones de comerciales.
 */
@Controller
@RequestMapping("/commissions")
@RequiredArgsConstructor
public class CommissionController {

    private final SupabaseService supabase;

    @GetMapping
    public String list(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String orgId = SessionUtils.uid(session);
        var rows = supabase.findBy("commissions", Map.of("org_id", orgId)).stream()
                .sorted((a, b) -> String.valueOf(b.get("operation_date")).compareTo(String.valueOf(a.get("operation_date"))))
                .toList();

        // Resumen por comercial
        Map<String, double[]> byAgent = new LinkedHashMap<>();
        for (var r : rows) {
            String agent = String.valueOf(r.getOrDefault("agent_name", "Sin asignar"));
            byAgent.computeIfAbsent(agent, k -> new double[]{0,0,0});
            double[] arr = byAgent.get(agent);
            arr[0]++; // operaciones
            arr[1] += parseD(r.get("commission_amount"));
            if ("Pagada".equalsIgnoreCase(String.valueOf(r.get("status")))) arr[2] += parseD(r.get("commission_amount"));
        }

        List<Map<String, Object>> agents = new ArrayList<>();
        byAgent.forEach((name, arr) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("ops", (int) arr[0]);
            m.put("total", arr[1]);
            m.put("paid", arr[2]);
            m.put("pending", arr[1] - arr[2]);
            agents.add(m);
        });
        agents.sort((a, b) -> Double.compare(parseD(b.get("total")), parseD(a.get("total"))));

        model.addAttribute("page", "Comisiones");
        model.addAttribute("rows", rows);
        model.addAttribute("agents", agents);
        return "commissions";
    }

    @GetMapping("/new")
    public String formNew(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String orgId = SessionUtils.uid(session);
        model.addAttribute("page", "Nueva comisión");
        model.addAttribute("c", new HashMap<>());
        model.addAttribute("members", supabase.findBy("users", Map.of("organization_id", orgId)));
        model.addAttribute("propiedades", supabase.query("propiedades", orgId));
        return "commission_form";
    }

    @PostMapping
    public String save(HttpServletRequest req, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String orgId = SessionUtils.uid(session);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("org_id", orgId);
        for (String f : List.of("agent_user_id","agent_name","operation_type","property_name",
                "client_name","operation_date","status","paid_date","notes")) {
            String v = req.getParameter(f);
            if (v != null && !v.isBlank()) data.put(f, v);
        }
        data.put("operation_value", parseD(req.getParameter("operation_value")));
        data.put("commission_pct", parseD(req.getParameter("commission_pct")));
        data.put("created_at", OffsetDateTime.now().toString());

        Map<String, Object> saved = supabase.insert("commissions", data);
        SessionUtils.flash(session, saved != null ? "Comisión registrada" : "ERROR registrando comisión");
        return "redirect:/commissions";
    }

    @PostMapping("/{id}/status")
    public String changeStatus(@PathVariable String id, @RequestParam String status, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String orgId = SessionUtils.uid(session);
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("status", status);
        if ("Pagada".equalsIgnoreCase(status)) {
            upd.put("paid_date", java.time.LocalDate.now().toString());
        }
        supabase.update("commissions", id, upd, orgId);
        SessionUtils.flash(session, "Comisión marcada como " + status);
        return "redirect:/commissions";
    }

    private static double parseD(Object o) { if (o == null) return 0; try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
