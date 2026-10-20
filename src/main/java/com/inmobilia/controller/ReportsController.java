package com.inmobilia.controller;

import com.inmobilia.service.PermissionService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reportes y analítica avanzada: P&L, rentabilidad por propiedad,
 * top inquilinos, resumen fiscal anual. Todos imprimibles a PDF.
 */
@Controller
@RequestMapping("/reports")
@RequiredArgsConstructor
@Slf4j
public class ReportsController {

    private final SupabaseService supabase;
    private final PermissionService permissions;

    @GetMapping
    public String index(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canRead(role, "reportes")) return "redirect:/dashboard";
        model.addAttribute("page", "Reportes y analítica");
        return "reports/index";
    }

    // ─── P&L (Cuenta de resultados) ───
    @GetMapping("/pnl")
    public String pnl(@RequestParam(required = false) Integer year,
                      HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canRead(role, "reportes") && !permissions.canRead(role, "transacciones")) return "redirect:/dashboard";

        String uid = SessionUtils.uid(session);
        int currentYear = year != null ? year : LocalDate.now().getYear();

        var transacciones = supabase.query("transacciones", uid).stream()
                .filter(t -> {
                    String d = String.valueOf(t.getOrDefault("date", ""));
                    return d.length() >= 4 && String.valueOf(currentYear).equals(d.substring(0, 4));
                })
                .toList();

        // Por mes y tipo
        double[] ingresosMes = new double[12];
        double[] gastosMes   = new double[12];
        Map<String, Double> ingresosCat = new TreeMap<>();
        Map<String, Double> gastosCat = new TreeMap<>();

        for (var t : transacciones) {
            int month;
            try {
                String d = String.valueOf(t.getOrDefault("date", ""));
                month = Integer.parseInt(d.substring(5, 7));
            } catch (Exception e) { continue; }
            double amt = parseDouble(t.get("amount"));
            String cat = String.valueOf(t.getOrDefault("category", "Sin categoría"));
            if ("Ingreso".equalsIgnoreCase(String.valueOf(t.getOrDefault("type", "")))) {
                ingresosMes[month - 1] += amt;
                ingresosCat.merge(cat, amt, Double::sum);
            } else {
                gastosMes[month - 1] += amt;
                gastosCat.merge(cat, amt, Double::sum);
            }
        }

        double totalIngresos = Arrays.stream(ingresosMes).sum();
        double totalGastos = Arrays.stream(gastosMes).sum();
        double beneficioBruto = totalIngresos - totalGastos;
        double margen = totalIngresos > 0 ? (beneficioBruto / totalIngresos * 100) : 0;

        model.addAttribute("page", "P&L · " + currentYear);
        model.addAttribute("year", currentYear);
        model.addAttribute("yearAnt", currentYear - 1);
        model.addAttribute("yearSig", currentYear + 1);
        model.addAttribute("ingresosMes", ingresosMes);
        model.addAttribute("gastosMes", gastosMes);
        model.addAttribute("ingresosCat", ingresosCat);
        model.addAttribute("gastosCat", gastosCat);
        model.addAttribute("totalIngresos", totalIngresos);
        model.addAttribute("totalGastos", totalGastos);
        model.addAttribute("beneficioBruto", beneficioBruto);
        model.addAttribute("margen", margen);
        return "reports/pnl";
    }

    // ─── Rentabilidad por propiedad ───
    @GetMapping("/portfolio")
    public String portfolio(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canRead(role, "reportes") && !permissions.canRead(role, "propiedades")) return "redirect:/dashboard";

        String uid = SessionUtils.uid(session);
        var propiedades = supabase.query("propiedades", uid);
        var transacciones = supabase.query("transacciones", uid);

        List<Map<String, Object>> rows = new ArrayList<>();
        double totalValor = 0, totalIngresos = 0, totalGastos = 0, totalRentaTeorica = 0;

        for (var p : propiedades) {
            String name = String.valueOf(p.getOrDefault("name", ""));
            double valor = parseDouble(p.get("current_value"));
            double compra = parseDouble(p.get("purchase_price"));
            double rentaTeorica = parseDouble(p.get("rental_income")) * 12;
            double gastosFijos = parseDouble(p.get("monthly_expenses")) * 12;

            double ingresos = transacciones.stream()
                    .filter(t -> name.equalsIgnoreCase(String.valueOf(t.getOrDefault("property_name", "")))
                              && "Ingreso".equalsIgnoreCase(String.valueOf(t.getOrDefault("type", ""))))
                    .mapToDouble(t -> parseDouble(t.get("amount"))).sum();
            double gastos = transacciones.stream()
                    .filter(t -> name.equalsIgnoreCase(String.valueOf(t.getOrDefault("property_name", "")))
                              && !"Ingreso".equalsIgnoreCase(String.valueOf(t.getOrDefault("type", ""))))
                    .mapToDouble(t -> parseDouble(t.get("amount"))).sum();

            double noi = ingresos - gastos;
            double capRate = valor > 0 ? (noi / valor * 100) : 0;
            double yield = compra > 0 ? (rentaTeorica / compra * 100) : 0;
            double plusvalia = compra > 0 ? ((valor - compra) / compra * 100) : 0;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.get("id"));
            row.put("name", name);
            row.put("city", p.get("city"));
            row.put("status", p.get("status"));
            row.put("type", p.get("type"));
            row.put("compra", compra);
            row.put("valor", valor);
            row.put("plusvalia", plusvalia);
            row.put("rentaTeorica", rentaTeorica);
            row.put("ingresos", ingresos);
            row.put("gastos", gastos);
            row.put("noi", noi);
            row.put("capRate", capRate);
            row.put("yield", yield);
            rows.add(row);

            totalValor += valor;
            totalIngresos += ingresos;
            totalGastos += gastos;
            totalRentaTeorica += rentaTeorica;
        }

        // Ordenar por capRate descendente (los más rentables arriba)
        rows.sort((a, b) -> Double.compare(parseDouble(b.get("capRate")), parseDouble(a.get("capRate"))));

        double capRateMedio = totalValor > 0 ? ((totalIngresos - totalGastos) / totalValor * 100) : 0;
        double yieldMedio = totalValor > 0 ? (totalRentaTeorica / totalValor * 100) : 0;

        model.addAttribute("page", "Rentabilidad por propiedad");
        model.addAttribute("rows", rows);
        model.addAttribute("totalValor", totalValor);
        model.addAttribute("totalIngresos", totalIngresos);
        model.addAttribute("totalGastos", totalGastos);
        model.addAttribute("totalNOI", totalIngresos - totalGastos);
        model.addAttribute("capRateMedio", capRateMedio);
        model.addAttribute("yieldMedio", yieldMedio);
        return "reports/portfolio";
    }

    // ─── Top inquilinos ───
    @GetMapping("/tenants")
    public String tenants(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canRead(role, "reportes") && !permissions.canRead(role, "inquilinos")) return "redirect:/dashboard";

        String uid = SessionUtils.uid(session);
        var inquilinos = supabase.query("inquilinos", uid);
        var transacciones = supabase.query("transacciones", uid);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (var inq : inquilinos) {
            String name = String.valueOf(inq.getOrDefault("name", ""));
            String prop = String.valueOf(inq.getOrDefault("property_name", ""));
            double rentaMensual = parseDouble(inq.get("rent_amount"));

            double pagado = transacciones.stream()
                    .filter(t -> {
                        String tProp = String.valueOf(t.getOrDefault("property_name", ""));
                        String tDesc = String.valueOf(t.getOrDefault("description", "")).toLowerCase();
                        return "Ingreso".equalsIgnoreCase(String.valueOf(t.getOrDefault("type", "")))
                            && (prop.equalsIgnoreCase(tProp) || tDesc.contains(name.toLowerCase()));
                    })
                    .mapToDouble(t -> parseDouble(t.get("amount"))).sum();

            long pagosCount = transacciones.stream()
                    .filter(t -> "Ingreso".equalsIgnoreCase(String.valueOf(t.getOrDefault("type", "")))
                              && (prop.equalsIgnoreCase(String.valueOf(t.getOrDefault("property_name", "")))
                              ||  String.valueOf(t.getOrDefault("description", "")).toLowerCase().contains(name.toLowerCase())))
                    .count();

            int score = (int) parseDouble(inq.get("score"));
            String status = String.valueOf(inq.getOrDefault("status", ""));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", inq.get("id"));
            row.put("name", name);
            row.put("property", prop);
            row.put("rent", rentaMensual);
            row.put("totalPagado", pagado);
            row.put("pagosCount", pagosCount);
            row.put("score", score);
            row.put("status", status);
            rows.add(row);
        }

        rows.sort((a, b) -> Double.compare(parseDouble(b.get("totalPagado")), parseDouble(a.get("totalPagado"))));

        model.addAttribute("page", "Top inquilinos");
        model.addAttribute("rows", rows);
        return "reports/tenants";
    }

    // ─── Resumen fiscal anual (para declaración) ───
    @GetMapping("/fiscal")
    public String fiscal(@RequestParam(required = false) Integer year,
                         HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canRead(role, "reportes") && !permissions.canRead(role, "transacciones")) return "redirect:/dashboard";

        String uid = SessionUtils.uid(session);
        int currentYear = year != null ? year : LocalDate.now().getYear() - 1; // ejercicio anterior por defecto

        var transacciones = supabase.query("transacciones", uid).stream()
                .filter(t -> {
                    String d = String.valueOf(t.getOrDefault("date", ""));
                    return d.length() >= 4 && String.valueOf(currentYear).equals(d.substring(0, 4));
                })
                .toList();
        var invoices = supabase.query("invoices", uid).stream()
                .filter(i -> {
                    String d = String.valueOf(i.getOrDefault("fecha", ""));
                    return d.length() >= 4 && String.valueOf(currentYear).equals(d.substring(0, 4));
                })
                .toList();

        // Trimestres IVA (modelo 303)
        double[] baseTrim = new double[4];
        double[] ivaTrim  = new double[4];
        double[] irpfTrim = new double[4];
        for (var inv : invoices) {
            String s = String.valueOf(inv.get("status"));
            if ("Anulada".equalsIgnoreCase(s)) continue;
            try {
                int month = Integer.parseInt(String.valueOf(inv.get("fecha")).substring(5, 7));
                int trim = (month - 1) / 3;
                baseTrim[trim] += parseDouble(inv.get("base_imponible"));
                ivaTrim[trim] += parseDouble(inv.get("iva_amount"));
                irpfTrim[trim] += parseDouble(inv.get("irpf_amount"));
            } catch (Exception ignored) {}
        }

        // Ingresos por rentas (para la declaración anual de impuestos)
        double rentaInmobiliaria = transacciones.stream()
                .filter(t -> "Ingreso".equalsIgnoreCase(String.valueOf(t.getOrDefault("type", ""))))
                .mapToDouble(t -> parseDouble(t.get("amount"))).sum();
        double gastosDeducibles = transacciones.stream()
                .filter(t -> !"Ingreso".equalsIgnoreCase(String.valueOf(t.getOrDefault("type", ""))))
                .mapToDouble(t -> parseDouble(t.get("amount"))).sum();
        double rendimientoNeto = rentaInmobiliaria - gastosDeducibles;

        // Gastos por categoría (deducibles vs no)
        Map<String, Double> gastosCat = new TreeMap<>();
        for (var t : transacciones) {
            if (!"Ingreso".equalsIgnoreCase(String.valueOf(t.getOrDefault("type", "")))) {
                String cat = String.valueOf(t.getOrDefault("category", "Otros"));
                gastosCat.merge(cat, parseDouble(t.get("amount")), Double::sum);
            }
        }

        model.addAttribute("page", "Resumen fiscal · " + currentYear);
        model.addAttribute("year", currentYear);
        model.addAttribute("yearAnt", currentYear - 1);
        model.addAttribute("yearSig", currentYear + 1);
        model.addAttribute("baseTrim", baseTrim);
        model.addAttribute("ivaTrim", ivaTrim);
        model.addAttribute("irpfTrim", irpfTrim);
        model.addAttribute("rentaInmobiliaria", rentaInmobiliaria);
        model.addAttribute("gastosDeducibles", gastosDeducibles);
        model.addAttribute("rendimientoNeto", rendimientoNeto);
        model.addAttribute("gastosCat", gastosCat);
        model.addAttribute("nFacturas", invoices.size());
        return "reports/fiscal";
    }

    private static double parseDouble(Object o) {
        if (o == null) return 0;
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }
}
