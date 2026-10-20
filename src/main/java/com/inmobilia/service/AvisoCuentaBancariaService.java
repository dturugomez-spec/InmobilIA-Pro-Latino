package com.inmobilia.service;

import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Cambiar la cuenta bancaria de la empresa es la puerta de un fraude clásico (alguien pone
 * su IBAN y los clientes le pagan a él). Cada cambio queda en el registro de actividad y el
 * titular de la cuenta recibe un email, aunque lo haya hecho él mismo.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AvisoCuentaBancariaService {

    private final SupabaseService supabase;
    private final EmailService emailService;
    private final AuditTrail audit;

    /** Sin espacios y en mayúsculas ("es12 3456…" → "ES123456…"); null → "". */
    public static String normalizar(Object iban) {
        if (iban == null) return "";
        String s = String.valueOf(iban);
        return "null".equals(s) ? "" : s.replaceAll("\\s", "").toUpperCase();
    }

    /** "ES9121000418450200051332" → "ES91 •••• 1332" (para emails y registros). */
    public static String enmascarar(Object iban) {
        String n = normalizar(iban);
        if (n.isEmpty()) return "(vacía)";
        if (n.length() < 8) return "••••";
        return n.substring(0, 4) + " •••• " + n.substring(n.length() - 4);
    }

    /** ¿Parece un IBAN? (ya normalizado: sin espacios y en mayúsculas). */
    public static boolean pareceIban(String normalizado) {
        return normalizado != null && normalizado.matches("[A-Z]{2}\\d{2}[A-Z0-9]{8,30}");
    }

    /** Para enseñar una cuenta guardada: un IBAN, enmascarado; otro texto, tal cual; nada → null. */
    public static String textoCuenta(Object cuenta) {
        String c = cuenta == null ? "" : String.valueOf(cuenta).trim();
        if (c.isEmpty() || "null".equals(c)) return null;
        String n = normalizar(c);
        return pareceIban(n) ? enmascarar(n) : c;
    }

    /**
     * Si la cuenta ha cambiado, lo registra y avisa al titular. {@code donde} describe qué
     * cuenta es ("Mi negocio (facturas y recibos)", "Acreedor SEPA (cobro de recibos)").
     */
    public boolean siCambia(HttpServletRequest req, HttpSession session, String donde, String tabla,
                            Object antes, Object despues) {
        String a = normalizar(antes), d = normalizar(despues);
        if (a.equals(d)) return false;

        String descripcion = "Cambió la cuenta bancaria de " + donde + ": " + enmascarar(a) + " → " + enmascarar(d);
        audit.registrar(req, session, "cuenta_bancaria_cambiada", tabla, null, descripcion, "security", AuditTrail.SEGURIDAD);
        avisarTitular(SessionUtils.uid(session), SessionUtils.name(session), SessionUtils.email(session), donde, a, d);
        return true;
    }

    private void avisarTitular(String orgId, String quien, String emailQuien, String donde, String antes, String despues) {
        try {
            List<Map<String, Object>> filas = supabase.findBy("users", Map.of("id", orgId));
            if (filas.isEmpty()) return;
            String para = String.valueOf(filas.get(0).get("email"));
            if (para.isBlank() || "null".equals(para)) return;
            emailService.send(para, "Aviso de seguridad: se ha cambiado la cuenta bancaria · InmobilIA Pro",
                    html(quien, emailQuien, donde, antes, despues));
        } catch (Exception e) {
            log.warn("No se pudo avisar del cambio de cuenta bancaria a la org {}: {}", orgId, e.getMessage());
        }
    }

    static String html(String quien, String emailQuien, String donde, String antes, String despues) {
        String cuando = ZonedDateTime.now(ZoneId.of("Europe/Madrid"))
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'a las' HH:mm"));
        String persona = HtmlUtils.htmlEscape(quien == null ? "Alguien de tu equipo" : quien)
                + (emailQuien == null ? "" : " (" + HtmlUtils.htmlEscape(emailQuien) + ")");
        return "<div style=\"font-family:Arial,sans-serif;font-size:14px;color:#0f172a;max-width:560px\">"
                + "<h2 style=\"font-size:18px;margin:0 0 12px\">Se ha cambiado una cuenta bancaria de tu empresa</h2>"
                + "<p><b>Dónde:</b> " + HtmlUtils.htmlEscape(donde) + "<br>"
                + "<b>Quién:</b> " + persona + "<br>"
                + "<b>Cuándo:</b> " + cuando + "<br>"
                + "<b>Antes:</b> " + enmascarar(antes) + "<br>"
                + "<b>Ahora:</b> " + enmascarar(despues) + "</p>"
                + "<p>Si este cambio es correcto, no tienes que hacer nada.</p>"
                + "<p style=\"background:#fde8e7;padding:10px 12px;border-radius:6px\"><b>Si no lo reconoces:</b> "
                + "entra en InmobilIA Pro, vuelve a poner la cuenta correcta, revisa el registro de actividad "
                + "y cambia la contraseña de esa persona o desactívala desde Equipo.</p>"
                + "<p style=\"color:#55627a;font-size:12px\">Este aviso se envía siempre que cambia una cuenta bancaria, "
                + "aunque lo hayas hecho tú.</p></div>";
    }
}
