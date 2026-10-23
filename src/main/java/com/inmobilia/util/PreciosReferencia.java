package com.inmobilia.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

/**
 * Precio orientativo de venta por m² en las principales ciudades de Latinoamérica, en USD
 * (referencia aproximada de portales y estudios de mercado). Solo sirve para una primera
 * estimación: la valoración real debe apoyarse en comparables de la zona.
 * Se convierte a la moneda de la inmobiliaria con CurrencyService.
 */
public final class PreciosReferencia {
    private PreciosReferencia() {}

    /** Por país: ciudad (minúsculas, sin tildes) → USD/m². La clave "*" es el valor por defecto del país. */
    private static final Map<String, Map<String, Double>> USD_M2 = Map.ofEntries(
        Map.entry("MX", Map.ofEntries(Map.entry("ciudad de mexico", 2700.0), Map.entry("cdmx", 2700.0),
                Map.entry("monterrey", 1900.0), Map.entry("guadalajara", 1800.0), Map.entry("queretaro", 1500.0),
                Map.entry("merida", 1400.0), Map.entry("puebla", 1200.0), Map.entry("cancun", 2000.0),
                Map.entry("playa del carmen", 2200.0), Map.entry("tijuana", 1400.0), Map.entry("*", 1100.0))),
        Map.entry("CO", Map.ofEntries(Map.entry("bogota", 1600.0), Map.entry("medellin", 1500.0),
                Map.entry("cali", 1100.0), Map.entry("barranquilla", 1100.0), Map.entry("cartagena", 1700.0),
                Map.entry("bucaramanga", 1000.0), Map.entry("*", 900.0))),
        Map.entry("AR", Map.ofEntries(Map.entry("buenos aires", 2400.0), Map.entry("caba", 2400.0),
                Map.entry("cordoba", 1400.0), Map.entry("rosario", 1400.0), Map.entry("mendoza", 1300.0),
                Map.entry("mar del plata", 1400.0), Map.entry("*", 1100.0))),
        Map.entry("CL", Map.ofEntries(Map.entry("santiago", 2600.0), Map.entry("vina del mar", 2300.0),
                Map.entry("valparaiso", 1800.0), Map.entry("concepcion", 1900.0), Map.entry("antofagasta", 2000.0),
                Map.entry("*", 1600.0))),
        Map.entry("PE", Map.ofEntries(Map.entry("lima", 1700.0), Map.entry("miraflores", 2500.0),
                Map.entry("arequipa", 1000.0), Map.entry("trujillo", 900.0), Map.entry("cusco", 1100.0),
                Map.entry("*", 800.0))),
        Map.entry("EC", Map.of("quito", 1100.0, "guayaquil", 1000.0, "cuenca", 900.0, "*", 800.0)),
        Map.entry("UY", Map.of("montevideo", 2300.0, "punta del este", 3000.0, "*", 1500.0)),
        Map.entry("PY", Map.of("asuncion", 1200.0, "*", 800.0)),
        Map.entry("BO", Map.of("la paz", 900.0, "santa cruz", 900.0, "*", 700.0)),
        Map.entry("VE", Map.of("caracas", 800.0, "*", 500.0)),
        Map.entry("CR", Map.of("san jose", 1500.0, "*", 1100.0)),
        Map.entry("PA", Map.of("panama", 1800.0, "ciudad de panama", 1800.0, "*", 1100.0)),
        Map.entry("GT", Map.of("guatemala", 1500.0, "ciudad de guatemala", 1500.0, "*", 900.0)),
        Map.entry("HN", Map.of("tegucigalpa", 900.0, "san pedro sula", 900.0, "*", 700.0)),
        Map.entry("SV", Map.of("san salvador", 1300.0, "*", 900.0)),
        Map.entry("NI", Map.of("managua", 800.0, "*", 600.0)),
        Map.entry("DO", Map.of("santo domingo", 1400.0, "punta cana", 2000.0, "*", 1000.0))
    );

    /** Ciudad principal de cada país (para ejemplos y valores por defecto en formularios). */
    private static final Map<String, String> CAPITAL = Map.ofEntries(
        Map.entry("MX", "Ciudad de México"), Map.entry("CO", "Bogotá"), Map.entry("AR", "Buenos Aires"),
        Map.entry("CL", "Santiago"), Map.entry("PE", "Lima"), Map.entry("EC", "Quito"), Map.entry("UY", "Montevideo"),
        Map.entry("PY", "Asunción"), Map.entry("BO", "La Paz"), Map.entry("VE", "Caracas"), Map.entry("CR", "San José"),
        Map.entry("PA", "Ciudad de Panamá"), Map.entry("GT", "Ciudad de Guatemala"), Map.entry("HN", "Tegucigalpa"),
        Map.entry("SV", "San Salvador"), Map.entry("NI", "Managua"), Map.entry("DO", "Santo Domingo"));

    /** USD/m² orientativo de la ciudad (o el del país si no está en la tabla). */
    public static double usdPorM2(Pais pais, String ciudad) {
        Map<String, Double> t = USD_M2.getOrDefault(pais.codigo(), Map.of("*", 1000.0));
        if (ciudad != null) {
            Double v = t.get(normalizar(ciudad));
            if (v != null) return v;
        }
        return t.getOrDefault("*", 1000.0);
    }

    /** true si la ciudad tiene precio propio en la tabla (si no, se usa la media del país). */
    public static boolean conocida(Pais pais, String ciudad) {
        return ciudad != null && USD_M2.getOrDefault(pais.codigo(), Map.of()).containsKey(normalizar(ciudad));
    }

    /** La ciudad de la inmobiliaria si la eligió; si no, la principal del país. */
    public static String ciudadPrincipal(Pais pais) {
        if (pais.ciudad() != null && !pais.ciudad().isBlank()) return pais.ciudad();
        return CAPITAL.getOrDefault(pais.codigo(), pais.nombre());
    }

    private static String normalizar(String s) {
        String n = Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "").replaceAll("\\s+", " ");
    }
}
