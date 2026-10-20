package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Servicio del chatbot AI. Maneja conversaciones con visitantes, llama a la IA
 * con contexto de propiedades disponibles, detecta intenciones y captura leads.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatbotService {

    private final SupabaseService supabase;
    private final AIService aiService;
    private final NotificationService notif;
    private final WorkflowEngine workflowEngine;
    private final RateLimiter rateLimiter;

    // El chatbot es público y cada respuesta de IA gasta un crédito de la inmobiliaria:
    // sin límites, un script podía vaciarle los créditos (y las llamadas a OpenAI) en minutos.
    static final int MAX_MENSAJES_CONVERSACION = 25;          // cada 2 horas
    static final int MAX_IA_POR_HORA_INMOBILIARIA = 150;
    static final String RESPUESTA_LIMITE_CONVERSACION =
            "Gracias por tu interés. Para seguir ayudándote, déjanos tu nombre, email o teléfono y un agente te contestará personalmente.";
    static final String RESPUESTA_SIN_IA =
            "Ahora mismo no puedo responderte. Déjanos tu nombre, email o teléfono y un agente te contestará enseguida.";

    /** Procesa un mensaje del usuario y devuelve respuesta del bot. */
    public Map<String, Object> procesar(String conversacionId, String userMessage) {
        // Cargar conversación
        List<Map<String, Object>> convs = supabase.selectFilter(
                "chatbot_conversaciones", "id=eq." + conversacionId);
        if (convs.isEmpty()) return Map.of("ok", false, "error", "Conversación no encontrada");
        Map<String, Object> conv = convs.get(0);
        String userId = String.valueOf(conv.get("user_id"));

        if (!rateLimiter.tryAcquire("chatbot-conv:" + conversacionId, MAX_MENSAJES_CONVERSACION, java.time.Duration.ofHours(2))) {
            return Map.of("ok", true, "respuesta", RESPUESTA_LIMITE_CONVERSACION,
                    "propiedades_ids", List.of(), "pedir_email", true);
        }

        // Guardar mensaje del usuario
        guardarMensaje(conversacionId, "user", userMessage, null, null);

        // Detectar intención
        detectarIntencion(conversacionId, userMessage);

        // Cargar config
        List<Map<String, Object>> cfgs = supabase.selectFilter("chatbot_config", "user_id=eq." + userId);
        Map<String, Object> cfg = cfgs.isEmpty() ? Collections.emptyMap() : cfgs.get(0);

        // Cargar historial (últimos 10 mensajes)
        List<Map<String, Object>> historial = supabase.selectFilter(
                "chatbot_mensajes",
                "conversacion_id=eq." + conversacionId + "&order=created_at.desc&limit=10");
        Collections.reverse(historial);

        // Detectar si pide buscar propiedades
        boolean buscandoProp = userMessage.toLowerCase().matches(
                ".*(piso|casa|chalet|atico|alquiler|comprar|vender|busco|propiedad).*");

        String respuesta;
        List<String> propIds = new ArrayList<>();

        if (buscandoProp && Boolean.TRUE.equals(cfg.get("permitir_buscar_propiedades"))) {
            // Llamar función de búsqueda
            Map<String, Object> resultado = buscarPropiedades(userId, userMessage);
            propIds = (List<String>) resultado.getOrDefault("ids", new ArrayList<>());
            String propsContexto = (String) resultado.getOrDefault("contexto", "");

            // Preguntar a IA con el contexto
            String prompt = construirPrompt(historial, userMessage, propsContexto, cfg);
            respuesta = llamarIA(prompt, cfg, userId);
        } else if (userMessage.toLowerCase().contains("visita") || userMessage.toLowerCase().contains("ver el piso")) {
            // Sugerir agendar visita
            respuesta = "📅 ¡Genial! Para agendar una visita necesito tu nombre, email y teléfono. ¿Me los puedes facilitar?";
            registrarPasoCaptura(conversacionId, "agendar_visita");
        } else {
            // Chat libre
            String prompt = construirPrompt(historial, userMessage, "", cfg);
            respuesta = llamarIA(prompt, cfg, userId);
        }

        // Detectar si visitante da email/teléfono
        extraerDatosVisitante(conversacionId, userMessage, conv);

        // Guardar respuesta del bot
        guardarMensaje(conversacionId, "assistant", respuesta, null, propIds);

        // Actualizar contador
        int total = ((Number) conv.getOrDefault("mensajes_total", 0)).intValue() + 2;
        supabase.update("chatbot_conversaciones", conversacionId, Map.of(
                "mensajes_total", total,
                "ultima_actividad_at", OffsetDateTime.now().toString()
        ));

        // Verificar paso para pedir email
        int captarPaso = ((Number) cfg.getOrDefault("captar_email_pasos", 3)).intValue();
        boolean pedirEmail = total >= captarPaso * 2 &&
                (conv.get("visitor_email") == null || String.valueOf(conv.get("visitor_email")).isBlank());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("respuesta", respuesta);
        result.put("propiedades_ids", propIds);
        result.put("pedir_email", pedirEmail);
        return result;
    }

    /** Busca propiedades según criterios del visitante (búsqueda fuzzy, filtro en memoria). */
    private Map<String, Object> buscarPropiedades(String userId, String mensaje) {
        String msg = mensaje.toLowerCase();

        // Detectar tipo deseado (se compara contra propiedades.type, que es texto libre)
        String tipoDeseado = null;
        if (msg.contains("piso") || msg.contains("apartamento")) tipoDeseado = "piso";
        else if (msg.contains("casa") || msg.contains("chalet")) tipoDeseado = "casa";
        else if (msg.contains("atico") || msg.contains("ático")) tipoDeseado = "atico";
        else if (msg.contains("local")) tipoDeseado = "local";
        else if (msg.contains("oficina")) tipoDeseado = "oficina";
        else if (msg.contains("garaje")) tipoDeseado = "garaje";

        // Detectar precio máximo (números)
        double precioMax = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,9})\\s*(?:€|euros|mil|k)").matcher(msg);
        if (m.find()) {
            precioMax = Double.parseDouble(m.group(1)); // como mucho 9 cifras: un número enorme ya no rompe el chat
            if (msg.contains("mil") || msg.contains("k")) precioMax *= 1000;
        }

        List<Map<String, Object>> todas = supabase.selectFilter("propiedades",
                "user_id=eq." + userId + "&limit=100");
        StringBuilder ctx = new StringBuilder();
        List<String> ids = new ArrayList<>();
        int added = 0;
        for (Map<String, Object> p : todas) {
            if ("Reservado".equalsIgnoreCase(String.valueOf(p.get("status")))) continue;
            double precio = nd(p.get("current_value"));
            if (precio <= 0) precio = nd(p.get("purchase_price"));
            if (precioMax > 0 && (precio <= 0 || precio > precioMax)) continue;
            if (tipoDeseado != null) {
                String type = String.valueOf(p.getOrDefault("type", "")).toLowerCase();
                if (!type.contains(tipoDeseado) && !tipoDeseado.contains(type)) continue;
            }
            ctx.append(String.format("- %s | %s | %s€ | %s hab. | %s m²\n",
                    p.getOrDefault("name", p.getOrDefault("address", "?")), p.get("type"),
                    precio > 0 ? Math.round(precio) : "?",
                    p.get("bedrooms"), p.get("area_m2")));
            ids.add(String.valueOf(p.get("id")));
            if (++added >= 5) break;
        }
        if (added == 0) {
            ctx.append("No hay propiedades disponibles con esos criterios ahora mismo.");
        } else {
            ctx.insert(0, "PROPIEDADES DISPONIBLES:\n");
        }
        return Map.of("contexto", ctx.toString(), "ids", ids);
    }

    private static double nd(Object o) { try { return o == null ? 0 : Double.parseDouble(String.valueOf(o).replace(",", ".")); } catch (Exception e) { return 0; } }

    private String construirPrompt(List<Map<String, Object>> historial, String userMsg, String contexto, Map<String, Object> cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("CONTEXTO INMOBILIARIO:\n").append(contexto).append("\n\n");
        sb.append("HISTORIAL CONVERSACIÓN:\n");
        for (Map<String, Object> m : historial) {
            String rol = "user".equals(m.get("rol")) ? "Cliente" : "Bot";
            sb.append(rol).append(": ").append(m.get("contenido")).append("\n");
        }
        sb.append("\nCliente: ").append(userMsg);
        sb.append("\nBot:");
        return sb.toString();
    }

    private String llamarIA(String prompt, Map<String, Object> cfg, String userId) {
        // Tope por inmobiliaria: aunque lleguen muchas conversaciones a la vez, no se vacían sus créditos
        if (!rateLimiter.tryAcquire("chatbot-org:" + userId, MAX_IA_POR_HORA_INMOBILIARIA, java.time.Duration.ofHours(1))) {
            return RESPUESTA_SIN_IA;
        }
        String persona = String.valueOf(cfg.getOrDefault("persona_prompt",
                "Eres un asistente inmobiliario amable. Máx. 3 frases."));
        try {
            return aiService.call(null, prompt, persona, 200, userId);
        } catch (Exception e) {
            log.warn("Err IA chatbot: {}", e.getMessage());
            return "Disculpa, tengo un problema técnico. ¿Puedes dejarme tu email y un agente te contactará?";
        }
    }

    private void detectarIntencion(String conversacionId, String msg) {
        String m = msg.toLowerCase();
        String intencion = null;
        if (m.contains("compr") || m.contains("vent")) intencion = "comprar";
        else if (m.contains("alquil") || m.contains("arrend")) intencion = "alquilar";
        else if (m.contains("valor") || m.contains("tasar")) intencion = "valorar";
        else if (m.contains("info") || m.contains("ayuda")) intencion = "info";

        if (intencion != null) {
            supabase.update("chatbot_conversaciones", conversacionId, Map.of("intencion_detectada", intencion));
        }
    }

    private void extraerDatosVisitante(String convId, String msg, Map<String, Object> conv) {
        Map<String, Object> upd = new LinkedHashMap<>();

        // Email
        java.util.regex.Matcher emailM = java.util.regex.Pattern.compile(
                "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}").matcher(msg);
        if (emailM.find() && conv.get("visitor_email") == null) {
            upd.put("visitor_email", emailM.group());
        }

        // Teléfono español
        java.util.regex.Matcher telM = java.util.regex.Pattern.compile(
                "(?:\\+34\\s?)?([6-9]\\d{2}[\\s-]?\\d{3}[\\s-]?\\d{3})").matcher(msg);
        if (telM.find() && conv.get("visitor_telefono") == null) {
            upd.put("visitor_telefono", telM.group().replaceAll("[\\s-]", ""));
        }

        if (!upd.isEmpty()) {
            supabase.update("chatbot_conversaciones", convId, upd);
            // Si tenemos email + telefono → crear lead automáticamente
            if (upd.get("visitor_email") != null || conv.get("visitor_email") != null) {
                crearLeadAutomatico(convId, conv, upd);
            }
        }
    }

    private void crearLeadAutomatico(String convId, Map<String, Object> conv, Map<String, Object> nuevosDatos) {
        try {
            // Verificar que no exista ya
            if (conv.get("lead_creado_id") != null) return;

            String userId = String.valueOf(conv.get("user_id"));
            String email = (String) nuevosDatos.getOrDefault("visitor_email", conv.get("visitor_email"));
            String telefono = (String) nuevosDatos.getOrDefault("visitor_telefono", conv.get("visitor_telefono"));
            String nombre = (String) conv.getOrDefault("visitor_nombre", "Visitante chat");

            Map<String, Object> lead = new LinkedHashMap<>();
            lead.put("user_id", userId);
            lead.put("nombre", nombre);
            lead.put("email", email);
            lead.put("telefono", telefono);
            lead.put("origen", "chatbot_web");
            lead.put("etapa", "Nuevo");
            lead.put("interes", conv.get("intencion_detectada"));
            lead.put("zona", conv.get("zona_detectada"));
            if (conv.get("presupuesto_detectado") != null)
                lead.put("presupuesto", conv.get("presupuesto_detectado"));
            lead.put("notas", "Lead capturado por el chatbot. Conversación: " + convId);
            lead.put("created_at", OffsetDateTime.now().toString());
            Map<String, Object> saved = supabase.insert("leads", lead);

            supabase.update("chatbot_conversaciones", convId, Map.of(
                    "lead_creado_id", saved.get("id"),
                    "estado", "convertida_lead"
            ));

            // Notificar al agente
            notif.create(userId, "🤖 Nuevo lead del chatbot",
                    nombre + " (" + email + ") ha contactado por el chatbot",
                    "/leads", "alta");

            // Trigger workflow
            workflowEngine.triggerEvent(userId, "lead_creado", lead);
        } catch (Exception e) {
            log.warn("Err crear lead auto: {}", e.getMessage());
        }
    }

    private void registrarPasoCaptura(String convId, String paso) {
        guardarMensaje(convId, "system", "Iniciado paso: " + paso, paso, null);
    }

    private void guardarMensaje(String convId, String rol, String contenido, String funcion, List<String> propIds) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("conversacion_id", convId);
        msg.put("rol", rol);
        msg.put("contenido", contenido);
        if (funcion != null) msg.put("funcion_llamada", funcion);
        if (propIds != null && !propIds.isEmpty()) msg.put("propiedades_mostradas", propIds);
        msg.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("chatbot_mensajes", msg);
    }

    /** Inicia una nueva conversación. */
    public Map<String, Object> iniciarConversacion(String widgetToken, String sessionId,
                                                    String pagina, String userAgent, String ip) {
        List<Map<String, Object>> cfgs = supabase.selectFilter("chatbot_config", "widget_token=eq." + widgetToken);
        if (cfgs.isEmpty() || !Boolean.TRUE.equals(cfgs.get(0).get("activo"))) {
            return Map.of("ok", false);
        }
        Map<String, Object> cfg = cfgs.get(0);

        Map<String, Object> conv = new LinkedHashMap<>();
        conv.put("user_id", cfg.get("user_id"));
        conv.put("session_id", sessionId);
        conv.put("pagina_origen", pagina);
        conv.put("user_agent", userAgent);
        conv.put("ip", ip);
        conv.put("estado", "activa");
        conv.put("iniciada_at", OffsetDateTime.now().toString());
        Map<String, Object> saved = supabase.insert("chatbot_conversaciones", conv);

        // Incrementar contador
        int total = ((Number) cfg.getOrDefault("total_conversaciones", 0)).intValue() + 1;
        supabase.update("chatbot_config", String.valueOf(cfg.get("id")),
                Map.of("total_conversaciones", total));

        // Guardar mensaje bienvenida
        String bienvenida = String.valueOf(cfg.getOrDefault("mensaje_bienvenida",
                "👋 ¡Hola! ¿En qué puedo ayudarte?"));
        guardarMensaje(String.valueOf(saved.get("id")), "assistant", bienvenida, null, null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("conversacion_id", saved.get("id"));
        result.put("nombre_bot", cfg.getOrDefault("nombre_bot", "Asistente"));
        result.put("avatar_url", cfg.get("avatar_url"));
        result.put("color_primario", cfg.getOrDefault("color_primario", "#3b82f6"));
        result.put("mensaje_bienvenida", bienvenida);
        return result;
    }
}
