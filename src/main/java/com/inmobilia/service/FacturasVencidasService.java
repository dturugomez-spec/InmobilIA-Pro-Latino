package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cada mañana: las facturas emitidas cuyo vencimiento ya pasó pasan a "Vencida" y lanzan la
 * automatización "factura impagada" (recordatorio al cliente, tarea de cobro...). Antes nada
 * marcaba una factura como vencida, así que esa automatización no podía saltar nunca.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FacturasVencidasService {

    private final SupabaseService supabase;
    private final WorkflowEngine workflows;

    @Scheduled(cron = "0 15 8 * * *", zone = "${app.zona-horaria:America/Mexico_City}")
    public void revisar() {
        String hoy = LocalDate.now(com.inmobilia.util.PaisActual.get().zonaId()).toString();
        List<Map<String, Object>> vencidas = supabase.selectFilter("invoices",
                "status=eq.Emitida&vencimiento=lt." + hoy + "&order=vencimiento.asc&limit=1000");
        int n = 0;
        for (Map<String, Object> f : vencidas) {
            String org = String.valueOf(f.get("user_id"));
            if (supabase.update("invoices", String.valueOf(f.get("id")), Map.of("status", "Vencida"), org)) {
                Map<String, Object> evento = new LinkedHashMap<>(f);
                evento.put("status", "Vencida");
                workflows.triggerEvent(org, "factura_impagada", evento);
                n++;
            }
        }
        if (n > 0) log.info("[CRON] {} facturas pasadas a Vencida", n);
    }
}
