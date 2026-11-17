package com.inmobilia.util;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.*;

/**
 * Lo que cuesta InmobilIA Pro en cada moneda de Latinoamérica (al mes, sin impuestos; el pack de
 * créditos es un pago único). Son los precios de la versión española (Pro 49 €, Business 149 €,
 * Enterprise desde 399 €, activo adicional 4 €, sociedad adicional 15 €, Future-Proof 29 €,
 * 100 créditos 10 €) pasados al cambio del {@link #FECHA_CAMBIO} y redondeados a tres cifras
 * (el dólar, a la unidad o al medio dólar en los extras).
 *
 * Cada inmobiliaria paga en la moneda con la que trabaja (la de su país, o USD si trabaja en
 * dólares); si esa moneda no está en la tabla, en USD. Los importes NO siguen al tipo de cambio:
 * se cambian aquí a mano (y la app crea en Stripe el precio nuevo con la misma lookup key).
 */
public final class PreciosPlataforma {
    private PreciosPlataforma() {}

    public static final String FECHA_CAMBIO = "16 de noviembre de 2026 (XE)";

    public static final String PLAN_PRO = "plan_pro";
    public static final String PLAN_BUSINESS = "plan_business";
    public static final String PLAN_ENTERPRISE = "plan_enterprise";   // «desde»: se pacta, no va a Stripe
    public static final String ACTIVO_EXTRA = "activo_extra";
    public static final String SOCIEDAD_EXTRA = "sociedad_extra";
    public static final String MODULO_FUTUREPROOF = "modulo_futureproof";
    public static final String CREDITOS = "creditos_100";

    /** Orden de las columnas de la tabla. */
    public static final List<String> CONCEPTOS = List.of(PLAN_PRO, PLAN_BUSINESS, PLAN_ENTERPRISE,
            ACTIVO_EXTRA, SOCIEDAD_EXTRA, MODULO_FUTUREPROOF, CREDITOS);

    /** En Stripe van sin decimales (el importe es en unidades, no en céntimos). */
    public static final Set<String> SIN_DECIMALES = Set.of("CLP", "PYG");

    public static final String USD = "USD";

    private static final Map<String, Map<String, BigDecimal>> TABLA = new LinkedHashMap<>();

    private static void fila(String moneda, String... importes) {
        Map<String, BigDecimal> f = new LinkedHashMap<>();
        for (int i = 0; i < CONCEPTOS.size(); i++) f.put(CONCEPTOS.get(i), new BigDecimal(importes[i]));
        TABLA.put(moneda, Collections.unmodifiableMap(f));
    }

    static {
        //        moneda   Pro       Business   Enterprise  Activo  Sociedad  Future-Proof  100 créditos
        fila(USD,   "54",     "164",     "439",      "4.5",  "16.5",   "32",     "11");
        fila("MXN", "1000",   "3050",    "8160",     "82",   "307",    "593",    "204");
        fila("COP", "179000", "544000",  "1460000",  "14600","54800",  "106000", "36500");
        fila("ARS", "83500",  "254000",  "680000",   "6820", "25600",  "49400",  "17000");
        fila("CLP", "53900",  "164000",  "439000",   "4400", "16500",  "31900",  "11000");
        fila("PEN", "184",    "560",     "1500",     "15",   "56",     "109",    "38");
        fila("UYU", "2190",   "6650",    "17800",    "179",  "670",    "1300",   "447");
        fila("PYG", "334000", "1020000", "2720000",  "27300","102000", "198000", "68200");
        fila("BOB", "628",    "1910",    "5120",     "51",   "192",    "372",    "128");
        fila("CRC", "24200",  "73600",   "197000",   "1980", "7410",   "14300",  "4940");
        fila("GTQ", "412",    "1250",    "3350",     "34",   "126",    "244",    "84");
        fila("HNL", "1450",   "4410",    "11800",    "118",  "444",    "859",    "296");
        fila("NIO", "1980",   "6030",    "16200",    "162",  "607",    "1170",   "405");
        fila("DOP", "3270",   "9950",    "26700",    "267",  "1000",   "1940",   "668");
    }

    /** Monedas en las que se cobra (USD la primera). */
    public static Set<String> monedas() { return Collections.unmodifiableSet(TABLA.keySet()); }

    /** Moneda en la que paga una inmobiliaria de este país. */
    public static String monedaDe(Pais p) {
        if (p == null) return USD;
        if (TABLA.containsKey(p.moneda())) return p.moneda();
        return TABLA.containsKey(p.monedaLocal()) ? p.monedaLocal() : USD;
    }

    /** Moneda de la inmobiliaria (o del visitante) de esta petición. */
    public static String moneda() { return monedaDe(PaisActual.get()); }

    public static BigDecimal importe(String moneda, String concepto) {
        Map<String, BigDecimal> f = TABLA.get(moneda);
        BigDecimal v = f == null ? null : f.get(concepto);
        if (v == null) throw new IllegalArgumentException("Sin precio para " + concepto + " en " + moneda);
        return v;
    }

    public static BigDecimal importe(String concepto) { return importe(moneda(), concepto); }

    /** ¿Hay precio para este concepto (p. ej. un módulo)? */
    public static boolean existe(String concepto) { return TABLA.get(USD).containsKey(concepto); }

    /** Importe tal y como lo pide Stripe: en céntimos, salvo en las monedas sin decimales. */
    public static long unidadesStripe(String moneda, String concepto) {
        BigDecimal v = importe(moneda, concepto);
        return SIN_DECIMALES.contains(moneda) ? v.longValueExact() : v.movePointRight(2).longValueExact();
    }

    /** Todas las monedas de un concepto, en unidades de Stripe (clave en minúsculas: "mxn"). */
    public static Map<String, Long> unidadesStripe(String concepto) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String m : TABLA.keySet()) out.put(m.toLowerCase(Locale.ROOT), unidadesStripe(m, concepto));
        return out;
    }

    /** "1,000 MXN", "179.000 COP", "4,50 USD": número al estilo del país y código de la moneda. */
    public static String formato(BigDecimal v, String moneda, Locale locale) {
        NumberFormat nf = NumberFormat.getNumberInstance(locale == null ? Locale.forLanguageTag("es-419") : locale);
        boolean decimales = v.stripTrailingZeros().scale() > 0;
        nf.setMinimumFractionDigits(decimales ? 2 : 0);
        nf.setMaximumFractionDigits(decimales ? 2 : 0);
        return nf.format(v) + " " + moneda;
    }

    /** Precio de un concepto en la moneda de esta petición. */
    public static String texto(String concepto) {
        return formato(importe(concepto), moneda(), PaisActual.get().locale());
    }

    /** Lo que suman n unidades de un extra (n activos adicionales…). */
    public static String texto(String concepto, int cantidad) {
        return formato(importe(concepto).multiply(BigDecimal.valueOf(Math.max(0, cantidad))), moneda(), PaisActual.get().locale());
    }

    /** Para las vistas: ${precios.plan_pro}, ${precios['modulo_' + m.clave()]}, ${precios.moneda}. */
    public static Map<String, String> vista() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String c : CONCEPTOS) out.put(c, texto(c));
        out.put("moneda", moneda());
        return out;
    }
}
