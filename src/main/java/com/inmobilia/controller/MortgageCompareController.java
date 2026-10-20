package com.inmobilia.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Comparador de hipotecas: fija vs variable vs mixta.
 * Calcula el coste total de cada modalidad en distintos escenarios de tasa de interés.
 */
@Controller
public class MortgageCompareController {

    @GetMapping("/comparador-hipotecas")
    public String form() { return "mortgage_compare"; }

    @PostMapping("/comparador-hipotecas")
    @ResponseBody
    public Map<String, Object> compare(@RequestBody Map<String, Object> body) {
        double capital = pd(body.get("capital"));
        int plazoAnos = (int) pd(body.get("plazoAnos"));

        // Fija
        double tipoFijo = pd(body.get("tipoFijo")) / 100.0;

        // Variable (diferencial + euribor)
        double diferencialVar = pd(body.get("diferencialVar")) / 100.0;
        double euriborActual = pd(body.get("euriborActual")) / 100.0;

        // Mixta: x años fijo + resto variable
        double tipoMixtoFijo = pd(body.get("tipoMixtoFijo")) / 100.0;
        int anosFijoMixto = (int) pd(body.get("anosFijoMixto"));
        double diferencialMixto = pd(body.get("diferencialMixto")) / 100.0;

        // Escenarios de tasa de interés (3 casos)
        double euriborOptimista = pd(body.get("euriborOptimista")) / 100.0;
        double euriborMedio = pd(body.get("euriborMedio")) / 100.0;
        double euriborPesimista = pd(body.get("euriborPesimista")) / 100.0;

        if (capital <= 0 || plazoAnos <= 0) {
            return Map.of("ok", false, "error", "Datos inválidos");
        }

        int n = plazoAnos * 12;

        // FIJA
        Map<String, Object> fija = simulFija(capital, tipoFijo, n);

        // VARIABLE — 3 escenarios
        Map<String, Object> varOpt = simulVar(capital, diferencialVar, euriborActual, euriborOptimista, n);
        Map<String, Object> varMed = simulVar(capital, diferencialVar, euriborActual, euriborMedio, n);
        Map<String, Object> varPes = simulVar(capital, diferencialVar, euriborActual, euriborPesimista, n);

        // MIXTA — 3 escenarios
        Map<String, Object> mixOpt = simulMixta(capital, tipoMixtoFijo, anosFijoMixto, diferencialMixto, euriborOptimista, n);
        Map<String, Object> mixMed = simulMixta(capital, tipoMixtoFijo, anosFijoMixto, diferencialMixto, euriborMedio, n);
        Map<String, Object> mixPes = simulMixta(capital, tipoMixtoFijo, anosFijoMixto, diferencialMixto, euriborPesimista, n);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("capital", capital);
        result.put("plazoAnos", plazoAnos);
        result.put("fija", fija);
        result.put("variable", Map.of("optimista", varOpt, "medio", varMed, "pesimista", varPes));
        result.put("mixta", Map.of("optimista", mixOpt, "medio", mixMed, "pesimista", mixPes));

        // Ranking por coste medio
        double cFija = (double) fija.get("totalPagado");
        double cVar = (double) varMed.get("totalPagado");
        double cMix = (double) mixMed.get("totalPagado");
        String mejor;
        if (cFija <= cVar && cFija <= cMix) mejor = "fija";
        else if (cMix <= cVar) mejor = "mixta";
        else mejor = "variable";
        result.put("mejorEscenarioMedio", mejor);

        return result;
    }

    private Map<String, Object> simulFija(double C, double tipoAnual, int n) {
        double i = tipoAnual / 12;
        double cuota = i == 0 ? C / n : C * i / (1 - Math.pow(1 + i, -n));
        double total = cuota * n;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("cuotaMensual", round(cuota));
        r.put("totalIntereses", round(total - C));
        r.put("totalPagado", round(total));
        r.put("tipoMedio", round(tipoAnual * 100));
        return r;
    }

    private Map<String, Object> simulVar(double C, double diferencial, double euriborActual, double euriborFuturo, int n) {
        // Primer año al euribor actual + diferencial. Luego revisión anual al euriborFuturo.
        double saldo = C;
        double total = 0;
        double tipo1 = euriborActual + diferencial;
        double i1 = tipo1 / 12;
        double cuota1 = i1 == 0 ? saldo / n : saldo * i1 / (1 - Math.pow(1 + i1, -n));
        for (int m = 1; m <= 12 && saldo > 0.01; m++) {
            double inter = saldo * i1;
            double amort = cuota1 - inter;
            if (amort > saldo) amort = saldo;
            saldo -= amort;
            total += cuota1;
        }
        // Resto del préstamo a tipoFuturo
        double tipo2 = euriborFuturo + diferencial;
        double i2 = tipo2 / 12;
        int rest = n - 12;
        if (rest > 0 && saldo > 0.01) {
            double cuota2 = i2 == 0 ? saldo / rest : saldo * i2 / (1 - Math.pow(1 + i2, -rest));
            for (int m = 1; m <= rest && saldo > 0.01; m++) {
                double inter = saldo * i2;
                double amort = cuota2 - inter;
                if (amort > saldo) amort = saldo;
                saldo -= amort;
                total += cuota2;
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("cuotaInicial", round(cuota1));
        r.put("totalPagado", round(total));
        r.put("totalIntereses", round(total - C));
        r.put("tipoAplicado", round((euriborFuturo + diferencial) * 100));
        return r;
    }

    private Map<String, Object> simulMixta(double C, double tipoFijo, int anosFijo, double diferencial, double euriborFuturo, int n) {
        double saldo = C;
        double total = 0;
        int mesesFijo = Math.min(anosFijo * 12, n);
        double i1 = tipoFijo / 12;
        double cuota1 = i1 == 0 ? saldo / n : saldo * i1 / (1 - Math.pow(1 + i1, -n));
        for (int m = 1; m <= mesesFijo && saldo > 0.01; m++) {
            double inter = saldo * i1;
            double amort = cuota1 - inter;
            if (amort > saldo) amort = saldo;
            saldo -= amort;
            total += cuota1;
        }
        int rest = n - mesesFijo;
        double tipo2 = euriborFuturo + diferencial;
        double i2 = tipo2 / 12;
        if (rest > 0 && saldo > 0.01) {
            double cuota2 = i2 == 0 ? saldo / rest : saldo * i2 / (1 - Math.pow(1 + i2, -rest));
            for (int m = 1; m <= rest && saldo > 0.01; m++) {
                double inter = saldo * i2;
                double amort = cuota2 - inter;
                if (amort > saldo) amort = saldo;
                saldo -= amort;
                total += cuota2;
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("cuotaFija", round(cuota1));
        r.put("totalPagado", round(total));
        r.put("totalIntereses", round(total - C));
        r.put("tipoVariableAplicado", round((euriborFuturo + diferencial) * 100));
        return r;
    }

    private static double pd(Object o) { if (o == null) return 0; try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
