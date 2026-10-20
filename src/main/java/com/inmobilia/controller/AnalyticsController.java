package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Analítica avanzada: comparativas año vs año, forecast, top insights.
 */
@Controller
@RequiredArgsConstructor
public class AnalyticsController {

    private final SupabaseService supabase;

    @GetMapping("/analytics")
    public String index(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.uid(session);

        var transacciones = supabase.query("transacciones", uid);
        int yActual = LocalDate.now().getYear();
        int yAnterior = yActual - 1;

        // ─── Comparativa año vs año ───
        double[] ingresosActual = new double[12];
        double[] ingresosPrev = new double[12];
        double[] gastosActual = new double[12];
        double[] gastosPrev = new double[12];

        for (var t : transacciones) {
            String d = String.valueOf(t.getOrDefault("date", ""));
            if (d.length() < 10) continue;
            try {
                int y = Integer.parseInt(d.substring(0, 4));
                int m = Integer.parseInt(d.substring(5, 7));
                double amt = parseD(t.get("amount"));
                boolean ing = "Ingreso".equalsIgnoreCase(String.valueOf(t.getOrDefault("type", "")));
                if (y == yActual) {
                    if (ing) ingresosActual[m - 1] += amt; else gastosActual[m - 1] += amt;
                } else if (y == yAnterior) {
                    if (ing) ingresosPrev[m - 1] += amt; else gastosPrev[m - 1] += amt;
                }
            } catch (Exception ignored) {}
        }

        double totalActual = sum(ingresosActual) - sum(gastosActual);
        double totalPrev = sum(ingresosPrev) - sum(gastosPrev);
        double yoyPct = totalPrev != 0 ? ((totalActual - totalPrev) / Math.abs(totalPrev) * 100) : 0;

        // ─── Forecast 12 meses (media móvil de ingresos últimos 6 meses) ───
        double[] forecast = new double[12];
        double avgRev = average(ingresosActual, 6);
        double avgExp = average(gastosActual, 6);
        for (int i = 0; i < 12; i++) forecast[i] = avgRev - avgExp;

        // ─── Top insights ───
        List<Map<String, Object>> insights = new ArrayList<>();
        if (totalActual > totalPrev * 1.1) {
            insights.add(insight("🚀", "success", "Crecimiento fuerte",
                    String.format("Tu cashflow ha crecido un %.1f%% vs el año pasado.", yoyPct)));
        } else if (totalActual < totalPrev * 0.9) {
            insights.add(insight("⚠", "warning", "Caída de cashflow",
                    String.format("Cashflow %.1f%% por debajo del año pasado.", Math.abs(yoyPct))));
        }

        // Mejor y peor mes
        int bestMonth = bestIdx(ingresosActual);
        int worstMonth = worstIdx(ingresosActual, 6);
        if (bestMonth >= 0) {
            insights.add(insight("📈", "info", "Mejor mes",
                    monthName(bestMonth) + " fue tu mes con más ingresos: " +
                    String.format("%,.0f €", ingresosActual[bestMonth])));
        }

        // Cap rate medio
        var props = supabase.query("propiedades", uid);
        double valorTotal = props.stream().mapToDouble(p -> parseD(p.get("current_value"))).sum();
        double rentaTotal = props.stream().mapToDouble(p -> parseD(p.get("rental_income")) * 12).sum();
        double yieldMedio = valorTotal > 0 ? (rentaTotal / valorTotal * 100) : 0;
        if (yieldMedio > 0) {
            insights.add(insight("💎", yieldMedio >= 5 ? "success" : "info", "Yield medio cartera",
                    String.format("%.2f%% bruto anual sobre el valor de mercado.", yieldMedio)));
        }

        // Inquilinos morosos
        long morosos = supabase.query("inquilinos", uid).stream()
                .filter(i -> "Moroso".equalsIgnoreCase(String.valueOf(i.getOrDefault("status", ""))))
                .count();
        if (morosos > 0) {
            insights.add(insight("🔴", "error", "Inquilinos morosos",
                    morosos + " inquilino(s) marcados como morosos. Revisa cobros."));
        }

        // Ocupación
        long ocupadas = props.stream().filter(p -> "Ocupado".equalsIgnoreCase(String.valueOf(p.getOrDefault("status", "")))).count();
        double occRate = !props.isEmpty() ? (ocupadas * 100.0 / props.size()) : 0;
        if (props.size() >= 3 && occRate < 70) {
            insights.add(insight("🏚", "warning", "Ocupación baja",
                    String.format("Solo %.0f%% de tus propiedades están ocupadas.", occRate)));
        }

        model.addAttribute("page", "Analítica avanzada");
        model.addAttribute("yActual", yActual);
        model.addAttribute("yAnterior", yAnterior);
        model.addAttribute("ingresosActual", ingresosActual);
        model.addAttribute("ingresosPrev", ingresosPrev);
        model.addAttribute("gastosActual", gastosActual);
        model.addAttribute("gastosPrev", gastosPrev);
        model.addAttribute("forecast", forecast);
        model.addAttribute("totalActual", totalActual);
        model.addAttribute("totalPrev", totalPrev);
        model.addAttribute("yoyPct", yoyPct);
        model.addAttribute("insights", insights);
        model.addAttribute("yieldMedio", yieldMedio);
        model.addAttribute("occRate", occRate);
        return "analytics";
    }

    private static Map<String, Object> insight(String icon, String tone, String title, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("icon", icon); m.put("tone", tone); m.put("title", title); m.put("desc", desc);
        return m;
    }
    private static double parseD(Object o) { if (o == null) return 0; try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
    private static double sum(double[] a) { double s = 0; for (double x : a) s += x; return s; }
    private static double average(double[] a, int last) {
        double s = 0; int c = 0;
        int start = Math.max(0, a.length - last);
        for (int i = start; i < a.length; i++) { if (a[i] > 0) { s += a[i]; c++; } }
        return c > 0 ? s / c : 0;
    }
    private static int bestIdx(double[] a) {
        int idx = -1; double max = -1;
        for (int i = 0; i < a.length; i++) if (a[i] > max) { max = a[i]; idx = i; }
        return idx;
    }
    private static int worstIdx(double[] a, int upTo) {
        int idx = -1; double min = Double.MAX_VALUE;
        for (int i = 0; i < Math.min(upTo, a.length); i++) if (a[i] > 0 && a[i] < min) { min = a[i]; idx = i; }
        return idx;
    }
    private static String monthName(int m) {
        return new String[]{"Enero","Febrero","Marzo","Abril","Mayo","Junio","Julio","Agosto","Septiembre","Octubre","Noviembre","Diciembre"}[m];
    }
}
