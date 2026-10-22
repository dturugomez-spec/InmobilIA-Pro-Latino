package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Motor de automatizaciones. Cuando pasa algo en la app (lead nuevo, visita realizada, factura
 * vencida…) busca las automatizaciones activas de esa inmobiliaria para ese evento y ejecuta sus
 * acciones.
 *
 * EVENTOS: ver {@link #EVENTOS}; cada uno se lanza desde donde ocurre de verdad (formularios,
 * CRUD, visitas, firmas, facturas, matching, embudos y la revisión diaria de facturas vencidas).
 *
 * ACCIONES: send_email, send_notification, create_task, change_status, update_field,
 * assign_agent, wait_minutes.
 *
 * Seguridad: las acciones que escriben solo tocan tablas de negocio permitidas y siempre filtradas
 * por la inmobiliaria (antes tabla y registro salían tal cual de la configuración: con un flujo
 * preparado se podía escribir en datos de otra inmobiliaria o en la tabla de usuarios).
 * Esperas: wait_minutes ya no duerme un hilo; deja el resto del flujo programado para más tarde.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowEngine {

    /** Eventos que la app lanza de verdad (y que ofrece el editor). */
    public static final Set<String> EVENTOS = Set.of(
            "lead_creado", "lead_calificado", "visita_programada", "visita_realizada", "visita_cancelada",
            "propiedad_creada", "propiedad_vendida", "contrato_firmado", "factura_creada", "factura_impagada",
            "factura_pagada", "match_encontrado", "kanban_card_movida");

    /** Tablas en las que una automatización puede cambiar datos, y su columna de estado. */
    static final Map<String, String> COLUMNA_ESTADO = Map.of(
            "leads", "etapa", "propiedades", "status", "tareas", "estado", "visitas", "estado",
            "oportunidades", "status", "contratos", "status", "inquilinos", "status", "invoices", "status",
            "reservas", "status");
    private static final Set<String> CAMPOS_PROTEGIDOS = Set.of("id", "user_id", "org_id", "organization_id", "created_at");
    /** Clave interna del payload: acción por la que se sigue tras una espera. */
    static final String DESDE = "__desde";


    private final SupabaseService supabase;
    private final EmailService emailService;
    private final NotificationService notif;
    private final CampaignService campaignService;
    private final com.inmobilia.service.PaisService paises;

    /** Dispara un evento. Busca workflows que escuchen ese evento y los ejecuta. */
    @Async
    public void triggerEvent(String userId, String evento, Map<String, Object> payload) {
        if (userId == null || userId.isBlank() || "null".equals(userId)) return;
        try {
            List<Map<String, Object>> workflows = supabase.selectFilter(
                    "workflows",
                    "user_id=eq." + userId +
                            "&trigger_evento=eq." + evento +
                            "&activo=eq.true");

            for (Map<String, Object> wf : workflows) {
                Map<String, Object> datos = new LinkedHashMap<>(payload == null ? Map.of() : payload);
                completarConLead(datos, userId);
                if (!matchesConditions(wf, datos)) continue;

                int delayMin = num(wf.get("delay_minutos"));
                Instant cuando = Instant.now().plus(delayMin, ChronoUnit.MINUTES);
                if (Boolean.TRUE.equals(wf.get("solo_horario_laboral"))) cuando = siguienteHoraLaboral(cuando);
                if (cuando.isAfter(Instant.now().plusSeconds(30)) || !Boolean.TRUE.equals(wf.get("ejecutar_inmediato"))) {
                    encolar(wf, datos, userId, evento, cuando);
                } else {
                    ejecutarWorkflow(wf, datos, userId);
                }
            }
        } catch (Exception e) {
            log.error("Error trigger event {} para {}: {}", evento, userId, e.getMessage());
        }
    }

    private void encolar(Map<String, Object> wf, Map<String, Object> payload, String userId, String evento, Instant cuando) {
        Map<String, Object> ej = new LinkedHashMap<>();
        ej.put("workflow_id", wf.get("id"));
        ej.put("user_id", userId);
        ej.put("trigger_evento", evento);
        ej.put("payload", payload);
        ej.put("estado", "pendiente");
        ej.put("ejecutar_at", cuando.toString());
        ej.put("iniciado_at", Instant.now().toString());
        supabase.insert("workflow_ejecuciones", ej);
    }

    /** Ejecuta un workflow (desde la acción __desde si viene de una espera). */
    @SuppressWarnings("unchecked")
    public void ejecutarWorkflow(Map<String, Object> wf, Map<String, Object> payload, String userId) {
        long t0 = System.currentTimeMillis();
        String wfId = String.valueOf(wf.get("id"));
        Map<String, Object> datos = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        int desde = num(datos.remove(DESDE));

        Object accionesObj = wf.get("acciones");
        if (!(accionesObj instanceof List)) return;
        List<Map<String, Object>> acciones = (List<Map<String, Object>>) accionesObj;

        Map<String, Object> ejecucion = new LinkedHashMap<>();
        ejecucion.put("workflow_id", wfId);
        ejecucion.put("user_id", userId);
        ejecucion.put("trigger_evento", wf.get("trigger_evento"));
        ejecucion.put("payload", datos);
        ejecucion.put("estado", "ejecutando");
        ejecucion.put("iniciado_at", OffsetDateTime.now().toString());
        ejecucion.put("acciones_total", acciones.size());
        Map<String, Object> ejSaved = supabase.insert("workflow_ejecuciones", ejecucion);

        List<Map<String, Object>> resultados = new ArrayList<>();
        int exitos = 0, ejecutadas = 0;
        String errorMensaje = null;
        boolean enPausa = false;

        for (int i = desde; i < acciones.size(); i++) {
            Map<String, Object> accion = acciones.get(i);
            if ("wait_minutes".equals(String.valueOf(accion.get("tipo")))) {
                // El resto del flujo se programa para dentro de X minutos (sin bloquear nada)
                Map<String, Object> cfg = accion.get("config") instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of();
                int min = Math.max(1, num(cfg.getOrDefault("minutos", 5)));
                Map<String, Object> resto = new LinkedHashMap<>(datos);
                resto.put(DESDE, i + 1);
                Instant cuando = Instant.now().plus(min, ChronoUnit.MINUTES);
                if (Boolean.TRUE.equals(wf.get("solo_horario_laboral"))) cuando = siguienteHoraLaboral(cuando);
                encolar(wf, resto, userId, String.valueOf(wf.get("trigger_evento")), cuando);
                resultados.add(Map.of("accion", "wait_minutes", "ok", true, "detalle", "Sigue en " + min + " min"));
                exitos++;
                ejecutadas++;
                enPausa = true;
                break;
            }
            ejecutadas++;
            try {
                Map<String, Object> resultado = ejecutarAccion(accion, datos, userId);
                resultados.add(resultado);
                if (Boolean.TRUE.equals(resultado.get("ok"))) exitos++;
                else if (resultado.get("error") != null) errorMensaje = String.valueOf(resultado.get("error"));
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
        boolean exitoTotal = exitos == ejecutadas;

        if (ejSaved != null) {
            Map<String, Object> updEj = new LinkedHashMap<>();
            updEj.put("estado", !exitoTotal ? "error" : enPausa ? "en_espera" : "exitoso");
            updEj.put("completado_at", OffsetDateTime.now().toString());
            updEj.put("duracion_ms", (int) duracion);
            updEj.put("acciones_ejecutadas", exitos);
            updEj.put("resultado", resultados);
            if (errorMensaje != null) updEj.put("error_mensaje", errorMensaje);
            supabase.update("workflow_ejecuciones", String.valueOf(ejSaved.get("id")), updEj, userId);
        }

        if (!enPausa || !exitoTotal) {
            Map<String, Object> updWf = new LinkedHashMap<>();
            updWf.put("veces_ejecutado", num(wf.get("veces_ejecutado")) + 1);
            if (exitoTotal) updWf.put("veces_exito", num(wf.get("veces_exito")) + 1);
            else updWf.put("veces_error", num(wf.get("veces_error")) + 1);
            updWf.put("ultima_ejecucion", OffsetDateTime.now().toString());
            if (errorMensaje != null) updWf.put("ultimo_error", errorMensaje);
            supabase.update("workflows", wfId, updWf, userId);
        }
        log.info("Workflow {} ejecutado: {}/{} acciones OK ({}ms)", wf.get("nombre"), exitos, ejecutadas, duracion);
    }

    /** Ejecuta una acción individual. */
    @SuppressWarnings("unchecked")
    Map<String, Object> ejecutarAccion(Map<String, Object> accion, Map<String, Object> payload, String userId) {
        String tipo = String.valueOf(accion.get("tipo"));
        Map<String, Object> config = accion.get("config") instanceof Map<?, ?> c ? (Map<String, Object>) c : new LinkedHashMap<>();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("accion", tipo);

        switch (tipo) {
            case "send_email" -> {
                String to = resolver(String.valueOf(config.get("to")), payload).trim();
                if (!to.matches("[^@\\s{}]+@[^@\\s{}]+\\.[^@\\s{}]+")) {
                    // Antes se "enviaba" a "{email}" cuando el dato no venía y se contaba como éxito
                    res.put("ok", false);
                    res.put("error", "Sin email de destino (" + to + ")");
                    return res;
                }
                String subject = resolver(String.valueOf(config.get("subject")), payload);
                String body = resolver(String.valueOf(config.get("body")), payload);
                EmailService.SendResult r = emailService.send(to, subject, body);
                res.put("ok", r.success());
                res.put("detalle", r.success() ? "Email enviado a " + to : r.message());
                if (!r.success()) res.put("error", r.message());
            }
            case "send_notification" -> {
                String titulo = resolver(String.valueOf(config.get("titulo")), payload);
                String mensaje = resolver(String.valueOf(config.get("mensaje")), payload);
                String urgencia = String.valueOf(config.getOrDefault("urgencia", "media"));
                String link = String.valueOf(config.getOrDefault("link", "/dashboard"));
                notif.create(userId, titulo, mensaje, link, urgencia);
                res.put("ok", true);
            }
            case "create_task" -> {
                Map<String, Object> tarea = new LinkedHashMap<>();
                tarea.put("user_id", userId);
                tarea.put("titulo", resolver(String.valueOf(config.get("titulo")), payload));
                tarea.put("descripcion", resolver(String.valueOf(config.getOrDefault("descripcion", "")), payload));
                tarea.put("prioridad", config.getOrDefault("prioridad", "media"));
                tarea.put("estado", "pendiente");
                tarea.put("created_at", OffsetDateTime.now().toString());
                Object asignado = config.get("asignado_a") != null ? config.get("asignado_a") : payload.get("asignado_a");
                if (asignado != null && esDelEquipo(String.valueOf(asignado), userId)) tarea.put("asignado_a", asignado);
                if (config.get("dias_limite") != null) {
                    tarea.put("fecha_limite", OffsetDateTime.now().plusDays(num(config.get("dias_limite"))).toString());
                }
                if (payload.get("lead_id") != null) tarea.put("lead_id", payload.get("lead_id"));
                if (payload.get("propiedad_id") != null) tarea.put("propiedad_id", payload.get("propiedad_id"));
                res.put("ok", supabase.insert("tareas", tarea) != null);
            }
            case "change_status" -> {
                String tabla = tablaPermitida(config.get("tabla"));
                String registroId = String.valueOf(config.getOrDefault("registro_id", payload.get("id")));
                String nuevo = valorEstado(tabla, String.valueOf(config.get("estado")));
                if (tabla == null) return error(res, "Tabla no permitida: " + config.get("tabla"));
                boolean ok = supabase.update(tabla, registroId, Map.of(COLUMNA_ESTADO.get(tabla), nuevo), userId);
                res.put("ok", ok);
                res.put("detalle", "Estado cambiado a " + nuevo);
            }
            case "update_field" -> {
                String tabla = tablaPermitida(config.get("tabla"));
                String registroId = String.valueOf(config.getOrDefault("registro_id", payload.get("id")));
                String campo = String.valueOf(config.get("campo"));
                if (tabla == null) return error(res, "Tabla no permitida: " + config.get("tabla"));
                if (CAMPOS_PROTEGIDOS.contains(campo) || !campo.matches("[a-z_]{1,60}")) return error(res, "Campo no permitido: " + campo);
                Map<String, Object> upd = new HashMap<>();
                upd.put(campo, config.get("valor"));
                res.put("ok", supabase.update(tabla, registroId, upd, userId));
            }
            case "assign_agent" -> {
                String tabla = tablaPermitida(config.getOrDefault("tabla", "leads"));
                String registroId = String.valueOf(payload.get("id"));
                String agenteId = String.valueOf(config.get("agente_id"));
                if (tabla == null) return error(res, "Tabla no permitida: " + config.get("tabla"));
                if ("auto".equals(agenteId)) agenteId = pickLeastBusyAgent(userId);
                else if (!esDelEquipo(agenteId, userId)) agenteId = null;
                if (agenteId == null) return error(res, "No hay agentes disponibles");
                boolean ok = supabase.update(tabla, registroId, Map.of("asignado_a", agenteId), userId);
                payload.put("asignado_a", agenteId); // la tarea que venga después se le asigna a él
                res.put("ok", ok);
                res.put("detalle", "Asignado a " + agenteId);
            }
            default -> {
                res.put("ok", false);
                res.put("error", "Acción desconocida: " + tipo);
            }
        }
        return res;
    }

    private static Map<String, Object> error(Map<String, Object> res, String msg) {
        res.put("ok", false);
        res.put("error", msg);
        return res;
    }

    /** Tabla de negocio permitida ("facturas" = invoices, de flujos antiguos), o null. */
    static String tablaPermitida(Object tabla) {
        String t = tabla == null ? "" : String.valueOf(tabla).trim().toLowerCase();
        if (t.equals("facturas")) t = "invoices";
        return COLUMNA_ESTADO.containsKey(t) ? t : null;
    }

    /** Estados que usa cada tabla (los flujos antiguos decían "morosa" para una factura vencida). */
    static String valorEstado(String tabla, String estado) {
        if ("invoices".equals(tabla) && estado != null && estado.equalsIgnoreCase("morosa")) return "Vencida";
        return estado;
    }

    /** Sustituye {variables} con datos del payload. */
    String resolver(String texto, Map<String, Object> payload) {
        if (texto == null || "null".equals(texto)) return "";
        String r = texto;
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            if (e.getValue() != null) r = r.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        return r;
    }

    /** Si el evento trae lead_id pero no sus datos (embudos, visitas…), los añade: nombre, email, teléfono. */
    void completarConLead(Map<String, Object> payload, String userId) {
        Object leadId = payload.get("lead_id");
        if (leadId == null || (payload.get("email") != null && payload.get("nombre") != null)) return;
        try {
            List<Map<String, Object>> l = supabase.selectOwned("leads", String.valueOf(leadId), userId);
            if (l.isEmpty()) return;
            for (String k : List.of("nombre", "email", "telefono", "presupuesto", "zona")) payload.putIfAbsent(k, l.get(0).get(k));
        } catch (Exception ignored) { }
    }

    /** Comprueba condiciones del trigger (operadores: =, >, <, >=, <=, contains). */
    @SuppressWarnings("unchecked")
    boolean matchesConditions(Map<String, Object> wf, Map<String, Object> payload) {
        Object cond = wf.get("trigger_condiciones");
        if (!(cond instanceof Map)) return true;  // sin condiciones → siempre dispara
        Map<String, Object> condiciones = (Map<String, Object>) cond;
        if (condiciones.isEmpty() || condiciones.get("campo") == null) return true;

        String campo = String.valueOf(condiciones.get("campo"));
        String op = String.valueOf(condiciones.get("operador"));
        Object esperado = condiciones.get("valor");
        Object real = payload.get(campo);
        if (real == null || esperado == null) return false;

        try {
            return switch (op) {
                case "=" -> real.toString().equalsIgnoreCase(esperado.toString());
                case "!=" -> !real.toString().equalsIgnoreCase(esperado.toString());
                case ">" -> Double.parseDouble(real.toString()) > Double.parseDouble(esperado.toString());
                case ">=" -> Double.parseDouble(real.toString()) >= Double.parseDouble(esperado.toString());
                case "<" -> Double.parseDouble(real.toString()) < Double.parseDouble(esperado.toString());
                case "<=" -> Double.parseDouble(real.toString()) <= Double.parseDouble(esperado.toString());
                case "contains" -> real.toString().toLowerCase().contains(esperado.toString().toLowerCase());
                default -> true;
            };
        } catch (Exception e) {
            return false;
        }
    }

    /** Agente activo del equipo con menos tareas pendientes (antes buscaba "org_id" y nunca encontraba a nadie). */
    String pickLeastBusyAgent(String userId) {
        List<Map<String, Object>> agentes = supabase.selectFilter(
                "users", "organization_id=eq." + userId + "&active=eq.true&role=in.(comercial,director)");
        if (agentes.isEmpty()) return null;
        String mejor = null;
        int min = Integer.MAX_VALUE;
        for (Map<String, Object> a : agentes) {
            int n = supabase.selectFilter("tareas", "user_id=eq." + userId + "&asignado_a=eq." + a.get("id")
                    + "&estado=eq.pendiente&limit=500").size();
            if (n < min) {
                min = n;
                mejor = String.valueOf(a.get("id"));
            }
        }
        return mejor;
    }

    private boolean esDelEquipo(String agenteId, String orgId) {
        if (agenteId == null || !agenteId.matches("[0-9a-fA-F-]{36}")) return false;
        if (agenteId.equals(orgId)) return true;
        return !supabase.selectFilter("users", "id=eq." + agenteId + "&organization_id=eq." + orgId + "&limit=1").isEmpty();
    }

    /**
     * Lunes a viernes de 9:00 a 20:00 en la zona horaria de la inmobiliaria (PaisActual: el flujo se
     * ejecuta con su país); si cae fuera, el siguiente día laborable a las 9:00.
     */
    static Instant siguienteHoraLaboral(Instant cuando) {
        ZonedDateTime t = cuando.atZone(com.inmobilia.util.PaisActual.get().zonaId());
        for (int i = 0; i < 8; i++) {
            boolean laborable = t.getDayOfWeek() != DayOfWeek.SATURDAY && t.getDayOfWeek() != DayOfWeek.SUNDAY;
            if (laborable && t.getHour() >= 9 && t.getHour() < 20) return t.toInstant();
            if (laborable && t.getHour() < 9) return t.withHour(9).withMinute(0).withSecond(0).withNano(0).toInstant();
            t = t.plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0);
        }
        return t.toInstant();
    }

    private static int num(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return o == null ? 0 : (int) Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    /** Cron cada minuto para procesar ejecuciones diferidas (retrasos y esperas). */
    @Scheduled(cron = "30 * * * * *")
    @SuppressWarnings("unchecked")
    public void procesarPendientes() {
        List<Map<String, Object>> pendientes = supabase.selectFilter(
                "workflow_ejecuciones",
                "estado=eq.pendiente&ejecutar_at=lte." + Instant.now().truncatedTo(ChronoUnit.SECONDS) + "&limit=100");

        for (Map<String, Object> ej : pendientes) {
            paises.usar(String.valueOf(ej.get("user_id")));
            try {
                String userId = String.valueOf(ej.get("user_id"));
                // Marcar antes de ejecutar para no duplicar
                supabase.update("workflow_ejecuciones", String.valueOf(ej.get("id")), Map.of("estado", "lanzada"), userId);
                List<Map<String, Object>> wfs = supabase.selectFilter("workflows",
                        "id=eq." + ej.get("workflow_id") + "&user_id=eq." + userId + "&activo=eq.true");
                if (wfs.isEmpty()) continue; // borrado o desactivado mientras esperaba
                ejecutarWorkflow(wfs.get(0), ej.get("payload") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of(), userId);
            } catch (Exception e) {
                log.warn("Err procesar pendiente: {}", e.getMessage());
            }
        }
    }
}
