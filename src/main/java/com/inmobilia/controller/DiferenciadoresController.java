package com.inmobilia.controller;

import com.inmobilia.service.BenchmarkRentaService;
import com.inmobilia.service.ComparadorCcaaService;
import com.inmobilia.service.OfficialDataService;
import com.inmobilia.service.PortfolioStressService;
import com.inmobilia.service.SubastasBoeService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Controlador para las 4 herramientas diferenciadoras vs competencia
 * (Witei, Tokko, Idealista Pro, Argus, Stessa, Buildium):
 *  - /herramientas/subastas-boe        Hunter de subastas BOE con criterios.
 *  - /herramientas/benchmark-renta     Renta vs indice MITMA + zona tensionada.
 *  - /herramientas/stress-cartera      Stress test agregado de portfolio.
 *  - /herramientas/comparador-ccaa     Comparador 19 CCAA fiscal+mercado.
 */
@Controller
@RequestMapping("/herramientas")
@RequiredArgsConstructor
public class DiferenciadoresController {

    private final SubastasBoeService subastas;
    private final BenchmarkRentaService benchmark;
    private final PortfolioStressService stress;
    private final ComparadorCcaaService ccaa;
    private final SupabaseService supabase;
    private final OfficialDataService officialData;

    // ─────────────── 1. SUBASTAS BOE HUNTER ───────────────
    @GetMapping("/subastas-boe")
    public String subastasForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Subastas BOE Hunter");
        model.addAttribute("tipos", subastas.tiposBien());
        model.addAttribute("provincias", subastas.provincias());
        try {
            model.addAttribute("subastasRecientes", subastas.recentSubastas(15));
        } catch (Exception e) {
            model.addAttribute("subastasRecientes", java.util.List.of());
        }
        return "herramientas/subastas_boe";
    }

    @PostMapping("/subastas-boe")
    public String subastasBuscar(@RequestParam(required = false) String tipoBien,
                                  @RequestParam(required = false) String provincia,
                                  @RequestParam(required = false) Double precioMin,
                                  @RequestParam(required = false) Double precioMax,
                                  @RequestParam(required = false) String texto,
                                  HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String url = subastas.buildSearchUrl(tipoBien, provincia, precioMin, precioMax, texto);
        model.addAttribute("page", "Subastas BOE Hunter");
        model.addAttribute("tipos", subastas.tiposBien());
        model.addAttribute("provincias", subastas.provincias());
        model.addAttribute("urlBusqueda", url);
        model.addAttribute("filtros", java.util.Map.of(
                "tipoBien", tipoBien == null ? "" : tipoBien,
                "provincia", provincia == null ? "" : provincia,
                "precioMin", precioMin == null ? 0 : precioMin,
                "precioMax", precioMax == null ? 0 : precioMax,
                "texto", texto == null ? "" : texto
        ));
        try {
            model.addAttribute("subastasRecientes", subastas.recentSubastas(15));
        } catch (Exception e) {
            model.addAttribute("subastasRecientes", java.util.List.of());
        }
        return "herramientas/subastas_boe";
    }

    // ─────────────── 2. BENCHMARK RENTA MITMA ───────────────
    @GetMapping("/benchmark-renta")
    public String benchmarkForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Benchmark Renta Ministerio");
        model.addAttribute("ranking", benchmark.rankingProvincias());
        return "herramientas/benchmark_renta";
    }

    @PostMapping("/benchmark-renta")
    public String benchmarkRun(@RequestParam String provincia,
                                @RequestParam(required = false, defaultValue = "") String municipio,
                                @RequestParam double m2,
                                @RequestParam double rentaMes,
                                @RequestParam(defaultValue = "true") boolean nuevoContrato,
                                HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        var res = benchmark.evaluar(provincia, municipio, m2, rentaMes, nuevoContrato);
        model.addAttribute("page", "Benchmark Renta Ministerio");
        model.addAttribute("ranking", benchmark.rankingProvincias());
        model.addAttribute("benchmark", res);
        model.addAttribute("provincia", provincia);
        model.addAttribute("municipio", municipio);
        model.addAttribute("m2", m2);
        model.addAttribute("rentaMes", rentaMes);
        return "herramientas/benchmark_renta";
    }

    // ─────────────── 3. STRESS TEST CARTERA ───────────────
    @GetMapping("/stress-cartera")
    public String stressCartera(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.uid(session);
        var props     = supabase.query("propiedades", uid);
        var contratos = supabase.query("contratos", uid);

        double euribor = 3.5;
        try {
            var live = officialData.euriborLive();
            if (live != null && live.valor() != null) euribor = live.valor();
        } catch (Exception ignored) {}

        var report = stress.run(props, contratos, euribor);
        model.addAttribute("page", "Stress Test de Cartera");
        model.addAttribute("report", report);
        model.addAttribute("euribor", euribor);
        return "herramientas/stress_cartera";
    }

    // ─────────────── 4. COMPARADOR CCAA ───────────────
    @GetMapping("/comparador-ccaa")
    public String comparadorCcaa(@RequestParam(required = false) String orderBy,
                                  HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String order = orderBy == null ? "yieldNeto" : orderBy;
        model.addAttribute("page", "Comparador CCAA");
        model.addAttribute("rows", ccaa.top(order, 0));
        model.addAttribute("resumen", ccaa.resumen());
        model.addAttribute("orderBy", order);
        try {
            model.addAttribute("ipvYoY", officialData.ipvYoY());
        } catch (Exception ignored) {}
        return "herramientas/comparador_ccaa";
    }
}
