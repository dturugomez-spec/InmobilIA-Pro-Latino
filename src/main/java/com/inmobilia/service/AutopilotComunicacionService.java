package com.inmobilia.service;

import com.inmobilia.util.Fechas;
import com.inmobilia.util.HtmlSeguro;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Communication AutoPilot: redacta y envía a los inquilinos los avisos que tocan, sacados de
 * los propios datos de la cuenta:
 *   - renovacion:    contrato activo que vence en 90 días o menos
 *   - actualizacion: actualización de renta (IPC/IRAV) en 30 días o menos
 *   - pago:          recibo sin pagar emitido hace 5 días o más
 *   - incidencia:    incidencia del portal del inquilino sin responder
 *
 * Cada aviso sale con una plantilla que se puede mejorar con IA y editar antes de enviar. Los de
 * renovación, actualización y pago se pueden enviar solos cada mañana (siempre con la plantilla,
 * sin IA: nada que la IA pueda inventarse). Las incidencias siempre las responde una persona.
 * Un aviso enviado no se repite (comunicaciones_autopilot).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutopilotComunicacionService {

    private final SupabaseService supabase;
    private final EmailService email;
    private final AIService ai;
    private final PlanService planes;
    private final PaisService paises;

    public static final List<String> TIPOS = List.of("renovacion", "actualizacion", "pago", "incidencia");
    /** Tipos que pueden enviarse solos. */
    public static final List<String> AUTOMATICOS = List.of("renovacion", "actualizacion", "pago");

    static final int DIAS_RENOVACION = 90, DIAS_ACTUALIZACION = 30, DIAS_IMPAGO = 5;
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Un aviso pendiente (o ya enviado) para un inquilino. */
    public record Aviso(String tipo, String refId, String destinatario, String email, String inmueble,
                        String motivo, String asunto, String cuerpo, String enviadoEl) {
        public String clave() { return tipo + ":" + refId; }
        public boolean conEmail() { return email != null && email.contains("@"); }
    }

    /** Avisos de la cuenta, de más urgente a menos. Los ya enviados llevan enviadoEl. */
    public List<Aviso> avisos(String org) {
        paises.usar(org);   // moneda, formato y hora del país de la inmobiliaria (también en el envío automático)
        Map<String, Map<String, Object>> inquilinos = new HashMap<>();
        Map<String, Map<String, Object>> porNombre = new HashMap<>();
        for (Map<String, Object> i : supabase.selectFilter("inquilinos", "user_id=eq." + org + "&limit=2000")) {
            inquilinos.put(str(i.get("id")), i);
            if (!str(i.get("name")).isBlank()) porNombre.put(norm(str(i.get("name"))), i);
        }
        Map<String, String> enviados = new HashMap<>();
        for (Map<String, Object> c : supabase.selectFilter("comunicaciones_autopilot",
                "select=tipo,ref_id,created_at,enviado&user_id=eq." + org + "&enviado=eq.true&order=created_at.desc&limit=2000"))
            enviados.putIfAbsent(c.get("tipo") + ":" + c.get("ref_id"), str(c.get("created_at")));
        String firma = firma(org);

        List<Aviso> out = new ArrayList<>();
        LocalDate hoy = LocalDate.now();
        for (Map<String, Object> c : supabase.selectFilter("contratos", "user_id=eq." + org + "&limit=2000")) {
            if (!activo(c)) continue;
            Map<String, Object> inq = inquilinoDe(c, inquilinos, porNombre);
            String nombre = primero(str(c.get("tenant_name")), inq == null ? "" : str(inq.get("name")), "inquilino");
            String inmueble = primero(str(c.get("property_name")), inq == null ? "" : str(inq.get("property_name")), "el inmueble");
            String mail = inq == null ? "" : str(inq.get("email"));
            LocalDate fin = fecha(primero(str(c.get("fecha_fin")), str(c.get("end_date")), ""));
            if (fin != null && !fin.isBefore(hoy) && ChronoUnit.DAYS.between(hoy, fin) <= DIAS_RENOVACION) {
                String ref = str(c.get("id"));
                out.add(plantilla(new Aviso("renovacion", ref, nombre, mail, inmueble,
                        "El contrato vence el " + fin.format(FECHA) + " (en " + ChronoUnit.DAYS.between(hoy, fin) + " días)",
                        null, null, enviados.get("renovacion:" + ref)), fin, null, firma));
            }
            LocalDate act = fecha(str(c.get("proxima_actualizacion")));
            if (act != null && !act.isBefore(hoy) && ChronoUnit.DAYS.between(hoy, act) <= DIAS_ACTUALIZACION) {
                String ref = c.get("id") + "@" + act;   // una vez por cada actualización
                out.add(plantilla(new Aviso("actualizacion", ref, nombre, mail, inmueble,
                        "La renta se actualiza el " + act.format(FECHA), null, null, enviados.get("actualizacion:" + ref)),
                        act, c.get("porcentaje_actualizacion"), firma));
            }
        }
        for (Map<String, Object> r : supabase.selectFilter("receipts", "user_id=eq." + org + "&order=fecha_emision.desc&limit=500")) {
            if (pagado(r)) continue;
            LocalDate emitido = fecha(str(r.get("fecha_emision")));
            if (emitido == null || ChronoUnit.DAYS.between(emitido, hoy) < DIAS_IMPAGO) continue;
            Map<String, Object> inq = porNombre.get(norm(str(r.get("inquilino_nombre"))));
            String ref = str(r.get("id"));
            out.add(plantilla(new Aviso("pago", ref, primero(str(r.get("inquilino_nombre")), "inquilino"),
                    inq == null ? "" : str(inq.get("email")), primero(str(r.get("property_name")), "el inmueble"),
                    "Recibo de " + periodo(r) + " sin pagar (" + importe(r.get("importe")) + ")", null, null,
                    enviados.get("pago:" + ref)), emitido, r, firma));
        }
        for (Map<String, Object> t : supabase.selectFilter("tenant_requests", "org_id=eq." + org + "&order=created_at.desc&limit=200")) {
            String estado = str(t.get("status"));
            if ("Resuelta".equalsIgnoreCase(estado) || "Cerrada".equalsIgnoreCase(estado) || !str(t.get("response")).isBlank()) continue;
            Map<String, Object> inq = inquilinos.get(str(t.get("inquilino_id")));
            String ref = str(t.get("id"));
            out.add(plantilla(new Aviso("incidencia", ref, inq == null ? "inquilino" : primero(str(inq.get("name")), "inquilino"),
                    inq == null ? "" : str(inq.get("email")), primero(str(t.get("property_name")), "el inmueble"),
                    "Incidencia sin responder: " + str(t.get("title")), null, null, enviados.get("incidencia:" + ref)), null, t, firma));
        }
        out.sort(Comparator.comparing((Aviso a) -> a.enviadoEl() != null).thenComparing(a -> TIPOS.indexOf(a.tipo())));
        return out;
    }

    /** Aviso concreto (para redactarlo o enviarlo), o null si ya no toca. */
    public Aviso aviso(String org, String tipo, String refId) {
        for (Aviso a : avisos(org)) if (a.tipo().equals(tipo) && a.refId().equals(refId)) return a;
        return null;
    }

    /** Rellena asunto y cuerpo con la plantilla de su tipo. */
    Aviso plantilla(Aviso a, LocalDate fecha, Object extra, String firma) {
        String saludo = "Hola, " + nombrePila(a.destinatario()) + ":\n\n";
        String asunto, cuerpo;
        switch (a.tipo()) {
            case "renovacion" -> {
                asunto = "Tu contrato de " + a.inmueble() + " vence el " + fecha.format(FECHA);
                cuerpo = saludo + "Te escribimos porque el contrato de arrendamiento de " + a.inmueble() + " vence el "
                        + fecha.format(FECHA) + ".\n\nNos gustaría saber si quieres renovarlo. Si es así, respóndenos a este "
                        + "correo y te enviamos las condiciones; si prefieres no renovar, avísanos para organizar la entrega.\n\n";
            }
            case "actualizacion" -> {
                String pct = extra == null || str(extra).isBlank() ? "" : " (" + str(extra).replace('.', ',') + " %)";
                asunto = "Actualización de la renta de " + a.inmueble();
                cuerpo = saludo + "Como recoge tu contrato, la renta de " + a.inmueble() + " se actualiza el "
                        + fecha.format(FECHA) + pct + " según el índice pactado.\n\nEn el próximo recibo verás el importe "
                        + "actualizado. Si tienes cualquier duda, respóndenos a este correo.\n\n";
            }
            case "pago" -> {
                @SuppressWarnings("unchecked") Map<String, Object> r = (Map<String, Object>) extra;
                asunto = "Recordatorio: recibo de " + periodo(r) + " pendiente";
                cuerpo = saludo + "Según nuestros registros, el recibo de " + periodo(r) + " de " + a.inmueble() + " por "
                        + importe(r.get("importe")) + " sigue pendiente de pago.\n\nSi ya lo has pagado, no hagas caso de este "
                        + "mensaje (y perdona la molestia). Si no, ¿puedes hacerlo en los próximos días? Cualquier problema, "
                        + "respóndenos y lo vemos.\n\n";
            }
            default -> {
                @SuppressWarnings("unchecked") Map<String, Object> t = (Map<String, Object>) extra;
                asunto = "Re: " + primero(str(t.get("title")), "tu incidencia");
                cuerpo = saludo + "Hemos recibido tu aviso: «" + str(t.get("title")) + "».\n\nLo estamos revisando y te "
                        + "contamos los siguientes pasos en cuanto los tengamos.\n\n";
            }
        }
        return new Aviso(a.tipo(), a.refId(), a.destinatario(), a.email(), a.inmueble(), a.motivo(), asunto,
                cuerpo + "Un saludo,\n" + firma, a.enviadoEl());
    }

    /** La IA reescribe el borrador (mismos datos, sin inventar). Null si no responde. */
    public String mejorarConIA(String org, Aviso a, String borrador, String instrucciones) {
        String prompt = "Mejora este mensaje para un inquilino. Mantén TODOS los datos (fechas, importes, inmueble) "
                + "tal cual, no inventes ninguno, tono cordial y profesional, español neutro de Latinoamérica, sin asunto.\n"
                + (instrucciones == null || instrucciones.isBlank() ? "" : "Indicaciones: " + instrucciones + "\n")
                + "Motivo: " + a.motivo() + "\n\nMENSAJE:\n" + borrador;
        String r = ai.call(null, prompt, "Redactas comunicaciones de una gestora de inmuebles a sus inquilinos.", 500, org);
        return r == null || r.isBlank() ? null : AIService.limpiar(r).trim();
    }

    public record Resultado(boolean ok, String mensaje) {}

    /** Envía el aviso por correo y lo apunta. En una incidencia, la respuesta queda también en el portal. */
    public Resultado enviar(String org, Aviso a, String asunto, String cuerpo, String modo) {
        if (!a.conEmail()) return new Resultado(false, a.destinatario() + " no tiene email en su ficha de inquilino.");
        if (asunto == null || asunto.isBlank() || cuerpo == null || cuerpo.isBlank()) return new Resultado(false, "Falta el asunto o el mensaje.");
        EmailService.SendResult r = email.sendConRespuesta(a.email(), asunto, html(cuerpo), responderA(org));
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("user_id", org);
        log.put("tipo", a.tipo());
        log.put("ref_id", a.refId());
        log.put("destinatario", a.destinatario());
        log.put("email", a.email());
        log.put("asunto", asunto);
        log.put("cuerpo", cuerpo);
        log.put("modo", modo);
        log.put("enviado", r.success());
        if (!r.success()) log.put("error", r.message());
        supabase.insert("comunicaciones_autopilot", log);
        if (!r.success()) return new Resultado(false, "No se ha podido enviar a " + a.email() + ": " + r.message());
        if ("incidencia".equals(a.tipo())) {
            supabase.updateDe("tenant_requests", a.refId(), Map.of("response", cuerpo, "responded_at", OffsetDateTime.now().toString(),
                    "status", "En curso"), "org_id", org);
        }
        return new Resultado(true, "Enviado a " + a.destinatario() + " (" + a.email() + ").");
    }

    // ─── Envío automático ───────────────────────────────────────────────

    public Map<String, Object> config(String org) {
        List<Map<String, Object>> r = supabase.selectFilter("autopilot_config", "user_id=eq." + org + "&limit=1");
        return r.isEmpty() ? Map.of() : r.get(0);
    }

    public boolean guardarConfig(String org, boolean renovacion, boolean actualizacion, boolean pago) {
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("auto_renovacion", renovacion);
        datos.put("auto_actualizacion", actualizacion);
        datos.put("auto_pago", pago);
        datos.put("updated_at", OffsetDateTime.now().toString());
        Map<String, Object> actual = config(org);
        if (actual.isEmpty()) {
            datos.put("user_id", org);
            return supabase.insert("autopilot_config", datos) != null;
        }
        return supabase.update("autopilot_config", str(actual.get("id")), datos, org);
    }

    /** Cada mañana: los avisos marcados como automáticos, con su plantilla. */
    @Scheduled(cron = "0 0 9 * * *", zone = "${app.zona-horaria:America/Mexico_City}")
    public void enviarAutomaticos() {
        List<Map<String, Object>> cuentas;
        try {
            cuentas = supabase.selectFilter("autopilot_config", "or=(auto_renovacion.eq.true,auto_actualizacion.eq.true,auto_pago.eq.true)&limit=1000");
        } catch (Exception e) { return; }
        int enviados = 0;
        for (Map<String, Object> cfg : cuentas) {
            String org = str(cfg.get("user_id"));
            if (!planes.puedeUsarRuta(org, RUTA)) continue;   // el plan ya no lo incluye
            enviados += enviarAutomaticos(org, cfg);
        }
        if (enviados > 0) log.info("[AutoPilot] {} avisos enviados solos", enviados);
    }

    int enviarAutomaticos(String org, Map<String, Object> cfg) {
        int n = 0;
        for (Aviso a : avisos(org)) {
            if (a.enviadoEl() != null || !a.conEmail() || !AUTOMATICOS.contains(a.tipo())) continue;
            if (!Boolean.TRUE.equals(cfg.get("auto_" + a.tipo()))) continue;
            if (enviar(org, a, a.asunto(), a.cuerpo(), "automatico").ok()) n++;
        }
        return n;
    }

    public static final String RUTA = "/herramientas/autopilot-comunicacion";

    /** Últimos enviados (historial). */
    public List<Map<String, Object>> historial(String org) {
        return supabase.selectFilter("comunicaciones_autopilot", "user_id=eq." + org + "&order=created_at.desc&limit=30");
    }

    // ─── Utilidades ─────────────────────────────────────────────────────

    /** Las respuestas de los inquilinos van al email de contacto de la empresa (o al del titular). */
    private String responderA(String org) {
        List<Map<String, Object>> p = supabase.selectFilter("business_profile", "select=contact_email&org_id=eq." + org + "&limit=1");
        if (!p.isEmpty() && str(p.get(0).get("contact_email")).contains("@")) return str(p.get(0).get("contact_email"));
        List<Map<String, Object>> u = supabase.selectFilter("users", "select=email&id=eq." + org + "&limit=1");
        return u.isEmpty() ? null : str(u.get(0).get("email"));
    }

    private String firma(String org) {
        List<Map<String, Object>> p = supabase.selectFilter("business_profile", "org_id=eq." + org + "&limit=1");
        if (!p.isEmpty()) {
            String n = primero(str(p.get(0).get("legal_name")), str(p.get(0).get("contact_name")), "");
            String tel = str(p.get(0).get("contact_phone"));
            if (!n.isBlank()) return n + (tel.isBlank() ? "" : "\n" + tel);
        }
        List<Map<String, Object>> u = supabase.selectFilter("users", "select=name,empresa&id=eq." + org + "&limit=1");
        return u.isEmpty() ? "La propiedad" : primero(str(u.get(0).get("empresa")), str(u.get(0).get("name")), "La propiedad");
    }

    private static Map<String, Object> inquilinoDe(Map<String, Object> c, Map<String, Map<String, Object>> porId,
                                                   Map<String, Map<String, Object>> porNombre) {
        for (String k : List.of("inquilino_id", "tenant_id")) {
            Map<String, Object> i = porId.get(str(c.get(k)));
            if (i != null) return i;
        }
        return porNombre.get(norm(str(c.get("tenant_name"))));
    }

    private static boolean activo(Map<String, Object> c) {
        String e = (str(c.get("estado")) + " " + str(c.get("status"))).toLowerCase();
        return !e.contains("finaliz") && !e.contains("rescind") && !e.contains("cancel") && !e.contains("cerrad") && !e.contains("inactiv");
    }

    private static boolean pagado(Map<String, Object> r) {
        String s = str(r.get("status")).toLowerCase();
        return s.contains("pagad") || s.contains("cobrad") || s.contains("anulad") || !str(r.get("fecha_pago")).isBlank();
    }

    private static String periodo(Map<String, Object> r) {
        String[] meses = {"enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"};
        try {
            int m = Integer.parseInt(str(r.get("period_month")));
            return meses[m - 1] + " de " + str(r.get("period_year"));
        } catch (Exception e) {
            return primero(str(r.get("concepto")), "este mes");
        }
    }

    private static String importe(Object v) {
        Double d = com.inmobilia.util.Numeros.decimal(v);
        String mon = com.inmobilia.util.PaisActual.moneda();
        if (d == null) return str(v) + " " + mon;
        java.text.NumberFormat f = java.text.NumberFormat.getNumberInstance(com.inmobilia.util.PaisActual.get().locale());
        f.setMinimumFractionDigits(d % 1 == 0 ? 0 : 2);
        f.setMaximumFractionDigits(2);
        return f.format(d) + " " + mon;
    }

    private static String html(String texto) {
        return "<div style='font-family:sans-serif;max-width:600px;line-height:1.5'>"
                + HtmlSeguro.escapar(texto).replace("\n", "<br>") + "</div>";
    }

    private static String nombrePila(String nombre) {
        if (nombre == null || nombre.isBlank()) return "buenos días";
        String n = nombre.trim();
        // Empresas (S.L., S.A.…): el nombre completo; personas: el nombre de pila
        return n.matches("(?i).*\\b(s\\.?l\\.?u?|s\\.?a\\.?|sociedad|c\\.?b\\.?)\\b.*") ? n : n.split("\\s+")[0];
    }

    private static LocalDate fecha(String s) {
        OffsetDateTime f = Fechas.leer(s == null || s.length() != 10 ? s : s + "T00:00:00");
        return f == null ? null : f.toLocalDate();
    }

    private static String primero(String... v) {
        for (String s : v) if (s != null && !s.isBlank()) return s;
        return "";
    }

    private static String norm(String s) {
        return java.text.Normalizer.normalize(s.toLowerCase().trim(), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    static String str(Object o) {
        return o == null || "null".equals(String.valueOf(o)) ? "" : String.valueOf(o).trim();
    }
}
