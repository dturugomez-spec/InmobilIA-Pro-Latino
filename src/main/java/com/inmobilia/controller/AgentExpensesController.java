package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/gastos-agente")
public class AgentExpensesController {

    private final SupabaseService supabase;

    @GetMapping
    public String list(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        String realUid = SessionUtils.realUid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> gastos = supabase.selectFilter(
                "gastos_agente", "user_id=eq." + uid + "&order=fecha.desc&limit=100");

        String mesActual = LocalDate.now().toString().substring(0, 7);
        double totalMes = gastos.stream()
                .filter(g -> String.valueOf(g.get("fecha")).startsWith(mesActual))
                .mapToDouble(g -> nd(g.get("importe"))).sum();
        long pendientes = gastos.stream().filter(g -> "pendiente".equals(g.get("estado"))).count();
        double totalKm = gastos.stream()
                .filter(g -> "kilometraje".equals(g.get("tipo")) && String.valueOf(g.get("fecha")).startsWith(mesActual))
                .mapToDouble(g -> nd(g.get("km"))).sum();

        model.addAttribute("gastos", gastos);
        model.addAttribute("totalMes", totalMes);
        model.addAttribute("pendientes", pendientes);
        model.addAttribute("totalKm", totalKm);
        return "agent_expenses";
    }

    @PostMapping("/crear")
    public String crear(@RequestParam Map<String, String> form, HttpSession session) {
        String uid = SessionUtils.uid(session);
        String realUid = SessionUtils.realUid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("agente_id", realUid);
        data.put("fecha", form.getOrDefault("fecha", LocalDate.now().toString()));
        data.put("tipo", form.get("tipo"));
        data.put("concepto", form.get("concepto"));

        if ("kilometraje".equals(form.get("tipo"))) {
            double km = parseD(form.get("km"));
            double precio = parseD(form.getOrDefault("importe_por_km", "0.26"));
            data.put("km", km);
            data.put("importe_por_km", precio);
            data.put("importe", km * precio);
            data.put("origen", form.get("origen"));
            data.put("destino", form.get("destino"));
        } else {
            data.put("importe", parseD(form.get("importe")));
        }

        data.put("notas", form.get("notas"));
        data.put("estado", "pendiente");
        data.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("gastos_agente", data);
        return "redirect:/gastos-agente";
    }

    @PostMapping("/{id}/aprobar")
    public String aprobar(@PathVariable String id, HttpSession session) {
        String realUid = SessionUtils.realUid(session);
        if (realUid == null) return "redirect:/login";
        supabase.update("gastos_agente", id, Map.of(
                "estado", "aprobado",
                "aprobado_por", realUid,
                "aprobado_at", OffsetDateTime.now().toString()
        ));
        return "redirect:/gastos-agente";
    }

    private static double parseD(String s) { try { return Double.parseDouble(s); } catch(Exception e) { return 0; } }
    private static double nd(Object o) { try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
}
