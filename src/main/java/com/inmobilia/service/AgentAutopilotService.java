package com.inmobilia.service;

import com.inmobilia.util.PaisActual;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 🚀 Autopiloto del Agente (human-in-the-loop).
 *
 * Analiza la cartera y PROPONE acciones concretas ya preparadas (mensaje
 * redactado por IA, precio calculado, tarea) en una bandeja. El agente aprueba
 * o descarta; al aprobar, se ejecuta de verdad. La IA hace el trabajo, el humano
 * decide — cero acciones automáticas sin supervisión.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentAutopilotService {

    private final SupabaseService supabase;
    private final AgentBriefingService briefingService;
    private final AIService aiService;
    private final EmailService emailService;
    private final BrandingService branding;
    private final NotificationService notif;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    // ─────────────────────────── CRON PROACTIVO ───────────────────────────

    /** Cada mañana a las 7:45 rellena la bandeja del Autopiloto para las agencias que lo activan. */
    @Scheduled(cron = "0 45 7 * * MON-FRI", zone = "${app.zona-horaria:America/Mexico_City}")
    public void cronDiario() {
        try {
            List<Map<String, Object>> admins = supabase.selectFilter(
                    "users", "autopiloto_auto=eq.true&limit=500");
            for (Map<String, Object> a : admins) {
                String uid = String.valueOf(a.get("id"));
                try {
                    int n = generarPropuestas(uid);
                    if (n > 0) {
                        notif.create(uid, "🚀 " + n + " acciones te esperan",
                                "El Autopiloto ha preparado " + n + " acciones para que revises y apruebes.",
                                "/autopiloto", "alta");
                    }
                } catch (Exception e) {
                    log.warn("[AUTOPILOTO] agencia {}: {}", uid, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[AUTOPILOTO] error cron: {}", e.getMessage());
        }
    }

    // ─────────────────────────── GENERAR PROPUESTAS ───────────────────────────

    /** Analiza la cartera y crea propuestas nuevas. Devuelve cuántas creó. */
    public int generarPropuestas(String uid) {
        AgentBriefingService.Briefing b = briefingService.generarAhora(uid);
        Map<String, Object> marca = branding.branding(uid);
        String agencia = String.valueOf(marca.getOrDefault("nombre", "la agencia"));

        // 🧠 Aprendizaje: tipos que el usuario descarta casi siempre → dejar de proponerlos
        Set<String> suprimidos = tiposSuprimidos(uid);

        // Evitar duplicados: leads/props que ya tienen una propuesta pendiente
        Set<String> yaPropuesto = new HashSet<>();
        try {
            for (Map<String, Object> a : supabase.selectFilter("agente_acciones",
                    "user_id=eq." + uid + "&estado=eq.propuesta&limit=200")) {
                yaPropuesto.add(a.get("tipo") + ":" + a.get("lead_id") + ":" + a.get("propiedad_id"));
            }
        } catch (Exception ignored) {}

        int creadas = 0, drafts = 0;

        // 1) Leads calientes sin contactar → mensaje de primer contacto (redactado por IA)
        for (Map<String, Object> l : b.leadsCalientes) {
            if (creadas >= 12 || suprimidos.contains("mensaje_lead")) break;
            String key = "mensaje_lead:" + l.get("id") + ":null";
            if (yaPropuesto.contains(key)) continue;
            String msg = drafts < 6 ? draftMensaje(uid, l, "primer contacto tras mostrar interés", agencia) : null;
            if (msg != null) drafts++;
            creadas += crearMensajeLead(uid, l, msg, "🔥 Lead caliente sin contactar", "alta") ? 1 : 0;
        }

        // 2) Leads en riesgo → mensaje de reactivación
        int n = 0;
        for (Map<String, Object> l : b.leadsEnRiesgo) {
            if (creadas >= 12 || n++ >= 4 || suprimidos.contains("mensaje_lead")) break;
            String key = "mensaje_lead:" + l.get("id") + ":null";
            if (yaPropuesto.contains(key)) continue;
            String msg = drafts < 8 ? draftMensaje(uid, l, "reactivación tras varios días sin contacto", agencia) : null;
            if (msg != null) drafts++;
            creadas += crearMensajeLead(uid, l, msg, "🚨 Reactivar lead que se enfría", "alta") ? 1 : 0;
        }

        // 3) Sugerencias de bajada de precio
        n = 0;
        for (Map<String, Object> s : b.sugerenciasPrecio) {
            if (creadas >= 12 || n++ >= 4 || suprimidos.contains("bajar_precio")) break;
            String key = "bajar_precio:null:" + s.get("id");
            if (yaPropuesto.contains(key)) continue;
            long precio = ((Number) s.get("precio_sugerido")).longValue();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("precio_nuevo", precio);
            insertar(uid, "bajar_precio", "media",
                    "Bajar precio de " + s.get("direccion") + " a " + String.format("%,d", precio) + (" " + PaisActual.moneda()),
                    s.get("dias") + " días en mercado sin cerrar (-" + s.get("pct") + "%)",
                    null, payload, null, String.valueOf(s.get("id")));
            creadas++;
        }

        // 4) Matches proactivos → tarea de aviso
        n = 0;
        for (Map<String, Object> m : b.matchesProactivos) {
            if (creadas >= 12 || n++ >= 3 || suprimidos.contains("crear_tarea")) break;
            String key = "crear_tarea:null:" + m.get("propiedad_id");
            if (yaPropuesto.contains(key)) continue;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("titulo", "Avisar a " + m.get("num_leads") + " leads que encajan con " + m.get("propiedad"));
            insertar(uid, "crear_tarea", "alta",
                    "Avisar a " + m.get("num_leads") + " leads sobre " + m.get("propiedad"),
                    "Propiedad nueva con compradores compatibles esperando",
                    null, payload, null, String.valueOf(m.get("propiedad_id")));
            creadas++;
        }
        return creadas;
    }

    // ─────────────────────────── APRENDIZAJE ───────────────────────────

    /** Estadística por tipo (últimos 30 días): [descartadas, total resueltas]. */
    private Map<String, long[]> statsPorTipo(String uid) {
        Map<String, long[]> m = new HashMap<>();
        try {
            String desde = LocalDate.now().minusDays(30).toString();
            List<Map<String, Object>> res = supabase.selectFilter("agente_acciones",
                    "user_id=eq." + uid + "&estado=in.(ejecutada,descartada)&resuelta_at=gte." + desde + "&limit=1000");
            for (Map<String, Object> r : res) {
                long[] s = m.computeIfAbsent(String.valueOf(r.get("tipo")), k -> new long[2]);
                s[1]++;
                if ("descartada".equals(r.get("estado"))) s[0]++;
            }
        } catch (Exception ignored) {}
        return m;
    }

    /** Un tipo se "suprime" si en 30 días se han resuelto ≥3 y se ha descartado ≥70%. */
    private static boolean esSuprimido(long[] s) {
        return s != null && s[1] >= 3 && s[0] * 10 >= s[1] * 7;
    }

    /** Tipos que el agente deja de proponer porque el usuario los descarta casi siempre. */
    public Set<String> tiposSuprimidos(String uid) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, long[]> e : statsPorTipo(uid).entrySet()) {
            if (esSuprimido(e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    /** Para el panel: qué ha aprendido (tipo legible + cuántas descartaste). */
    public List<Map<String, Object>> aprendizaje(String uid) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : statsPorTipo(uid).entrySet()) {
            if (!esSuprimido(e.getValue())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tipo", labelTipo(e.getKey()));
            m.put("descartadas", e.getValue()[0]);
            m.put("total", e.getValue()[1]);
            out.add(m);
        }
        return out;
    }

    private static String labelTipo(String tipo) {
        return switch (tipo) {
            case "mensaje_lead" -> "mensajes a leads";
            case "bajar_precio" -> "bajadas de precio";
            case "crear_tarea"  -> "avisos de match";
            default -> tipo;
        };
    }

    private boolean crearMensajeLead(String uid, Map<String, Object> lead, String mensajeIA, String titulo, String prioridad) {
        String email = String.valueOf(lead.getOrDefault("email", ""));
        boolean tieneEmail = !email.isBlank() && !"null".equals(email);
        String canal = tieneEmail ? "email" : "whatsapp";
        String cuerpo = mensajeIA != null ? mensajeIA :
                "Hola " + lead.getOrDefault("nombre", "") + ", soy tu agente. ¿Seguimos con tu búsqueda? Cuéntame cómo puedo ayudarte.";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("canal", canal);
        payload.put("asunto", "Seguimos con tu búsqueda");
        insertar(uid, "mensaje_lead", prioridad,
                titulo + ": " + lead.getOrDefault("nombre", "?") + (tieneEmail ? " (email)" : " (WhatsApp)"),
                "score " + lead.getOrDefault("score", "?"),
                cuerpo, payload, String.valueOf(lead.get("id")), null);
        return true;
    }

    private String draftMensaje(String uid, Map<String, Object> lead, String proposito, String agencia) {
        try {
            String prompt = "Redacta un mensaje breve (máx 45 palabras) de " + proposito +
                    " para el lead " + lead.getOrDefault("nombre", "cliente") +
                    (lead.get("zona") != null ? " (busca en " + lead.get("zona") + ")" : "") +
                    ". Cercano, profesional, con una pregunta o llamada a la acción. Firma como " + agencia +
                    ". Español de Latinoamérica (" + com.inmobilia.util.PaisActual.get().nombre() + "), con ustedes y sin vosotros. Solo el texto del mensaje, sin comillas.";
            String r = aiService.call(null, prompt, "Eres un agente inmobiliario cercano y eficaz.", 140, uid);
            return r == null || r.isBlank() ? null : r.trim();
        } catch (Exception e) { return null; }
    }

    private void insertar(String uid, String tipo, String prioridad, String titulo, String motivo,
                          String borrador, Map<String, Object> payload, String leadId, String propId) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("user_id", uid);
        a.put("tipo", tipo);
        a.put("prioridad", prioridad);
        a.put("titulo", titulo);
        a.put("motivo", motivo);
        if (borrador != null) a.put("borrador", borrador);
        try { a.put("payload_json", mapper.writeValueAsString(payload == null ? Map.of() : payload)); } catch (Exception e) { a.put("payload_json", "{}"); }
        if (leadId != null && !"null".equals(leadId)) a.put("lead_id", leadId);
        if (propId != null && !"null".equals(propId)) a.put("propiedad_id", propId);
        a.put("estado", "propuesta");
        a.put("created_at", OffsetDateTime.now().toString());
        try { supabase.insert("agente_acciones", a); } catch (Exception e) { log.warn("insert accion: {}", e.getMessage()); }
    }

    // ─────────────────────────── EJECUTAR / DESCARTAR ───────────────────────────

    public Map<String, Object> ejecutar(String uid, String accionId, String borradorEditado) {
        List<Map<String, Object>> rows = supabase.selectFilter("agente_acciones", "id=eq." + accionId);
        if (rows.isEmpty() || !uid.equals(String.valueOf(rows.get(0).get("user_id")))) return Map.of("ok", false);
        Map<String, Object> a = rows.get(0);
        if (!"propuesta".equals(a.get("estado"))) return Map.of("ok", false, "msg", "Ya resuelta");

        String tipo = String.valueOf(a.get("tipo"));
        Map<String, Object> payload = parse(a.get("payload_json"));
        String borrador = borradorEditado != null && !borradorEditado.isBlank() ? borradorEditado : String.valueOf(a.getOrDefault("borrador", ""));
        String resultado;
        try {
            resultado = switch (tipo) {
                case "mensaje_lead" -> ejecutarMensaje(uid, a, payload, borrador);
                case "bajar_precio" -> ejecutarBajarPrecio(uid, a, payload);
                case "crear_tarea"  -> ejecutarCrearTarea(uid, a, payload);
                case "marcar_contactado" -> ejecutarContactado(uid, a);
                default -> "Acción ejecutada";
            };
        } catch (Exception e) {
            marcar(accionId, "error", e.getMessage());
            return Map.of("ok", false, "msg", "Error: " + e.getMessage());
        }
        marcar(accionId, "ejecutada", resultado);
        return Map.of("ok", true, "msg", resultado);
    }

    /** Aprueba y ejecuta todas las propuestas pendientes (o solo las "seguras"). */
    public Map<String, Object> aprobarTodo(String uid, boolean soloSeguras) {
        List<Map<String, Object>> pend = supabase.selectFilter("agente_acciones",
                "user_id=eq." + uid + "&estado=eq.propuesta&limit=100");
        int ok = 0, err = 0, saltadas = 0;
        for (Map<String, Object> a : pend) {
            // "Seguras" = no envían nada al cliente (precio, tarea, contactado)
            if (soloSeguras && "mensaje_lead".equals(a.get("tipo"))) { saltadas++; continue; }
            Map<String, Object> r = ejecutar(uid, String.valueOf(a.get("id")), null);
            if (Boolean.TRUE.equals(r.get("ok"))) ok++; else err++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("ejecutadas", ok);
        out.put("errores", err);
        out.put("saltadas", saltadas);
        return out;
    }

    public Map<String, Object> descartar(String uid, String accionId) {
        List<Map<String, Object>> rows = supabase.selectFilter("agente_acciones", "id=eq." + accionId);
        if (rows.isEmpty() || !uid.equals(String.valueOf(rows.get(0).get("user_id")))) return Map.of("ok", false);
        marcar(accionId, "descartada", null);
        return Map.of("ok", true, "msg", "Descartada");
    }

    private String ejecutarMensaje(String uid, Map<String, Object> a, Map<String, Object> payload, String texto) {
        String leadId = String.valueOf(a.get("lead_id"));
        List<Map<String, Object>> leads = supabase.selectFilter("leads", "id=eq." + leadId);
        if (leads.isEmpty()) throw new IllegalStateException("Lead no encontrado");
        Map<String, Object> lead = leads.get(0);
        String canal = String.valueOf(payload.getOrDefault("canal", "email"));

        // Registrar la interacción siempre
        registrarInteraccion(uid, leadId, canal, "Mensaje enviado por Autopiloto: " + resumen(texto));

        if ("email".equals(canal)) {
            String email = String.valueOf(lead.getOrDefault("email", ""));
            if (email.isBlank() || "null".equals(email)) throw new IllegalStateException("El lead no tiene email");
            emailService.send(email, String.valueOf(payload.getOrDefault("asunto", "Mensaje de tu agencia")), texto.replace("\n", "<br>"));
            return "📧 Email enviado a " + lead.getOrDefault("nombre", "");
        } else {
            String tel = String.valueOf(lead.getOrDefault("telefono", "")).replaceAll("[\\s+\\-]", "");
            String url = "https://wa.me/" + tel + "?text=" + java.net.URLEncoder.encode(texto, java.nio.charset.StandardCharsets.UTF_8);
            return "WHATSAPP:" + url;  // el controlador lo convierte en enlace para abrir
        }
    }

    private String ejecutarBajarPrecio(String uid, Map<String, Object> a, Map<String, Object> payload) {
        String propId = String.valueOf(a.get("propiedad_id"));
        double precio = nd(payload.get("precio_nuevo"));
        if (precio <= 0) throw new IllegalStateException("Precio inválido");
        supabase.update("propiedades", propId, Map.of("current_value", precio), uid);
        return "💶 Precio actualizado a " + String.format("%,d", (long) precio) + (" " + PaisActual.moneda());
    }

    private String ejecutarCrearTarea(String uid, Map<String, Object> a, Map<String, Object> payload) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("user_id", uid);
        t.put("titulo", payload.getOrDefault("titulo", a.get("titulo")));
        t.put("prioridad", a.getOrDefault("prioridad", "alta"));
        t.put("estado", "pendiente");
        if (a.get("lead_id") != null) t.put("lead_id", a.get("lead_id"));
        t.put("fecha_limite", LocalDate.now().plusDays(1).toString());
        t.put("descripcion", "Creada desde el Autopiloto del Agente");
        t.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("tareas", t);
        return "📋 Tarea creada";
    }

    private String ejecutarContactado(String uid, Map<String, Object> a) {
        supabase.update("leads", String.valueOf(a.get("lead_id")), Map.of("etapa", "Contactado"));
        return "✅ Lead marcado como contactado";
    }

    private void registrarInteraccion(String uid, String leadId, String tipo, String detalle) {
        try {
            Map<String, Object> nota = new LinkedHashMap<>();
            nota.put("lead_id", leadId);
            nota.put("user_id", uid);
            nota.put("tipo", tipo);
            nota.put("detalle", detalle);
            nota.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("lead_interacciones", nota);
        } catch (Exception ignored) {}
    }

    private void marcar(String id, String estado, String resultado) {
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("estado", estado);
        if (resultado != null) upd.put("resultado", resultado.startsWith("WHATSAPP:") ? "Enlace de WhatsApp generado" : resultado);
        upd.put("resuelta_at", OffsetDateTime.now().toString());
        try { supabase.update("agente_acciones", id, upd); } catch (Exception ignored) {}
    }

    // helpers
    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(Object json) {
        try { return json == null ? Map.of() : mapper.readValue(String.valueOf(json), Map.class); } catch (Exception e) { return Map.of(); }
    }
    private static String resumen(String s) { return s == null ? "" : (s.length() > 60 ? s.substring(0, 60) + "…" : s); }
    private static double nd(Object o) { try { return o == null ? 0 : Double.parseDouble(String.valueOf(o).replace(",", ".")); } catch (Exception e) { return 0; } }
}
