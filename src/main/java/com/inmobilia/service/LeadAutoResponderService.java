package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * ⚡ Speed-to-lead: responde AL INSTANTE a un lead nuevo del formulario público
 * con un email personalizado (la propiedad consultada + alternativas + contacto).
 *
 * Seguridad por diseño:
 *   - OFF por defecto. Solo actúa si la agencia activa `auto_respuesta_leads`.
 *   - Nunca envía a direcciones de prueba (@example.com, @test, etc.) para no
 *     quemar la reputación del dominio.
 *   - Asíncrono: no bloquea la respuesta del formulario al visitante.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeadAutoResponderService {

    private final SupabaseService supabase;
    private final EmailService emailService;

    private static final String BASE = com.inmobilia.util.UrlBase.BASE;

    @Async
    public void responder(String uid, Map<String, Object> lead, Map<String, Object> propiedad) {
        try {
            if (uid == null || uid.isBlank() || "null".equals(uid)) return;

            // 1) ¿La agencia tiene la auto-respuesta activada?
            List<Map<String, Object>> us = supabase.selectFilter("users",
                    "id=eq." + uid + "&select=auto_respuesta_leads,name&limit=1");
            if (us.isEmpty()) return;
            Object flag = us.get(0).get("auto_respuesta_leads");
            boolean activa = Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
            if (!activa) return;

            // 2) Email del lead válido y NO de prueba
            String to = String.valueOf(lead.getOrDefault("email", "")).trim();
            if (!esEmailReal(to)) {
                log.info("[auto-respuesta] omitida: email no válido/de prueba ({})", to);
                return;
            }

            String agencia = str(us.get(0).get("name"), "nuestra inmobiliaria");
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
            h.append("<p>Mientras tanto, si quieres <strong>agendar una visita</strong> o resolver cualquier duda, simplemente responde a este correo.</p>");
            h.append(alternativasHtml);
            h.append("<p style='margin-top:22px;color:#64748b;font-size:13px'>Un saludo,<br>").append(esc(agencia)).append("</p>");
            h.append("<hr style='border:none;border-top:1px solid #e2e8f0;margin:18px 0'>");
            h.append("<p style='font-size:11px;color:#94a3b8'>Respuesta automática. Un asesor revisará tu solicitud personalmente.</p>");
            h.append("</div>");

            emailService.send(null, to, asunto, h.toString());
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
            return String.format("%,.0f €", v).replace(",", ".");
        } catch (Exception e) { return ""; }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
