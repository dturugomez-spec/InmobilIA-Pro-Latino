package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Procesa el envío de campañas. Segmenta destinatarios, sustituye variables,
 * inserta tracking (pixel + redirect links), y envía vía EmailService.
 *
 * Variables soportadas en plantillas: {nombre}, {email}, {propiedad_direccion},
 * {propiedad_precio}, {agente_nombre}, {fecha}, etc.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignService {

    private final SupabaseService supabase;
    private final EmailService emailService;
    private final FirmaEnlaces firmaEnlaces;

    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;
    private static final Pattern VAR_PATTERN = Pattern.compile("\\{([a-z_]+)\\}");

    /** Lanza el envío de una campaña en background. */
    @Async
    public void enviarCampana(String campanaId) {
        log.info("Iniciando envío campaña {}", campanaId);
        List<Map<String, Object>> rows = supabase.selectFilter("campanas", "id=eq." + campanaId);
        if (rows.isEmpty()) return;
        Map<String, Object> camp = rows.get(0);

        // Marcar enviando
        supabase.update("campanas", campanaId, Map.of(
                "estado", "enviando",
                "enviado_at", OffsetDateTime.now().toString()
        ));

        // 1) Obtener destinatarios según segmentación
        List<Map<String, Object>> destinatarios = obtenerDestinatarios(camp);
        log.info("Campaña {}: {} destinatarios", campanaId, destinatarios.size());

        // 2) Crear envíos individuales y procesar
        int enviados = 0, fallidos = 0;
        Random rand = new Random();
        boolean abTest = Boolean.TRUE.equals(camp.get("ab_test"));
        int splitPct = ((Number) camp.getOrDefault("ab_split_pct", 50)).intValue();

        for (Map<String, Object> d : destinatarios) {
            try {
                String email = String.valueOf(d.get("email"));
                if (email == null || email.isBlank() || "null".equals(email)) continue;

                // Verificar baja
                List<Map<String, Object>> bajas = supabase.selectFilter(
                        "bajas_marketing", "user_id=eq." + camp.get("user_id") + "&email=eq." + email);
                if (!bajas.isEmpty()) {
                    continue;
                }

                String variante = "A";
                if (abTest && rand.nextInt(100) >= splitPct) variante = "B";

                // Crear registro de envío
                Map<String, Object> envio = new LinkedHashMap<>();
                envio.put("campana_id", campanaId);
                envio.put("user_id", camp.get("user_id"));
                envio.put("destinatario_email", email);
                envio.put("destinatario_nombre", d.get("nombre"));
                envio.put("destinatario_telefono", d.get("telefono"));
                if (d.get("lead_id") != null) envio.put("lead_id", d.get("lead_id"));
                if (d.get("inquilino_id") != null) envio.put("inquilino_id", d.get("inquilino_id"));
                envio.put("variante", variante);
                envio.put("estado", "pendiente");
                envio.put("created_at", OffsetDateTime.now().toString());
                Map<String, Object> saved = supabase.insert("campana_envios", envio);
                String token = String.valueOf(saved.get("tracking_token"));

                // Sustituir variables + inyectar tracking
                String asunto = "A".equals(variante) ? String.valueOf(camp.get("asunto")) : String.valueOf(camp.getOrDefault("asunto_b", camp.get("asunto")));
                String html = "A".equals(variante) ? String.valueOf(camp.get("contenido")) : String.valueOf(camp.getOrDefault("contenido_b", camp.get("contenido")));

                String asuntoFinal = aplicarVariables(asunto, d);
                String htmlFinal = aplicarVariables(html, d);
                htmlFinal = inyectarTracking(htmlFinal, token, Boolean.TRUE.equals(camp.get("incluir_unsubscribe")));

                emailService.send(email, asuntoFinal, htmlFinal);

                supabase.update("campana_envios", String.valueOf(saved.get("id")), Map.of(
                        "estado", "enviado",
                        "enviado_at", OffsetDateTime.now().toString()
                ));
                enviados++;

                // Throttling: 100ms entre envíos para evitar spam filters
                Thread.sleep(100);
            } catch (Exception e) {
                fallidos++;
                log.warn("Err envio {}: {}", d.get("email"), e.getMessage());
            }
        }

        // Actualizar campaña
        supabase.update("campanas", campanaId, Map.of(
                "estado", "enviada",
                "destinatarios_total", destinatarios.size(),
                "enviados", enviados,
                "fallidos", fallidos,
                "completado_at", OffsetDateTime.now().toString(),
                "updated_at", OffsetDateTime.now().toString()
        ));
        log.info("Campaña {} completada: {} enviados, {} fallidos", campanaId, enviados, fallidos);
    }

    /** Segmentación de destinatarios. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> obtenerDestinatarios(Map<String, Object> camp) {
        String tipo = String.valueOf(camp.get("segmento_tipo"));
        String userId = String.valueOf(camp.get("user_id"));
        List<Map<String, Object>> resultado = new ArrayList<>();

        switch (tipo) {
            case "todos_leads":
                resultado.addAll(supabase.selectFilter("leads", "user_id=eq." + userId));
                break;
            case "todos_inquilinos":
                resultado.addAll(supabase.selectFilter("inquilinos", "user_id=eq." + userId));
                break;
            case "todos_propietarios":
                resultado.addAll(supabase.selectFilter("propietarios", "user_id=eq." + userId));
                break;
            case "lista_custom":
                Object lista = camp.get("lista_destinatarios");
                if (lista instanceof List) {
                    for (Object e : (List<?>) lista) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("email", String.valueOf(e));
                        r.put("nombre", "");
                        resultado.add(r);
                    }
                }
                break;
            case "segmentado":
                // Filtros avanzados sobre leads
                Map<String, Object> filtros = (Map<String, Object>) camp.get("segmento_filtros");
                StringBuilder q = new StringBuilder("user_id=eq.").append(userId);
                if (filtros != null) {
                    if (filtros.get("tipo") != null) q.append("&tipo=eq.").append(filtros.get("tipo"));
                    if (filtros.get("estado") != null) q.append("&estado=eq.").append(filtros.get("estado"));
                    if (filtros.get("presupuesto_min") != null) q.append("&presupuesto_min=gte.").append(filtros.get("presupuesto_min"));
                    if (filtros.get("zona") != null) q.append("&zona=eq.").append(filtros.get("zona"));
                }
                resultado.addAll(supabase.selectFilter("leads", q.toString()));
                break;
        }
        return resultado;
    }

    /** Sustituye {variables} en el texto. */
    public String aplicarVariables(String texto, Map<String, Object> dato) {
        if (texto == null) return "";
        Matcher m = VAR_PATTERN.matcher(texto);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String var = m.group(1);
            String valor = "";
            switch (var) {
                case "nombre": valor = s(dato.get("nombre")); break;
                case "email": valor = s(dato.get("email")); break;
                case "telefono": valor = s(dato.get("telefono")); break;
                case "fecha": valor = java.time.LocalDate.now().toString(); break;
                default: valor = s(dato.get(var));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(valor));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Inyecta pixel tracking + reescribe links. */
    private static final java.util.regex.Pattern ENLACE = java.util.regex.Pattern.compile("href=\"(https?://[^\"]+)\"");

    public String reescribirEnlaces(String html, String token) {
        java.util.regex.Matcher m = ENLACE.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String destino = m.group(1).replace("&amp;", "&"); // en el HTML los & van escapados
            String enlace = BASE_URL + "/track/click/" + token
                    + "?url=" + java.net.URLEncoder.encode(destino, java.nio.charset.StandardCharsets.UTF_8)
                    + "&s=" + firmaEnlaces.firmar(token, destino);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("href=\"" + enlace.replace("&", "&amp;") + "\""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String inyectarTracking(String html, String token, boolean incluirUnsub) {
        // Pixel apertura
        String pixel = "<img src='" + BASE_URL + "/track/open/" + token + "' width='1' height='1' style='display:none' alt=''/>";

        // Reescribir links (a href="X") -> /track/click/{token}?url=X&s=firma
        // El destino va codificado (antes se perdía todo lo que iba tras el primer "&") y firmado
        // (antes /track/click redirigía a cualquier web que se pusiera en la URL).
        html = reescribirEnlaces(html, token);

        // Footer con unsubscribe
        String footer = "";
        if (incluirUnsub) {
            footer = "<hr style='margin-top:30px;border:none;border-top:1px solid #e2e8f0'/>" +
                    "<p style='text-align:center;color:#94a3b8;font-size:11px;margin-top:15px'>" +
                    "Si no deseas recibir más comunicaciones, <a href='" + BASE_URL + "/track/unsubscribe/" + token + "' style='color:#64748b'>date de baja aquí</a>." +
                    "</p>";
        }

        return html + footer + pixel;
    }

    /** Cron cada minuto para lanzar campañas programadas. */
    @Scheduled(cron = "0 * * * * *")
    public void cronProgramadas() {
        List<Map<String, Object>> programadas = supabase.selectFilter(
                "campanas",
                "estado=eq.programada&enviar_at=lte." + OffsetDateTime.now()
        );
        for (Map<String, Object> c : programadas) {
            enviarCampana(String.valueOf(c.get("id")));
        }
    }

    private static String s(Object o) { return o == null ? "" : String.valueOf(o); }
}
