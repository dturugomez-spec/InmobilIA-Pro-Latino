package com.inmobilia.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 🔁 Seguimiento Automático Inteligente (Auto-nurture).
 *
 * Cada lead entra en una cadencia que el Agente ejecuta en piloto automático:
 * bienvenida → recordatorio → aporta valor → tarea de llamada. Cada mensaje lo
 * redacta la IA. Se para sola cuando el lead avanza (o se pierde). Dos modos:
 *   - Proponer (por defecto): cada paso entra como propuesta en el Autopiloto.
 *   - Enviar: los emails salen solos; los WhatsApp y llamadas se proponen/crean.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SeguimientoService {

    private final SupabaseService supabase;
    private final AIService aiService;
    private final EmailService emailService;
    private final BrandingService branding;
    private final NotificationService notif;
    private final ObjectMapper mapper = new ObjectMapper();

    public record Paso(int dia, String canal, String proposito) {}

    private static final List<Paso> PASOS = List.of(
            new Paso(0, "email",    "bienvenida cálida, presentarte y ofrecer ayuda con su búsqueda"),
            new Paso(2, "whatsapp", "recordatorio suave preguntando si vio tu mensaje y si necesita algo"),
            new Paso(5, "email",    "aportar valor: novedades del mercado de su zona y disposición a enseñar inmuebles"),
            new Paso(9, "tarea",    "llamar personalmente para retomar el contacto")
    );

    // Etapas en las que el lead sigue siendo candidato de nurture
    private static final Set<String> ETAPAS_ACTIVAS = Set.of("Nuevo", "Contactado");

    /** Cron diario 9:30: inscribe leads nuevos y avanza las cadencias vencidas. */
    @Scheduled(cron = "0 30 9 * * *", zone = "${app.zona-horaria:America/Mexico_City}")
    public void cronDiario() {
        try {
            List<Map<String, Object>> agencias = supabase.selectFilter(
                    "users", "auto_seguimiento=eq.true&limit=500");
            for (Map<String, Object> a : agencias) {
                String uid = String.valueOf(a.get("id"));
                boolean envia = Boolean.TRUE.equals(a.get("auto_seguimiento_envia"));
                try {
                    inscribirNuevos(uid);
                    avanzar(uid, envia);
                } catch (Exception e) {
                    log.warn("[NURTURE] agencia {}: {}", uid, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[NURTURE] error general: {}", e.getMessage());
        }
    }

    /** Inscribe leads recientes (etapa Nuevo) que aún no están en una cadencia. */
    public int inscribirNuevos(String uid) {
        String hace3 = LocalDate.now().minusDays(3).toString();
        List<Map<String, Object>> leads = supabase.selectFilter("leads",
                "user_id=eq." + uid + "&etapa=eq.Nuevo&created_at=gte." + hace3 + "&limit=100");
        if (leads.isEmpty()) return 0;

        Set<String> yaInscritos = new HashSet<>();
        for (Map<String, Object> i : supabase.selectFilter("cadencia_inscripciones",
                "user_id=eq." + uid + "&limit=1000")) {
            yaInscritos.add(String.valueOf(i.get("lead_id")));
        }

        int n = 0;
        for (Map<String, Object> l : leads) {
            String leadId = String.valueOf(l.get("id"));
            if (yaInscritos.contains(leadId)) continue;
            Map<String, Object> ins = new LinkedHashMap<>();
            ins.put("user_id", uid);
            ins.put("lead_id", leadId);
            ins.put("lead_nombre", l.getOrDefault("nombre", ""));
            ins.put("tipo", "Venta".equalsIgnoreCase(String.valueOf(l.get("interes"))) ? "vendedor" : "comprador");
            ins.put("paso", 0);
            ins.put("estado", "activa");
            ins.put("proximo_at", OffsetDateTime.now().toString());  // paso 0 = día 0 (ya)
            ins.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("cadencia_inscripciones", ins);
            n++;
        }
        return n;
    }

    /** Ejecuta los pasos vencidos de las cadencias activas. */
    public int avanzar(String uid, boolean envia) {
        String ahora = OffsetDateTime.now().toString();
        List<Map<String, Object>> pendientes = supabase.selectFilter("cadencia_inscripciones",
                "user_id=eq." + uid + "&estado=eq.activa&proximo_at=lte." + ahora + "&limit=50");
        Map<String, Object> marca = branding.branding(uid);
        String agencia = String.valueOf(marca.getOrDefault("nombre", "la agencia"));

        int ejecutados = 0;
        for (Map<String, Object> ins : pendientes) {
            try {
                String leadId = String.valueOf(ins.get("lead_id"));
                List<Map<String, Object>> lr = supabase.selectFilter("leads", "id=eq." + leadId);
                if (lr.isEmpty()) { cerrar(ins, "parada"); continue; }
                Map<String, Object> lead = lr.get(0);

                // Auto-stop: el lead avanzó o se perdió
                String etapa = String.valueOf(lead.getOrDefault("etapa", "Nuevo"));
                if (!ETAPAS_ACTIVAS.contains(etapa)) { cerrar(ins, "completada"); continue; }

                int paso = (int) nd(ins.get("paso"));
                if (paso >= PASOS.size()) { cerrar(ins, "completada"); continue; }
                Paso p = PASOS.get(paso);

                ejecutarPaso(uid, lead, p, agencia, envia);
                ejecutados++;

                // Programar siguiente paso
                int siguiente = paso + 1;
                Map<String, Object> upd = new LinkedHashMap<>();
                upd.put("paso", siguiente);
                upd.put("pasos_dados", (int) nd(ins.get("pasos_dados")) + 1);
                upd.put("ultimo_at", ahora);
                if (siguiente >= PASOS.size()) {
                    upd.put("estado", "completada");
                } else {
                    long diasHasta = PASOS.get(siguiente).dia() - p.dia();
                    upd.put("proximo_at", OffsetDateTime.now().plusDays(Math.max(1, diasHasta)).toString());
                }
                supabase.update("cadencia_inscripciones", String.valueOf(ins.get("id")), upd);
            } catch (Exception e) {
                log.warn("[NURTURE] paso lead {}: {}", ins.get("lead_id"), e.getMessage());
            }
        }
        return ejecutados;
    }

    private void ejecutarPaso(String uid, Map<String, Object> lead, Paso p, String agencia, boolean envia) {
        String leadId = String.valueOf(lead.get("id"));

        if ("tarea".equals(p.canal())) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("user_id", uid);
            t.put("titulo", "📞 Llamar a " + lead.getOrDefault("nombre", "lead") + " (seguimiento automático)");
            t.put("prioridad", "alta");
            t.put("estado", "pendiente");
            t.put("lead_id", leadId);
            t.put("fecha_limite", LocalDate.now().plusDays(1).toString());
            t.put("descripcion", "Paso de la cadencia de seguimiento del Agente");
            t.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("tareas", t);
            return;
        }

        String mensaje = draft(uid, lead, p.proposito(), agencia);
        String email = String.valueOf(lead.getOrDefault("email", ""));
        boolean tieneEmail = !email.isBlank() && !"null".equals(email);

        // Modo ENVIAR: los emails salen solos
        if (envia && "email".equals(p.canal()) && tieneEmail) {
            try {
                emailService.send(email, "Seguimos con tu búsqueda", mensaje.replace("\n", "<br>"));
                registrar(uid, leadId, "email", "Email automático (cadencia): " + resumen(mensaje));
                return;
            } catch (Exception ignored) {}
        }

        // Resto: proponer en el Autopiloto (bandeja de acciones)
        proponer(uid, lead, p.canal(), mensaje, tieneEmail);
    }

    /** Inserta el paso como propuesta en el Autopiloto (tabla agente_acciones). */
    private void proponer(String uid, Map<String, Object> lead, String canal, String mensaje, boolean tieneEmail) {
        String realCanal = "email".equals(canal) && tieneEmail ? "email" : "whatsapp";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("canal", realCanal);
        payload.put("asunto", "Seguimos con tu búsqueda");

        Map<String, Object> a = new LinkedHashMap<>();
        a.put("user_id", uid);
        a.put("tipo", "mensaje_lead");
        a.put("prioridad", "media");
        a.put("titulo", "🔁 Seguimiento: " + lead.getOrDefault("nombre", "lead") + " (" + realCanal + ")");
        a.put("motivo", "Cadencia de seguimiento automático");
        a.put("borrador", mensaje);
        try { a.put("payload_json", mapper.writeValueAsString(payload)); } catch (Exception e) { a.put("payload_json", "{}"); }
        a.put("lead_id", lead.get("id"));
        a.put("estado", "propuesta");
        a.put("created_at", OffsetDateTime.now().toString());
        try { supabase.insert("agente_acciones", a); } catch (Exception ignored) {}
    }

    private String draft(String uid, Map<String, Object> lead, String proposito, String agencia) {
        try {
            String prompt = "Redacta un mensaje breve (máx 50 palabras) para el lead " +
                    lead.getOrDefault("nombre", "cliente") +
                    (lead.get("zona") != null ? " (busca en " + lead.get("zona") + ")" : "") +
                    ". Propósito: " + proposito + ". Cercano y profesional, con una pregunta o llamada a la acción. " +
                    "Firma como " + agencia + ". Español de Latinoamérica (" + com.inmobilia.util.PaisActual.get().nombre() + "), con ustedes y sin vosotros. Solo el texto, sin comillas.";
            String r = aiService.call(null, prompt, "Eres un agente inmobiliario cercano y constante en el seguimiento.", 150, uid);
            return r == null || r.isBlank()
                    ? "Hola " + lead.getOrDefault("nombre", "") + ", ¿seguimos con tu búsqueda? Estoy aquí para ayudarte. " + agencia
                    : r.trim();
        } catch (Exception e) {
            return "Hola " + lead.getOrDefault("nombre", "") + ", ¿seguimos con tu búsqueda? " + agencia;
        }
    }

    // ─── Controles manuales ───
    public int inscribirManual(String uid, String leadId) {
        List<Map<String, Object>> lr = supabase.selectFilter("leads", "id=eq." + leadId);
        if (lr.isEmpty() || !uid.equals(String.valueOf(lr.get(0).get("user_id")))) return 0;
        List<Map<String, Object>> ya = supabase.selectFilter("cadencia_inscripciones",
                "user_id=eq." + uid + "&lead_id=eq." + leadId + "&limit=1");
        if (!ya.isEmpty()) return 0;
        Map<String, Object> ins = new LinkedHashMap<>();
        ins.put("user_id", uid);
        ins.put("lead_id", leadId);
        ins.put("lead_nombre", lr.get(0).getOrDefault("nombre", ""));
        ins.put("tipo", "Venta".equalsIgnoreCase(String.valueOf(lr.get(0).get("interes"))) ? "vendedor" : "comprador");
        ins.put("paso", 0);
        ins.put("estado", "activa");
        ins.put("proximo_at", OffsetDateTime.now().toString());
        ins.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("cadencia_inscripciones", ins);
        return 1;
    }

    public void cambiarEstado(String uid, String inscripcionId, String estado) {
        List<Map<String, Object>> r = supabase.selectFilter("cadencia_inscripciones", "id=eq." + inscripcionId);
        if (!r.isEmpty() && uid.equals(String.valueOf(r.get(0).get("user_id")))) {
            supabase.update("cadencia_inscripciones", inscripcionId, Map.of("estado", estado));
        }
    }

    public List<Paso> pasos() { return PASOS; }

    private void cerrar(Map<String, Object> ins, String estado) {
        try {
            supabase.update("cadencia_inscripciones", String.valueOf(ins.get("id")),
                    Map.of("estado", estado, "ultimo_at", OffsetDateTime.now().toString()));
        } catch (Exception ignored) {}
    }
    private void registrar(String uid, String leadId, String tipo, String detalle) {
        try {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("lead_id", leadId); n.put("user_id", uid); n.put("tipo", tipo);
            n.put("detalle", detalle); n.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("lead_interacciones", n);
        } catch (Exception ignored) {}
    }
    private static String resumen(String s) { return s == null ? "" : (s.length() > 60 ? s.substring(0, 60) + "…" : s); }
    private static double nd(Object o) { try { return o == null ? 0 : Double.parseDouble(String.valueOf(o).replace(",", ".")); } catch (Exception e) { return 0; } }
}
