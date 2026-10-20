package com.inmobilia.service;

import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Calculadoras fiscales: ITP, AJD, Plusvalia Municipal.
 * Tramos 2026. Port de calc_itp, calc_ajd, calc_plusvalia_municipal.
 */
@Service
public class TaxService {

    public static final Map<String, Double> ITP_CCAA_2026 = Map.ofEntries(
            Map.entry("Andalucia", 0.07),
            Map.entry("Aragon", 0.08),
            Map.entry("Asturias", 0.08),
            Map.entry("Baleares", 0.08),
            Map.entry("Canarias", 0.065),
            Map.entry("Cantabria", 0.09),
            Map.entry("Castilla-La Mancha", 0.09),
            Map.entry("Castilla y Leon", 0.08),
            Map.entry("Cataluna", 0.10),
            Map.entry("Ceuta", 0.06),
            Map.entry("Comunidad de Madrid", 0.06),
            Map.entry("Comunidad Valenciana", 0.10),
            Map.entry("Extremadura", 0.08),
            Map.entry("Galicia", 0.09),
            Map.entry("La Rioja", 0.07),
            Map.entry("Melilla", 0.06),
            Map.entry("Murcia", 0.08),
            Map.entry("Navarra", 0.06),
            Map.entry("Pais Vasco", 0.04));

    public static final Map<String, Double> AJD_CCAA_2026 = Map.ofEntries(
            Map.entry("Andalucia", 0.012),
            Map.entry("Aragon", 0.015),
            Map.entry("Asturias", 0.012),
            Map.entry("Baleares", 0.012),
            Map.entry("Canarias", 0.01),
            Map.entry("Cantabria", 0.015),
            Map.entry("Castilla-La Mancha", 0.015),
            Map.entry("Castilla y Leon", 0.015),
            Map.entry("Cataluna", 0.015),
            Map.entry("Ceuta", 0.005),
            Map.entry("Comunidad de Madrid", 0.0075),
            Map.entry("Comunidad Valenciana", 0.015),
            Map.entry("Extremadura", 0.015),
            Map.entry("Galicia", 0.015),
            Map.entry("La Rioja", 0.01),
            Map.entry("Melilla", 0.005),
            Map.entry("Murcia", 0.02),
            Map.entry("Navarra", 0.005),
            Map.entry("Pais Vasco", 0.00));

    /** Coeficientes objetivos plusvalia municipal (post STC 182/2021). */
    public static final Map<Integer, Double> COEF_PLUSVALIA = Map.ofEntries(
            Map.entry(1, 0.15), Map.entry(2, 0.15), Map.entry(3, 0.16),
            Map.entry(4, 0.17), Map.entry(5, 0.17), Map.entry(6, 0.16),
            Map.entry(7, 0.15), Map.entry(8, 0.15), Map.entry(9, 0.14),
            Map.entry(10, 0.15), Map.entry(11, 0.17), Map.entry(12, 0.18),
            Map.entry(13, 0.19), Map.entry(14, 0.19), Map.entry(15, 0.20),
            Map.entry(16, 0.22), Map.entry(17, 0.25), Map.entry(18, 0.28),
            Map.entry(19, 0.32), Map.entry(20, 0.45));

    public record ItpResult(double importe, double tipo) {}
    public record AjdResult(double importe, double tipo) {}
    public record PlusvaliaResult(double cuota, double base, double coeficiente) {}

    public ItpResult calcItp(String ccaa, double precio, boolean viviendaHabitual, boolean joven) {
        double tipo = ITP_CCAA_2026.getOrDefault(ccaa, 0.08);
        if (viviendaHabitual && ("Comunidad de Madrid".equals(ccaa) || "Andalucia".equals(ccaa))) {
            tipo = Math.max(tipo - 0.01, 0.04);
        }
        if (joven && ("Comunidad de Madrid".equals(ccaa) || "Cataluna".equals(ccaa) || "Andalucia".equals(ccaa))) {
            tipo = Math.max(tipo - 0.02, 0.03);
        }
        return new ItpResult(round2(precio * tipo), tipo);
    }

    public AjdResult calcAjd(String ccaa, double importeHipoteca) {
        double tipo = AJD_CCAA_2026.getOrDefault(ccaa, 0.015);
        return new AjdResult(round2(importeHipoteca * tipo), tipo);
    }

    public PlusvaliaResult calcPlusvaliaMunicipal(double valorCatastralSuelo, int aniosTenencia,
                                                   double tipoMunicipal, Double coefReal) {
        int anios = Math.max(1, Math.min(aniosTenencia, 20));
        double coef = coefReal != null ? coefReal : COEF_PLUSVALIA.get(anios);
        double base = valorCatastralSuelo * coef;
        double cuota = base * tipoMunicipal;
        return new PlusvaliaResult(round2(cuota), round2(base), coef);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
