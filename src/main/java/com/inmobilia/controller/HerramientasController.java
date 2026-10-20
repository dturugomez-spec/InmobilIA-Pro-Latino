package com.inmobilia.controller;

import com.inmobilia.service.FutureProofScoreService;
import com.inmobilia.service.FutureProofScoreService.ScoreResult;
import com.inmobilia.service.HealthScoreService;
import com.inmobilia.service.NewsService;
import com.inmobilia.service.OfficialDataService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Herramientas varias: Health Score, Future-Proof, ESG, Turistico vs Largo Plazo,
 * Mantenimiento Predictivo, AutoPilot, Radar de Mercado, Holding.
 */
@Controller
@RequestMapping("/herramientas")
@RequiredArgsConstructor
public class HerramientasController {

    private final HealthScoreService health;
    private final FutureProofScoreService futureProof;
    private final SupabaseService supabase;
    private final NewsService newsService;
    private final OfficialDataService officialData;

    @GetMapping("/health-score")
    public String healthScore(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.uid(session);
        var props    = supabase.query("propiedades", uid);
        var tenants  = supabase.query("inquilinos", uid);
        var contratos= supabase.query("contratos", uid);
        var maint    = supabase.query("mantenimiento", uid);
        var tasks    = supabase.query("tareas", uid);
        var res = health.compute(props, tenants, contratos, maint, tasks);
        model.addAttribute("page", "Health Score Cartera");
        model.addAttribute("score", res.score());
        model.addAttribute("dims", res.dimensions());
        model.addAttribute("insights", res.insights());
        return "herramientas/health_score";
    }

    @GetMapping("/future-proof")
    public String futureProofForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Future-Proof Score");
        return "herramientas/future_proof";
    }

    @PostMapping("/future-proof")
    public String futureProofRun(@RequestParam double potenciaKw,
                                  @RequestParam(defaultValue = "false") boolean ampliable,
                                  @RequestParam(defaultValue = "false") boolean trifasica,
                                  @RequestParam(defaultValue = "false") boolean preVE,
                                  @RequestParam(defaultValue = "false") boolean placas,
                                  @RequestParam(defaultValue = "false") boolean bateria,
                                  @RequestParam(defaultValue = "250") double cargaSuelo,
                                  @RequestParam(defaultValue = "2.4") double alturaLibre,
                                  @RequestParam(defaultValue = "0.80") double anchoPuerta,
                                  @RequestParam(defaultValue = "false") boolean wifiMesh,
                                  @RequestParam(defaultValue = "false") boolean ascensor,
                                  @RequestParam(defaultValue = "false") boolean sinEscalones,
                                  HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        double power = futureProof.powerReadiness(potenciaKw, ampliable, trifasica, preVE, placas, bateria);
        double rob   = futureProof.roboticsReadiness(cargaSuelo, alturaLibre, anchoPuerta, wifiMesh, ascensor, sinEscalones);
        ScoreResult comb = futureProof.combine(power, rob);
        model.addAttribute("page", "Future-Proof Score");
        model.addAttribute("result", comb);
        return "herramientas/future_proof";
    }

    @GetMapping("/esg")
    public String esgForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Score ESG");
        return "herramientas/esg";
    }

    @PostMapping("/esg")
    public String esgRun(@RequestParam String certificacion,
                         @RequestParam(defaultValue = "false") boolean aislamientoTermico,
                         @RequestParam(defaultValue = "false") boolean placasSolares,
                         @RequestParam(defaultValue = "false") boolean accesibilidad,
                         @RequestParam(defaultValue = "false") boolean tenantSatisfaction,
                         @RequestParam(defaultValue = "false") boolean gobiernoCorporativo,
                         HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        int env = 0, social = 0, gov = 0;
        if (aislamientoTermico) env += 20;
        if (placasSolares)      env += 20;
        if ("A".equals(certificacion))      env += 40;
        else if ("B".equals(certificacion)) env += 30;
        else if ("C".equals(certificacion)) env += 20;
        else if ("D".equals(certificacion)) env += 10;
        if (accesibilidad) social += 50;
        if (tenantSatisfaction) social += 50;
        if (gobiernoCorporativo) gov += 100;
        int total = (env + social + gov) / 3;
        String rating = total >= 80 ? "AAA" : total >= 60 ? "AA" : total >= 40 ? "A" : "B";
        model.addAttribute("page", "Score ESG");
        model.addAttribute("env", env);
        model.addAttribute("social", social);
        model.addAttribute("gov", gov);
        model.addAttribute("total", total);
        model.addAttribute("rating", rating);
        return "herramientas/esg";
    }

    @GetMapping("/turistico")
    public String turisticoForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Turistico vs Largo Plazo");
        return "herramientas/turistico";
    }

    @PostMapping("/turistico")
    public String turisticoRun(@RequestParam double rentaLargoPlazoMes,
                                @RequestParam double tarifaDiariaTuristico,
                                @RequestParam(defaultValue = "65") double ocupacionPct,
                                @RequestParam(defaultValue = "25") double gastosGestion,
                                @RequestParam(defaultValue = "250") double limpiezaMes,
                                HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        double mesLp = rentaLargoPlazoMes;
        double anualLp = mesLp * 12;
        double ingresosTurMes = tarifaDiariaTuristico * 30 * ocupacionPct / 100.0;
        double netoTurMes = ingresosTurMes * (1 - gastosGestion / 100.0) - limpiezaMes;
        double anualTur = netoTurMes * 12;
        double diferencia = anualTur - anualLp;
        String ganador = diferencia > 0 ? "Turistico" : "Largo plazo";
        model.addAttribute("page", "Turistico vs Largo Plazo");
        model.addAttribute("anualLp", anualLp);
        model.addAttribute("anualTur", anualTur);
        model.addAttribute("mesNetoTur", netoTurMes);
        model.addAttribute("diferencia", diferencia);
        model.addAttribute("ganador", ganador);
        return "herramientas/turistico";
    }

    @GetMapping("/mantenimiento-predictivo")
    public String mantenimientoPredictivo(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.uid(session);
        List<Map<String, Object>> props = supabase.query("propiedades", uid);
        // Heuristica simple: propiedades con > 30 anios o sin registros recientes
        List<Map<String, Object>> riesgo = props.stream().filter(p -> {
            Object y = p.get("year_built");
            if (y instanceof Number n) return (2026 - n.intValue()) >= 30;
            try { return (2026 - Integer.parseInt(String.valueOf(y))) >= 30; }
            catch (Exception e) { return false; }
        }).toList();
        model.addAttribute("page", "Mantenimiento Predictivo");
        model.addAttribute("props", props);
        model.addAttribute("riesgo", riesgo);
        return "herramientas/mantenimiento_predictivo";
    }

    @GetMapping("/radar")
    public String radar(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Radar de Mercado");
        try {
            model.addAttribute("marketNews", newsService.marketNews(18));
        } catch (Exception e) { model.addAttribute("marketNews", java.util.List.of()); }
        try {
            model.addAttribute("regulatoryNews", newsService.regulatoryNews(12));
        } catch (Exception e) { model.addAttribute("regulatoryNews", java.util.List.of()); }
        try {
            model.addAttribute("euribor", officialData.euriborLive());
            model.addAttribute("euriborDelta", officialData.euriborDelta12m());
            model.addAttribute("ipvYoY", officialData.ipvYoY());
            model.addAttribute("tedr", officialData.bancoDeEspanaTedr());
        } catch (Exception ignored) {}
        return "herramientas/radar";
    }

    @GetMapping("/holding")
    public String holdingForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Optimizador Holding");
        return "herramientas/holding";
    }

    @PostMapping("/holding")
    public String holdingRun(@RequestParam double ingresosAnuales,
                             @RequestParam(defaultValue = "Persona fisica") String estructuraActual,
                             HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        double pfIrpf = ingresosAnuales * 0.45;
        double pjIs = ingresosAnuales * 0.25;
        double ahorro = pfIrpf - pjIs;
        String recomendacion = ingresosAnuales >= 60000 ? "Sociedad Limitada" : "Persona fisica (aun)";
        model.addAttribute("page", "Optimizador Holding");
        model.addAttribute("pfIrpf", pfIrpf);
        model.addAttribute("pjIs", pjIs);
        model.addAttribute("ahorro", ahorro);
        model.addAttribute("recomendacion", recomendacion);
        model.addAttribute("estructuraActual", estructuraActual);
        return "herramientas/holding";
    }
}
