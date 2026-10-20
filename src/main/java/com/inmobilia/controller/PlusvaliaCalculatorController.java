package com.inmobilia.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calculadora de plusvalía municipal (IIVTNU) tras STC 182/2021 / RDL 26/2021.
 * Dos métodos: real y objetivo. El contribuyente elige el más bajo.
 */
@Controller
public class PlusvaliaCalculatorController {

    @GetMapping("/calculadora-plusvalia")
    public String form() { return "plusvalia_calculator"; }

    @PostMapping("/calculadora-plusvalia")
    @ResponseBody
    public Map<String, Object> calc(@RequestBody Map<String, Object> body) {
        try {
            double precioCompra = parseD(body.get("precioCompra"));
            double precioVenta = parseD(body.get("precioVenta"));
            String fechaCompraStr = String.valueOf(body.getOrDefault("fechaCompra", ""));
            String fechaVentaStr = String.valueOf(body.getOrDefault("fechaVenta", ""));
            double valorCatastralSuelo = parseD(body.get("valorCatastralSuelo"));
            double tipoMunicipal = parseD(body.get("tipoMunicipal"));     // % bonificación municipio
            if (tipoMunicipal == 0) tipoMunicipal = 30.0;                  // tipo máximo legal por defecto

            LocalDate fechaCompra = LocalDate.parse(fechaCompraStr);
            LocalDate fechaVenta = LocalDate.parse(fechaVentaStr);
            long years = ChronoUnit.YEARS.between(fechaCompra, fechaVenta);
            if (years < 1) {
                return Map.of("ok", false, "error", "Menos de 1 año: no procede plusvalía");
            }

            // ─── Método REAL ───
            double plusvaliaReal = precioVenta - precioCompra;
            if (plusvaliaReal <= 0) {
                return Map.of(
                    "ok", true,
                    "noProcede", true,
                    "msg", "No hay incremento de valor → no procede el impuesto (STC 182/2021)",
                    "plusvaliaReal", plusvaliaReal
                );
            }
            // El % se aplica sobre la plusvalía proporcional al valor catastral del suelo
            // Simplificación: plusvalía atribuible al suelo = (vcSuelo / vcTotal) * plusvalíaReal
            // Asumimos vcSuelo es el % del catastro (usuario lo introduce)
            double baseImpReal = plusvaliaReal * (valorCatastralSuelo / Math.max(precioVenta, 1));
            double cuotaReal = baseImpReal * tipoMunicipal / 100;

            // ─── Método OBJETIVO (coeficientes RDL 26/2021) ───
            double coef = coefByYears((int) years);
            double baseImpObjetivo = valorCatastralSuelo * coef;
            double cuotaObjetivo = baseImpObjetivo * tipoMunicipal / 100;

            // El contribuyente elige el menor
            double cuotaFinal = Math.min(cuotaReal, cuotaObjetivo);
            String metodoElegido = cuotaReal <= cuotaObjetivo ? "REAL" : "OBJETIVO";

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("years", years);
            r.put("plusvaliaReal", plusvaliaReal);
            r.put("baseImpReal", baseImpReal);
            r.put("cuotaReal", cuotaReal);
            r.put("coefObjetivo", coef);
            r.put("baseImpObjetivo", baseImpObjetivo);
            r.put("cuotaObjetivo", cuotaObjetivo);
            r.put("cuotaFinal", cuotaFinal);
            r.put("metodoElegido", metodoElegido);
            r.put("ahorro", Math.abs(cuotaReal - cuotaObjetivo));
            return r;
        } catch (Exception e) {
            return Map.of("ok", false, "error", "Datos inválidos: " + e.getMessage());
        }
    }

    /** Coeficientes oficiales RDL 26/2021 según años transcurridos. */
    private static double coefByYears(int years) {
        if (years <= 1) return 0.14;
        if (years == 2) return 0.13;
        if (years == 3) return 0.15;
        if (years == 4) return 0.17;
        if (years == 5) return 0.17;
        if (years == 6) return 0.16;
        if (years == 7) return 0.12;
        if (years == 8) return 0.10;
        if (years == 9) return 0.09;
        if (years == 10) return 0.08;
        if (years == 11) return 0.08;
        if (years == 12) return 0.08;
        if (years == 13) return 0.08;
        if (years == 14) return 0.10;
        if (years == 15) return 0.12;
        if (years == 16) return 0.16;
        if (years == 17) return 0.20;
        if (years == 18) return 0.26;
        if (years == 19) return 0.36;
        return 0.45;  // 20 años o más
    }

    private static double parseD(Object o) { if (o == null) return 0; try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; } }
}
