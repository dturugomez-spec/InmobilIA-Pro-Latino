package com.inmobilia.controller;

import com.inmobilia.service.FinancialService;
import com.inmobilia.service.FinancialService.*;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.FormatUtils;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Controladores de todas las herramientas financieras:
 * DCF, Monte Carlo, Stress Test, Yield, Hipoteca, Waterfall, Sensibilidad,
 * Portfolio, Reformas, Valoracion, Fiscal, ROI, Trading, CMA, Tanteo, Biblioteca.
 */
@Controller
@RequestMapping("/finanzas")
@RequiredArgsConstructor
public class FinancialAnalysisController {

    private final FinancialService financial;
    private final SupabaseService supabase;

    // ───────────────────────────── DCF ─────────────────────────────
    @GetMapping("/dcf")
    public String dcfForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "DCF & Flujos Descontados");
        return "analisis/dcf";
    }

    @PostMapping("/dcf")
    public String dcfRun(@RequestParam double price,
                         @RequestParam double rentMonthly,
                         @RequestParam(defaultValue = "5")  double vacancyPct,
                         @RequestParam(defaultValue = "25") double opexPct,
                         @RequestParam(defaultValue = "10") int years,
                         @RequestParam(defaultValue = "2")  double growthPct,
                         @RequestParam(defaultValue = "2.5") double growthExpPct,
                         @RequestParam(defaultValue = "5.5") double exitCapPct,
                         @RequestParam(defaultValue = "8")  double discountPct,
                         @RequestParam(defaultValue = "10") double buyingCostsPct,
                         @RequestParam(defaultValue = "70") double ltvPct,
                         @RequestParam(defaultValue = "4")  double interestPct,
                         @RequestParam(defaultValue = "25") int loanTerm,
                         @RequestParam(defaultValue = "1")  double capexPct,
                         HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        DcfInputs in = new DcfInputs(price, rentMonthly, vacancyPct, opexPct, years,
                growthPct, growthExpPct, exitCapPct, discountPct,
                buyingCostsPct, ltvPct, interestPct, loanTerm, capexPct);
        DcfOutputs out = financial.computeDcf(in);
        model.addAttribute("page", "DCF & Flujos Descontados");
        model.addAttribute("in", in);
        model.addAttribute("out", out);
        return "analisis/dcf";
    }

    // ─────────────────────────── MONTE CARLO ───────────────────────
    @GetMapping("/montecarlo")
    public String mcForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Simulacion Monte Carlo");
        return "analisis/montecarlo";
    }

    @PostMapping("/montecarlo")
    public String mcRun(@RequestParam double investment,
                        @RequestParam double noiBase,
                        @RequestParam(defaultValue = "10") int years,
                        @RequestParam(defaultValue = "15") double volNoiPct,
                        @RequestParam(defaultValue = "20") double volCapPct,
                        @RequestParam(defaultValue = "5.5") double capBasePct,
                        @RequestParam(defaultValue = "2") double growthBasePct,
                        @RequestParam(defaultValue = "5000") int simulations,
                        @RequestParam(defaultValue = "8") double irrTargetPct,
                        HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        MonteCarloInputs in = new MonteCarloInputs(investment, noiBase, years, volNoiPct,
                volCapPct, capBasePct, growthBasePct, simulations, irrTargetPct);
        MonteCarloOutputs out = financial.monteCarlo(in);
        model.addAttribute("page", "Simulacion Monte Carlo");
        model.addAttribute("in", in);
        model.addAttribute("out", out);
        return "analisis/montecarlo";
    }

    // ─────────────────────────── STRESS TEST ───────────────────────
    @GetMapping("/stress")
    public String stressForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Stress Test & DSCR");
        return "analisis/stress";
    }

    @PostMapping("/stress")
    public String stressRun(@RequestParam double noi,
                            @RequestParam double debt,
                            @RequestParam double rate,
                            @RequestParam(defaultValue = "25") int term,
                            @RequestParam(defaultValue = "1.25") double dscrMin,
                            @RequestParam(defaultValue = "20") double noiStressPct,
                            HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        StressInputs in = new StressInputs(noi, debt, rate, term, dscrMin, noiStressPct);
        List<StressScenario> out = financial.stressTest(in);
        model.addAttribute("page", "Stress Test & DSCR");
        model.addAttribute("in", in);
        model.addAttribute("scenarios", out);
        return "analisis/stress";
    }

    // ─────────────────────────── YIELD QUICK ───────────────────────
    @GetMapping("/yield")
    public String yieldForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Rentabilidad & Yield");
        return "analisis/yield";
    }

    @PostMapping("/yield")
    public String yieldRun(@RequestParam double price,
                           @RequestParam double rentMonthly,
                           @RequestParam(defaultValue = "0") double expensesMonthly,
                           @RequestParam(defaultValue = "0") double mortgageMonthly,
                           @RequestParam(defaultValue = "0") double equity,
                           HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        YieldResult out = financial.computeYield(price, rentMonthly, expensesMonthly, mortgageMonthly, equity);
        model.addAttribute("page", "Rentabilidad & Yield");
        model.addAttribute("out", out);
        return "analisis/yield";
    }

    // ─────────────────────────── HIPOTECA ───────────────────────
    @GetMapping("/hipoteca")
    public String hipotecaForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Estructuracion de Deuda");
        return "analisis/hipoteca";
    }

    @PostMapping("/hipoteca")
    public String hipotecaRun(@RequestParam double capital,
                              @RequestParam double tae,
                              @RequestParam int anios,
                              @RequestParam(defaultValue = "frances") String sistema,
                              HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        List<CuotaMes> rows = "aleman".equalsIgnoreCase(sistema)
                ? financial.amortizacionAleman(capital, tae, anios)
                : financial.amortizacionFrances(capital, tae, anios);
        double totalInt = rows.stream().mapToDouble(CuotaMes::interes).sum();
        double totalPag = rows.stream().mapToDouble(CuotaMes::cuota).sum();
        model.addAttribute("page", "Estructuracion de Deuda");
        model.addAttribute("capital", capital);
        model.addAttribute("tae", tae);
        model.addAttribute("anios", anios);
        model.addAttribute("sistema", sistema);
        model.addAttribute("rows", rows);
        model.addAttribute("cuotaInicial", rows.isEmpty() ? 0 : rows.get(0).cuota());
        model.addAttribute("totalInteres", totalInt);
        model.addAttribute("totalPagado", totalPag);
        return "analisis/hipoteca";
    }

    // ─────────────────────────── WATERFALL ───────────────────────
    @GetMapping("/waterfall")
    public String waterfallForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Waterfall de Flujos");
        return "analisis/waterfall";
    }

    @PostMapping("/waterfall")
    public String waterfallRun(@RequestParam double equityLp,
                               @RequestParam double equityGp,
                               @RequestParam String cashflows,
                               @RequestParam(defaultValue = "8") double preferredRate,
                               @RequestParam(defaultValue = "20") double promotePct,
                               HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        double[] cfs = Arrays.stream(cashflows.split("[,;\\s]+"))
                .filter(s -> !s.isBlank())
                .mapToDouble(s -> {
                    try { return Double.parseDouble(s.replace(',', '.')); }
                    catch (Exception e) { return 0; }
                }).toArray();
        WaterfallResult out = financial.waterfall(equityLp, equityGp, cfs, preferredRate,
                List.of(new WaterfallTier("Promote", promotePct, 0)));
        model.addAttribute("page", "Waterfall de Flujos");
        model.addAttribute("out", out);
        return "analisis/waterfall";
    }

    // ─────────────────────────── SENSIBILIDAD ───────────────────────
    @GetMapping("/sensibilidad")
    public String sensibilidadForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Analisis de Sensibilidad");
        return "analisis/sensibilidad";
    }

    @PostMapping("/sensibilidad")
    public String sensibilidadRun(@RequestParam double price,
                                   @RequestParam double rentMonthly,
                                   HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        // Sensibilidad sobre renta ±20% y precio ±10%
        double[] rentFactors  = {0.8, 0.9, 1.0, 1.1, 1.2};
        double[] priceFactors = {0.9, 0.95, 1.0, 1.05, 1.1};
        List<Map<String, Object>> grid = new ArrayList<>();
        for (double pf : priceFactors) {
            List<Double> fila = new ArrayList<>();
            for (double rf : rentFactors) {
                double p = price * pf;
                double rm = rentMonthly * rf;
                double y = p > 0 ? rm * 12 / p * 100 : 0;
                fila.add(y);
            }
            grid.add(Map.of(
                    "precio",   price * pf,
                    "yields",   fila
            ));
        }
        model.addAttribute("page", "Analisis de Sensibilidad");
        model.addAttribute("rentFactors", rentFactors);
        model.addAttribute("grid", grid);
        model.addAttribute("price", price);
        model.addAttribute("rent", rentMonthly);
        return "analisis/sensibilidad";
    }

    // ─────────────────────────── PORTFOLIO (optimizacion) ───────
    @GetMapping("/portfolio")
    public String portfolio(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.uid(session);
        List<Map<String, Object>> props = supabase.query("propiedades", uid);
        double totValue = 0, totRent = 0, totExp = 0;
        for (var p : props) {
            totValue += num(p, "current_value");
            totRent  += num(p, "rental_income");
            totExp   += num(p, "monthly_expenses");
        }
        double noi = (totRent - totExp) * 12;
        double cap = totValue > 0 ? noi / totValue * 100 : 0;
        model.addAttribute("page", "Optimizacion de Cartera");
        model.addAttribute("props", props);
        model.addAttribute("totValue", FormatUtils.dinero(totValue));
        model.addAttribute("noiAnual", FormatUtils.dinero(noi));
        model.addAttribute("capCartera", cap);
        return "analisis/portfolio";
    }

    // ─────────────────────────── REFORMAS (Value-Add) ───────────
    @GetMapping("/reformas")
    public String reformasForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Simulador Value-Add");
        return "analisis/reformas";
    }

    @PostMapping("/reformas")
    public String reformasRun(@RequestParam double precioCompra,
                              @RequestParam double costeReforma,
                              @RequestParam double rentaActual,
                              @RequestParam double rentaPostReforma,
                              @RequestParam(defaultValue = "12") int mesesObra,
                              HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        double inversion = precioCompra + costeReforma;
        double deltaRenta = (rentaPostReforma - rentaActual) * 12;
        double yieldPre  = precioCompra > 0 ? rentaActual * 12 / precioCompra * 100 : 0;
        double yieldPost = inversion > 0 ? rentaPostReforma * 12 / inversion * 100 : 0;
        double paybackAnios = deltaRenta > 0 ? costeReforma / deltaRenta : 0;
        double roiReforma = costeReforma > 0 ? deltaRenta * 10 / costeReforma * 100 : 0;
        model.addAttribute("page", "Simulador Value-Add");
        model.addAttribute("inversion", FormatUtils.dinero(inversion));
        model.addAttribute("deltaRenta", FormatUtils.dinero(deltaRenta));
        model.addAttribute("yieldPre", yieldPre);
        model.addAttribute("yieldPost", yieldPost);
        model.addAttribute("paybackAnios", paybackAnios);
        model.addAttribute("roiReforma", roiReforma);
        model.addAttribute("mesesObra", mesesObra);
        return "analisis/reformas";
    }

    // ─────────────────────────── VALORACION ───────────
    @GetMapping("/valoracion")
    public String valoracionForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Modelos de Valoracion");
        return "analisis/valoracion";
    }

    @PostMapping("/valoracion")
    public String valoracionRun(@RequestParam double noi,
                                @RequestParam(defaultValue = "5.5") double capRatePct,
                                @RequestParam double m2,
                                @RequestParam double precioM2Mercado,
                                @RequestParam(defaultValue = "0") double comparable1,
                                @RequestParam(defaultValue = "0") double comparable2,
                                @RequestParam(defaultValue = "0") double comparable3,
                                HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        double valorIngresos = capRatePct > 0 ? noi / (capRatePct / 100.0) : 0;
        double valorMercado  = m2 * precioM2Mercado;
        double[] comps = Arrays.stream(new double[]{comparable1, comparable2, comparable3})
                .filter(v -> v > 0).toArray();
        double valorComparables = comps.length > 0
                ? Arrays.stream(comps).average().orElse(0)
                : 0;
        double valorSintesis = media(valorIngresos, valorMercado, valorComparables);
        model.addAttribute("page", "Modelos de Valoracion");
        model.addAttribute("valorIngresos", FormatUtils.dinero(valorIngresos));
        model.addAttribute("valorMercado", FormatUtils.dinero(valorMercado));
        model.addAttribute("valorComparables", FormatUtils.dinero(valorComparables));
        model.addAttribute("valorSintesis", FormatUtils.dinero(valorSintesis));
        return "analisis/valoracion";
    }

    // ─────────────────────────── QUICK CHECK ───────────
    @GetMapping("/quickcheck")
    public String quickCheckForm(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Quick Check Inmobiliario");
        return "analisis/quickcheck";
    }

    @PostMapping("/quickcheck")
    public String quickCheckRun(@RequestParam double price,
                                @RequestParam double rentMonthly,
                                @RequestParam(defaultValue = "30") double downPct,
                                @RequestParam(defaultValue = "4") double interestPct,
                                @RequestParam(defaultValue = "25") int term,
                                @RequestParam(defaultValue = "25") double opexPct,
                                HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        double equity = price * downPct / 100.0;
        double debt   = price - equity;
        double cuota  = financial.cuotaMensualFrances(debt, interestPct, term);
        double expenses = rentMonthly * opexPct / 100.0;
        YieldResult y = financial.computeYield(price, rentMonthly, expenses, cuota, equity);
        String veredicto = y.cashOnCash() >= 8 ? "EXCELENTE"
                         : y.cashOnCash() >= 5 ? "Aceptable"
                         : y.cashOnCash() >= 0 ? "Marginal"
                         : "Rechazable";
        model.addAttribute("page", "Quick Check Inmobiliario");
        model.addAttribute("yieldResult", y);
        model.addAttribute("equity", FormatUtils.dinero(equity));
        model.addAttribute("debt", FormatUtils.dinero(debt));
        model.addAttribute("cuota", FormatUtils.dinero(cuota));
        model.addAttribute("veredicto", veredicto);
        return "analisis/quickcheck";
    }

    // ─────────────────────────── BIBLIOTECA ───────────
    @GetMapping("/biblioteca")
    public String biblioteca(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.uid(session);
        List<Map<String, Object>> rows = supabase.query("analisis_financieros", uid);
        model.addAttribute("page", "Biblioteca de Analisis");
        model.addAttribute("rows", rows);
        return "analisis/biblioteca";
    }

    private double num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
    }

    private double media(double... vals) {
        double s = 0; int c = 0;
        for (double v : vals) if (v > 0) { s += v; c++; }
        return c > 0 ? s / c : 0;
    }
}
