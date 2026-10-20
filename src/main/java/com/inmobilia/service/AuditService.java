package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Servicio de auditoría: registra acciones críticas en la tabla audit_log.
 * Asíncrono: no bloquea la respuesta al usuario. Para registrar desde un controlador
 * usa AuditTrail, que extrae la IP y el usuario de la petición.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final SupabaseService supabase;

    @Async
    public void log(String userId, String actorId, String actorNombre, String actorRol,
                    String accion, String tabla, String registroId, String descripcion,
                    Map<String, Object> antes, Map<String, Object> despues,
                    String ip, String userAgent, String metodo, String url,
                    String nivel, String categoria) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            if (userId != null) data.put("user_id", userId);
            if (actorId != null) data.put("actor_id", actorId);
            data.put("actor_nombre", actorNombre);
            data.put("actor_rol", actorRol);
            data.put("accion", accion);
            data.put("tabla", tabla);
            data.put("registro_id", registroId);
            data.put("descripcion", descripcion);
            if (antes != null) data.put("valores_antes", antes);
            if (despues != null) {
                data.put("valores_despues", despues);
                if (antes != null) {
                    List<String> cambios = new ArrayList<>();
                    for (String k : despues.keySet()) {
                        if (!Objects.equals(despues.get(k), antes.get(k))) cambios.add(k);
                    }
                    data.put("campos_cambiados", cambios);
                }
            }
            data.put("ip", ip);
            data.put("user_agent", userAgent);
            data.put("metodo", metodo);
            data.put("url", url);
            data.put("nivel", nivel != null ? nivel : "info");
            data.put("categoria", categoria != null ? categoria : "datos");
            data.put("created_at", OffsetDateTime.now().toString());

            supabase.insert("audit_log", data);
        } catch (Exception e) {
            log.warn("Audit err: {}", e.getMessage());
        }
    }

    @Async
    public void logSimple(String userId, String accion, String descripcion, String nivel) {
        log(userId, userId, null, null, accion, null, null, descripcion,
                null, null, null, null, null, null, nivel, "datos");
    }
}
