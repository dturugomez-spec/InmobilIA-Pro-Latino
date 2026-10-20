package com.inmobilia.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Servicio multi-divisa con caché de tipos de cambio.
 * API gratuita: https://api.frankfurter.app (Banco Central Europeo)
 */
@Service
@Slf4j
public class CurrencyService {

    private static final List<String> DIVISAS = List.of("EUR", "USD", "GBP", "CHF", "MXN", "ARS", "BRL");
    private final RestTemplate rest = new RestTemplate();
    private final Map<String, Double> cache = new HashMap<>();
    private long ultimaActualizacion = 0;

    /** Convierte de origen a destino. */
    public double convertir(double importe, String origen, String destino) {
        if (origen.equals(destino)) return importe;
        if (System.currentTimeMillis() - ultimaActualizacion > 3_600_000) actualizarTipos();
        double aEur = origen.equals("EUR") ? 1.0 : 1.0 / cache.getOrDefault(origen, 1.0);
        double aDest = destino.equals("EUR") ? 1.0 : cache.getOrDefault(destino, 1.0);
        return importe * aEur * aDest;
    }

    public List<String> divisas() { return DIVISAS; }

    public Map<String, Double> tiposActuales() {
        if (cache.isEmpty()) actualizarTipos();
        return new LinkedHashMap<>(cache);
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "Europe/Madrid")
    public void actualizarTipos() {
        try {
            String url = "https://api.frankfurter.app/latest?from=EUR&to=" + String.join(",",
                    DIVISAS.stream().filter(d -> !d.equals("EUR")).toList());
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = rest.getForObject(url, Map.class);
            if (resp != null && resp.get("rates") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rates = (Map<String, Object>) resp.get("rates");
                cache.clear();
                cache.put("EUR", 1.0);
                rates.forEach((k, v) -> cache.put(k, Double.parseDouble(v.toString())));
                ultimaActualizacion = System.currentTimeMillis();
                log.info("Tipos de cambio actualizados: {} divisas", cache.size());
            }
        } catch (Exception e) {
            log.warn("Err update currency: {}", e.getMessage());
            // Valores aproximados de fallback
            cache.put("EUR", 1.0); cache.put("USD", 1.08); cache.put("GBP", 0.85);
            cache.put("CHF", 0.96); cache.put("MXN", 19.5); cache.put("ARS", 1000.0); cache.put("BRL", 5.4);
        }
    }
}
