package com.inmobilia.controller;

import com.inmobilia.service.PortfolioStressService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Herramientas diferenciadoras vs competencia (Tokko, EasyBroker, Wasi, Stessa, Buildium):
 *  - /herramientas/stress-cartera      Stress test agregado de portfolio.
 */
@Controller
@RequestMapping("/herramientas")
@RequiredArgsConstructor
public class DiferenciadoresController {

    /** Tasa de referencia por defecto (% anual) si la persona no indica la de su crédito. */
    private static final double TASA_REFERENCIA_DEFECTO = 10.0;

    private final PortfolioStressService stress;
    private final SupabaseService supabase;

    // ─────────────── STRESS TEST CARTERA ───────────────
    @GetMapping("/stress-cartera")
    public String stressCartera(@RequestParam(required = false) Double tasa,
                                HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.uid(session);
        var props     = supabase.query("propiedades", uid);
        var contratos = supabase.query("contratos", uid);

        double tasaBase = tasa != null && tasa > 0 && tasa < 200 ? tasa : TASA_REFERENCIA_DEFECTO;
        var report = stress.run(props, contratos, tasaBase);
        model.addAttribute("page", "Stress Test de Cartera");
        model.addAttribute("report", report);
        model.addAttribute("euribor", tasaBase);
        return "herramientas/stress_cartera";
    }
}
