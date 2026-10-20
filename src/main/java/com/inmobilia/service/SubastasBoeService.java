package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Buscador / Hunter de subastas de bienes inmuebles publicadas en el BOE.
 * Combina:
 *  1. Lectura del RSS sumario del BOE filtrando subastas inmobiliarias.
 *  2. Construccion de URL profundas a subastas.boe.es con criterios de inversion.
 *  3. Catalogo de provincias y tipos de bien para el formulario.
 *
 * No existe API pura para subastas.boe.es; se ofrece deep-link automatizado
 * y monitorizacion via RSS oficial del BOE.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubastasBoeService {

    private final HttpClient httpClient;

    public record SubastaHit(String titulo, String url, String fecha, String tipo, String resumen) {}

    public record TipoBien(String code, String label, String icon) {}

    private static final List<TipoBien> TIPOS = List.of(
        new TipoBien("VIVIENDA",       "Viviendas",           "🏠"),
        new TipoBien("LOCAL",          "Locales comerciales", "🏪"),
        new TipoBien("OFICINA",        "Oficinas",            "🏢"),
        new TipoBien("NAVE",           "Naves industriales",  "🏭"),
        new TipoBien("SUELO",          "Solares y suelos",    "🌳"),
        new TipoBien("GARAJE",         "Plazas de garaje",    "🅿️"),
        new TipoBien("TRASTERO",       "Trasteros",           "📦"),
        new TipoBien("RUSTICO",        "Fincas rusticas",     "🌾"),
        new TipoBien("EDIFICIO",       "Edificios completos", "🏛️")
    );

    /** Provincias espanolas (catalogo INE simplificado para selector). */
    private static final List<String> PROVINCIAS = List.of(
        "Alava", "Albacete", "Alicante", "Almeria", "Asturias", "Avila",
        "Badajoz", "Baleares", "Barcelona", "Bizkaia", "Burgos", "Caceres",
        "Cadiz", "Cantabria", "Castellon", "Ciudad Real", "Cordoba", "Cuenca",
        "Gipuzkoa", "Girona", "Granada", "Guadalajara", "Huelva", "Huesca",
        "Jaen", "La Coruna", "La Rioja", "Las Palmas", "Leon", "Lleida",
        "Lugo", "Madrid", "Malaga", "Murcia", "Navarra", "Ourense",
        "Palencia", "Pontevedra", "Salamanca", "Santa Cruz de Tenerife",
        "Segovia", "Sevilla", "Soria", "Tarragona", "Teruel", "Toledo",
        "Valencia", "Valladolid", "Zamora", "Zaragoza", "Ceuta", "Melilla"
    );

    public List<TipoBien> tiposBien() { return TIPOS; }
    public List<String> provincias() { return PROVINCIAS; }

    /**
     * Construye una URL de busqueda avanzada a subastas.boe.es con los parametros
     * de inversion del usuario. El BOE acepta multiples campos via parametros campo[]/dato[].
     */
    public String buildSearchUrl(String tipoBien, String provincia,
                                  Double precioMin, Double precioMax,
                                  String texto) {
        try {
            StringBuilder sb = new StringBuilder("https://subastas.boe.es/subastas_ava.php?accion=Buscar");
            int idx = 0;
            // Solo bienes inmuebles
            sb.append("&campo[").append(idx).append("]=BIEN.TIPO");
            sb.append("&dato[").append(idx).append("]=BIEN_INMUEBLE");
            idx++;
            if (tipoBien != null && !tipoBien.isBlank() && !"TODOS".equals(tipoBien)) {
                sb.append("&campo[").append(idx).append("]=BIEN.SUBTIPO.0");
                sb.append("&dato[").append(idx).append("]=").append(enc(tipoBien));
                idx++;
            }
            if (provincia != null && !provincia.isBlank()) {
                sb.append("&campo[").append(idx).append("]=BIEN.LOCALIZACION.PROVINCIA");
                sb.append("&dato[").append(idx).append("]=").append(enc(provincia));
                idx++;
            }
            if (precioMin != null && precioMin > 0) {
                sb.append("&campo[").append(idx).append("]=SUBASTA.VALORSUBASTA.DESDE");
                sb.append("&dato[").append(idx).append("]=").append(precioMin.intValue());
                idx++;
            }
            if (precioMax != null && precioMax > 0) {
                sb.append("&campo[").append(idx).append("]=SUBASTA.VALORSUBASTA.HASTA");
                sb.append("&dato[").append(idx).append("]=").append(precioMax.intValue());
                idx++;
            }
            if (texto != null && !texto.isBlank()) {
                sb.append("&campo[").append(idx).append("]=SUBASTA.DESCRIPCION");
                sb.append("&dato[").append(idx).append("]=").append(enc(texto));
            }
            return sb.toString();
        } catch (Exception e) {
            return "https://subastas.boe.es/subastas_ava.php";
        }
    }

    /**
     * Lee el RSS principal del BOE y devuelve solo items que contengan
     * la palabra "subasta" + algun keyword inmobiliario.
     */
    @Cacheable(value = "subastasBoe", unless = "#result == null || #result.isEmpty()")
    public List<SubastaHit> recentSubastas(int limit) {
        List<SubastaHit> out = new ArrayList<>();
        String[] feeds = {
            "https://www.boe.es/rss/canal.php?c=subastas",
            "https://www.boe.es/rss/boe.php"
        };
        for (String url : feeds) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(7))
                        .header("User-Agent", "InmobilIAPro/1.0 RSS Reader")
                        .header("Accept", "application/rss+xml,application/xml")
                        .GET().build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() < 200 || resp.statusCode() >= 300) continue;
                var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                        .parse(new ByteArrayInputStream(resp.body().getBytes(StandardCharsets.UTF_8)));
                var items = doc.getElementsByTagName("item");
                int max = Math.min(items.getLength(), 40);
                for (int i = 0; i < max; i++) {
                    var el = (org.w3c.dom.Element) items.item(i);
                    String title = txt(el, "title");
                    String link  = txt(el, "link");
                    String desc  = txt(el, "description");
                    String date  = txt(el, "pubDate");
                    String low = (title + " " + desc).toLowerCase();
                    boolean isSubasta = low.contains("subasta") || low.contains("adjudicaci");
                    boolean isInmueble = low.contains("vivienda") || low.contains("inmueble")
                            || low.contains("finca") || low.contains("local")
                            || low.contains("solar") || low.contains("piso")
                            || low.contains("nave") || low.contains("oficina");
                    if (!isSubasta || !isInmueble) continue;
                    String tipo = guessTipo(low);
                    out.add(new SubastaHit(
                        clean(title, 160), link, shortDate(date), tipo, clean(desc, 240)
                    ));
                }
            } catch (Exception e) {
                log.debug("Subastas RSS fallo {}: {}", url, e.getMessage());
            }
        }
        out.sort(Comparator.comparing(SubastaHit::fecha).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private String guessTipo(String low) {
        if (low.contains("vivienda") || low.contains("piso")) return "🏠 Vivienda";
        if (low.contains("local"))    return "🏪 Local";
        if (low.contains("oficina"))  return "🏢 Oficina";
        if (low.contains("nave"))     return "🏭 Nave";
        if (low.contains("solar") || low.contains("suelo")) return "🌳 Suelo";
        if (low.contains("garaje"))   return "🅿️ Garaje";
        if (low.contains("rustica") || low.contains("finca")) return "🌾 Rustica";
        return "🏛️ Inmueble";
    }

    private String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private String txt(org.w3c.dom.Element parent, String tag) {
        var n = parent.getElementsByTagName(tag);
        if (n.getLength() == 0) return "";
        String v = n.item(0).getTextContent();
        return v == null ? "" : v.trim();
    }

    private String clean(String raw, int max) {
        if (raw == null) return "";
        String s = raw.replaceAll("<[^>]+>", "")
                      .replaceAll("&nbsp;", " ")
                      .replaceAll("&amp;", "&")
                      .replaceAll("&quot;", "\"")
                      .replaceAll("&#39;", "'")
                      .replaceAll("&lt;", "<")
                      .replaceAll("&gt;", ">")
                      .replaceAll("\\s+", " ").trim();
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    private String shortDate(String rfc822) {
        if (rfc822 == null || rfc822.isBlank()) return "";
        try {
            String[] p = rfc822.split(" ");
            if (p.length < 5) return rfc822;
            return p[1] + " " + p[2].toLowerCase() + " · " + p[4].substring(0, Math.min(5, p[4].length()));
        } catch (Exception e) {
            return rfc822;
        }
    }
}
