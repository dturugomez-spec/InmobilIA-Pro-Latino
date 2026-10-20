package com.inmobilia.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.*;

/**
 * APIs publicas: Euribor (BCE), Catastro, Nominatim (OSM), INE IPV, BOE.
 * Port de oficial_get_euribor_*, oficial_catastro_por_rc, oficial_geocode, oficial_ine_ipv, oficial_boe_url_busqueda.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OfficialDataService {

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public record EuriborLive(Double valor, String fecha, String fuente) {}
    public record EuriborHistoryRow(String fecha, Double valor) {}
    public record InePoint(String periodo, Double valor) {}
    public record Geocode(Double lat, Double lon, String display) {}
    public record MortgageRate(Double tedrNuevo, Double tedrVariable, Double tedrFijo, String fecha, String fuente) {}
    public record BoeHit(String titulo, String url, String fecha) {}
    public record CatastroDireccionHit(String rc, String direccion, String uso, String m2) {}

    @Cacheable(value = "euribor", unless = "#result == null || #result.valor() == null")
    public EuriborLive euriborLive() {
        String[][] endpoints = {
            {"https://data-api.ecb.europa.eu/service/data/FM/M.U2.EUR.RT0.MM.EURIBOR12MD_.HSTA?lastNObservations=1&format=jsondata", "BCE SDMX v2"},
            {"https://sdw-wsrest.ecb.europa.eu/service/data/FM/M.U2.EUR.RT0.MM.EURIBOR12MD_.HSTA?lastNObservations=1&format=jsondata", "BCE SDMX v1"}
        };
        for (String[] ep : endpoints) {
            try {
                String body = httpGet(ep[0], "application/json", 7);
                if (body == null) continue;
                JsonNode jd = mapper.readTree(body);
                JsonNode series = jd.path("dataSets").get(0).path("series");
                String firstKey = series.fieldNames().next();
                JsonNode obs = series.path(firstKey).path("observations");
                String last = null;
                int maxK = -1;
                Iterator<String> it = obs.fieldNames();
                while (it.hasNext()) {
                    String k = it.next();
                    int ki = Integer.parseInt(k);
                    if (ki > maxK) { maxK = ki; last = k; }
                }
                if (last == null) continue;
                JsonNode valNode = obs.path(last).get(0);
                if (valNode.isNull()) continue;
                double val = valNode.asDouble();
                String fecha = "";
                try {
                    fecha = jd.path("structure").path("dimensions").path("observation").get(0)
                              .path("values").get(maxK).path("id").asText("");
                } catch (Exception ignored) {}
                return new EuriborLive(val, fecha, ep[1]);
            } catch (Exception e) {
                log.debug("Endpoint BCE fallo: {}", ep[1]);
            }
        }
        return new EuriborLive(null, "", "");
    }

    @Cacheable(value = "euriborHistory")
    public List<EuriborHistoryRow> euriborHistory(int months) {
        String url = "https://data-api.ecb.europa.eu/service/data/FM/M.U2.EUR.RT0.MM.EURIBOR12MD_.HSTA?lastNObservations="
                + months + "&format=jsondata";
        try {
            String body = httpGet(url, "application/json", 10);
            if (body == null) return List.of();
            JsonNode jd = mapper.readTree(body);
            JsonNode series = jd.path("dataSets").get(0).path("series");
            String key = series.fieldNames().next();
            JsonNode obs = series.path(key).path("observations");
            JsonNode times = jd.path("structure").path("dimensions").path("observation").get(0).path("values");
            List<EuriborHistoryRow> rows = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> it = obs.fields();
            while (it.hasNext()) {
                var e = it.next();
                JsonNode v = e.getValue().get(0);
                if (v == null || v.isNull()) continue;
                rows.add(new EuriborHistoryRow(times.get(Integer.parseInt(e.getKey())).path("id").asText(""), v.asDouble()));
            }
            rows.sort(Comparator.comparing(EuriborHistoryRow::fecha));
            return rows;
        } catch (Exception e) {
            log.warn("Error historico Euribor: {}", e.getMessage());
            return List.of();
        }
    }

    @Cacheable(value = "catastro", key = "#rc")
    public Map<String, Object> catastroPorRc(String rc) {
        Map<String, Object> out = new LinkedHashMap<>();
        String v = rc == null ? "" : rc.trim().toUpperCase();
        if (v.length() != 20) {
            out.put("error", "La referencia catastral debe tener 20 caracteres.");
            return out;
        }
        String url = "https://ovc.catastro.meh.es/ovcservweb/OVCSWLocalizacionRC/OVCCallejero.asmx/Consulta_DNPRC?Provincia=&Municipio=&RC=" + v;
        try {
            String body = httpGet(url, "application/xml", 10);
            if (body == null) { out.put("error", "Sin respuesta de Catastro."); return out; }
            var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            out.put("rc", v);
            out.put("direccion",   firstText(doc, "dir", "ldt"));
            out.put("municipio",   firstText(doc, "nm"));
            out.put("provincia",   firstText(doc, "np"));
            out.put("uso",         firstText(doc, "use"));
            out.put("sup_construida_m2", firstText(doc, "sfc"));
            out.put("sup_suelo_m2",      firstText(doc, "sfs"));
            out.put("anio_construccion", firstText(doc, "ant"));
            out.put("n_plantas",         firstText(doc, "npt"));
            out.put("clase",             firstText(doc, "cn"));
            boolean empty = out.entrySet().stream().filter(e -> !e.getKey().equals("rc"))
                    .allMatch(e -> e.getValue() == null || String.valueOf(e.getValue()).isBlank());
            if (empty) {
                out.clear();
                out.put("error", "Sin resultados en Catastro.");
            }
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    private String firstText(org.w3c.dom.Document doc, String... tags) {
        for (String t : tags) {
            var n = doc.getElementsByTagName(t);
            if (n.getLength() > 0) {
                String v = n.item(0).getTextContent();
                if (v != null && !v.isBlank()) return v.trim();
            }
        }
        return "";
    }

    @Cacheable(value = "geocode", key = "#address", unless = "#result == null")
    public Geocode geocode(String address) {
        if (address == null || address.trim().length() < 3) return null;
        try {
            String url = "https://nominatim.openstreetmap.org/search?q="
                    + URLEncoder.encode(address, StandardCharsets.UTF_8)
                    + "&format=json&limit=1&countrycodes=es&addressdetails=1";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "InmobilIAPro/1.0 (contacto@inmobilia.local)")
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            JsonNode data = mapper.readTree(resp.body());
            if (!data.isArray() || data.size() == 0) return null;
            JsonNode h = data.get(0);
            return new Geocode(h.path("lat").asDouble(), h.path("lon").asDouble(),
                    h.path("display_name").asText(address));
        } catch (Exception e) {
            log.warn("Geocode fallo: {}", e.getMessage());
            return null;
        }
    }

    @Cacheable(value = "ine")
    public List<InePoint> ineIpv() {
        try {
            String body = httpGet("https://servicios.ine.es/wstempus/js/ES/DATOS_SERIE/IPV4411?nult=12",
                    "application/json", 10);
            if (body == null) return List.of();
            JsonNode jd = mapper.readTree(body);
            List<InePoint> rows = new ArrayList<>();
            for (JsonNode d : jd.path("Data")) {
                rows.add(new InePoint(d.path("Anyo").asText() + "T" + d.path("FK_Periodo").asText(),
                        d.path("Valor").asDouble()));
            }
            return rows;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Variacion Euribor 12M: (actual - hace 12 meses) en puntos porcentuales.
     * Usa el historico para evitar llamadas extra.
     */
    public Double euriborDelta12m() {
        List<EuriborHistoryRow> h = euriborHistory(13);
        if (h == null || h.size() < 2) return null;
        double last = h.get(h.size() - 1).valor();
        double prev = h.get(0).valor();
        return last - prev;
    }

    /** Sparkline: ultimos N valores como array double. */
    public double[] euriborSpark(int n) {
        List<EuriborHistoryRow> h = euriborHistory(n);
        if (h == null || h.isEmpty()) return new double[0];
        double[] arr = new double[h.size()];
        for (int i = 0; i < h.size(); i++) arr[i] = h.get(i).valor();
        return arr;
    }

    /**
     * IPV: variacion interanual entre el primer y ultimo punto devueltos (%).
     */
    public Double ipvYoY() {
        List<InePoint> pts = ineIpv();
        if (pts == null || pts.size() < 2) return null;
        double last = pts.get(0).valor();
        double prev = pts.get(pts.size() - 1).valor();
        if (prev == 0) return null;
        return ((last - prev) / prev) * 100.0;
    }

    /**
     * Banco de Espana: tipo efectivo hipotecas vivienda (TEDR).
     * Fuente publica via tabla BdE. Si falla, devuelve valores nulos.
     */
    @Cacheable(value = "tedr", unless = "#result == null || #result.tedrNuevo() == null")
    public MortgageRate bancoDeEspanaTedr() {
        try {
            String body = httpGet("https://www.bde.es/webbe/es/estadisticas/compartido/datos/csv/tipos_interes_cuadro.csv",
                    "text/csv", 8);
            if (body == null) return new MortgageRate(null, null, null, "", "");
            String[] lines = body.split("\\r?\\n");
            Double nuevo = null, variable = null, fijo = null;
            String fecha = "";
            for (String ln : lines) {
                String low = ln.toLowerCase();
                String[] f = ln.split("[;,]");
                if (f.length < 2) continue;
                String last = f[f.length - 1].replace(",", ".").trim();
                Double v = parseD(last);
                if (v == null) continue;
                if (low.contains("hogares") && low.contains("vivienda") && nuevo == null) nuevo = v;
                if (low.contains("variable") && variable == null) variable = v;
                if (low.contains("fijo") && fijo == null) fijo = v;
            }
            if (nuevo == null && variable == null && fijo == null) {
                return new MortgageRate(null, null, null, "", "BdE (sin datos)");
            }
            return new MortgageRate(nuevo, variable, fijo, fecha, "Banco de Espana");
        } catch (Exception e) {
            log.debug("TEDR BdE fallo: {}", e.getMessage());
            return new MortgageRate(null, null, null, "", "");
        }
    }

    private Double parseD(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return null; }
    }

    /**
     * Catastro por direccion: prov + muni + tipo via + via + numero → lista de inmuebles.
     * Fuente: OVCSWLocalizacionRC/OVCCallejero.asmx
     */
    public List<CatastroDireccionHit> catastroPorDireccion(String provincia, String municipio,
                                                            String tipoVia, String nombreVia, String numero) {
        List<CatastroDireccionHit> out = new ArrayList<>();
        String url = "https://ovc.catastro.meh.es/ovcservweb/OVCSWLocalizacionRC/OVCCallejero.asmx/Consulta_DNPLOC"
                + "?Provincia=" + enc(provincia) + "&Municipio=" + enc(municipio)
                + "&Sigla=" + enc(tipoVia) + "&Calle=" + enc(nombreVia)
                + "&Numero=" + enc(numero) + "&Bloque=&Escalera=&Planta=&Puerta=";
        try {
            String body = httpGet(url, "application/xml", 10);
            if (body == null) return out;
            var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            var bis = doc.getElementsByTagName("bi");
            for (int i = 0; i < bis.getLength() && i < 25; i++) {
                var el = (org.w3c.dom.Element) bis.item(i);
                String pc1 = textOf(el, "pc1"), pc2 = textOf(el, "pc2");
                String car = textOf(el, "car"), cc1 = textOf(el, "cc1"), cc2 = textOf(el, "cc2");
                String rc = (pc1 + pc2 + car + cc1 + cc2).trim();
                String dir = textOf(el, "ldt");
                if (dir.isBlank()) dir = textOf(el, "dir");
                out.add(new CatastroDireccionHit(rc, dir, textOf(el, "luso"), textOf(el, "sfc")));
            }
        } catch (Exception e) {
            log.debug("Catastro direccion fallo: {}", e.getMessage());
        }
        return out;
    }

    private String textOf(org.w3c.dom.Element el, String tag) {
        var n = el.getElementsByTagName(tag);
        if (n.getLength() == 0) return "";
        String v = n.item(0).getTextContent();
        return v == null ? "" : v.trim();
    }

    private String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    public String boeSearchUrl(String query) {
        return "https://www.boe.es/buscar/doc.php?coleccion=iberlex&campo%5B0%5D=ID_SRCH&dato%5B0%5D="
                + URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8);
    }

    private String httpGet(String url, String accept, int timeoutSec) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .header("Accept", accept)
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) return resp.body();
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
