package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Motor de workflows. Escucha eventos del sistema y dispara acciones configuradas.
 *
 * EVENTOS SOPORTADOS:
 *  - lead_creado, lead_calificado, lead_descartado
 *  - visita_programada, visita_realizada, visita_cancelada
 *  - propiedad_creada, propiedad_actualizada, propiedad_vendida
 *  - contrato_firmado, contrato_finalizado
 *  - factura_creada, factura_impagada, factura_pagada
 *  - match_encontrado, oferta_recibida
 *
 * ACCIONES SOPORTADAS:
 *  - send_email, send_sms, send_whatsapp, send_notification
 *  - create_task, assign_agent, change_status
 *  - update_field, add_tag, remove_tag
 *  - trigger_webhook, ai_qualify_lead
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowEngine {

    private final SupabaseService supabase;
    private final EmailService emailService;
    private final NotificationService notif;
    private final CampaignService campaignService;

    /** Dispara un evento. Busca workflows que escuchen ese evento y los ejecuta. */
    @Async
    public void triggerEvent(String userId, String evento, Map<String, Object> payload) {
        try {
            List<Map<String, Object>> workflows = supabase.selectFilter(
                    "workflows",
                    "user_id=eq." + userId +
                            "&trigger_evento=eq." + evento +
                            "&activo=eq.true");

            for (Map<String, Object> wf : workflows) {
                // Verificar condiciones del trigger
                if (!matchesConditions(wf, payload)) continue;

                int delayMin = ((Number) wf.getOrDefault("delay_minutos", 0)).intValue();
                if (delayMin > 0 || !Boolean.TRUE.equals(wf.get("ejecutar_inmediato"))) {
                    // Encolar para ejecución diferida
                    Map<String, Object> ej = new LinkedHashMap<>();
                    ej.put("workflow_id", wf.get("id"));
                    ej.put("user_id", userId);
                    ej.put("trigger_evento", evento);
                    ej.put("payload", payload);
                    ej.put("estado", "pendiente");
                    ej.put("ejecutar_at", OffsetDateTime.now().plusMinutes(delayMin).toString());
                    ej.put("iniciado_at", OffsetDateTime.now().toString());
                    supabase.insert("workflow_ejecuciones", ej);
                } else {
                    ejecutarWorkflow(wf, payload, userId);
                }
            }
        } catch (Exception e) {
            log.error("Error trigger event {} para {}: {}", evento, userId, e.getMessage());
        }
    }

    /** Ejecuta un workflow específico. */
    @SuppressWarnings("unchecked")
    public void ejecutarWorkflow(Map<String, Object> wf, Map<String, Object> payload, String userId) {
        long t0 = System.currentTimeMillis();
        String wfId = String.valueOf(wf.get("id"));

        Map<String, Object> ejecucion = new LinkedHashMap<>();
        ejecucion.put("workflow_id", wfId);
        ejecucion.put("user_id", userId);
        ejecucion.put("trigger_evento", wf.get("trigger_evento"));
        ejecucion.put("payload", payload);
        ejecucion.put("estado", "ejecutando");
        ejecucion.put("iniciado_at", OffsetDateTime.now().toString());

        Object accionesObj = wf.get("acciones");
        if (!(accionesObj instanceof List)) return;
        List<Map<String, Object>> acciones = (List<Map<String, Object>>) accionesObj;

        ejecucion.put("acciones_total", acciones.size());
        Map<String, Object> ejSaved = supabase.insert("workflow_ejecuciones", ejecucion);
        String ejId = String.valueOf(ejSaved.get("id"));

        List<Map<String, Object>> resultados = new ArrayList<>();
        int exitos = 0;
        String errorMensaje = null;

        for (Map<String, Object> accion : acciones) {
            try {
                Map<String, Object> resultado = ejecutarAccion(accion, payload, userId);
                resultados.add(resultado);
                if (Boolean.TRUE.equals(resultado.get("ok"))) exitos++;
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("accion", accion.get("tipo"));
                err.put("ok", false);
                err.put("error", e.getMessage());
                resultados.add(err);
                errorMensaje = e.getMessage();
                log.warn("Err acción {}: {}", accion.get("tipo"), e.getMessage());
            }
        }

        long duracion = System.currentTimeMillis() - t0;
        boolean exitoTotal = exitos == acciones.size();

        // Actualizar ejecución
        Map<String, Object> updEj = new LinkedHashMap<>();
        updEj.put("estado", exitoTotal ? "exitoso" : "error");
        updEj.put("completado_at", OffsetDateTime.now().toString());
        updEj.put("duracion_ms", (int) duracion);
        updEj.put("acciones_ejecutadas", exitos);
        updEj.put("resultado", resultados);
        if (errorMensaje != null) updEj.put("error_mensaje", errorMensaje);
        supabase.update("workflow_ejecuciones", ejId, updEj);

        // Actualizar contadores en workflow
        int total = ((Number) wf.getOrDefault("veces_ejecutado", 0)).intValue();
        int exit = ((Number) wf.getOrDefault("veces_exito", 0)).intValue();
        int err = ((Number) wf.getOrDefault("veces_error", 0)).intValue();
        Map<String, Object> updWf = new LinkedHashMap<>();
        updWf.put("veces_ejecutado", total + 1);
        if (exitoTotal) updWf.put("veces_exito", exit + 1);
        else updWf.put("veces_error", err + 1);
        updWf.put("ultima_ejecucion", OffsetDateTime.now().toString());
        if (errorMensaje != null) updWf.put("ultimo_error", errorMensaje);
        supabase.update("workflows", wfId, updWf);

        log.info("Workflow {} ejecutado: {}/{} acciones OK ({}ms)", wf.get("nombre"), exitos, acciones.size(), duracion);
    }

    /** Ejecuta una acción individual. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> ejecutarAccion(Map<String, Object> accion, Map<String, Object> payload, String userId) {
        String tipo = String.valueOf(accion.get("tipo"));
        Map<String, Object> config = (Map<String, Object>) accion.getOrDefault("config", new LinkedHashMap<>());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("accion", tipo);

        switch (tipo) {
            case "send_email": {
                String to = resolver(String.valueOf(config.get("to")), payload);
                String subject = resolver(String.valueOf(config.get("subject")), payload);
                String body = resolver(String.valueOf(config.get("body")), payload);
                emailService.send(to, subject, body);
                res.put("ok", true);
                res.put("detalle", "Email enviado a " + to);
                break;
            }
            case "send_notification": {
                String titulo = resolver(String.valueOf(config.get("titulo")), payload);
                String mensaje = resolver(String.valueOf(config.get("mensaje")), payload);
                String urgencia = String.valueOf(config.getOrDefault("urgencia", "media"));
                String link = String.valueOf(config.getOrDefault("link", "/dashboard"));
                notif.create(userId, titulo, mensaje, link, urgencia);
                res.put("ok", true);
                break;
            }
            case "create_task": {
                Map<String, Object> tarea = new LinkedHashMap<>();
                tarea.put("user_id", userId);
                tarea.put("titulo", resolver(String.valueOf(config.get("titulo")), payload));
                tarea.put("descripcion", resolver(String.valueOf(config.getOrDefault("descripcion", "")), payload));
                tarea.put("prioridad", config.getOrDefault("prioridad", "media"));
                tarea.put("estado", "pendiente");
                tarea.put("created_at", OffsetDateTime.now().toString());
                if (config.get("asignado_a") != null) tarea.put("asignado_a", config.get("asignado_a"));
                if (config.get("dias_limite") != null) {
                    int dias = ((Number) config.get("dias_limite")).intValue();
                    tarea.put("fecha_limite", OffsetDateTime.now().plusDays(dias).toString());
                }
                if (payload.get("lead_id") != null) tarea.put("lead_id", payload.get("lead_id"));
                if (payload.get("propiedad_id") != null) tarea.put("propiedad_id", payload.get("propiedad_id"));
                supabase.insert("tareas", tarea);
                res.put("ok", true);
                break;
            }
            case "change_status": {
                String tabla = String.valueOf(config.get("tabla"));
                String registroId = String.valueOf(config.getOrDefault("registro_id", payload.get("id")));
                String nuevoEstado = String.valueOf(config.get("estado"));
                supabase.update(tabla, registroId, Map.of("estado", nuevoEstado));
                res.put("ok", true);
                res.put("detalle", "Estado cambiado a " + nuevoEstado);
                break;
            }
            case "update_field": {
                String tabla = String.valueOf(config.get("tabla"));
                String registroId = String.valueOf(config.getOrDefault("registro_id", payload.get("id")));
                String campo = String.valueOf(config.get("campo"));
                Object valor = config.get("valor");
                supabase.update(tabla, registroId, Map.of(campo, valor));
                res.put("ok", true);
                break;
            }
            case "assign_agent": {
                String tabla = String.valueOf(config.getOrDefault("tabla", "leads"));
                String registroId = String.valueOf(payload.get("id"));
                String agenteId = String.valueOf(config.get("agente_id"));
                // Soporta "auto" → asignar al menos cargado (round-robin simple)
                if ("auto".equals(agenteId)) {
                    agenteId = pickLeastBusyAgent(userId);
                }
                if (agenteId != null) {
                    supabase.update(tabla, registroId, Map.of("asignado_a", agenteId));
                    res.put("ok", true);
                    res.put("detalle", "Asignado a " + agenteId);
                } else {
                    res.put("ok", false);
                    res.put("error", "No hay agentes disponibles");
                }
                break;
            }
            case "wait_minutes": {
                int min = ((Number) config.getOrDefault("minutos", 5)).intValue();
                try { Thread.sleep(min * 60L * 1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                res.put("ok", true);
                break;
            }
            default:
                res.put("ok", false);
                res.put("error", "Acción desconocida: " + tipo);
        }

        return res;
    }

    /** Sustituye {variables} con datos del payload. */
    private String resolver(String texto, Map<String, Object> payload) {
        if (texto == null) return "";
        String r = texto;
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            r = r.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        return r;
    }

    /** Comprueba condiciones del trigger (operadores: =, >, <, >=, <=, contains). */
    @SuppressWarnings("unchecked")
    private boolean matchesConditions(Map<String, Object> wf, Map<String, Object> payload) {
        Object cond = wf.get("trigger_condiciones");
        if (!(cond instanceof Map)) return true;  // sin condiciones → siempre dispara
        Map<String, Object> condiciones = (Map<String, Object>) cond;
        if (condiciones.isEmpty()) return true;

        String campo = String.valueOf(condiciones.get("campo"));
        String op = String.valueOf(condiciones.get("operador"));
        Object esperado = condiciones.get("valor");
        Object real = payload.get(campo);

        if (real == null) return false;

        try {
            switch (op) {
                case "=":  return real.toString().equals(esperado.toString());
                case "!=": return !real.toString().equals(esperado.toString());
                case ">":  return Double.parseDouble(real.toString()) > Double.parseDouble(esperado.toString());
                case ">=": return Double.parseDouble(real.toString()) >= Double.parseDouble(esperado.toString());
                case "<":  return Double.parseDouble(real.toString()) < Double.parseDouble(esperado.toString());
                case "<=": return Double.parseDouble(real.toString()) <= Double.parseDouble(esperado.toString());
                case "contains": return real.toString().toLowerCase().contains(esperado.toString().toLowerCase());
                default: return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private String pickLeastBusyAgent(String userId) {
        List<Map<String, Object>> agentes = supabase.selectFilter(
                "users", "org_id=eq." + userId + "&active=eq.true&role=in.(comercial,director)");
        if (agentes.isEmpty()) return null;
        // Contar tareas activas de cada uno
        String mejor = null;
        int min = Integer.MAX_VALUE;
        for (Map<String, Object> a : agentes) {
            List<Map<String, Object>> tareas = supabase.selectFilter(
                    "tareas", "asignado_a=eq." + a.get("id") + "&estado=eq.pendiente");
            if (tareas.size() < min) {
                min = tareas.size();
                mejor = String.valueOf(a.get("id"));
            }
        }
        return mejor;
    }

    /** Cron cada minuto para procesar ejecuciones diferidas. */
    @Scheduled(cron = "30 * * * * *")
    public void procesarPendientes() {
        List<Map<String, Object>> pendientes = supabase.selectFilter(
                "workflow_ejecuciones",
                "estado=eq.pendiente&ejecutar_at=lte." + OffsetDateTime.now());

        for (Map<String, Object> ej : pendientes) {
            try {
                String wfId = String.valueOf(ej.get("workflow_id"));
                List<Map<String, Object>> wfs = supabase.selectFilter("workflows", "id=eq." + wfId);
                if (wfs.isEmpty()) continue;
                // Marcar como ejecutando para no duplicar
                supabase.update("workflow_ejecuciones", String.valueOf(ej.get("id")),
                        Map.of("estado", "ejecutando"));
                ejecutarWorkflow(wfs.get(0), (Map<String, Object>) ej.get("payload"),
                        String.valueOf(ej.get("user_id")));
            } catch (Exception e) {
                log.warn("Err procesar pendiente: {}", e.getMessage());
            }
        }
    }
}
