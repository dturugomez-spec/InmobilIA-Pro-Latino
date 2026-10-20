package com.inmobilia.util;

import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * País de la inmobiliaria: moneda, impuesto indirecto, documentos fiscales, prefijo y zona horaria.
 * Cada inmobiliaria elige el suyo en Configuración (columna users.pais) y la app se adapta.
 *
 * @param moneda      código ISO 4217 que se muestra junto a los importes ("1.500.000 MXN")
 * @param impuesto    nombre del impuesto al consumo (IVA, IGV, ITBIS, ITBMS, ISV)
 * @param tipoImpuesto tipo general en %
 * @param idFiscal    identificador tributario de personas y empresas (RFC, NIT, CUIT, RUT, RUC…)
 * @param idPersonal  documento de identidad (INE, cédula, DNI, DPI, DUI…)
 * @param leyDatos    ley de protección de datos personales (null si no hay una ley general)
 * @param portales    portales inmobiliarios habituales del país, para textos y sugerencias
 */
public record Pais(String codigo, String nombre, String bandera, String moneda, Locale locale,
                   String impuesto, double tipoImpuesto, String idFiscal, String idPersonal,
                   String prefijo, String zona, String leyDatos, String portales) {

    public static final String POR_DEFECTO = "MX";

    private static final Map<String, Pais> TODOS = new LinkedHashMap<>();

    static {
        reg(new Pais("MX", "México", "🇲🇽", "MXN", Locale.forLanguageTag("es-MX"), "IVA", 16, "RFC", "INE",
                "+52", "America/Mexico_City",
                "Ley Federal de Protección de Datos Personales en Posesión de los Particulares",
                "Inmuebles24, Vivanuncios, Lamudi, Propiedades.com"));
        reg(new Pais("CO", "Colombia", "🇨🇴", "COP", Locale.forLanguageTag("es-CO"), "IVA", 19, "NIT", "cédula",
                "+57", "America/Bogota", "Ley 1581 de 2012 de protección de datos personales",
                "Finca Raíz, Metrocuadrado, Ciencuadras"));
        reg(new Pais("AR", "Argentina", "🇦🇷", "ARS", Locale.forLanguageTag("es-AR"), "IVA", 21, "CUIT", "DNI",
                "+54", "America/Argentina/Buenos_Aires", "Ley 25.326 de protección de los datos personales",
                "Zonaprop, Argenprop, Mercado Libre Inmuebles"));
        reg(new Pais("CL", "Chile", "🇨🇱", "CLP", Locale.forLanguageTag("es-CL"), "IVA", 19, "RUT", "RUN",
                "+56", "America/Santiago", "Ley 19.628 sobre protección de la vida privada",
                "Portal Inmobiliario, Toctoc, Yapo"));
        reg(new Pais("PE", "Perú", "🇵🇪", "PEN", Locale.forLanguageTag("es-PE"), "IGV", 18, "RUC", "DNI",
                "+51", "America/Lima", "Ley 29733 de protección de datos personales",
                "Urbania, Adondevivir, Nexo Inmobiliario"));
        reg(new Pais("EC", "Ecuador", "🇪🇨", "USD", Locale.forLanguageTag("es-EC"), "IVA", 15, "RUC", "cédula",
                "+593", "America/Guayaquil", "Ley Orgánica de Protección de Datos Personales",
                "Plusvalía, Properati, Vive1"));
        reg(new Pais("UY", "Uruguay", "🇺🇾", "UYU", Locale.forLanguageTag("es-UY"), "IVA", 22, "RUT", "cédula",
                "+598", "America/Montevideo", "Ley 18.331 de protección de datos personales",
                "Mercado Libre Inmuebles, InfoCasas, Gallito"));
        reg(new Pais("PY", "Paraguay", "🇵🇾", "PYG", Locale.forLanguageTag("es-PY"), "IVA", 10, "RUC", "cédula",
                "+595", "America/Asuncion", null, "InfoCasas, Clasipar"));
        reg(new Pais("BO", "Bolivia", "🇧🇴", "BOB", Locale.forLanguageTag("es-BO"), "IVA", 13, "NIT", "cédula",
                "+591", "America/La_Paz", null, "InfoCasas, UltraCasas"));
        reg(new Pais("VE", "Venezuela", "🇻🇪", "USD", Locale.forLanguageTag("es-VE"), "IVA", 16, "RIF", "cédula",
                "+58", "America/Caracas", null, "Tu Inmueble, Mercado Libre Inmuebles"));
        reg(new Pais("CR", "Costa Rica", "🇨🇷", "CRC", Locale.forLanguageTag("es-CR"), "IVA", 13, "cédula jurídica", "cédula",
                "+506", "America/Costa_Rica", "Ley 8968 de protección de la persona frente al tratamiento de sus datos personales",
                "Encuentra24, Properati"));
        reg(new Pais("PA", "Panamá", "🇵🇦", "USD", Locale.forLanguageTag("es-PA"), "ITBMS", 7, "RUC", "cédula",
                "+507", "America/Panama", "Ley 81 de 2019 de protección de datos personales",
                "Encuentra24, Compreoalquile"));
        reg(new Pais("GT", "Guatemala", "🇬🇹", "GTQ", Locale.forLanguageTag("es-GT"), "IVA", 12, "NIT", "DPI",
                "+502", "America/Guatemala", null, "Encuentra24, Mercado Libre Inmuebles"));
        reg(new Pais("HN", "Honduras", "🇭🇳", "HNL", Locale.forLanguageTag("es-HN"), "ISV", 15, "RTN", "DNI",
                "+504", "America/Tegucigalpa", null, "Encuentra24"));
        reg(new Pais("SV", "El Salvador", "🇸🇻", "USD", Locale.forLanguageTag("es-SV"), "IVA", 13, "NIT", "DUI",
                "+503", "America/El_Salvador", null, "Encuentra24"));
        reg(new Pais("NI", "Nicaragua", "🇳🇮", "NIO", Locale.forLanguageTag("es-NI"), "IVA", 15, "RUC", "cédula",
                "+505", "America/Managua", "Ley 787 de protección de datos personales", "Encuentra24"));
        reg(new Pais("DO", "República Dominicana", "🇩🇴", "DOP", Locale.forLanguageTag("es-DO"), "ITBIS", 18, "RNC", "cédula",
                "+1", "America/Santo_Domingo", "Ley 172-13 de protección de datos de carácter personal",
                "Corotos, SuperCasas"));
    }

    private static void reg(Pais p) { TODOS.put(p.codigo(), p); }

    /** País por código ISO (MX, CO…). Si no se reconoce, el país por defecto. */
    public static Pais de(Object codigo) {
        if (codigo == null) return TODOS.get(POR_DEFECTO);
        Pais p = TODOS.get(String.valueOf(codigo).trim().toUpperCase(Locale.ROOT));
        return p != null ? p : TODOS.get(POR_DEFECTO);
    }

    public static boolean existe(String codigo) {
        return codigo != null && TODOS.containsKey(codigo.trim().toUpperCase(Locale.ROOT));
    }

    public static Collection<Pais> todos() { return TODOS.values(); }

    public ZoneId zonaId() { return ZoneId.of(zona); }

    /** Ley de datos para textos legales; genérico donde no hay una ley general. */
    public String leyDatosTexto() {
        return leyDatos != null ? leyDatos : "la legislación de protección de datos personales aplicable en " + nombre;
    }

    /** Etiqueta del identificador fiscal para formularios: "RFC", "NIT", "CUIT"… */
    public String etiquetaIdFiscal() { return idFiscal.toUpperCase(Locale.ROOT).equals(idFiscal) ? idFiscal : capitalizar(idFiscal); }

    public String etiquetaIdPersonal() { return idPersonal.toUpperCase(Locale.ROOT).equals(idPersonal) ? idPersonal : capitalizar(idPersonal); }

    /** "IVA 16 %" */
    public String impuestoConTipo() {
        return impuesto + " " + (tipoImpuesto == Math.rint(tipoImpuesto)
                ? String.valueOf((int) tipoImpuesto) : String.valueOf(tipoImpuesto)) + " %";
    }

    private static String capitalizar(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
