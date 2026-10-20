package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * WhatsApp Business Cloud API (Meta). Envía mensajes vía
 * https://graph.facebook.com/v18.0/{phone_number_id}/messages
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsAppService {

    private final SupabaseService supabase;
    private final RestTemplate rest = new RestTemplate();
    private static final String API_BASE = "https://graph.facebook.com/v18.0";

    /** Envía mensaje de texto libre (sólo permitido dentro de ventana 24h o usando plantilla). */
    public Map<String, Object> enviarTexto(String userId, String numero, String texto) {
        Map<String, Object> cfg = config(userId);
        if (cfg == null) return Map.of("ok", false, "error", "WhatsApp no configurado");

        try {
            Map<String, Object> body = Map.of(
                    "messaging_product", "whatsapp",
                    "to", normalizar(numero),
                    "type", "text",
                    "text", Map.of("body", texto)
            );
            Map<?, ?> resp = post(cfg, body);
            String wamid = extraerId(resp);
            guardarMensaje(userId, numero, "saliente", "texto", texto, null, wamid, null);
            return Map.of("ok", true, "id", wamid != null ? wamid : "");
        } catch (Exception e) {
            log.warn("WA send err: {}", e.getMessage());
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    /** Envía plantilla aprobada por Meta (la única forma de iniciar conversación). */
    public Map<String, Object> enviarPlantilla(String userId, String numero, String nombrePlantilla,
                                                String idioma, List<String> params) {
        Map<String, Object> cfg = config(userId);
        if (cfg == null) return Map.of("ok", false, "error", "Configura WhatsApp primero");

        try {
            List<Map<String, Object>> components = new ArrayList<>();
            if (params != null && !params.isEmpty()) {
                List<Map<String, String>> parameters = new ArrayList<>();
                for (String p : params) parameters.add(Map.of("type", "text", "text", p));
                components.add(Map.of("type", "body", "parameters", parameters));
            }
            Map<String, Object> body = Map.of(
                    "messaging_product", "whatsapp",
                    "to", normalizar(numero),
                    "type", "template",
                    "template", Map.of(
                            "name", nombrePlantilla,
                            "language", Map.of("code", idioma != null ? idioma : "es"),
                            "components", components
                    )
            );
            Map<?, ?> resp = post(cfg, body);
            String wamid = extraerId(resp);
            guardarMensaje(userId, numero, "saliente", "plantilla",
                    "Plantilla: " + nombrePlantilla, nombrePlantilla, wamid, params);
            return Map.of("ok", true, "id", wamid != null ? wamid : "");
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    /** Procesa webhook de Meta (mensajes entrantes, estados de entrega, etc.). */
    @SuppressWarnings("unchecked")
    public void procesarWebhook(Map<String, Object> body) {
        try {
            List<Map<String, Object>> entries = (List<Map<String, Object>>) body.get("entry");
            if (entries == null) return;
            for (Map<String, Object> entry : entries) {
                List<Map<String, Object>> changes = (List<Map<String, Object>>) entry.get("changes");
                for (Map<String, Object> change : changes) {
                    Map<String, Object> value = (Map<String, Object>) change.get("value");
                    List<Map<String, Object>> mensajes = (List<Map<String, Object>>) value.get("messages");
                    if (mensajes == null) continue;

                    for (Map<String, Object> m : mensajes) {
                        String numero = String.valueOf(m.get("from"));
                        String tipo = String.valueOf(m.get("type"));
                        String texto = "";
                        if (m.get("text") instanceof Map) texto = String.valueOf(((Map<?, ?>) m.get("text")).get("body"));

                        // Encontrar usuario por phone_number_id del business
                        String businessId = String.valueOf(value.get("metadata") instanceof Map
                                ? ((Map<?, ?>) value.get("metadata")).get("phone_number_id") : "");
                        List<Map<String, Object>> cfgs = supabase.selectFilter("whatsapp_config", "phone_number_id=eq." + businessId);
                        if (cfgs.isEmpty()) continue;
                        String userId = String.valueOf(cfgs.get(0).get("user_id"));

                        guardarMensaje(userId, numero, "entrante", tipo, texto, null,
                                String.valueOf(m.get("id")), null);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Err webhook WA: {}", e.getMessage());
        }
    }

    private Map<String, Object> config(String userId) {
        List<Map<String, Object>> rows = supabase.selectFilter("whatsapp_config", "user_id=eq." + userId + "&activo=eq.true");
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<?, ?> post(Map<String, Object> cfg, Map<String, Object> body) {
        String url = API_BASE + "/" + cfg.get("phone_number_id") + "/messages";
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(String.valueOf(cfg.get("access_token")));
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForObject(url, new HttpEntity<>(body, h), Map.class);
    }

    @SuppressWarnings("unchecked")
    private String extraerId(Map<?, ?> resp) {
        if (resp == null) return null;
        List<Map<String, Object>> msgs = (List<Map<String, Object>>) resp.get("messages");
        if (msgs != null && !msgs.isEmpty()) return String.valueOf(msgs.get(0).get("id"));
        return null;
    }

    private String normalizar(String tel) {
        String clean = tel.replaceAll("[\\s+\\-()]", "");
        if (!clean.startsWith("34") && clean.length() == 9) clean = "34" + clean;
        return clean;
    }

    private void guardarMensaje(String userId, String numero, String dir, String tipo,
                                String texto, String template, String wamid, Object params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", userId);
        m.put("chat_id", normalizar(numero));
        m.put("direccion", dir);
        m.put("tipo", tipo);
        m.put("mensaje", texto);
        if (template != null) m.put("template_name", template);
        if (params != null) m.put("template_params", params);
        if (wamid != null) m.put("wa_message_id", wamid);
        m.put("fecha", OffsetDateTime.now().toString());
        m.put("created_at", OffsetDateTime.now().toString());
        supabase.insert("whatsapp_mensajes", m);
    }
}
