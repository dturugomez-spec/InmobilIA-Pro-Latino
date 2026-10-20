package com.inmobilia.service;

import com.inmobilia.util.PaisActual;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Siembra el EMBUDO COMPLETO de automatizaciones para una agencia nueva.
 *
 * Cubre todo el proceso, del trabajador al cliente:
 *   Captación → Lead → Cualificación → Visita → Oferta → Contrato → Cobro → Postventa
 *
 * Se llama una sola vez al crear la cuenta (desde la aprobación de solicitud).
 * Crea registros reales en la tabla `workflows` que el WorkflowEngine ya ejecuta.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultAutomationsSeeder {

    private final SupabaseService supabase;

    /** Crea el pack de automatizaciones por defecto para un usuario/agencia. */
    @Async
    public void seed(String userId) {
        try {
            // Evitar duplicar si ya tiene workflows
            List<Map<String, Object>> existentes = supabase.selectFilter(
                    "workflows", "user_id=eq." + userId + "&limit=1");
            if (!existentes.isEmpty()) {
                log.info("Agencia {} ya tiene workflows, no se siembran defaults", userId);
                return;
            }

            // ─── 1. CAPTACIÓN: nueva propiedad → matching automático ───
            crear(userId,
                "🏠 Nueva propiedad → buscar leads compatibles",
                "Cuando captas un inmueble, avisa a los leads cuyo criterio encaja (búsqueda inversa).",
                "propiedad_creada", null,
                List.of(
                    accion("send_notification", Map.of(
                        "titulo", "🏠 Propiedad captada",
                        "mensaje", "Ejecutando matching con leads activos...",
                        "urgencia", "media", "link", "/matches"))
                ), 0);

            // ─── 2. LEAD NUEVO: bienvenida + asignación + tarea seguimiento ───
            crear(userId,
                "🎯 Lead nuevo → bienvenida + asignar + tarea 24h",
                "Todo lead que entra recibe email, se asigna al agente con menos carga y crea tarea de contacto en 24h.",
                "lead_creado", null,
                List.of(
                    accion("assign_agent", Map.of("tabla", "leads", "agente_id", "auto")),
                    accion("send_email", Map.of(
                        "to", "{email}",
                        "subject", "👋 Hola {nombre}, gracias por contactar",
                        "body", "<p>Hola {nombre},</p><p>Hemos recibido tu solicitud y un agente te contactará en menos de 24h. Mientras tanto, estamos buscando propiedades que encajen con lo que necesitas.</p><p>Un saludo,<br>El equipo</p>")),
                    accion("create_task", Map.of(
                        "titulo", "Llamar a {nombre} (lead nuevo)",
                        "descripcion", "Contactar en menos de 24h",
                        "prioridad", "alta", "dias_limite", 1))
                ), 0);

            // ─── 3. LEAD PREMIUM: presupuesto alto → director + urgente ───
            crear(userId,
                ("💎 Lead premium (>500k " + PaisActual.moneda() + ") → director + urgente"),
                ("Si el presupuesto supera 500.000 " + PaisActual.moneda() + ", notifica al director y crea tarea urgente para HOY."),
                "lead_creado",
                Map.of("campo", "presupuesto_max", "operador", ">", "valor", 500000),
                List.of(
                    accion("send_notification", Map.of(
                        "titulo", "💎 Lead PREMIUM",
                        "mensaje", ("{nombre} · presupuesto {presupuesto_max} " + PaisActual.moneda()),
                        "urgencia", "alta", "link", "/leads-scoring")),
                    accion("create_task", Map.of(
                        "titulo", "🚨 LEAD VIP {nombre} — contactar HOY",
                        "prioridad", "urgente", "dias_limite", 0))
                ), 0);

            // ─── 4. VISITA REALIZADA: feedback automático 2h después ───
            crear(userId,
                "📅 Visita realizada → pedir feedback (2h después)",
                "Dos horas después de una visita, pide opinión al cliente y crea tarea de seguimiento.",
                "visita_realizada", null,
                List.of(
                    accion("send_email", Map.of(
                        "to", "{visitante_email}",
                        "subject", "¿Qué te ha parecido la visita?",
                        "body", "<p>Hola {visitante_nombre},</p><p>Esperamos que te haya gustado la propiedad. ¿Tienes alguna duda o quieres dar el siguiente paso?</p><p>Responde a este email y te ayudamos.</p>")),
                    accion("create_task", Map.of(
                        "titulo", "Seguimiento post-visita de {visitante_nombre}",
                        "prioridad", "media", "dias_limite", 1))
                ), 120);  // delay 2h

            // ─── 5. CONTRATO FIRMADO: alta + bienvenida cliente ───
            crear(userId,
                "✍️ Contrato firmado → alta operación + notificación",
                "Al firmarse un contrato, notifica al equipo y registra la operación cerrada.",
                "contrato_firmado", null,
                List.of(
                    accion("send_notification", Map.of(
                        "titulo", "🎉 Contrato firmado",
                        "mensaje", "Nueva operación cerrada. ¡Enhorabuena!",
                        "urgencia", "alta", "link", "/contratos")),
                    accion("create_task", Map.of(
                        "titulo", "Gestionar postventa del contrato firmado",
                        "prioridad", "media", "dias_limite", 3))
                ), 0);

            // ─── 6. FACTURA IMPAGADA: recordatorio + tarea cobro ───
            crear(userId,
                "⚠️ Factura impagada → recordatorio + tarea",
                "Cuando una factura pasa a impagada, envía recordatorio al cliente y crea tarea de cobro.",
                "factura_impagada", null,
                List.of(
                    accion("send_email", Map.of(
                        "to", "{cliente_email}",
                        "subject", "Recordatorio de pago pendiente",
                        "body", "<p>Hola,</p><p>Te recordamos que tienes una factura pendiente de pago. Si ya la has abonado, ignora este mensaje.</p>")),
                    accion("change_status", Map.of("tabla", "facturas", "estado", "morosa")),
                    accion("create_task", Map.of(
                        "titulo", "Gestionar cobro factura impagada",
                        "prioridad", "alta", "dias_limite", 2))
                ), 0);

            // ─── 7. KANBAN: lead movido a "ganado" → NPS ───
            crear(userId,
                "🏆 Operación ganada → encuesta NPS",
                "Cuando mueves un lead a 'ganado' en el pipeline, pide valoración NPS al cliente.",
                "kanban_card_movida",
                Map.of("campo", "columna_nueva", "operador", "=", "valor", "ganado"),
                List.of(
                    accion("send_email", Map.of(
                        "to", "{email}",
                        "subject", "¿Nos recomendarías? 🙏",
                        "body", "<p>Hola,</p><p>Gracias por confiar en nosotros. ¿Del 0 al 10, qué probabilidad hay de que nos recomiendes a un amigo o familiar?</p><p>Tu opinión nos ayuda muchísimo.</p>"))
                ), 60);  // delay 1h

            log.info("✅ Sembradas 7 automatizaciones por defecto para agencia {}", userId);
        } catch (Exception e) {
            log.warn("No se pudieron sembrar automatizaciones para {}: {}", userId, e.getMessage());
        }
    }

    private void crear(String userId, String nombre, String desc, String trigger,
                       Map<String, Object> condiciones, List<Map<String, Object>> acciones, int delayMin) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("user_id", userId);
        w.put("nombre", nombre);
        w.put("descripcion", desc);
        w.put("trigger_evento", trigger);
        if (condiciones != null) w.put("trigger_condiciones", condiciones);
        w.put("acciones", acciones);
        w.put("activo", true);
        w.put("ejecutar_inmediato", delayMin == 0);
        w.put("delay_minutos", delayMin);
        w.put("created_at", OffsetDateTime.now().toString());
        w.put("updated_at", OffsetDateTime.now().toString());
        supabase.insert("workflows", w);
    }

    private Map<String, Object> accion(String tipo, Map<String, Object> config) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("tipo", tipo);
        a.put("config", config);
        return a;
    }
}
