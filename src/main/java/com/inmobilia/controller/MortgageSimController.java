package com.inmobilia.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Simulador hipoteca con tabla francesa + amortización anticipada.
 */
@Controller
public class MortgageSimController {

    @GetMapping("/calculadora-hipoteca")
    public String form() { return "mortgage_simulator"; }

    @PostMapping("/calculadora-hipoteca")
    @ResponseBody
    public Map<String, Object> simulate(@RequestBody Map<String, Object> body) {
        double capital = parseD(body.get("capital"));
        double tipoAnual = parseD(body.get("tipoAnual")) / 100.0;
        int plazoAnos = (int) parseD(body.get("plazoAnos"));
        double amortAnticipada = parseD(body.get("amortAnticipada"));
        int mesAmort = (int) parseD(body.get("mesAmort"));
        String tipoAmort = String.valueOf(body.getOrDefault("tipoAmort", "cuota")); // "cuota" o "plazo"

        if (capital <= 0 || plazoAnos <= 0 || tipoAnual < 0) {
            return Map.of("ok", false, "error", "Datos inválidos");
        }

        int n = plazoAnos * 12;
        double i = tipoAnual / 12;
        // Cuota francesa: c = C * i / (1 - (1+i)^-n)
        double cuota = i == 0 ? capital / n : capital * i / (1 - Math.pow(1 + i, -n));

        List<Map<String, Object>> rows = new ArrayList<>();
        double saldo = capital;
        double totalIntereses = 0;
        double cuotaActual = cuota;
        int plazosRestantes = n;

        for (int mes = 1; mes <= n && saldo > 0.01; mes++) {
            double interesMes = saldo * i;
            double amortMes = cuotaActual - interesMes;
            if (amortMes > saldo) amortMes = saldo;
            saldo -= amortMes;
            totalIntereses += interesMes;

            // Aplicar amortización anticipada
            boolean amortizado = false;
            if (mes == mesAmort && amortAnticipada > 0) {
                double amortReal = Math.min(amortAnticipada, saldo);
                saldo -= amortReal;
                amortizado = true;

                // Recalcular según opción
                if ("cuota".equals(tipoAmort)) {
                    plazosRestantes = n - mes;
                    cuotaActual = i == 0 ? saldo / plazosRestantes : saldo * i / (1 - Math.pow(1 + i, -plazosRestantes));
                }
                // Si tipoAmort == "plazo": mantenemos cuota, se reduce el plazo automáticamente
            }

            if (mes % 12 == 0 || mes == n || mes <= 24 || amortizado) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("mes", mes);
                r.put("year", (mes - 1) / 12 + 1);
                r.put("cuota", round(cuotaActual));
                r.put("interes", round(interesMes));
                r.put("amortizacion", round(amortMes));
                r.put("saldoPendiente", round(saldo));
                r.put("amortAnticipada", amortizado ? amortAnticipada : 0);
                rows.add(r);
            }
        }

        double totalPagado = capital + totalIntereses;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("cuotaMensualInicial", round(cuota));
        result.put("totalIntereses", round(totalIntereses));
        result.put("totalPagado", round(totalPagado));
        result.put("ratio", round(totalIntereses / capital * 100));
        result.put("rows", rows);
        return result;
    }

    private static double parseD(Object o) { if (o == null) return 0; try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
