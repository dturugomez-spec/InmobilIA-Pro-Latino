package com.inmobilia.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calculadora IRPF venta de vivienda.
 * Aplica tramos del ahorro 2026 y exenciones:
 *  - Reinversión en vivienda habitual (art. 38 LIRPF)
 *  - Mayores de 65 años (vivienda habitual)
 *  - Mayores de 65 años (rentas vitalicias, máx. 240.000€)
 *  - Dación en pago (vivienda habitual)
 */
@Controller
public class IrpfSaleController {

    // Tramos IRPF base del ahorro (2026 — incluido el nuevo tramo de >300k al 30%)
    private static final double[][] TRAMOS = {
            {6000,    0.19},
            {50000,   0.21},
            {200000,  0.23},
            {300000,  0.27},
            {Double.MAX_VALUE, 0.30}
    };

    @GetMapping("/calculadora-irpf-venta")
    public String form() { return "irpf_sale_calculator"; }

    @PostMapping("/calculadora-irpf-venta")
    @ResponseBody
    public Map<String, Object> calc(@RequestBody Map<String, Object> body) {
        double precioVenta = pd(body.get("precioVenta"));
        double gastosVenta = pd(body.get("gastosVenta"));
        double precioCompra = pd(body.get("precioCompra"));
        double gastosCompra = pd(body.get("gastosCompra"));
        double inversiones = pd(body.get("inversiones")); // mejoras
        boolean esHabitual = (boolean) body.getOrDefault("esHabitual", false);
        boolean reinvertir = (boolean) body.getOrDefault("reinvertir", false);
        double importeReinversion = pd(body.get("importeReinversion"));
        boolean mayor65 = (boolean) body.getOrDefault("mayor65", false);
        boolean rentaVitalicia = (boolean) body.getOrDefault("rentaVitalicia", false);
        double importeRentaVit = pd(body.get("importeRentaVit"));

        // Valor de transmisión y adquisición
        double vt = precioVenta - gastosVenta;
        double va = precioCompra + gastosCompra + inversiones;
        double ganancia = vt - va;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valorTransmision", round(vt));
        result.put("valorAdquisicion", round(va));
        result.put("gananciaPatrimonial", round(ganancia));

        if (ganancia <= 0) {
            result.put("ok", true);
            result.put("gananciaSujeta", 0);
            result.put("irpf", 0);
            result.put("neto", round(precioVenta - gastosVenta));
            result.put("mensaje", "Pérdida patrimonial — no tributa (puede compensarse en 4 años).");
            return result;
        }

        // Exenciones
        double gananciaExenta = 0;
        StringBuilder exenciones = new StringBuilder();

        if (esHabitual && mayor65) {
            gananciaExenta = ganancia;
            exenciones.append("Exención total por >65 años + vivienda habitual. ");
        } else if (esHabitual && reinvertir && importeReinversion > 0) {
            // Exención proporcional a la reinversión
            double proporcion = Math.min(1.0, importeReinversion / precioVenta);
            gananciaExenta = ganancia * proporcion;
            exenciones.append(String.format("Exención por reinversión: %.1f%% (%.2f€). ", proporcion * 100, gananciaExenta));
        } else if (mayor65 && rentaVitalicia && importeRentaVit > 0) {
            double max = 240000;
            double aplicable = Math.min(importeRentaVit, max);
            double proporcion = Math.min(1.0, aplicable / precioVenta);
            gananciaExenta = ganancia * proporcion;
            exenciones.append(String.format("Exención >65 años + renta vitalicia (máx. 240.000€): %.2f€. ", gananciaExenta));
        }

        double gananciaSujeta = ganancia - gananciaExenta;
        double irpf = calcularIrpfAhorro(gananciaSujeta);
        double tipoEfectivo = gananciaSujeta > 0 ? (irpf / gananciaSujeta * 100) : 0;

        result.put("ok", true);
        result.put("gananciaExenta", round(gananciaExenta));
        result.put("gananciaSujeta", round(gananciaSujeta));
        result.put("irpf", round(irpf));
        result.put("tipoEfectivo", round(tipoEfectivo));
        result.put("neto", round(precioVenta - gastosVenta - irpf));
        result.put("exenciones", exenciones.toString().trim());
        result.put("tramosAplicados", desgloseTramos(gananciaSujeta));
        return result;
    }

    private double calcularIrpfAhorro(double base) {
        if (base <= 0) return 0;
        double impuesto = 0;
        double restante = base;
        double previo = 0;
        for (double[] tramo : TRAMOS) {
            double limite = tramo[0];
            double tipo = tramo[1];
            double anchura = limite - previo;
            if (restante <= anchura) {
                impuesto += restante * tipo;
                return impuesto;
            } else {
                impuesto += anchura * tipo;
                restante -= anchura;
                previo = limite;
            }
        }
        return impuesto;
    }

    private java.util.List<Map<String, Object>> desgloseTramos(double base) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        double restante = base;
        double previo = 0;
        for (double[] t : TRAMOS) {
            if (restante <= 0) break;
            double limite = t[0];
            double tipo = t[1];
            double anchura = limite - previo;
            double aplicado = Math.min(restante, anchura);
            double cuota = aplicado * tipo;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("desde", previo);
            row.put("hasta", limite == Double.MAX_VALUE ? "∞" : limite);
            row.put("tipo", round(tipo * 100));
            row.put("baseEnTramo", round(aplicado));
            row.put("cuota", round(cuota));
            out.add(row);
            restante -= aplicado;
            previo = limite;
        }
        return out;
    }

    private static double pd(Object o) { if (o == null) return 0; try { return Double.parseDouble(String.valueOf(o)); } catch(Exception e) { return 0; } }
    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
