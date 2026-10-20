package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stress Test agregado de toda la cartera (no de un solo activo).
 * Aplica simultaneamente shocks macro y operativos sobre el portfolio:
 *  - Subida de la tasa de interés de los créditos (+100/+200/+300 pb)
 *  - Caida de rentas (vacancia + morosidad)
 *  - Aumento de OPEX (inflacion +5/+10%)
 *
 * Calcula DSCR agregado, NOI, cashflow neto y propiedades en zona roja.
 * Esta vision agregada de cartera no la ofrecen Stessa, Buildium ni Argus.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PortfolioStressService {

    public record Scenario(
        String name,
        String descripcion,
        double euriborDeltaPp,
        double vacanciaPctExtra,
        double opexInflacion,
        double cashflowAnual,
        double noiAnual,
        double dscrAgregado,
        int propsEnRojo,
        int propsTotal,
        String veredicto,
        String veredictoColor
    ) {}

    public record StressReport(
        Scenario base,
        List<Scenario> escenarios,
        double rentasAnualesBase,
        double opexAnualBase,
        double deudaAnualBase,
        double euriborActual,
        int nProps,
        List<Map<String, Object>> propiedadesDetalle,
        String resumenEjecutivo
    ) {}

    /**
     * Ejecuta el stress test completo.
     * @param props lista de propiedades del usuario.
     * @param contratos contratos activos.
     * @param euriborActual tasa de referencia actual de los créditos (% anual).
     */
    public StressReport run(List<Map<String, Object>> props,
                             List<Map<String, Object>> contratos,
                             double euriborActual) {
        if (props == null) props = List.of();
        if (contratos == null) contratos = List.of();

        // --- 1. Calcular base (acepta nombres EN existentes y ES opcionales nuevos) ---
        double rentasAnuales = 0, opexAnual = 0, deudaAnual = 0;
        Map<String, Double> rentaPorProp = new LinkedHashMap<>();
        for (Map<String, Object> c : contratos) {
            String pid = firstNonBlank(
                String.valueOf(c.get("property_id")),
                String.valueOf(c.get("propiedad_id")),
                String.valueOf(c.get("property_name"))
            );
            double r = pickNum(c, "rent", "renta_mensual", "rent_amount") * 12;
            rentasAnuales += r;
            rentaPorProp.merge(pid, r, Double::sum);
        }

        for (Map<String, Object> p : props) {
            // OPEX: si existen los granulares (ibi_anual, comunidad_mes, ...) los usa,
            // si no, recurre al agregado mensual (monthly_expenses * 12).
            double granular = num(p.get("ibi_anual"))
                            + num(p.get("comunidad_mes")) * 12
                            + num(p.get("seguro_anual"))
                            + num(p.get("mantenimiento_anual"));
            if (granular > 0) {
                opexAnual += granular;
            } else {
                opexAnual += pickNum(p, "monthly_expenses") * 12;
            }
            // Hipoteca: cuota mensual conocida (mortgage_payment) o estimada por capital*tipo
            double cuotaMes = pickNum(p, "mortgage_payment", "hipoteca_cuota_mensual");
            if (cuotaMes <= 0) {
                double capital = num(p.get("hipoteca_capital_pendiente"));
                double tipo = num(p.get("hipoteca_tipo"));
                if (capital > 0 && tipo > 0) {
                    cuotaMes = capital * (tipo / 100.0) / 12.0;
                }
            }
            deudaAnual += cuotaMes * 12;

            // Renta por propiedad si no hay contrato cruzado
            String pid = String.valueOf(p.get("id"));
            if (!rentaPorProp.containsKey(pid)) {
                double rp = pickNum(p, "rental_income", "renta_mensual") * 12;
                if (rp > 0) {
                    rentasAnuales += rp;
                    rentaPorProp.put(pid, rp);
                }
            }
        }

        // Si no hay datos, usar heuristicas razonables para que no salga 0
        if (rentasAnuales == 0 && !props.isEmpty()) {
            for (Map<String, Object> p : props) {
                rentasAnuales += pickNum(p, "purchase_price", "precio_compra") * 0.045;
            }
        }
        if (opexAnual == 0 && !props.isEmpty()) {
            opexAnual = rentasAnuales * 0.25;
        }

        Scenario base = buildScenario("Base actual",
                "Situacion economica y operativa actual sin shocks",
                0, 0, 0, rentasAnuales, opexAnual, deudaAnual,
                euriborActual, props, rentaPorProp);

        // --- 2. Escenarios adversos ---
        List<Scenario> esc = new ArrayList<>();
        esc.add(buildScenario("Tasa de interés +100 pb",
                "Subida tipos 1 punto. Hipotecas variables se encarecen.",
                1.0, 0, 0, rentasAnuales, opexAnual, deudaAnual,
                euriborActual, props, rentaPorProp));

        esc.add(buildScenario("Tasa de interés +200 pb",
                "Crisis monetaria. Tipos +2 puntos. Escenario 2022-2023.",
                2.0, 0, 0, rentasAnuales, opexAnual, deudaAnual,
                euriborActual, props, rentaPorProp));

        esc.add(buildScenario("Vacancia +10% + morosidad",
                "Recesion: ingresos -10% por desocupacion e impagos.",
                0, 10, 0, rentasAnuales, opexAnual, deudaAnual,
                euriborActual, props, rentaPorProp));

        esc.add(buildScenario("Inflacion OPEX +10%",
                "Costes operativos suben 10% (luz, mantenimiento, predial, seguros).",
                0, 0, 10, rentasAnuales, opexAnual, deudaAnual,
                euriborActual, props, rentaPorProp));

        esc.add(buildScenario("Escenario combinado severo",
                "Tipos +200 pb + vacancia +15% + opex +10%. Tormenta perfecta.",
                2.0, 15, 10, rentasAnuales, opexAnual, deudaAnual,
                euriborActual, props, rentaPorProp));

        esc.add(buildScenario("Escenario apocaliptico",
                "Crisis 2008-style: tipos +300pb + vacancia 25% + opex +15%.",
                3.0, 25, 15, rentasAnuales, opexAnual, deudaAnual,
                euriborActual, props, rentaPorProp));

        // --- 3. Detalle por propiedad en escenario base ---
        List<Map<String, Object>> detalle = new ArrayList<>();
        for (Map<String, Object> p : props) {
            String pid = String.valueOf(p.get("id"));
            double r = rentaPorProp.getOrDefault(pid, 0.0);
            if (r == 0) r = pickNum(p, "rental_income", "renta_mensual") * 12;
            if (r == 0) r = pickNum(p, "purchase_price", "precio_compra") * 0.045;
            double granOpex = num(p.get("ibi_anual")) + num(p.get("comunidad_mes")) * 12
                            + num(p.get("seguro_anual")) + num(p.get("mantenimiento_anual"));
            double opex = granOpex > 0 ? granOpex : pickNum(p, "monthly_expenses") * 12;
            double cuota = pickNum(p, "mortgage_payment", "hipoteca_cuota_mensual") * 12;
            double noi = r - opex;
            double dscr = cuota > 0 ? noi / cuota : 99;
            String estado = dscr < 1.0 ? "🔴 Critico"
                          : dscr < 1.25 ? "🟠 Frontera"
                          : "🟢 Saludable";
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nombre", String.valueOf(p.getOrDefault("nombre", "Propiedad " + pid)));
            row.put("renta", r);
            row.put("opex", opex);
            row.put("cuota", cuota);
            row.put("noi", noi);
            row.put("dscr", dscr);
            row.put("estado", estado);
            detalle.add(row);
        }

        // --- 4. Resumen ejecutivo ---
        Scenario severo = esc.get(esc.size() - 2);
        String resumen;
        if (severo.dscrAgregado() < 1.0) {
            resumen = "ALERTA CRITICA: en escenario combinado severo la cartera deja de cubrir el servicio de la deuda. "
                    + "Reduce apalancamiento o refinancia a tipo fijo.";
        } else if (severo.dscrAgregado() < 1.25) {
            resumen = "Sensibilidad alta a shocks. Tu cartera resiste el escenario base pero entra en zona frontera ante crisis severa.";
        } else if (esc.get(1).dscrAgregado() < 1.25) {
            resumen = "Resistencia moderada. Subida de tipos +200pb te lleva a zona de control. Considerar coberturas (IRS) o amortizacion parcial.";
        } else {
            resumen = "Cartera robusta. Soporta shocks severos manteniendo DSCR > 1.25. Capacidad de apalancamiento adicional disponible.";
        }

        return new StressReport(
            base, esc, rentasAnuales, opexAnual, deudaAnual,
            euriborActual, props.size(), detalle, resumen
        );
    }

    private Scenario buildScenario(String name, String desc,
                                    double euriborDelta, double vacEx, double opexInf,
                                    double rentas, double opex, double deuda,
                                    double euribor, List<Map<String, Object>> props,
                                    Map<String, Double> rentaPorProp) {
        // Aplicar shocks
        double rentasShock = rentas * (1 - vacEx / 100.0);
        double opexShock   = opex   * (1 + opexInf / 100.0);
        // Deuda: ajustamos solo si hay euriborDelta (variable)
        double deudaShock  = deuda;
        if (euriborDelta > 0 && deuda > 0) {
            // Aprox: cada +1pp de euribor -> +X% de cuota anual segun proporcion intereses
            // Asumimos 60% de la cuota es intereses sobre capital pendiente -> +1pp ~ +12% cuota anual aprox
            deudaShock = deuda * (1 + (euriborDelta * 0.12));
        }

        double noi = rentasShock - opexShock;
        double cashflow = noi - deudaShock;
        double dscr = deudaShock > 0 ? noi / deudaShock : 99;

        // Contar props en rojo bajo este escenario
        int rojo = 0;
        for (Map<String, Object> p : props) {
            String pid = String.valueOf(p.get("id"));
            double r = rentaPorProp.getOrDefault(pid, num(p.get("precio_compra")) * 0.045);
            r *= (1 - vacEx / 100.0);
            double op = (num(p.get("ibi_anual")) + num(p.get("comunidad_mes")) * 12
                       + num(p.get("seguro_anual")) + num(p.get("mantenimiento_anual")))
                       * (1 + opexInf / 100.0);
            double cuota = num(p.get("hipoteca_cuota_mensual")) * 12;
            if (euriborDelta > 0) cuota *= (1 + (euriborDelta * 0.12));
            double pNoi = r - op;
            double pDscr = cuota > 0 ? pNoi / cuota : 99;
            if (pDscr < 1.0) rojo++;
        }

        String veredicto, color;
        if (dscr < 1.0)        { veredicto = "INSOLVENTE";  color = "#EE5D50"; }
        else if (dscr < 1.25)  { veredicto = "FRONTERA";    color = "#FF8A65"; }
        else if (dscr < 1.5)   { veredicto = "ACEPTABLE";   color = "#FFB547"; }
        else if (dscr < 2.0)   { veredicto = "SOLIDO";      color = "#8BC34A"; }
        else                    { veredicto = "EXCELENTE";   color = "#05CD99"; }

        return new Scenario(name, desc, euriborDelta, vacEx, opexInf,
                cashflow, noi, dscr, rojo, props.size(), veredicto, color);
    }

    private static double num(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o).replace(",", ".").trim()); }
        catch (Exception e) { return 0.0; }
    }

    /** Devuelve el primer numero > 0 encontrado entre las keys candidatas. */
    private static double pickNum(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            double v = num(m.get(k));
            if (v != 0) return v;
        }
        return 0.0;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank() && !"null".equals(v)) return v;
        return "";
    }
}
