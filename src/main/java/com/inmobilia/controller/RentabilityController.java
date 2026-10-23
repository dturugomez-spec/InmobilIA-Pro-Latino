package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Analizador de rentabilidad inmobiliaria.
 * Calcula: rentabilidad bruta, neta, cash-on-cash, ROCE y TIR a N años
 * considerando ingresos, gastos, hipoteca, vacancia, IPC y revalorización.
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/rentabilidad")
public class RentabilityController {

    private final SupabaseService supabase;

    @GetMapping
    public String list(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> analisis = supabase.selectFilter(
                "analisis_rentabilidad", "user_id=eq." + uid + "&order=created_at.desc");
        model.addAttribute("analisis", analisis);
        return "rentability_list";
    }

    @GetMapping("/nuevo")
    public String form(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        model.addAttribute("propiedades", supabase.selectFilter("propiedades", "user_id=eq." + uid));
        return "rentability_form";
    }

    @PostMapping("/calcular")
    @ResponseBody
    public Map<String, Object> calculate(@RequestBody Map<String, Object> body) {
        double precioCompra = pd(body.get("precioCompra"));
        double gastosCompra = pd(body.get("gastosCompra"));
        double reformas = pd(body.get("reformas"));
        double mobiliario = pd(body.get("mobiliario"));
        double inversionTotal = precioCompra + gastosCompra + reformas + mobiliario;

        boolean hipoteca = Boolean.TRUE.equals(body.get("hipoteca"));
        double capitalHip = hipoteca ? pd(body.get("capitalHipoteca")) : 0;
        double tipoInteres = pd(body.get("tipoInteres")) / 100.0;
        int plazoAnos = (int) pd(body.get("plazoAnos"));
        double cuotaMensual = 0;
        if (hipoteca && capitalHip > 0 && plazoAnos > 0) {
            int n = plazoAnos * 12;
            double i = tipoInteres / 12;
            cuotaMensual = i == 0 ? capitalHip / n : capitalHip * i / (1 - Math.pow(1 + i, -n));
        }
        double inversionInicial = inversionTotal - capitalHip; // capital propio

        double rentaMensual = pd(body.get("rentaMensual"));
        double incrementoRenta = pd(body.get("incrementoRentaAnual")) / 100.0;
        double ibi = pd(body.get("ibi"));
        double comunidadMensual = pd(body.get("comunidad"));
        double seguroHogar = pd(body.get("seguroHogar"));
        double seguroImpago = pd(body.get("seguroImpago"));
        double mantenimiento = pd(body.get("mantenimiento"));
        double gestionPct = pd(body.get("gestion")) / 100.0;
        double otros = pd(body.get("otrosGastos"));
        double vacanciaPct = pd(body.get("tasaVacancia")) / 100.0;
        double revaloriza = pd(body.get("revalorizacionAnual")) / 100.0;
        int horizonte = Math.max(1, (int) pd(body.get("horizonteAnos")));

        // === AÑO 1 ===
        double ingresosBrutos = rentaMensual * 12;
        double ingresosNetos = ingresosBrutos * (1 - vacanciaPct);
        double gastosAnuales = ibi + (comunidadMensual * 12) + seguroHogar + seguroImpago + mantenimiento + otros
                              + (ingresosNetos * gestionPct);
        double pagosHipoteca = cuotaMensual * 12;
        double flujoNeto = ingresosNetos - gastosAnuales - pagosHipoteca;

        double rentBruta = inversionTotal > 0 ? (ingresosBrutos / inversionTotal) * 100 : 0;
        double rentNeta = inversionTotal > 0 ? ((ingresosNetos - gastosAnuales) / inversionTotal) * 100 : 0;
        double cashOnCash = inversionInicial > 0 ? (flujoNeto / inversionInicial) * 100 : 0;

        // === PROYECCIÓN A N AÑOS ===
        List<Map<String, Object>> proyeccion = new ArrayList<>();
        double rentaActual = rentaMensual;
        double valorPropiedad = precioCompra;
        double cashflowAcumulado = 0;
        double saldoHipoteca = capitalHip;

        for (int year = 1; year <= horizonte; year++) {
            if (year > 1) {
                rentaActual *= (1 + incrementoRenta);
                valorPropiedad *= (1 + revaloriza);
            }
            double ingY = rentaActual * 12 * (1 - vacanciaPct);
            double gastosY = (ibi + comunidadMensual * 12 + seguroHogar + seguroImpago + mantenimiento + otros)
                            * Math.pow(1.02, year - 1)  // IPC 2%
                            + ingY * gestionPct;
            double hipY = cuotaMensual * 12;

            // Amortización aproximada de la hipoteca (interés vs amortización)
            double intYear = 0;
            double amortYear = 0;
            if (hipoteca && saldoHipoteca > 0) {
                double i = tipoInteres / 12;
                double tempSaldo = saldoHipoteca;
                for (int m = 0; m < 12 && tempSaldo > 0.01; m++) {
                    double interMes = tempSaldo * i;
                    double amortMes = cuotaMensual - interMes;
                    if (amortMes > tempSaldo) amortMes = tempSaldo;
                    intYear += interMes;
                    amortYear += amortMes;
                    tempSaldo -= amortMes;
                }
                saldoHipoteca = tempSaldo;
            }

            double cashflowY = ingY - gastosY - hipY;
            cashflowAcumulado += cashflowY;
            double patrimonio = valorPropiedad - saldoHipoteca;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("year", year);
            row.put("rentaMensual", round(rentaActual));
            row.put("ingresos", round(ingY));
            row.put("gastos", round(gastosY));
            row.put("hipoteca", round(hipY));
            row.put("intereses", round(intYear));
            row.put("amortizacionHip", round(amortYear));
            row.put("cashflow", round(cashflowY));
            row.put("cashflowAcumulado", round(cashflowAcumulado));
            row.put("valorPropiedad", round(valorPropiedad));
            row.put("saldoHipoteca", round(saldoHipoteca));
            row.put("patrimonioNeto", round(patrimonio));
            proyeccion.add(row);
        }

        // TIR aproximada (método simple)
        double tir = calcularTIR(inversionInicial, proyeccion, valorPropiedad, saldoHipoteca);

        // ROI total (incluye revalorización)
        double valorFinal = valorPropiedad - saldoHipoteca;
        double roiTotal = inversionInicial > 0 ?
                ((cashflowAcumulado + valorFinal - inversionInicial) / inversionInicial) * 100 : 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("inversionTotal", round(inversionTotal));
        result.put("inversionInicial", round(inversionInicial));
        result.put("cuotaMensualHipoteca", round(cuotaMensual));
        result.put("ingresosBrutos", round(ingresosBrutos));
        result.put("ingresosNetos", round(ingresosNetos));
        result.put("gastosAnuales", round(gastosAnuales));
        result.put("flujoNetoAnual", round(flujoNeto));
        result.put("rentabilidadBruta", round(rentBruta));
        result.put("rentabilidadNeta", round(rentNeta));
        result.put("cashOnCash", round(cashOnCash));
        result.put("tir", round(tir));
        result.put("roiTotal", round(roiTotal));
        result.put("cashflowAcumulado", round(cashflowAcumulado));
        result.put("patrimonioFinal", round(valorFinal));
        result.put("proyeccion", proyeccion);

        // Semáforo de evaluación
        String evaluacion;
        String color;
        if (rentNeta >= 7) { evaluacion = "Excelente inversión"; color = "emerald"; }
        else if (rentNeta >= 5) { evaluacion = "Buena inversión"; color = "green"; }
        else if (rentNeta >= 3) { evaluacion = "Inversión aceptable"; color = "amber"; }
        else if (rentNeta >= 0) { evaluacion = "Rentabilidad baja"; color = "orange"; }
        else { evaluacion = "Inversión en pérdidas"; color = "red"; }
        result.put("evaluacion", evaluacion);
        result.put("evaluacionColor", color);

        return result;
    }

    @PostMapping("/guardar")
    @ResponseBody
    public Map<String, Object> save(@RequestBody Map<String, Object> body, HttpSession session) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return Map.of("ok", false);

        if (pd(body.get("precioCompra")) <= 0 || pd(body.get("rentaMensual")) <= 0) {
            return Map.of("ok", false, "error", "Faltan el precio de compra o la renta");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        // Solo un inmueble de esta inmobiliaria
        String propiedadId = body.get("propiedadId") == null ? "" : String.valueOf(body.get("propiedadId"));
        if (!propiedadId.isBlank() && !supabase.selectOwned("propiedades", propiedadId, uid).isEmpty()) {
            data.put("propiedad_id", propiedadId);
        }
        String nombre = String.valueOf(body.getOrDefault("nombre", "")).trim();
        data.put("nombre", nombre.isEmpty() ? "Análisis" : nombre.substring(0, Math.min(120, nombre.length())));
        data.put("cuota_mensual", body.get("cuotaMensual"));
        data.put("precio_compra", body.get("precioCompra"));
        data.put("gastos_compra", body.get("gastosCompra"));
        data.put("reformas", body.get("reformas"));
        data.put("mobiliario", body.get("mobiliario"));
        data.put("hipoteca", body.get("hipoteca"));
        data.put("capital_hipoteca", body.get("capitalHipoteca"));
        data.put("tipo_interes", body.get("tipoInteres"));
        data.put("plazo_anos", body.get("plazoAnos"));
        data.put("renta_mensual", body.get("rentaMensual"));
        data.put("incremento_renta_anual", body.get("incrementoRentaAnual"));
        data.put("ibi", body.get("ibi"));
        data.put("comunidad", body.get("comunidad"));
        data.put("seguro_hogar", body.get("seguroHogar"));
        data.put("seguro_impago", body.get("seguroImpago"));
        data.put("mantenimiento", body.get("mantenimiento"));
        data.put("gestion", body.get("gestion"));
        data.put("otros_gastos", body.get("otrosGastos"));
        data.put("tasa_vacancia", body.get("tasaVacancia"));
        data.put("revalorizacion_anual", body.get("revalorizacionAnual"));
        data.put("horizonte_anos", body.get("horizonteAnos"));
        // Columnas NUMERIC(5,2): por encima de ±999,99 Postgres rechaza la fila entera
        data.put("rentabilidad_bruta", acotar(body.get("rentabilidadBruta")));
        data.put("rentabilidad_neta", acotar(body.get("rentabilidadNeta")));
        data.put("cash_on_cash", acotar(body.get("cashOnCash")));
        data.put("tir_estimada", acotar(body.get("tir")));
        data.put("created_at", OffsetDateTime.now().toString());
        data.put("updated_at", OffsetDateTime.now().toString());

        Map<String, Object> saved = supabase.insert("analisis_rentabilidad", data);
        if (saved == null || saved.get("id") == null) return Map.of("ok", false, "error", "No se pudo guardar");
        return Map.of("ok", true, "id", saved.get("id"));
    }

    public static double acotar(Object v) {
        return Math.max(-999.99, Math.min(999.99, round(pd(v))));
    }

    /** TIR simplificada por bisección sobre el flujo de caja anual + valor residual. */
    private double calcularTIR(double inversion, List<Map<String, Object>> flujos, double valorFinal, double saldoHip) {
        if (inversion <= 0 || flujos.isEmpty()) return 0;
        double low = -0.5, high = 1.0;
        double valorResidual = valorFinal - saldoHip;

        for (int iter = 0; iter < 50; iter++) {
            double mid = (low + high) / 2;
            double vpn = -inversion;
            for (int y = 0; y < flujos.size(); y++) {
                double cf = ((Number) flujos.get(y).get("cashflow")).doubleValue();
                if (y == flujos.size() - 1) cf += valorResidual;  // venta hipotética al final
                vpn += cf / Math.pow(1 + mid, y + 1);
            }
            if (Math.abs(vpn) < 0.5) return mid * 100;
            if (vpn > 0) low = mid;
            else high = mid;
        }
        return ((low + high) / 2) * 100;
    }

    private static double pd(Object o) { if (o == null) return 0; try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
