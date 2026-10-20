package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agregador de noticias RSS de medios locales del país de la inmobiliaria
 * especializados en vivienda, alquiler y precios. Cache 30 min para no abusar
 * de los servidores origen.
 *
 * Fuentes:
 *  - El Confidencial Vivienda
 *  - Expansion Vivienda
 *  - Europa Press Inmobiliario
 *  - Noticias de normativa de vivienda (regulatorio)
 *
 * Si todas las fuentes fallan (sin internet), devuelve lista vacia sin romper la UI.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NewsService {

    private final HttpClient httpClient;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    // API de noticias (opcional): si hay GNEWS_API_KEY, se usa GNews (funciona desde datacenter).
    @org.springframework.beans.factory.annotation.Value("${gnews.api-key:${GNEWS_API_KEY:}}")
    private String gnewsKey;

    public record Article(
        String title, String summary, String url,
        String source, String sourceIcon, String pubDate,
        String category
    ) {}

    private record Feed(String url, String source, String icon, String category) {}

    // Google News RSS por país: agrega muchas fuentes locales, fechas actuales y NO bloquea IPs de
    // datacenter (a diferencia de los RSS directos de los medios, que rechazan a Railway).
    private static String googleNews(String query) {
        String gl = com.inmobilia.util.PaisActual.get().codigo();
        return "https://news.google.com/rss/search?q=" + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&hl=es-419&gl=" + gl + "&ceid=" + gl + ":es-419";
    }

    private static List<Feed> marketFeeds() {
        return List.of(
            new Feed(googleNews("vivienda OR inmobiliario OR alquiler OR renta OR hipoteca OR crédito hipotecario"),
                     "Actualidad", "", "Vivienda"),
            new Feed(googleNews("mercado inmobiliario OR precio de la vivienda OR bienes raíces OR desarrollo inmobiliario"),
                     "Mercado", "", "Vivienda"));
    }

    private static List<Feed> regulatoryFeeds() {
        return List.of(
            new Feed(googleNews("ley de vivienda OR ley de arrendamiento OR reforma inmobiliaria OR impuesto predial"),
                     "Normativa", "", "Legal"),
            new Feed(googleNews("decreto vivienda OR regulación alquiler OR registro de la propiedad OR catastro"),
                     "Normativa", "", "Legal"));
    }

    // Palabras clave para quedarnos solo con noticias inmobiliarias de los feeds de economía general.
    private static final String[] KW_INMO = {"vivienda","alquiler","arrendamiento","hipoteca","inmobil",
        "departamento","apartamento","bienes raíces","catastro","urbanismo","desalojo","predial",
        "preventa","desarrollador","arrendador","inquilino","metro cuadrado","precio del suelo","renta"};

    private boolean esInmobiliario(Article a) {
        String low = (a.title() + " " + a.summary()).toLowerCase();
        for (String k : KW_INMO) if (low.contains(k)) return true;
        return false;
    }

    /** Refresco periódico: evacúa la caché para que las noticias no se queden congeladas. */
    @CacheEvict(value = {"newsMarket", "newsRegulatory"}, allEntries = true)
    @Scheduled(fixedRate = 3L * 60 * 60 * 1000)
    public void refrescarNoticias() {
        log.info("Noticias: caché evacuada para refresco periódico");
    }

    @Cacheable(value = "newsMarket", key = "T(com.inmobilia.util.PaisActual).get().codigo() + '-' + #limit", unless = "#result == null || #result.isEmpty()")
    public List<Article> marketNews(int limit) {
        List<Article> all = new ArrayList<>();
        if (gnewsKey != null && !gnewsKey.isBlank()) {
            // Fuente preferente: API GNews (funciona desde la IP de Railway; los RSS directos la bloquean)
            all.addAll(fetchGnews("vivienda OR inmobiliario OR alquiler OR hipoteca", 10));
            all.addAll(fetchGnews("mercado inmobiliario OR precio vivienda OR bienes raíces", 10));
        } else {
            for (Feed f : marketFeeds()) {
                try {
                    boolean filtrar = "Economia".equals(f.category());
                    for (Article a : fetch(f)) if (!filtrar || esInmobiliario(a)) all.add(a);
                } catch (Exception e) {
                    log.debug("RSS fallo {}: {}", f.source(), e.getMessage());
                }
            }
        }
        // Quitar duplicados por URL, manteniendo orden
        Map<String, Article> unicos = new LinkedHashMap<>();
        for (Article a : all) unicos.putIfAbsent(a.url(), a);
        List<Article> res = new ArrayList<>(unicos.values());
        res.sort(Comparator.comparing(Article::pubDate).reversed());
        return res.size() > limit ? res.subList(0, limit) : res;
    }

    /** Trae noticias de la API GNews (JSON). Gratis hasta 100 peticiones/día; con la caché sobra. */
    private List<Article> fetchGnews(String query, int max) {
        List<Article> out = new ArrayList<>();
        try {
            String url = "https://gnews.io/api/v4/search?q=" + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&lang=es&country=" + com.inmobilia.util.PaisActual.get().codigo().toLowerCase() + "&max=" + max + "&apikey=" + gnewsKey;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.debug("GNews HTTP {}: {}", resp.statusCode(), resp.body());
                return out;
            }
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(resp.body());
            for (com.fasterxml.jackson.databind.JsonNode a : root.path("articles")) {
                String title = a.path("title").asText("");
                String link = a.path("url").asText("");
                if (title.isBlank() || link.isBlank()) continue;
                String fecha = a.path("publishedAt").asText("");
                try {
                    fecha = java.time.OffsetDateTime.parse(fecha)
                            .format(java.time.format.DateTimeFormatter.ofPattern("dd MMM · HH:mm", new java.util.Locale("es")));
                } catch (Exception ignored) {}
                out.add(new Article(cleanHtml(title, 140), cleanHtml(a.path("description").asText(""), 220), link,
                        a.path("source").path("name").asText("Noticias"), "", fecha, "Mercado"));
            }
        } catch (Exception e) {
            log.debug("GNews fallo: {}", e.getMessage());
        }
        return out;
    }

    @Cacheable(value = "newsRegulatory", key = "T(com.inmobilia.util.PaisActual).get().codigo() + '-' + #limit", unless = "#result == null || #result.isEmpty()")
    public List<Article> regulatoryNews(int limit) {
        List<Article> all = new ArrayList<>();
        for (Feed f : regulatoryFeeds()) {
            try {
                List<Article> items = fetch(f);
                // Filtrar solo los que tengan relacion con vivienda/inmobiliario
                for (Article a : items) {
                    String low = (a.title() + " " + a.summary()).toLowerCase();
                    if (low.contains("vivienda") || low.contains("alquiler") || low.contains("arrendamiento")
                            || low.contains("hipoteca") || low.contains("inmobil") || low.contains("urbanismo")
                            || low.contains("catastro") || low.contains("suelo") || low.contains("predial")
                            || low.contains("renta") || low.contains("registro de la propiedad")) {
                        all.add(a);
                    }
                }
            } catch (Exception e) {
                log.debug("RSS regulatorio fallo: {}", e.getMessage());
            }
        }
        all.sort(Comparator.comparing(Article::pubDate).reversed());
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    private List<Article> fetch(Feed feed) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(feed.url()))
                .timeout(Duration.ofSeconds(7))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36")
                .header("Accept", "application/rss+xml,application/xml,text/xml,*/*")
                .GET().build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) return List.of();

        List<Article> out = new ArrayList<>();
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(resp.body().getBytes(StandardCharsets.UTF_8)));
        var items = doc.getElementsByTagName("item");
        int max = Math.min(items.getLength(), 25);
        for (int i = 0; i < max; i++) {
            var el = (org.w3c.dom.Element) items.item(i);
            String title = text(el, "title");
            String link  = text(el, "link");
            String desc  = text(el, "description");
            String date  = text(el, "pubDate");
            if (title.isBlank() || link.isBlank()) continue;
            out.add(new Article(
                cleanHtml(title, 140),
                cleanHtml(desc, 220),
                link,
                feed.source(),
                feed.icon(),
                shortDate(date),
                feed.category()
            ));
        }
        return out;
    }

    private String text(org.w3c.dom.Element parent, String tag) {
        var n = parent.getElementsByTagName(tag);
        if (n.getLength() == 0) return "";
        var item = n.item(0);
        String v = item.getTextContent();
        return v == null ? "" : v.trim();
    }

    private String cleanHtml(String raw, int max) {
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

    /**
     * Convierte pubDate RFC-822 ("Wed, 24 Apr 2026 10:30:00 +0200")
     * a formato corto "24 abr · 10:30".
     */
    private String shortDate(String rfc822) {
        if (rfc822 == null || rfc822.isBlank()) return "";
        try {
            String[] parts = rfc822.split(" ");
            if (parts.length < 5) return rfc822;
            String day   = parts[1];
            String month = parts[2].toLowerCase();
            String time  = parts[4];
            if (time.length() >= 5) time = time.substring(0, 5);
            return day + " " + month + " · " + time;
        } catch (Exception e) {
            return rfc822.length() > 16 ? rfc822.substring(5, 16) : rfc822;
        }
    }
}
