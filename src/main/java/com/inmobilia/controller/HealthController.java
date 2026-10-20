package com.inmobilia.controller;

import com.inmobilia.service.AIService;
import com.inmobilia.service.StripeService;
import com.inmobilia.service.SupabaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health check completo + status page pública.
 */
@Controller
@RequiredArgsConstructor
public class HealthController {

    private final SupabaseService supabase;
    private final AIService aiService;
    private final StripeService stripeService;

    @GetMapping("/healthz/full")
    @ResponseBody
    public Map<String, Object> full() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("app", "InmobilIA Pro");
        out.put("status", "UP");
        out.put("timestamp", java.time.OffsetDateTime.now().toString());

        // Memoria
        long total = Runtime.getRuntime().totalMemory();
        long used = total - Runtime.getRuntime().freeMemory();
        Map<String, Object> mem = new LinkedHashMap<>();
        mem.put("usedMb", used / 1024 / 1024);
        mem.put("totalMb", total / 1024 / 1024);
        mem.put("usedPct", Math.round(used * 100.0 / total));
        out.put("memory", mem);

        // Uptime
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        out.put("uptime", Duration.ofMillis(uptimeMs).toString());

        // Servicios
        Map<String, Object> services = new LinkedHashMap<>();
        services.put("supabase", checkService("supabase", () -> supabase.isEnabled()));
        services.put("openai", checkService("openai", () -> aiService.hasPlatformKey()));
        services.put("stripe", checkService("stripe", () -> stripeService.isConfigured()));
        out.put("services", services);

        return out;
    }

    @GetMapping("/status")
    public String statusPage(Model model) {
        Map<String, Object> health = full();
        model.addAttribute("health", health);
        return "status_page";
    }

    private Map<String, Object> checkService(String name, java.util.function.BooleanSupplier check) {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            boolean ok = check.getAsBoolean();
            r.put("status", ok ? "UP" : "DEGRADED");
            r.put("configured", ok);
        } catch (Exception e) {
            r.put("status", "DOWN");
            r.put("error", e.getMessage());
        }
        return r;
    }
}
