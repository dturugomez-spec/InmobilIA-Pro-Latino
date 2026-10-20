package com.inmobilia.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 🤖 EJECUTOR DE ACCIONES DEL AGENTE
 *
 * Convierte el chat del agente en un AGENTE REAL que ACTÚA. La IA clasifica
 * la petición en lenguaje natural en una acción estructurada (JSON) y este
 * servicio la ejecuta sobre la base de datos.
 *
 * Acciones soportadas:
 *   - crear_tarea        → crea una tarea
 *   - asignar_lead       → asigna un lead a un agente
 *   - nota_lead          → registra una nota/interacción en un lead
 *   - cambiar_etapa      → mueve un lead en el pipeline (Nuevo→Contactado→Visita→Oferta→Firmado)
 *   - agendar_visita     → crea una visita a una propiedad con un lead
 *   - buscar_compradores → encuentra leads de la cartera que encajan con una propiedad
 *   - redactar_mensaje   → redacta un email/WhatsApp listo para copiar (no envía)
 *   - resumir_llamada    → nota + tarea de seguimiento desde una transcripción
 *   - responder          → respuesta normal (consulta, sin acción)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentActionService {

    private final AIService aiService;
    private final SupabaseService supabase;
    private final EmailService emailService;
    private final InformeMercadoService informes;
    private final CaptacionService captacion;
    private final ObjectMapper mapper = new ObjectMapper();

    // Diagnóstico: qué claves de OpenAI ve realmente el servidor (solo longitud, nunca el valor).
    @org.springframework.beans.factory.annotation.Value("${OPENAI_API_KEY_PLATFORM:}")
    private String envOpenAiPlatform;
    @org.springframework.beans.factory.annotation.Value("${OPENAI_API_KEY:}")
    private String envOpenAiPlain;

    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    private static final String SYSTEM = """
        Eres el copiloto de un agente inmobiliario. Decide qué ACCIÓN ejecutar según lo que pida.
        Devuelve SOLO un JSON válido, sin texto alrededor, con esta forma:
        {"accion":"<tipo>","params":{...},"confirmacion":"<frase corta para el usuario>"}

        Tipos de acción:
        - "crear_tarea": params {titulo, prioridad(baja|media|alta|urgente), dias_limite(int)}
        - "asignar_lead": params {lead_nombre, agente_nombre}
        - "nota_lead": params {lead_nombre, texto}
        - "cambiar_etapa": params {lead_nombre, etapa(Nuevo|Contactado|Visita|Oferta|Firmado|Perdido)}
              (ej. "marca a Ana como contactada", "Luis hizo la visita", "hemos firmado con Pedro")
        - "agendar_visita": params {lead_nombre, propiedad_nombre, fecha_iso(YYYY-MM-DDTHH:mm), duracion_min(int)}
              (ej. "agenda visita con Ana mañana a las 17 en el piso de Calle Mayor")
        - "buscar_compradores": params {propiedad_nombre}
              (ej. "¿qué leads encajan con el ático de Sol?", "busca compradores para...")
        - "sugerir_propiedades": params {lead_nombre}
              (ej. "¿qué pisos le enseño a Ana?", "qué le puedo ofrecer a Luis", "propiedades para el lead X")
        - "redactar_mensaje": params {canal(email|whatsapp), lead_nombre, asunto, cuerpo}
              (solo REDACTA un borrador; si el lead tiene teléfono se añade enlace de WhatsApp listo)
        - "enviar_email": params {lead_nombre, asunto, cuerpo}
              (ENVÍA el email de verdad; úsalo SOLO si el usuario dice explícitamente "envía/manda")
        - "generar_cma": params {propiedad_nombre, propietario_nombre(opcional)}
              (genera el informe de mercado/CMA de un inmueble y devuelve el enlace compartible)
        - "enlace_valoracion": params {}
              (devuelve el enlace público de valoración de la agencia para compartir)
        - "resumir_llamada": params {lead_nombre, resumen, siguiente_paso, dias_siguiente_paso(int)}
              (cuando el usuario pega notas/transcripción de una llamada)
        - "responder": params {texto}   (para consultas o cuando no hay acción clara)

        Si el usuario solo pregunta algo, usa "responder" con la respuesta en params.texto.
        Sé conciso. Español.
        """;

    public Map<String, Object> procesar(String uid, String peticion, String contexto) {
        return procesar(uid, peticion, contexto, List.of());
    }

    public Map<String, Object> procesar(String uid, String peticion, String contexto, List<Map<String, Object>> historial) {
        StringBuilder conv = new StringBuilder();
        if (historial != null && !historial.isEmpty()) {
            conv.append("CONVERSACIÓN previa (para contexto):\n");
            for (Map<String, Object> m : historial) {
                conv.append("agente".equals(String.valueOf(m.get("rol"))) ? "AGENTE: " : "TÚ: ")
                    .append(m.get("texto")).append("\n");
            }
            conv.append("\n");
        }
        String prompt = "HOY es " + OffsetDateTime.now().toLocalDate() + " (" +
                OffsetDateTime.now().getDayOfWeek() + ", hora " + OffsetDateTime.now().toLocalTime().withSecond(0).withNano(0) + ").\n\n" +
                conv + "CONTEXTO de la cartera:\n" + contexto + "\n\nPETICIÓN del agente: \"" + peticion + "\"";
        String raw;
        try {
            raw = aiService.call(null, prompt, SYSTEM, 600, uid);
        } catch (Exception e) {
            return resp("El asistente IA no está disponible ahora mismo (falta configurar OPENAI_API_KEY_PLATFORM).");
        }
        // aiService.call devuelve null si no hay clave o si la llamada falló: mensaje claro en vez de respuesta vacía.
        if (raw == null || raw.isBlank()) {
            if (!aiService.hasPlatformKey()) {
                int lp = envOpenAiPlatform == null ? 0 : envOpenAiPlatform.trim().length();
                int lk = envOpenAiPlain == null ? 0 : envOpenAiPlain.trim().length();
                return resp("⚠️ Falta la clave de OpenAI. El servidor ve OPENAI_API_KEY_PLATFORM(len=" + lp
                        + ") y OPENAI_API_KEY(len=" + lk + "). Pon tu clave sk-... en una de ellas en Railway"
                        + " y aplica/redeploya. (len=0 significa que la variable no llega a la app).");
            }
            return resp("⚠️ La clave de OpenAI está configurada, pero la llamada falló (clave inválida, sin saldo o problema de red). Revisa la clave y el saldo en platform.openai.com.");
        }

        Map<String, Object> json = parseJson(raw);
        if (json == null) {
            // La IA respondió texto plano: devolverlo tal cual
            return resp(raw);
        }

        String accion = String.valueOf(json.getOrDefault("accion", "responder"));
        @SuppressWarnings("unchecked")
        Map<String, Object> p = json.get("params") instanceof Map ? (Map<String, Object>) json.get("params") : new LinkedHashMap<>();
        String confirmacion = String.valueOf(json.getOrDefault("confirmacion", ""));

        try {
            switch (accion) {
                case "crear_tarea":   return crearTarea(uid, p, confirmacion);
                case "asignar_lead":  return asignarLead(uid, p, confirmacion);
                case "nota_lead":     return notaLead(uid, p, confirmacion);
                case "cambiar_etapa": return cambiarEtapa(uid, p, confirmacion);
                case "agendar_visita": return agendarVisita(uid, p, confirmacion);
                case "buscar_compradores": return buscarCompradores(uid, p);
                case "sugerir_propiedades": return sugerirPropiedades(uid, p);
                case "redactar_mensaje": return redactar(uid, p);
                case "enviar_email":  return enviarEmail(uid, p, confirmacion);
                case "generar_cma":   return generarCma(uid, p);
                case "enlace_valoracion": return enlaceValoracion(uid);
                case "resumir_llamada": return resumirLlamada(uid, p, confirmacion);
                default:              return resp(String.valueOf(p.getOrDefault("texto", raw)));
            }
        } catch (Exception e) {
            log.warn("Acción agente falló: {}", e.getMessage());
            return resp("No pude completar la acción: " + e.getMessage());
        }
    }

    // ─── Acciones ───
    private Map<String, Object> crearTarea(String uid, Map<String, Object> p, String conf) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("user_id", uid);
        t.put("titulo", p.getOrDefault("titulo", "Tarea"));
        t.put("prioridad", p.getOrDefault("prioridad", "media"));
        t.put("estado", "pendiente");
        int dias = parseInt(p.get("dias_limite"), 1);
        t.put("fecha_limite", LocalDate.now().plusDays(dias).toString());
        t.put("descripcion", "Creada por el Agente IA");
        t.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("tareas", t);
        return ok(conf.isBlank() ? "✅ Tarea creada: " + t.get("titulo") : conf, "tarea_creada");
    }

    private Map<String, Object> asignarLead(String uid, Map<String, Object> p, String conf) {
        String leadNombre = String.valueOf(p.getOrDefault("lead_nombre", ""));
        String agenteNombre = String.valueOf(p.getOrDefault("agente_nombre", ""));
        var leads = supabase.selectFilter("leads", "user_id=eq." + uid + "&nombre=ilike.*" + enc(leadNombre) + "*&limit=1");
        if (leads.isEmpty()) return resp("No encontré ningún lead que se llame \"" + leadNombre + "\".");
        var agentes = supabase.selectFilter("users",
                "or=(org_id.eq." + uid + ",id.eq." + uid + ")&name=ilike.*" + enc(agenteNombre) + "*&limit=1");
        if (agentes.isEmpty()) return resp("No encontré ningún agente que se llame \"" + agenteNombre + "\" en tu equipo.");
        String agenteId = String.valueOf(agentes.get(0).get("id"));
        supabase.update("leads", String.valueOf(leads.get(0).get("id")), Map.of("asignado_a", agenteId));
        return ok(conf.isBlank() ? "✅ Lead " + leads.get(0).get("nombre") + " asignado a " + agentes.get(0).getOrDefault("name", agenteNombre) : conf, "lead_asignado");
    }

    private Map<String, Object> notaLead(String uid, Map<String, Object> p, String conf) {
        String leadNombre = String.valueOf(p.getOrDefault("lead_nombre", ""));
        String texto = String.valueOf(p.getOrDefault("texto", ""));
        var leads = supabase.selectFilter("leads", "user_id=eq." + uid + "&nombre=ilike.*" + enc(leadNombre) + "*&limit=1");
        if (leads.isEmpty()) return resp("No encontré el lead \"" + leadNombre + "\".");
        Map<String, Object> nota = new LinkedHashMap<>();
        nota.put("lead_id", leads.get(0).get("id"));
        nota.put("user_id", uid);
        nota.put("tipo", "nota");
        nota.put("detalle", texto);
        nota.put("created_at", OffsetDateTime.now().toString());
        try { supabase.insert("lead_interacciones", nota); } catch (Exception ignored) {}
        return ok(conf.isBlank() ? "✅ Nota registrada en " + leadNombre : conf, "nota_creada");
    }

    private Map<String, Object> resumirLlamada(String uid, Map<String, Object> p, String conf) {
        String leadNombre = String.valueOf(p.getOrDefault("lead_nombre", ""));
        String resumen = String.valueOf(p.getOrDefault("resumen", ""));
        String siguiente = String.valueOf(p.getOrDefault("siguiente_paso", ""));
        int dias = parseInt(p.get("dias_siguiente_paso"), 2);

        var leads = supabase.selectFilter("leads", "user_id=eq." + uid + "&nombre=ilike.*" + enc(leadNombre) + "*&limit=1");
        String leadId = leads.isEmpty() ? null : String.valueOf(leads.get(0).get("id"));

        // 1. Nota con el resumen de la llamada
        if (leadId != null) {
            Map<String, Object> nota = new LinkedHashMap<>();
            nota.put("lead_id", leadId);
            nota.put("user_id", uid);
            nota.put("tipo", "llamada");
            nota.put("detalle", resumen);
            nota.put("created_at", OffsetDateTime.now().toString());
            try { supabase.insert("lead_interacciones", nota); } catch (Exception ignored) {}
        }
        // 2. Tarea con el siguiente paso
        if (!siguiente.isBlank() && !"null".equals(siguiente)) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("user_id", uid);
            t.put("titulo", siguiente + (leadNombre.isBlank() ? "" : " — " + leadNombre));
            t.put("prioridad", "media");
            t.put("estado", "pendiente");
            if (leadId != null) t.put("lead_id", leadId);
            t.put("fecha_limite", LocalDate.now().plusDays(dias).toString());
            t.put("created_at", OffsetDateTime.now().toString());
            try { supabase.insert("tareas", t); } catch (Exception ignored) {}
        }
        String msg = conf.isBlank()
            ? "✅ Llamada registrada en " + (leadNombre.isBlank() ? "el lead" : leadNombre) +
              (siguiente.isBlank() ? "" : " + tarea: " + siguiente)
            : conf;
        return ok(msg, "llamada_resumida");
    }

    private static final Set<String> ETAPAS_VALIDAS = Set.of("Nuevo", "Contactado", "Visita", "Oferta", "Firmado", "Perdido");

    private Map<String, Object> cambiarEtapa(String uid, Map<String, Object> p, String conf) {
        String leadNombre = String.valueOf(p.getOrDefault("lead_nombre", ""));
        String etapa = String.valueOf(p.getOrDefault("etapa", ""));
        // Normalizar capitalización ("contactado" → "Contactado")
        String etapaNorm = etapa.isBlank() ? "" :
                etapa.substring(0, 1).toUpperCase() + etapa.substring(1).toLowerCase();
        if (!ETAPAS_VALIDAS.contains(etapaNorm)) {
            return resp("Etapa no válida: \"" + etapa + "\". Usa: Nuevo, Contactado, Visita, Oferta, Firmado o Perdido.");
        }
        var leads = supabase.selectFilter("leads", "user_id=eq." + uid + "&nombre=ilike.*" + enc(leadNombre) + "*&limit=1");
        if (leads.isEmpty()) return resp("No encontré el lead \"" + leadNombre + "\".");
        supabase.update("leads", String.valueOf(leads.get(0).get("id")), Map.of("etapa", etapaNorm));
        return ok(conf.isBlank() ? "✅ " + leads.get(0).get("nombre") + " → etapa " + etapaNorm : conf, "etapa_cambiada");
    }

    private Map<String, Object> agendarVisita(String uid, Map<String, Object> p, String conf) {
        String leadNombre = String.valueOf(p.getOrDefault("lead_nombre", ""));
        String propNombre = String.valueOf(p.getOrDefault("propiedad_nombre", ""));
        String fechaIso = String.valueOf(p.getOrDefault("fecha_iso", ""));
        int duracion = parseInt(p.get("duracion_min"), 30);

        var props = supabase.selectFilter("propiedades",
                "user_id=eq." + uid + "&or=(name.ilike.*" + enc(propNombre) + "*,address.ilike.*" + enc(propNombre) + "*)&limit=1");
        if (props.isEmpty()) return resp("No encontré la propiedad \"" + propNombre + "\" en tu cartera.");
        var leads = supabase.selectFilter("leads", "user_id=eq." + uid + "&nombre=ilike.*" + enc(leadNombre) + "*&limit=1");

        OffsetDateTime inicio;
        try {
            inicio = java.time.LocalDateTime.parse(fechaIso.length() == 16 ? fechaIso : fechaIso.substring(0, 16))
                    .atZone(java.time.ZoneId.of("Europe/Madrid")).toOffsetDateTime();
        } catch (Exception e) {
            return resp("No entendí la fecha de la visita. Dímela con día y hora (ej. \"mañana a las 17:00\").");
        }

        Map<String, Object> v = new LinkedHashMap<>();
        v.put("user_id", uid);
        v.put("propiedad_id", props.get(0).get("id"));
        if (!leads.isEmpty()) v.put("lead_id", leads.get(0).get("id"));
        v.put("visitante_nombre", leads.isEmpty() ? (leadNombre.isBlank() ? "Visitante" : leadNombre) : leads.get(0).get("nombre"));
        if (!leads.isEmpty()) {
            v.put("visitante_email", leads.get(0).get("email"));
            v.put("visitante_telefono", leads.get(0).get("telefono"));
        }
        v.put("fecha_inicio", inicio.toString());
        v.put("fecha_fin", inicio.plusMinutes(duracion).toString());
        v.put("duracion_min", duracion);
        v.put("estado", "programada");
        v.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("visitas", v);

        return ok(conf.isBlank()
                ? "✅ Visita agendada: " + v.get("visitante_nombre") + " → " +
                  props.get(0).getOrDefault("name", props.get(0).get("address")) + " el " +
                  inicio.toLocalDate() + " a las " + inicio.toLocalTime().withSecond(0).withNano(0)
                : conf, "visita_agendada");
    }

    private Map<String, Object> buscarCompradores(String uid, Map<String, Object> p) {
        String propNombre = String.valueOf(p.getOrDefault("propiedad_nombre", ""));
        var props = supabase.selectFilter("propiedades",
                "user_id=eq." + uid + "&or=(name.ilike.*" + enc(propNombre) + "*,address.ilike.*" + enc(propNombre) + "*)&limit=1");
        if (props.isEmpty()) return resp("No encontré la propiedad \"" + propNombre + "\" en tu cartera.");
        Map<String, Object> prop = props.get(0);
        double precio = nd(prop.get("current_value"));
        if (precio <= 0) precio = nd(prop.get("purchase_price"));
        String city = String.valueOf(prop.getOrDefault("city", "")).toLowerCase();

        var leads = supabase.selectFilter("leads", "user_id=eq." + uid + "&limit=300");
        List<String> lineas = new ArrayList<>();
        for (var l : leads) {
            String etapa = String.valueOf(l.getOrDefault("etapa", ""));
            if ("Perdido".equalsIgnoreCase(etapa) || "Firmado".equalsIgnoreCase(etapa)) continue;
            double presup = nd(l.get("presupuesto"));
            if (presup > 0 && precio > 0 && precio > presup * 1.10) continue;
            String zona = String.valueOf(l.getOrDefault("zona", "")).toLowerCase();
            if (!city.isBlank() && !zona.isBlank() && !zona.contains(city) && !city.contains(zona)) continue;
            lineas.add("• " + l.getOrDefault("nombre", "?")
                    + (presup > 0 ? " (hasta " + Math.round(presup) + "€)" : "")
                    + (l.get("telefono") != null ? " · 📞 " + l.get("telefono") : ""));
            if (lineas.size() >= 5) break;
        }
        if (lineas.isEmpty()) {
            return resp("No encontré leads que encajen con " + prop.getOrDefault("name", propNombre) +
                    " ahora mismo. Prueba a compartir tu enlace de valoración o el simulador para captar más.");
        }
        return resp("🎯 Leads que encajan con " + prop.getOrDefault("name", prop.get("address")) +
                (precio > 0 ? " (" + Math.round(precio) + "€)" : "") + ":\n\n" + String.join("\n", lineas) +
                "\n\n¿Quieres que agende una visita o les redacte un mensaje?");
    }

    private Map<String, Object> sugerirPropiedades(String uid, Map<String, Object> p) {
        String leadNombre = String.valueOf(p.getOrDefault("lead_nombre", ""));
        var leads = supabase.selectFilter("leads", "user_id=eq." + uid + "&nombre=ilike.*" + enc(leadNombre) + "*&limit=1");
        if (leads.isEmpty()) return resp("No encontré el lead \"" + leadNombre + "\".");
        Map<String, Object> lead = leads.get(0);
        double presup = nd(lead.get("presupuesto"));
        String zona = String.valueOf(lead.getOrDefault("zona", "")).toLowerCase();

        var props = supabase.selectFilter("propiedades", "user_id=eq." + uid + "&limit=300");
        List<String> lineas = new ArrayList<>();
        for (var prop : props) {
            if ("Reservado".equalsIgnoreCase(String.valueOf(prop.get("status")))) continue;
            double precio = nd(prop.get("current_value"));
            if (precio <= 0) precio = nd(prop.get("purchase_price"));
            if (precio <= 0) continue;
            if (presup > 0 && precio > presup * 1.10) continue;  // dentro de presupuesto (+10%)
            String city = String.valueOf(prop.getOrDefault("city", "")).toLowerCase();
            if (!zona.isBlank() && !city.isBlank() && !city.contains(zona) && !zona.contains(city)) continue;
            lineas.add("• " + prop.getOrDefault("name", prop.getOrDefault("address", "Inmueble"))
                    + " — " + Math.round(precio) + "€"
                    + (prop.get("city") != null ? " (" + prop.get("city") + ")" : ""));
            if (lineas.size() >= 5) break;
        }
        if (lineas.isEmpty()) {
            return resp("No encontré propiedades de tu cartera que encajen con " + lead.getOrDefault("nombre", leadNombre) +
                    (presup > 0 ? " (presupuesto ~" + Math.round(presup) + "€)" : "") +
                    ". ¿Le abrimos una alerta con el simulador o buscamos en co-broking?");
        }
        return resp("🏠 Pisos para enseñarle a " + lead.getOrDefault("nombre", leadNombre) +
                (presup > 0 ? " (hasta ~" + Math.round(presup) + "€)" : "") + ":\n\n" + String.join("\n", lineas) +
                "\n\n¿Le agendo una visita o le redacto un mensaje con estas opciones?");
    }

    private Map<String, Object> redactar(String uid, Map<String, Object> p) {
        String canal = String.valueOf(p.getOrDefault("canal", "email"));
        String cuerpo = String.valueOf(p.getOrDefault("cuerpo", ""));
        String asunto = String.valueOf(p.getOrDefault("asunto", ""));
        String leadNombre = String.valueOf(p.getOrDefault("lead_nombre", p.getOrDefault("para", "")));

        StringBuilder s = new StringBuilder("📝 Borrador (" + canal + "):\n\n");
        if (!asunto.isBlank() && !"null".equals(asunto)) s.append("Asunto: ").append(asunto).append("\n\n");
        s.append(cuerpo);

        // Si es WhatsApp y conocemos el teléfono del lead → enlace listo para enviar en 1 clic
        if ("whatsapp".equalsIgnoreCase(canal) && !leadNombre.isBlank() && !"null".equals(leadNombre)) {
            try {
                var leads = supabase.selectFilter("leads", "user_id=eq." + uid + "&nombre=ilike.*" + enc(leadNombre) + "*&limit=1");
                if (!leads.isEmpty() && leads.get(0).get("telefono") != null) {
                    String tel = String.valueOf(leads.get(0).get("telefono")).replaceAll("[\\s+\\-]", "");
                    s.append("\n\n👉 Enviar ahora: https://wa.me/").append(tel).append("?text=")
                     .append(java.net.URLEncoder.encode(cuerpo, java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {}
        }
        Map<String, Object> r = resp(s.toString());
        r.put("borrador", true);
        return r;
    }

    /** ENVÍA un email real al lead (solo cuando el usuario lo pide explícitamente). */
    private Map<String, Object> enviarEmail(String uid, Map<String, Object> p, String conf) {
        String leadNombre = String.valueOf(p.getOrDefault("lead_nombre", ""));
        String asunto = String.valueOf(p.getOrDefault("asunto", "Mensaje de tu agencia"));
        String cuerpo = String.valueOf(p.getOrDefault("cuerpo", ""));
        if (cuerpo.isBlank() || "null".equals(cuerpo)) return resp("Dime qué quieres que le escriba y se lo envío.");

        var leads = supabase.selectFilter("leads", "user_id=eq." + uid + "&nombre=ilike.*" + enc(leadNombre) + "*&limit=1");
        if (leads.isEmpty()) return resp("No encontré el lead \"" + leadNombre + "\".");
        String email = String.valueOf(leads.get(0).getOrDefault("email", ""));
        if (email.isBlank() || "null".equals(email)) {
            return resp("El lead " + leads.get(0).get("nombre") + " no tiene email guardado. ¿Quieres que le redacte un WhatsApp?");
        }
        try {
            emailService.send(email, asunto, cuerpo.replace("\n", "<br>"));
        } catch (Exception e) {
            return resp("No pude enviar el email: " + e.getMessage());
        }
        // Registrar la interacción en el historial del lead
        try {
            Map<String, Object> nota = new LinkedHashMap<>();
            nota.put("lead_id", leads.get(0).get("id"));
            nota.put("user_id", uid);
            nota.put("tipo", "email");
            nota.put("detalle", "Email enviado por el Agente IA — Asunto: " + asunto);
            nota.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("lead_interacciones", nota);
        } catch (Exception ignored) {}
        return ok(conf.isBlank() ? "📧 Email enviado a " + leads.get(0).get("nombre") + " (" + email + ")" : conf, "email_enviado");
    }

    /** Genera el informe de mercado (CMA) de un inmueble y devuelve el enlace. */
    private Map<String, Object> generarCma(String uid, Map<String, Object> p) {
        String propNombre = String.valueOf(p.getOrDefault("propiedad_nombre", ""));
        var props = supabase.selectFilter("propiedades",
                "user_id=eq." + uid + "&or=(name.ilike.*" + enc(propNombre) + "*,address.ilike.*" + enc(propNombre) + "*)&limit=1");
        if (props.isEmpty()) return resp("No encontré la propiedad \"" + propNombre + "\" en tu cartera.");

        String propietario = String.valueOf(p.getOrDefault("propietario_nombre", ""));
        Map<String, Object> rec = informes.generarYGuardar(uid, props.get(0),
                propietario.isBlank() || "null".equals(propietario) ? null : propietario);
        String url = BASE_URL + "/informe/" + rec.get("token");
        return ok("📊 Informe de mercado generado para " +
                props.get(0).getOrDefault("name", props.get(0).get("address")) +
                "\n\nPrecio recomendado: " + rec.get("precio_recomendado") + "€" +
                "\n👉 Compártelo: " + url, "cma_generado");
    }

    /** Devuelve el enlace público de valoración de la agencia. */
    private Map<String, Object> enlaceValoracion(String uid) {
        String url = captacion.enlaceValoracion(uid);
        return ok("🏷️ Tu enlace de valoración para captar propietarios:\n\n" + url +
                "\n\n📲 Compartir por WhatsApp: https://wa.me/?text=" +
                java.net.URLEncoder.encode("Valora tu casa gratis en 30 segundos: " + url, java.nio.charset.StandardCharsets.UTF_8),
                "enlace_valoracion");
    }

    // ─── Helpers ───
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        // Quitar fences markdown ```json ... ```
        if (s.startsWith("```")) s = s.replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
        int i = s.indexOf('{'), j = s.lastIndexOf('}');
        if (i < 0 || j <= i) return null;
        try { return mapper.readValue(s.substring(i, j + 1), Map.class); } catch (Exception e) { return null; }
    }
    private Map<String, Object> resp(String txt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true); m.put("respuesta", txt); m.put("accion", "responder");
        return m;
    }
    private Map<String, Object> ok(String txt, String accion) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true); m.put("respuesta", txt); m.put("accion", accion); m.put("ejecutada", true);
        return m;
    }
    private static int parseInt(Object o, int def) { try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return def; } }
    private static double nd(Object o) { try { return o == null ? 0 : Double.parseDouble(String.valueOf(o).replace(",", ".")); } catch (Exception e) { return 0; } }
    // NO pre-codificar (%20): WebClient usa TEMPLATE_AND_VALUES y volvería a codificar el '%'
    // (%20 → %2520), rompiendo el ilike con nombres de varias palabras. Se deja el espacio en
    // crudo (WebClient lo codifica a %20 correctamente) y se quitan solo los caracteres que
    // romperían la sintaxis del filtro PostgREST (, ( ) * y % sueltos).
    private static String enc(String s) { return s == null ? "" : s.replaceAll("[,()*%]", "").trim(); }
}
