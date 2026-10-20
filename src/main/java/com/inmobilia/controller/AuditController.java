package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/auditoria")
public class AuditController {

    private final SupabaseService supabase;

    @GetMapping
    public String log(HttpSession session, Model model,
                       @RequestParam(required = false) String nivel,
                       @RequestParam(required = false) String tabla,
                       @RequestParam(required = false) String actor,
                       @RequestParam(required = false) String desde) {
        String uid = SessionUtils.uid(session);
        String role = SessionUtils.role(session);
        if (uid == null) return "redirect:/login";

        // Solo admin/director ven la auditoría completa
        if (!"admin".equals(role) && !"director".equals(role)) {
            return "redirect:/dashboard";
        }

        // Filtros validados: solo caracteres esperados (nada de inyectar parámetros PostgREST)
        StringBuilder q = new StringBuilder("user_id=eq.").append(uid).append("&order=created_at.desc&limit=500");
        if (nivel != null && nivel.matches("[a-z]{1,20}")) q.append("&nivel=eq.").append(nivel);
        if (tabla != null && tabla.matches("[a-z_]{1,40}")) q.append("&tabla=eq.").append(tabla);
        if (actor != null && actor.matches("[0-9a-fA-F-]{36}")) q.append("&actor_id=eq.").append(actor);
        if (desde != null && desde.matches("\\d{4}-\\d{2}-\\d{2}")) q.append("&created_at=gte.").append(desde);

        List<Map<String, Object>> logs = supabase.selectFilter("audit_log", q.toString());

        // Stats
        long criticos = logs.stream().filter(l -> "critical".equals(l.get("nivel"))).count();
        long security = logs.stream().filter(l -> "security".equals(l.get("nivel"))).count();
        long warnings = logs.stream().filter(l -> "warning".equals(l.get("nivel"))).count();

        // Tablas únicas
        Set<String> tablas = new TreeSet<>();
        for (Map<String, Object> l : logs) {
            if (l.get("tabla") != null) tablas.add(String.valueOf(l.get("tabla")));
        }

        model.addAttribute("logs", logs);
        model.addAttribute("criticos", criticos);
        model.addAttribute("security", security);
        model.addAttribute("warnings", warnings);
        model.addAttribute("tablas", tablas);
        return "audit_log";
    }
}
