package com.inmobilia.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Integración con el Catastro español (Dirección General del Catastro).
 *
 * APIs públicas usadas (gratuitas, sin token):
 *  - OVCCallejero: consulta inmueble por referencia catastral
 *  - OVCCoordenadas: obtiene ref. catastral por lat/lng
 *  - DNPRC: datos no protegidos del inmueble
 *
 * Endpoint base: https://ovc.catastro.meh.es/OVCServWeb/OVCWcfCallejero/...
 */
@Service
@Slf4j
public class CatastroService {

    private static final String BASE_URL = "https://ovc.catastro.meh.es/OVCServWeb/OVCWcfCallejero/COVCCallejero.svc/json";
    private static final Pattern RC_PATTERN = Pattern.compile("^[0-9A-Z]{20}$");

    private final RestTemplate restTemplate = new RestTemplate();

    /** Consulta datos de un inmueble por su referencia catastral. */
    public Map<String, Object> consultarPorRC(String referenciaCatastral) {
        if (referenciaCatastral == null || !RC_PATTERN.matcher(referenciaCatastral.toUpperCase()).matches()) {
            return Map.of("ok", false, "error", "Referencia catastral inválida (debe tener 20 caracteres)");
        }
        try {
            String url = BASE_URL + "/Consulta_DNPRC?RefCat=" + referenciaCatastral.toUpperCase();
            Map<?, ?> response = restTemplate.getForObject(URI.create(url), Map.class);
            return parseRespuesta(response);
        } catch (Exception e) {
            log.warn("Catastro RC err: {}", e.getMessage());
            return Map.of("ok", false, "error", "Error consultando Catastro");
        }
    }

    /** Consulta ref. catastral por coordenadas (lat/lng EPSG:4326). */
    public Map<String, Object> consultarPorCoordenadas(double lat, double lng) {
        try {
            String url = BASE_URL + "/Consulta_RCCOOR?SRS=EPSG:4326&Coordenada_X=" + lng + "&Coordenada_Y=" + lat;
            Map<?, ?> response = restTemplate.getForObject(URI.create(url), Map.class);
            return parseRespuesta(response);
        } catch (Exception e) {
            log.warn("Catastro coord err: {}", e.getMessage());
            return Map.of("ok", false, "error", "Error consultando coordenadas");
        }
    }

    /** Búsqueda por dirección (calle, número, municipio, provincia). */
    public Map<String, Object> consultarPorDireccion(String provincia, String municipio, String tipoVia, String calle, String numero) {
        try {
            String url = BASE_URL + "/Consulta_DNPLOC" +
                    "?Provincia=" + enc(provincia) +
                    "&Municipio=" + enc(municipio) +
                    "&Sigla=" + enc(tipoVia != null ? tipoVia : "CL") +
                    "&Calle=" + enc(calle) +
                    "&Numero=" + enc(numero);
            Map<?, ?> response = restTemplate.getForObject(URI.create(url), Map.class);
            return parseRespuesta(response);
        } catch (Exception e) {
            log.warn("Catastro dir err: {}", e.getMessage());
            return Map.of("ok", false, "error", "Error consultando dirección");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseRespuesta(Map<?, ?> raw) {
        if (raw == null) return Map.of("ok", false, "error", "Respuesta vacía");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("raw", raw);

        // Intentar extraer info común (formato del Catastro varía)
        try {
            Object consulta = raw.get("consulta_dnprcResult");
            if (consulta instanceof Map) {
                Map<String, Object> data = (Map<String, Object>) consulta;
                Object bico = data.get("bico");
                if (bico instanceof Map) {
                    Map<String, Object> bicoData = (Map<String, Object>) bico;
                    Map<String, Object> bi = (Map<String, Object>) bicoData.getOrDefault("bi", new LinkedHashMap<>());

                    Object dt = bi.get("dt");
                    if (dt instanceof Map) {
                        Map<String, Object> dtData = (Map<String, Object>) dt;
                        result.put("provincia", obtener(dtData, "loine", "cm"));
                        result.put("municipio", obtener(dtData, "loine", "cm"));
                    }

                    Object debi = bi.get("debi");
                    if (debi instanceof Map) {
                        Map<String, Object> debiData = (Map<String, Object>) debi;
                        result.put("uso", debiData.get("luso"));
                        result.put("ano_construccion", debiData.get("ant"));
                        result.put("superficie_construida", debiData.get("sfc"));
                        result.put("clase", debiData.get("cn"));
                    }
                }
            }
        } catch (Exception e) {
            // dejarlo como está
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private Object obtener(Map<String, Object> map, String... keys) {
        Object curr = map;
        for (String k : keys) {
            if (curr instanceof Map) curr = ((Map<String, Object>) curr).get(k);
            else return null;
        }
        return curr;
    }

    private String enc(String s) {
        return s == null ? "" : URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
