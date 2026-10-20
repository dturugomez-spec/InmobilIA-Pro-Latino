package com.inmobilia.controller;

import com.inmobilia.service.NotificationService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/calendario-fiscal")
public class FiscalCalendarController {

    private final SupabaseService supabase;
    private final NotificationService notif;

    /** Plazos AEAT 2026 (clave → {fecha, descripción}) */
    private static final List<Map<String, Object>> PLAZOS_FIJOS_2026 = new ArrayList<>();
    static {
        PLAZOS_FIJOS_2026.add(plazo("2026-01-30", "Modelo 303 4T 2025 - IVA trimestral", "iva", "alta"));
        PLAZOS_FIJOS_2026.add(plazo("2026-01-30", "Modelo 130 4T 2025 - IRPF pagos fraccionados", "irpf", "alta"));
        PLAZOS_FIJOS_2026.add(plazo("2026-01-30", "Modelo 390 - Resumen anual IVA", "iva", "media"));
        PLAZOS_FIJOS_2026.add(plazo("2026-01-30", "Modelo 180 - Retenciones alquileres anual", "retenciones", "alta"));
        PLAZOS_FIJOS_2026.add(plazo("2026-01-30", "Modelo 190 - Resumen retenciones IRPF", "retenciones", "alta"));
        PLAZOS_FIJOS_2026.add(plazo("2026-02-28", "Modelo 347 - Operaciones >3000€", "informativo", "media"));
        PLAZOS_FIJOS_2026.add(plazo("2026-04-20", "Modelo 303 1T 2026", "iva", "alta"));
        PLAZOS_FIJOS_2026.add(plazo("2026-04-20", "Modelo 130 1T 2026", "irpf", "alta"));
        PLAZOS_FIJOS_2026.add(plazo("2026-04-20", "Modelo 111 1T - Retenciones trabajadores", "retenciones", "alta"));
        PLAZOS_FIJOS_2026.add(plazo("2026-06-30", "Renta 2025 - Modelo 100", "renta", "alta"));
        PLAZOS_FIJOS_2026.add(plazo("2026-07-20", "Modelo 303 2T 2026", "iva", "alta"));
        PLAZOS_FIJOS_2026.add(plazo("2026-07-20", "Modelo 130 2T 2026", "irpf", "alta"));
        PLAZOS_FIJOS_2026.add(plazo("2026-07-25", "Impuesto Sociedades (200) si cierre 31/12", "is", "alta"));
        PLAZOS_FIJOS_2026.add(plazo("2026-10-20", "Modelo 303 3T 2026", "iva", "alta"));
        PLAZOS_FIJOS_2026.add(plazo("2026-10-20", "Modelo 130 3T 2026", "irpf", "alta"));
        PLAZOS_FIJOS_2026.add(plazo("2026-10-20", "Modelo 184 - Comunidades de bienes", "informativo", "media"));
    }

    private static Map<String, Object> plazo(String fecha, String desc, String tipo, String urgencia) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fecha", fecha); m.put("descripcion", desc); m.put("tipo", tipo); m.put("urgencia", urgencia);
        return m;
    }

    @GetMapping
    public String calendario(HttpSession session, Model model) {
        if (SessionUtils.uid(session) == null) return "redirect:/login";

        LocalDate hoy = LocalDate.now();
        List<Map<String, Object>> proximos = new ArrayList<>();
        List<Map<String, Object>> pasados = new ArrayList<>();

        for (Map<String, Object> p : PLAZOS_FIJOS_2026) {
            try {
                LocalDate f = LocalDate.parse(String.valueOf(p.get("fecha")));
                long dias = ChronoUnit.DAYS.between(hoy, f);
                Map<String, Object> copy = new LinkedHashMap<>(p);
                copy.put("dias", dias);
                if (dias >= 0) proximos.add(copy);
                else pasados.add(copy);
            } catch (Exception e) {}
        }

        model.addAttribute("proximos", proximos);
        model.addAttribute("pasados", pasados);
        return "fiscal_calendar";
    }

    /** Cron diario para avisar de plazos a 30, 14, 7, 1 días. */
    @Scheduled(cron = "0 0 9 * * *", zone = "Europe/Madrid")
    public void cronAvisos() {
        LocalDate hoy = LocalDate.now();
        List<Map<String, Object>> users = supabase.selectFilter("users", "active=eq.true");

        for (Map<String, Object> p : PLAZOS_FIJOS_2026) {
            try {
                LocalDate fecha = LocalDate.parse(String.valueOf(p.get("fecha")));
                long dias = ChronoUnit.DAYS.between(hoy, fecha);
                if (dias == 30 || dias == 14 || dias == 7 || dias == 1) {
                    for (Map<String, Object> u : users) {
                        String level = dias <= 7 ? "alta" : "media";
                        notif.create(String.valueOf(u.get("id")),
                                "📅 Plazo fiscal en " + dias + " días",
                                p.get("descripcion") + " (" + fecha + ")",
                                "/calendario-fiscal", level);
                    }
                }
            } catch (Exception e) {}
        }
    }
}
