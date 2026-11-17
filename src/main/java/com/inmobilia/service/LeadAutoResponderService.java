package com.inmobilia.service;

import com.inmobilia.util.PaisActual;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ⚡ Speed-to-lead: responde AL INSTANTE a un lead nuevo (portales y formularios públicos) con
 * un email personalizado: el inmueble consultado, huecos de visita reales, alternativas y el
 * contacto de la agencia. Las respuestas del cliente llegan al correo de la agencia (Reply-To).
 *
 *   - ENCENDIDO por defecto desde el alta (users.auto_respuesta_leads; se apaga en Respuesta a leads).
 *   - Si el contacto solo dejó teléfono, crea una tarea urgente para llamarle.
 *   - Apunta la primera respuesta para medir el tiempo de respuesta (RespuestaLeadsService).
 *   - Nunca envía a direcciones de prueba (@example.com, @test, etc.) para no quemar el dominio.
 *   - Asíncrono: no bloquea la respuesta del formulario al visitante.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeadAutoResponderService {

    private final SupabaseService supabase;
    private final EmailService emailService;
    private final RespuestaLeadsService respuestas;

    private static final String BASE = com.inmobilia.util.UrlBase.BASE;

    /** Encendida salvo que la agencia la haya apagado (un valor vacío cuenta como encendida). */
    public static boolean activa(Object flag) {
        return flag == null || "null".equals(String.valueOf(flag)) || Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
    }

    @Async
    public void responder(String uid, Map<String, Object> lead, Map<String, Object> propiedad) {
        try {
            if (uid == null || uid.isBlank() || "null".equals(uid)) return;

            // 1) ¿La agencia tiene la auto-respuesta activada?
            List<Map<String, Object>> us = supabase.selectFilter("users", "id=eq." + uid + "&limit=1");
            if (us.isEmpty()) return;
            if (!activa(us.get(0).get("auto_respuesta_leads"))) return;
            List<Map<String, Object>> bp = supabase.selectFilter("business_profile", "org_id=eq." + uid + "&limit=1");
            Map<String, Object> negocio = bp.isEmpty() ? Map.of() : bp.get(0);
            String leadId = lead.get("id") == null ? null : String.valueOf(lead.get("id"));

            // 2) Email del lead válido y NO de prueba; sin email, que le llamen ya
            String to = String.valueOf(lead.getOrDefault("email", "")).trim();
            if (!esEmailReal(to)) {
                String tel = str(lead.get("telefono"), "");
                if (!tel.isBlank() && leadId != null) {
                    Map<String, Object> tarea = new LinkedHashMap<>();
                    tarea.put("user_id", uid);
                    tarea.put("titulo", "Llamar ya a " + str(lead.get("nombre"), "un contacto nuevo") + " (" + tel + ")");
                    tarea.put("descripcion", "Contacto nuevo sin email: la respuesta automática no puede escribirle. Llámale cuanto antes."
                            + (propiedad != null ? " Pregunta por " + str(propiedad.getOrDefault("name", propiedad.get("address")), "el inmueble") + "." : ""));
                    tarea.put("prioridad", "urgente");
                    tarea.put("estado", "pendiente");
                    tarea.put("fecha_limite", java.time.Instant.now().plus(java.time.Duration.ofMinutes(15)).toString());
                    tarea.put("lead_id", leadId);
                    if (propiedad != null && propiedad.get("id") != null) tarea.put("propiedad_id", propiedad.get("id"));
                    supabase.insert("tareas", tarea);
                }
                log.info("[auto-respuesta] sin email real: {}", tel.isBlank() ? "nada que hacer" : "tarea de llamada creada");
                return;
            }

            String agencia = str(negocio.get("legal_name"), str(us.get(0).get("name"), "nuestra inmobiliaria"));
            String replyTo = str(negocio.get("contact_email"), str(us.get(0).get("email"), null));
            String telefonoAgencia = str(negocio.get("contact_phone"), null);
            String nombre = str(lead.get("nombre"), "").trim();
            String saludo = nombre.isBlank() ? "Hola" : "Hola " + nombre.split("\\s+")[0];
            String propNombre = propiedad == null ? null
                    : str(propiedad.getOrDefault("name", propiedad.get("address")), null);

            // 3) Alternativas: hasta 3 propiedades libres de la misma agencia
            String alternativasHtml = "";
            try {
                String q = "user_id=eq." + uid + "&limit=6&order=created_at.desc";
                List<Map<String, Object>> props = supabase.selectFilter("propiedades", q);
                StringBuilder sb = new StringBuilder();
                int n = 0;
                for (Map<String, Object> p : props) {
                    String pid = String.valueOf(p.get("id"));
                    if (propiedad != null && pid.equals(String.valueOf(propiedad.get("id")))) continue;
                    String nom = str(p.getOrDefault("name", p.get("address")), "Propiedad");
                    String ciudad = str(p.get("city"), "");
                    Object precio = p.get("current_value") != null ? p.get("current_value") : p.get("purchase_price");
                    sb.append("<li style='margin:6px 0'><a href='").append(BASE).append("/propiedad/").append(pid)
                      .append("' style='color:#4f46e5;text-decoration:none;font-weight:600'>").append(esc(nom)).append("</a>")
                      .append(ciudad.isBlank() ? "" : " · " + esc(ciudad))
                      .append(precio != null ? " · " + fmtPrecio(precio) : "").append("</li>");
                    if (++n >= 3) break;
                }
                if (n > 0) alternativasHtml = "<p style='margin-top:18px'><strong>También te pueden interesar:</strong></p><ul style='padding-left:18px'>" + sb + "</ul>";
            } catch (Exception ignored) {}

            String asunto = propNombre != null
                    ? "Gracias por tu interés en " + propNombre
                    : "Gracias por tu interés · " + agencia;

            StringBuilder h = new StringBuilder();
            h.append("<div style='font-family:Arial,sans-serif;max-width:600px;margin:auto;color:#0f172a'>");
            h.append("<p>").append(esc(saludo)).append(",</p>");
            if (propNombre != null) {
                h.append("<p>Hemos recibido tu solicitud de información sobre <strong>").append(esc(propNombre))
                 .append("</strong>. Un asesor de ").append(esc(agencia)).append(" te contactará en breve.</p>");
            } else {
                h.append("<p>Hemos recibido tu solicitud y un asesor de ").append(esc(agencia))
                 .append(" te contactará en breve.</p>");
            }
            // Huecos de visita reales (días laborables sin otra visita a esa hora)
            if (propNombre != null) {
                List<java.time.ZonedDateTime> huecos = respuestas.huecosVisita(uid, java.time.ZonedDateTime.now(RespuestaLeadsService.zona()), 3);
                if (!huecos.isEmpty()) {
                    h.append("<p><strong>¿Quieres verlo?</strong> Tenemos estos huecos libres:</p><ul style='padding-left:18px'>");
                    for (var x : huecos) h.append("<li style='margin:4px 0'>").append(esc(RespuestaLeadsService.textoHueco(x))).append("</li>");
                    h.append("</ul><p>Contesta a este correo con el que prefieras (o propón otro) y te lo confirmamos.</p>");
                } else {
                    h.append("<p>Si quieres <strong>agendar una visita</strong>, contesta a este correo con el día y la hora que te vengan bien.</p>");
                }
            } else {
                h.append("<p>Mientras tanto, si quieres <strong>agendar una visita</strong> o resolver cualquier duda, contesta a este correo.</p>");
            }
            if (telefonoAgencia != null) h.append("<p>También puedes llamarnos al <strong>").append(esc(telefonoAgencia)).append("</strong>.</p>");
            h.append(alternativasHtml);
            h.append("<p style='margin-top:22px;color:#64748b;font-size:13px'>Un saludo,<br>").append(esc(agencia)).append("</p>");
            h.append("<hr style='border:none;border-top:1px solid #e2e8f0;margin:18px 0'>");
            h.append("<p style='font-size:11px;color:#94a3b8'>Respuesta automática. Un asesor revisará tu solicitud personalmente.</p>");
            h.append("</div>");

            EmailService.SendResult r = emailService.sendConRespuesta(to, asunto, h.toString(), replyTo);
            if (r != null && r.success()) respuestas.marcarRespuesta(uid, leadId, "auto_email");
            log.info("[auto-respuesta] enviada a {} (agencia {})", to, uid);
        } catch (Exception e) {
            log.warn("[auto-respuesta] error: {}", e.getMessage());
        }
    }

    private static boolean esEmailReal(String e) {
        if (e == null) return false;
        String s = e.toLowerCase().trim();
        if (s.length() < 5 || !s.contains("@") || !s.contains(".") || "null".equals(s)) return false;
        return !(s.endsWith("@example.com") || s.contains("@example.") || s.contains("@test")
                || s.endsWith(".test") || s.endsWith("@test.com") || s.contains("ejemplo"));
    }

    private static String str(Object o, String def) {
        if (o == null) return def;
        String s = String.valueOf(o);
        return (s.isBlank() || "null".equals(s)) ? def : s;
    }

    private static String fmtPrecio(Object precio) {
        try {
            double v = Double.parseDouble(String.valueOf(precio));
            return String.format(("%,.0f " + PaisActual.moneda()), v).replace(",", ".");
        } catch (Exception e) { return ""; }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
