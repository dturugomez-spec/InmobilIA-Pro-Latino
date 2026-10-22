package com.inmobilia.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Servicio multi-divisa con caché de tipos de cambio (base USD).
 * API gratuita sin clave: https://open.er-api.com (incluye todas las monedas de Latinoamérica).
 *
 * Una conversión nunca espera a la red: usa los últimos tipos conocidos (o los aproximados de
 * respaldo) y, si están viejos, pide unos nuevos en segundo plano, una sola vez a la vez.
 */
@Service
@Slf4j
public class CurrencyService {

    private static final long CADUCIDAD_MS = 3_600_000;

    private static final List<String> DIVISAS = List.of(
            "USD", "MXN", "COP", "ARS", "CLP", "PEN", "UYU", "PYG", "BOB", "CRC", "GTQ", "HNL", "NIO", "DOP",
            "BRL", "EUR");

    /** Valores aproximados por si la API no responde (unidades por 1 USD). */
    private static final Map<String, Double> FALLBACK = Map.ofEntries(
            Map.entry("USD", 1.0), Map.entry("MXN", 18.5), Map.entry("COP", 4000.0), Map.entry("ARS", 1400.0),
            Map.entry("CLP", 950.0), Map.entry("PEN", 3.6), Map.entry("UYU", 40.0), Map.entry("PYG", 7600.0),
            Map.entry("BOB", 6.9), Map.entry("CRC", 505.0), Map.entry("GTQ", 7.7), Map.entry("HNL", 26.0),
            Map.entry("NIO", 36.8), Map.entry("DOP", 62.0), Map.entry("BRL", 5.5), Map.entry("EUR", 0.88));

    private final RestTemplate rest;
    /** Se sustituye entero (nunca se modifica): las lecturas concurrentes siempre ven un mapa completo. */
    private volatile Map<String, Double> tipos = FALLBACK;
    private volatile long ultimaActualizacion = 0;
    private final AtomicBoolean actualizando = new AtomicBoolean(false);

    public CurrencyService() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3_000);
        f.setReadTimeout(5_000);
        this.rest = new RestTemplate(f);
    }

    /** Convierte de origen a destino. */
    public double convertir(double importe, String origen, String destino) {
        if (origen.equals(destino)) return importe;
        refrescarSiCaducado();
        Map<String, Double> t = tipos;
        double porUsdOrigen = t.getOrDefault(origen, FALLBACK.getOrDefault(origen, 1.0));
        double porUsdDestino = t.getOrDefault(destino, FALLBACK.getOrDefault(destino, 1.0));
        return importe / porUsdOrigen * porUsdDestino;
    }

    public List<String> divisas() { return DIVISAS; }

    public Map<String, Double> tiposActuales() {
        refrescarSiCaducado();
        return new LinkedHashMap<>(tipos);
    }

    private void refrescarSiCaducado() {
        if (System.currentTimeMillis() - ultimaActualizacion > CADUCIDAD_MS && actualizando.compareAndSet(false, true)) {
            CompletableFuture.runAsync(() -> {
                try { actualizarTipos(); } finally { actualizando.set(false); }
            });
        }
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "${app.zona-horaria:America/Mexico_City}")
    public void actualizarTipos() {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = rest.getForObject("https://open.er-api.com/v6/latest/USD", Map.class);
            if (resp != null && resp.get("rates") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rates = (Map<String, Object>) resp.get("rates");
                Map<String, Double> nuevos = new HashMap<>(FALLBACK);
                for (String d : DIVISAS) {
                    Object v = rates.get(d);
                    if (v != null) nuevos.put(d, Double.parseDouble(v.toString()));
                }
                tipos = Map.copyOf(nuevos);
                log.info("Tipos de cambio actualizados: {} divisas", nuevos.size());
            }
        } catch (Exception e) {
            log.warn("Err update currency: {}", e.getMessage()); // se siguen usando los últimos tipos conocidos
        } finally {
            ultimaActualizacion = System.currentTimeMillis(); // reintentar dentro de una hora, no en cada conversión
        }
    }
}
