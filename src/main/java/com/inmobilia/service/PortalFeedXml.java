package com.inmobilia.service;

import java.text.Normalizer;
import java.util.*;

/**
 * Construye el XML de exportación de la cartera a portales.
 *
 * Feed genérico (referencia, operación, tipo, precio con moneda, superficie, ubicación con país,
 * características, fotos, enlace y contacto) con la moneda y el país de la inmobiliaria. Los
 * portales de cada país (Inmuebles24, Finca Raíz, Zonaprop, Portal Inmobiliario, Urbania…) o un
 * integrador lo pueden importar; si un portal exige su propio formato, se añade aquí un generador.
 *
 * Los datos salen de las columnas reales del inmueble (precio, operacion, provincia, fotos...)
 * con las antiguas como respaldo (current_value, city, featured_photo...).
 */
public final class PortalFeedXml {
    private PortalFeedXml() {}

    static final int MAX_FOTOS = 50;

    // ─── Normalización ──────────────────────────────────────────────────────

    /** Inmueble con las claves que usan los generadores. galeria: fotos subidas en la galería. */
    public static Map<String, Object> normalizar(Map<String, Object> p, List<String> galeria) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", p.get("id"));
        String operacion = operacion(p.get("operacion"));
        n.put("operacion", operacion);
        boolean alquiler = operacion.startsWith("alquiler");
        n.put("precio", alquiler ? positivo(p.get("precio"), p.get("rental_income"))
                : positivo(p.get("precio"), p.get("current_value"), p.get("purchase_price")));
        n.put("metros", positivo(p.get("metros_cuadrados"), p.get("area_m2")));
        n.put("habitaciones", primero(p.get("habitaciones"), p.get("bedrooms")));
        n.put("banos", primero(p.get("banos"), p.get("bathrooms")));
        String municipio = texto(p.get("municipio"), p.get("city"));
        String cp = texto(p.get("codigo_postal"), p.get("postal_code"));
        n.put("municipio", municipio);
        n.put("codigo_postal", cp);
        n.put("provincia", texto(p.get("provincia"), municipio)); // estado, departamento o provincia
        n.put("direccion", texto(p.get("direccion"), p.get("address"), p.get("name")));
        n.put("titulo", texto(p.get("name"), p.get("direccion"), p.get("address")));
        String descripcion = texto(p.get("descripcion"), p.get("notes"));
        n.put("descripcion", descripcion);
        n.put("referencia", texto(p.get("referencia"), p.get("reference"), p.get("id")));
        n.put("tipo", texto(p.get("tipo"), p.get("type")));
        n.put("lat", primero(p.get("lat")));
        n.put("lng", primero(p.get("lng"), p.get("lon")));
        n.put("eficiencia", letraEnergetica(texto(p.get("cee_calificacion"), p.get("eficiencia_energetica"))));
        n.put("fecha", p.get("created_at"));
        String d = sinAcentos(descripcion == null ? "" : descripcion.toLowerCase());
        n.put("garaje", si(p.get("garaje")) || d.contains("garaje") || d.contains("plaza de parking"));
        n.put("ascensor", si(p.get("ascensor")) || d.contains("ascensor"));
        n.put("terraza", si(p.get("terraza")) || d.contains("terraza"));
        n.put("piscina", si(p.get("piscina")) || d.contains("piscina"));
        n.put("amueblado", si(p.get("amueblado")) || d.contains("amueblad"));
        n.put("aire_acondicionado", si(p.get("aire_acondicionado")) || d.contains("aire acondicionado") || d.contains("a/a"));
        n.put("fotos", fotos(p, galeria));
        return n;
    }

    /** ¿Se puede anunciar? (sin precio el portal lo rechaza; reservado/vendido/alquilado no se anuncia) */
    public static boolean publicable(Map<String, Object> original, Map<String, Object> normalizado) {
        String estado = sinAcentos(String.valueOf(original.getOrDefault("status", "")).toLowerCase().trim());
        if (Set.of("reservado", "vendido", "alquilado", "retirado", "baja", "no disponible").contains(estado)) return false;
        return normalizado.get("precio") != null;
    }

    /** Lo que le falta a un inmueble para anunciarse bien (para avisar en la pantalla de portales). */
    public static List<String> faltan(Map<String, Object> n) {
        List<String> f = new ArrayList<>();
        if (n.get("precio") == null) f.add("precio");
        if (((List<?>) n.get("fotos")).isEmpty()) f.add("fotos");
        if (n.get("municipio") == null) f.add("municipio");
        if (n.get("metros") == null) f.add("metros");
        if (n.get("descripcion") == null) f.add("descripción");
        return f;
    }

    static String operacion(Object o) {
        String s = sinAcentos(o == null ? "" : String.valueOf(o).toLowerCase().trim());
        if (s.contains("temporal") || s.contains("vacacional")) return "alquiler_temporal";
        if (s.startsWith("alquil") || s.equals("rent") || s.equals("renta")) return "alquiler";
        if (s.contains("traspaso") || s.equals("transfer")) return "traspaso";
        return "venta";
    }

    /** Foto principal, fotos guardadas en el inmueble y galería, sin repetir. */
    static List<String> fotos(Map<String, Object> p, List<String> galeria) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        añadirFoto(urls, p.get("featured_photo"));
        for (String col : List.of("photos", "fotos")) {
            if (p.get(col) instanceof List<?> l) for (Object o : l) añadirFoto(urls, o instanceof Map<?, ?> m ? m.get("url") : o);
        }
        if (galeria != null) galeria.forEach(g -> añadirFoto(urls, g));
        return new ArrayList<>(new ArrayList<>(urls).subList(0, Math.min(urls.size(), MAX_FOTOS)));
    }

    private static void añadirFoto(Set<String> urls, Object o) {
        if (o == null) return;
        String s = String.valueOf(o).trim();
        if (s.matches("(?i)https?://\\S+")) urls.add(s);
    }

    // ─── Feed genérico ──────────────────────────────────────────────────────

    /**
     * @param baseUrl dirección pública de la app (enlace a la ficha de cada inmueble)
     * @param pais    país de la inmobiliaria: moneda y código de país del XML
     */
    public static String generico(Map<String, Object> cfg, List<Map<String, Object>> props, String baseUrl,
                                  com.inmobilia.util.Pais pais) {
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<properties version=\"1.0\">\n");
        for (Map<String, Object> p : props) {
            String op = String.valueOf(p.get("operacion"));
            x.append("  <property>\n");
            etiqueta(x, 4, "reference", p.get("referencia"));
            etiqueta(x, 4, "operation", switch (op) {
                case "alquiler" -> "rent";
                case "alquiler_temporal" -> "temporary_rent";
                case "traspaso" -> "transfer";
                default -> "sale";
            });
            etiqueta(x, 4, "property_type", tipo(String.valueOf(p.get("tipo"))));
            etiqueta(x, 4, "price", entero(p.get("precio")));
            etiqueta(x, 4, "price_freq", op.startsWith("alquiler") ? "month" : "sale");
            etiqueta(x, 4, "currency", pais.moneda());
            etiqueta(x, 4, "surface", entero(p.get("metros")));
            etiqueta(x, 4, "rooms", entero0(p.get("habitaciones")));
            etiqueta(x, 4, "bathrooms", entero0(p.get("banos")));
            etiqueta(x, 4, "address", p.get("direccion"));
            etiqueta(x, 4, "town", p.get("municipio"));
            etiqueta(x, 4, "region", p.get("provincia"));
            etiqueta(x, 4, "postal_code", p.get("codigo_postal"));
            etiqueta(x, 4, "country", pais.codigo());
            if (p.get("lat") != null && p.get("lng") != null) {
                etiqueta(x, 4, "latitude", p.get("lat"));
                etiqueta(x, 4, "longitude", p.get("lng"));
            }
            etiqueta(x, 4, "title", p.get("titulo"));
            etiqueta(x, 4, "description", p.get("descripcion"));
            etiqueta(x, 4, "url", baseUrl + "/propiedad/" + p.get("id"));
            etiqueta(x, 4, "date", p.get("fecha"));
            x.append("    <features>\n");
            if (Boolean.TRUE.equals(p.get("garaje"))) etiqueta(x, 6, "feature", "garage");
            if (Boolean.TRUE.equals(p.get("ascensor"))) etiqueta(x, 6, "feature", "elevator");
            if (Boolean.TRUE.equals(p.get("terraza"))) etiqueta(x, 6, "feature", "terrace");
            if (Boolean.TRUE.equals(p.get("piscina"))) etiqueta(x, 6, "feature", "swimming_pool");
            if (Boolean.TRUE.equals(p.get("amueblado"))) etiqueta(x, 6, "feature", "furnished");
            if (Boolean.TRUE.equals(p.get("aire_acondicionado"))) etiqueta(x, 6, "feature", "air_conditioning");
            x.append("    </features>\n");
            imagenes(x, p, "    <images>\n", "    </images>\n",
                    (i, url) -> "      <image order=\"" + i + "\">" + esc(url) + "</image>\n");
            contacto(x, cfg, "    <contact>\n", "    </contact>\n", "name", "phone", "email");
            x.append("  </property>\n");
        }
        return x.append("</properties>\n").toString();
    }

    /** Tipo en inglés, con los nombres de Latinoamérica y de España. */
    static String tipo(String t) {
        String s = sinAcentos(t == null ? "" : t.toLowerCase());
        if (s.contains("atico") || s.contains("penthouse") || s.contains("pent house")) return "penthouse";
        if (s.contains("duplex")) return "duplex";
        if (s.contains("estudio") || s.contains("studio") || s.contains("loft")) return "studio";
        if (s.contains("casa") || s.contains("chalet") || s.contains("unifamiliar") || s.contains("adosad")
                || s.contains("villa") || s.contains("quinta")) return "house";
        if (s.contains("local") || s.contains("comercial")) return "premises";
        if (s.contains("oficina")) return "office";
        if (s.contains("bodega industrial") || s.contains("nave") || s.contains("galpon")) return "warehouse";
        if (s.contains("garaje") || s.contains("parking") || s.contains("cochera")) return "garage";
        if (s.contains("trastero") || s.contains("bodega")) return "storage_room";
        if (s.contains("terreno") || s.contains("parcela") || s.contains("solar") || s.contains("lote")) return "land";
        return "apartment";
    }

    // ─── Ayudantes ──────────────────────────────────────────────────────────

    private interface Imagen { String xml(int orden, String url); }

    private static void imagenes(StringBuilder x, Map<String, Object> p, String abre, String cierra, Imagen img) {
        List<?> fotos = p.get("fotos") instanceof List<?> l ? l : List.of();
        if (fotos.isEmpty()) return;
        x.append(abre);
        int i = 1;
        for (Object f : fotos) x.append(img.xml(i++, String.valueOf(f)));
        x.append(cierra);
    }

    private static void contacto(StringBuilder x, Map<String, Object> cfg, String abre, String cierra,
                                 String nombre, String tel, String email) {
        x.append(abre);
        int sangria = abre.indexOf('<') + 2;
        etiqueta(x, sangria, nombre, cfg.get("company_name"));
        etiqueta(x, sangria, tel, cfg.get("company_phone"));
        etiqueta(x, sangria, email, cfg.get("company_email"));
        x.append(cierra);
    }

    private static void etiqueta(StringBuilder x, int sangria, String nombre, Object valor) {
        x.append(" ".repeat(sangria)).append('<').append(nombre).append('>')
                .append(esc(valor)).append("</").append(nombre).append(">\n");
    }

    /** Escapa para XML y quita los caracteres de control que lo invalidan. */
    static String esc(Object o) {
        if (o == null) return "";
        return String.valueOf(o)
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /** Número entero sin decimales ("250000", no "250000.0"); vacío si no hay. */
    static String entero(Object o) {
        Double d = num(o);
        return d == null ? "" : String.valueOf(Math.round(d));
    }

    /** Número de la base de datos (ya numérico) o escrito a mano (formato del país). */
    private static Double num(Object o) {
        if (o instanceof Number n) return Double.isFinite(n.doubleValue()) ? n.doubleValue() : null;
        return com.inmobilia.util.Numeros.decimal(o);
    }

    private static String entero0(Object o) {
        String e = entero(o);
        return e.isEmpty() ? "0" : e;
    }

    private static Object positivo(Object... vs) {
        for (Object v : vs) {
            Double d = num(v);
            if (d != null && d > 0) return d;
        }
        return null;
    }

    private static Object primero(Object... vs) {
        for (Object v : vs) if (v != null && !String.valueOf(v).isBlank()) return v;
        return null;
    }

    private static String texto(Object... vs) {
        for (Object v : vs) {
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (!s.isEmpty() && !"null".equals(s)) return s;
        }
        return null;
    }

    private static boolean si(Object o) {
        return Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(String.valueOf(o));
    }

    private static String letraEnergetica(String s) {
        if (s == null) return null;
        String l = s.trim().toUpperCase();
        return l.matches("[A-G]") ? l : null;
    }

    static String sinAcentos(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
