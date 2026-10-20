package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 🗑️ Papelera: antes de borrar un registro se guarda una copia completa durante 30 días,
 * para poder restaurarlo desde /trash. Pasado ese plazo se elimina para siempre.
 *
 * Tabla trash (migración V83): org_id y user_id (= la inmobiliaria), table_name, record_id,
 * actor_id, actor_name, snapshot (copia JSON), deleted_at, expires_at.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrashService {

    public static final int DIAS_EN_PAPELERA = 30;

    private final SupabaseService supabase;

    /**
     * Guarda en la papelera la fila {tabla}/{id} de la inmobiliaria. Devuelve false si no se
     * pudo (p. ej. la tabla trash aún no existe): en ese caso el borrado sigue como antes.
     */
    public boolean guardar(String tabla, String id, String orgId, String actorId, String actorNombre) {
        try {
            List<Map<String, Object>> filas = supabase.selectOwned(tabla, id, orgId);
            if (filas.isEmpty()) return false;
            OffsetDateTime ahora = OffsetDateTime.now(ZoneOffset.UTC);
            Map<String, Object> entrada = new LinkedHashMap<>();
            entrada.put("org_id", orgId);
            entrada.put("user_id", orgId);
            entrada.put("table_name", tabla);
            entrada.put("record_id", id);
            entrada.put("actor_id", actorId);
            entrada.put("actor_name", actorNombre);
            entrada.put("snapshot", filas.get(0));
            entrada.put("deleted_at", ahora.toString());
            entrada.put("expires_at", ahora.plusDays(DIAS_EN_PAPELERA).toString());
            Map<String, Object> res = supabase.insert("trash", entrada);
            return res != null && !res.isEmpty();
        } catch (Exception e) {
            log.warn("No se pudo guardar en la papelera {}/{}: {}", tabla, id, e.getMessage());
            return false;
        }
    }

    /** Cada noche elimina para siempre lo que lleva más de 30 días en la papelera. */
    @Scheduled(cron = "0 30 4 * * *", zone = "${app.zona-horaria:America/Mexico_City}")
    public void vaciarCaducados() {
        String ahora = OffsetDateTime.now(ZoneOffset.UTC).toString();
        List<Map<String, Object>> caducados = supabase.selectFilter("trash",
                "select=id,org_id&expires_at=lt." + ahora + "&limit=1000");
        for (Map<String, Object> t : caducados) {
            supabase.delete("trash", String.valueOf(t.get("id")), String.valueOf(t.get("org_id")));
        }
        if (!caducados.isEmpty()) log.info("Papelera: {} elementos caducados eliminados", caducados.size());
    }
}
