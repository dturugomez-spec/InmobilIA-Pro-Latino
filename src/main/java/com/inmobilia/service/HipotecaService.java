package com.inmobilia.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 🏦 Simulador de hipoteca / capacidad de compra.
 *
 * Calcula la cuota mensual (sistema francés) y la capacidad máxima de compra de
 * forma honesta, aplicando las dos restricciones reales del mercado español:
 *   1) Esfuerzo: la cuota no debería superar ~35% de los ingresos.
 *   2) Entrada: el banco financia hasta ~80% del precio, así que el ahorro debe
 *      cubrir el 20% de entrada + ~12% de gastos (≈30% del precio).
 * Toma la más restrictiva de las dos. Sin asesoramiento financiero vinculante.
 */
@Service
@Slf4j
public class HipotecaService {

    private static final double DTI_MAX = 0.35;        // esfuerzo máximo recomendado
    private static final double FINANCIACION_MAX = 0.80; // % del precio que financia el banco
    private static final double GASTOS_PCT = 0.12;     // impuestos + notaría + gestoría aprox
    private static final double INTERES_DEFAULT = 3.0; // % anual por defecto
    private static final int PLAZO_DEFAULT = 30;       // años por defecto

    public Map<String, Object> calcular(Map<String, String> in) {
        double precio = pd(in.get("precio_objetivo"));
        double ahorro = pd(in.get("ahorro"));
        double ingresos = pd(in.get("ingresos_mensuales"));
        int plazo = (int) pd(in.get("plazo_anos"));
        if (plazo <= 0) plazo = PLAZO_DEFAULT;
        double interes = pd(in.get("tipo_interes"));
        if (interes <= 0) interes = INTERES_DEFAULT;

        double i = interes / 100.0 / 12.0;
        int n = plazo * 12;

        // Préstamo para el precio objetivo: precio - (ahorro - gastos)
        double gastos = precio * GASTOS_PCT;
        double entradaDisponible = Math.max(0, ahorro - gastos);
        double prestamo = Math.max(0, precio - entradaDisponible);
        // El banco no financia más del 80% del precio
        double prestamoMaxBanco = precio * FINANCIACION_MAX;
        if (prestamo > prestamoMaxBanco) prestamo = prestamoMaxBanco;

        double cuota = cuota(prestamo, i, n);
        double esfuerzo = ingresos > 0 ? (cuota / ingresos) * 100.0 : 0;

        // Capacidad máxima de compra
        double capacidadPorIngresos = capacidadPorIngresos(ingresos, i, n, ahorro);
        double capacidadPorAhorro = ahorro > 0 ? ahorro / (1 - FINANCIACION_MAX + GASTOS_PCT) : 0; // ahorro cubre 20%+12%=32%
        double capacidad = Math.min(
                capacidadPorIngresos > 0 ? capacidadPorIngresos : Double.MAX_VALUE,
                capacidadPorAhorro > 0 ? capacidadPorAhorro : Double.MAX_VALUE);
        if (capacidad == Double.MAX_VALUE) capacidad = 0;

        boolean viable = ingresos > 0 && esfuerzo > 0 && esfuerzo <= DTI_MAX * 100 + 0.5
                && entradaDisponible + prestamo >= precio - 1;

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("cuota_estimada", redondea(cuota, 1));
        r.put("capital_prestamo", redondea(prestamo, 100));
        r.put("capacidad_max", redondea(capacidad, 1000));
        r.put("esfuerzo_pct", Math.round(esfuerzo * 10) / 10.0);
        r.put("entrada_necesaria", redondea(precio * (1 - FINANCIACION_MAX) + gastos, 100));
        r.put("plazo_anos", plazo);
        r.put("tipo_interes", interes);
        r.put("viable", viable);
        r.put("total_intereses", redondea(cuota * n - prestamo, 100));
        return r;
    }

    /** Cuota mensual (sistema francés). */
    private double cuota(double capital, double i, int n) {
        if (capital <= 0) return 0;
        if (i <= 0) return capital / n;
        return capital * i / (1 - Math.pow(1 + i, -n));
    }

    /** Precio máximo financiable según ingresos (cuota ≤ 35% ingresos). */
    private double capacidadPorIngresos(double ingresos, double i, int n, double ahorro) {
        if (ingresos <= 0) return 0;
        double cuotaMax = ingresos * DTI_MAX;
        double prestamoMax = i <= 0 ? cuotaMax * n : cuotaMax * (1 - Math.pow(1 + i, -n)) / i;
        // precio = prestamo / 0.8 (el banco financia 80%), validado luego por ahorro
        return prestamoMax / FINANCIACION_MAX;
    }

    private static double pd(Object o) { try { return o == null ? 0 : Double.parseDouble(String.valueOf(o).replace(",", ".")); } catch (Exception e) { return 0; } }
    private static long redondea(double v, double a) { return Math.round(v / a) * (long) a; }
}
