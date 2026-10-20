package com.inmobilia.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Servicio multi-divisa con caché de tipos de cambio (base USD).
 * API gratuita sin clave: https://open.er-api.com (incluye todas las monedas de Latinoamérica).
 */
@Service
@Slf4j
public class CurrencyService {

    private static final List<String> DIVISAS = List.of(
            "USD", "MXN", "COP", "ARS", "CLP", "PEN", "UYU", "PYG", "BOB", "CRC", "GTQ", "HNL", "NIO", "DOP",
            "BRL", "EUR");

    /** Valores aproximados por si la API no responde (unidades por 1 USD). */
    private static final Map<String, Double> FALLBACK = Map.ofEntries(
            Map.entry("USD", 1.0), Map.entry("MXN", 18.5), Map.entry("COP", 4000.0), Map.entry("ARS", 1400.0),
            Map.entry("CLP", 950.0), Map.entry("PEN", 3.6), Map.entry("UYU", 40.0), Map.entry("PYG", 7600.0),
            Map.entry("BOB", 6.9), Map.entry("CRC", 505.0), Map.entry("GTQ", 7.7), Map.entry("HNL", 26.0),
            Map.entry("NIO", 36.8), Map.entry("DOP", 62.0), Map.entry("BRL", 5.5), Map.entry("EUR", 0.88));

    private final RestTemplate rest = new RestTemplate();
    private final Map<String, Double> cache = new HashMap<>();
    private long ultimaActualizacion = 0;

    /** Convierte de origen a destino. */
    public double convertir(double importe, String origen, String destino) {
        if (origen.equals(destino)) return importe;
        if (System.currentTimeMillis() - ultimaActualizacion > 3_600_000) actualizarTipos();
        double porUsdOrigen = cache.getOrDefault(origen, FALLBACK.getOrDefault(origen, 1.0));
        double porUsdDestino = cache.getOrDefault(destino, FALLBACK.getOrDefault(destino, 1.0));
        return importe / porUsdOrigen * porUsdDestino;
    }

    public List<String> divisas() { return DIVISAS; }

    public Map<String, Double> tiposActuales() {
        if (cache.isEmpty()) actualizarTipos();
        return new LinkedHashMap<>(cache);
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "${app.zona-horaria:America/Mexico_City}")
    public synchronized void actualizarTipos() {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = rest.getForObject("https://open.er-api.com/v6/latest/USD", Map.class);
            if (resp != null && resp.get("rates") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rates = (Map<String, Object>) resp.get("rates");
                cache.clear();
                for (String d : DIVISAS) {
                    Object v = rates.get(d);
                    if (v != null) cache.put(d, Double.parseDouble(v.toString()));
                }
                ultimaActualizacion = System.currentTimeMillis();
                log.info("Tipos de cambio actualizados: {} divisas", cache.size());
            }
        } catch (Exception e) {
            log.warn("Err update currency: {}", e.getMessage());
            if (cache.isEmpty()) cache.putAll(FALLBACK);
            ultimaActualizacion = System.currentTimeMillis(); // reintentar dentro de una hora, no en cada conversión
        }
    }
}
