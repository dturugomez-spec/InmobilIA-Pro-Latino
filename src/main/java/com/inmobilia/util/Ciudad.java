package com.inmobilia.util;

import java.text.Normalizer;
import java.util.*;

/**
 * Ciudad de la inmobiliaria. Al elegirla, la app toma el país (moneda, impuesto, documentos, leyes
 * de datos y de arrendamiento) y la zona horaria exacta: en México, por ejemplo, Cancún y Tijuana
 * no tienen la hora de la capital.
 *
 * Se guarda como "MX:Guadalajara". "MX:" es "otra ciudad de México" (país sin ciudad concreta).
 *
 * @param zona zona horaria propia; null = la del país
 */
public record Ciudad(String pais, String nombre, String zona) {

    private static final Map<String, List<Ciudad>> POR_PAIS = new LinkedHashMap<>();

    static {
        reg("MX", "Ciudad de México", null, "Guadalajara", null, "Monterrey", null, "Querétaro", null, "Puebla", null,
                "León", null, "Mérida", "America/Merida", "Cancún", "America/Cancun", "Playa del Carmen", "America/Cancun",
                "Tijuana", "America/Tijuana", "Chihuahua", "America/Chihuahua", "Hermosillo", "America/Hermosillo",
                "San Luis Potosí", null, "Aguascalientes", null, "Toluca", null, "Puerto Vallarta", null);
        reg("CO", "Bogotá", null, "Medellín", null, "Cali", null, "Barranquilla", null, "Cartagena", null,
                "Bucaramanga", null, "Pereira", null, "Santa Marta", null);
        reg("AR", "Buenos Aires", null, "Córdoba", null, "Rosario", null, "Mendoza", null, "Mar del Plata", null,
                "La Plata", null, "Tucumán", null, "Neuquén", null);
        reg("CL", "Santiago", null, "Viña del Mar", null, "Valparaíso", null, "Concepción", null, "Antofagasta", null,
                "La Serena", null, "Puerto Montt", null, "Punta Arenas", "America/Punta_Arenas");
        reg("PE", "Lima", null, "Arequipa", null, "Trujillo", null, "Cusco", null, "Piura", null, "Chiclayo", null);
        reg("EC", "Quito", null, "Guayaquil", null, "Cuenca", null, "Manta", null);
        reg("UY", "Montevideo", null, "Punta del Este", null, "Colonia del Sacramento", null);
        reg("PY", "Asunción", null, "Ciudad del Este", null, "Encarnación", null);
        reg("BO", "La Paz", null, "Santa Cruz de la Sierra", null, "Cochabamba", null);
        reg("VE", "Caracas", null, "Maracaibo", null, "Valencia", null);
        reg("CR", "San José", null, "Heredia", null, "Liberia", null);
        reg("PA", "Ciudad de Panamá", null, "David", null);
        reg("GT", "Ciudad de Guatemala", null, "Quetzaltenango", null);
        reg("HN", "Tegucigalpa", null, "San Pedro Sula", null);
        reg("SV", "San Salvador", null, "Santa Ana", null);
        reg("NI", "Managua", null, "León", null);
        reg("DO", "Santo Domingo", null, "Santiago de los Caballeros", null, "Punta Cana", null);
    }

    private static void reg(String pais, String... nombreYZona) {
        List<Ciudad> l = new ArrayList<>();
        for (int i = 0; i < nombreYZona.length; i += 2) l.add(new Ciudad(pais, nombreYZona[i], nombreYZona[i + 1]));
        POR_PAIS.put(pais, List.copyOf(l));
    }

    /** "MX:Guadalajara" (o "MX:" si es otra ciudad del país). */
    public String clave() { return pais + ":" + (nombre == null ? "" : nombre); }

    /** Zona horaria efectiva: la de la ciudad o, si no tiene, la del país. */
    public String zonaEfectiva() { return zona != null ? zona : Pais.de(pais).zona(); }

    public static List<Ciudad> de(String pais) { return POR_PAIS.getOrDefault(pais, List.of()); }

    /** Países con sus ciudades, en el orden del selector. */
    public static Map<String, List<Ciudad>> porPais() { return Collections.unmodifiableMap(POR_PAIS); }

    /**
     * Lee "MX:Guadalajara" (también solo "MX" o solo "Guadalajara"). La ciudad se busca sin tildes ni
     * mayúsculas; una ciudad que no está en la lista se conserva con la zona del país.
     */
    public static Optional<Ciudad> leer(String valor) {
        if (valor == null || valor.isBlank()) return Optional.empty();
        String v = valor.trim();
        String pais = null, nombre = v;
        int dos = v.indexOf(':');
        if (dos >= 0) {
            pais = v.substring(0, dos).trim().toUpperCase(Locale.ROOT);
            nombre = v.substring(dos + 1).trim();
            if (!Pais.existe(pais)) return Optional.empty();
        } else if (Pais.existe(v)) {
            return Optional.of(new Ciudad(v.toUpperCase(Locale.ROOT), "", null));
        }
        String buscado = normalizar(nombre);
        for (var e : POR_PAIS.entrySet()) {
            if (pais != null && !pais.equals(e.getKey())) continue;
            for (Ciudad c : e.getValue()) if (normalizar(c.nombre()).equals(buscado)) return Optional.of(c);
        }
        if (pais == null) return Optional.empty();            // ciudad suelta que no conocemos: no adivinamos el país
        String limpio = nombre.length() > 80 ? nombre.substring(0, 80) : nombre;
        return Optional.of(new Ciudad(pais, limpio, null));   // ciudad del país que no está en la lista
    }

    private static String normalizar(String s) {
        return Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
