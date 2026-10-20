package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Guardar/cargar configuración personal del dashboard: orden y widgets visibles.
 * Se guarda en users.dashboard_config (JSONB).
 */
@Controller
@RequiredArgsConstructor
public class DashboardConfigController {

    private final SupabaseService supabase;

    @GetMapping("/dashboard/config")
    public String page(HttpSession session, org.springframework.ui.Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Personalizar dashboard");
        return "dashboard_config";
    }

    @GetMapping("/api/dashboard/config")
    @ResponseBody
    public Map<String, Object> get(HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return Map.of("ok", false);
        String uid = SessionUtils.realUid(session);
        var rows = supabase.findBy("users", Map.of("id", uid));
        if (rows.isEmpty()) return Map.of("ok", true, "widgets", defaultWidgets());
        Object cfg = rows.get(0).get("dashboard_config");
        if (cfg == null || "null".equals(String.valueOf(cfg))) {
            return Map.of("ok", true, "widgets", defaultWidgets());
        }
        return Map.of("ok", true, "widgets", cfg);
    }

    @PostMapping("/api/dashboard/config")
    @ResponseBody
    public Map<String, Object> save(@RequestBody Map<String, Object> body, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return Map.of("ok", false);
        String uid = SessionUtils.realUid(session);
        supabase.update("users", uid,
                Map.of("dashboard_config", body.get("widgets")), uid);
        return Map.of("ok", true);
    }

    private static List<Map<String, Object>> defaultWidgets() {
        return List.of(
            widget("kpis_main", "KPIs principales", true),
            widget("cashflow_chart", "Gráfica cashflow", true),
            widget("alerts", "Alertas", true),
            widget("recent_tx", "Últimas transacciones", true),
            widget("next_visits", "Próximas visitas", true),
            widget("upcoming_renewals", "Renovaciones próximas", false),
            widget("top_properties", "Top propiedades", false),
            widget("news_market", "Noticias mercado", false)
        );
    }

    private static Map<String, Object> widget(String id, String name, boolean visible) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("visible", visible);
        return m;
    }
}
