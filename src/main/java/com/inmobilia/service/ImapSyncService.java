package com.inmobilia.service;

import com.inmobilia.util.HtmlSeguro;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trae a la bandeja de la app los correos de las cuentas IMAP de cada inmobiliaria.
 * Antes la bandeja guardaba la cuenta pero nunca leía nada: siempre salía vacía.
 *
 * Cada 5 minutos, por cuenta activa: entra por IMAP (solo lectura, no marca nada como leído
 * en el servidor), coge los correos recibidos desde la última vez (los 14 últimos días la
 * primera), salta los que ya tiene y guarda los nuevos. Si el remitente es un lead de la
 * inmobiliaria, el correo queda enlazado a él.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImapSyncService {

    static final int MAX_POR_VUELTA = 50;
    public static final int DIAS_PRIMERA_VEZ = 14;
    static final int MAX_HTML = 200_000;
    static final int MAX_TEXTO = 50_000;

    private final SupabaseService supabase;
    private final CifradoService cifrado;

    /** Cuentas sincronizándose ahora (el botón y el automático no pisan la misma a la vez). */
    private final Set<String> enCurso = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelay = 5 * 60 * 1000L, initialDelay = 90 * 1000L)
    public void sincronizarTodas() {
        for (Map<String, Object> cuenta : supabase.selectFilter("imap_cuentas", "activa=eq.true")) {
            try {
                sincronizar(cuenta);
            } catch (Exception e) {
                log.warn("IMAP {}: {}", cuenta.get("email"), e.getMessage());
            }
        }
    }

    /** Sincroniza una cuenta. Devuelve cuántos correos nuevos ha guardado; si falla, lanza el motivo legible. */
    public int sincronizar(Map<String, Object> cuenta) throws Exception {
        String id = String.valueOf(cuenta.get("id"));
        if (!enCurso.add(id)) return 0;
        try {
            int nuevos = traer(cuenta);
            supabase.update("imap_cuentas", id, Map.of("ultimo_sync_at", Instant.now().toString()));
            supabase.update("imap_cuentas", id, Map.of("ultimo_error", "")); // columna de V85 (si no está, no pasa nada)
            return nuevos;
        } catch (Exception e) {
            String motivo = motivoLegible(e);
            supabase.update("imap_cuentas", id, Map.of("ultimo_error", motivo));
            throw new IllegalStateException(motivo, e);
        } finally {
            enCurso.remove(id);
        }
    }

    private int traer(Map<String, Object> cuenta) throws Exception {
        String id = String.valueOf(cuenta.get("id"));
        String guardada = cuenta.get("imap_pass") == null ? null : String.valueOf(cuenta.get("imap_pass"));
        String pass = cifrado.descifrar(guardada);
        if (pass == null || pass.isBlank())
            throw new IllegalStateException("Falta la contraseña de la cuenta (vuelve a añadirla)");
        // Las contraseñas guardadas en claro antes de cifrar se cifran ahora
        if (cifrado.activo() && !CifradoService.estaCifrado(guardada))
            supabase.update("imap_cuentas", id, Map.of("imap_pass", cifrado.cifrar(pass)));

        String host = String.valueOf(cuenta.get("imap_host")).trim();
        int puerto = cuenta.get("imap_port") instanceof Number n ? n.intValue() : 993;
        String usuario = cuenta.get("imap_user") == null || String.valueOf(cuenta.get("imap_user")).isBlank()
                ? String.valueOf(cuenta.get("email")) : String.valueOf(cuenta.get("imap_user")).trim();
        String protocolo = puerto == 143 ? "imap" : "imaps";

        Properties p = new Properties();
        p.put("mail." + protocolo + ".connectiontimeout", "15000");
        p.put("mail." + protocolo + ".timeout", "30000");
        if ("imap".equals(protocolo)) p.put("mail.imap.starttls.required", "true");
        Session sesion = Session.getInstance(p);

        Instant desde = desde(cuenta.get("ultimo_sync_at"), Instant.now());
        try (Store store = sesion.getStore(protocolo)) {
            store.connect(host, puerto, usuario, pass);
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            try {
                Message[] mensajes = inbox.search(new ReceivedDateTerm(ComparisonTerm.GE, Date.from(desde)));
                Set<String> yaEstan = idsGuardados(id, desde);
                String orgId = String.valueOf(cuenta.get("user_id"));
                int nuevos = 0;
                for (int i = Math.max(0, mensajes.length - MAX_POR_VUELTA); i < mensajes.length; i++) {
                    if (!(mensajes[i] instanceof MimeMessage m)) continue;
                    Map<String, Object> fila = aFila(m);
                    if (!yaEstan.add(String.valueOf(fila.get("message_id")))) continue;
                    fila.put("cuenta_id", id);
                    fila.put("user_id", orgId);
                    String lead = leadDe(orgId, (String) fila.get("de_email"));
                    if (lead != null) fila.put("lead_id", lead);
                    if (supabase.insert("emails_inbox", fila) != null) nuevos++;
                }
                return nuevos;
            } finally {
                inbox.close(false);
            }
        }
    }

    /** Desde cuándo buscar: un día antes del último sync (los servidores buscan por día) o 14 días la primera vez. */
    static Instant desde(Object ultimoSync, Instant ahora) {
        if (ultimoSync != null) {
            try {
                return OffsetDateTime.parse(String.valueOf(ultimoSync)).toInstant().minus(1, ChronoUnit.DAYS);
            } catch (Exception ignored) { }
        }
        return ahora.minus(DIAS_PRIMERA_VEZ, ChronoUnit.DAYS);
    }

    private Set<String> idsGuardados(String cuentaId, Instant desde) {
        String fecha = desde.minus(Duration.ofDays(2)).truncatedTo(ChronoUnit.SECONDS).toString(); // UTC: sin "+" en la URL
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> e : supabase.selectFilter("emails_inbox",
                "cuenta_id=eq." + cuentaId + "&fecha=gte." + fecha + "&limit=5000")) {
            ids.add(String.valueOf(e.get("message_id")));
        }
        return ids;
    }

    private String leadDe(String orgId, String email) {
        if (email == null || !email.matches("[A-Za-z0-9._@-]+")) return null;
        List<Map<String, Object>> l = supabase.selectFilter("leads",
                "user_id=eq." + orgId + "&email=ilike." + email + "&limit=1");
        return l.isEmpty() ? null : String.valueOf(l.get(0).get("id"));
    }

    /** Pasa un correo a la fila de emails_inbox (sin cuenta ni inmobiliaria). */
    static Map<String, Object> aFila(MimeMessage m) throws Exception {
        Map<String, Object> f = new LinkedHashMap<>();
        Address[] de = m.getFrom();
        InternetAddress remitente = de != null && de.length > 0 && de[0] instanceof InternetAddress ia ? ia : null;
        Date fecha = m.getReceivedDate() != null ? m.getReceivedDate() : m.getSentDate();
        String asunto = m.getSubject();

        String messageId = m.getMessageID();
        if (messageId == null || messageId.isBlank()) // sin Message-ID: huella con remitente, asunto y fecha
            messageId = "sin-id:" + Objects.hash(remitente == null ? "" : remitente.getAddress(), asunto, fecha);
        f.put("message_id", messageId);
        f.put("de_email", remitente == null ? null : remitente.getAddress());
        f.put("de_nombre", remitente == null ? null : remitente.getPersonal());
        f.put("para", direcciones(m.getRecipients(Message.RecipientType.TO)));
        f.put("cc", direcciones(m.getRecipients(Message.RecipientType.CC)));
        f.put("asunto", asunto == null || asunto.isBlank() ? "(sin asunto)" : asunto);

        String[] cuerpos = new String[2]; // [texto, html]
        leerCuerpo(m, cuerpos);
        String html = cuerpos[1] == null ? null : HtmlSeguro.limpiar(recortar(cuerpos[1], MAX_HTML));
        String texto = cuerpos[0] != null ? cuerpos[0] : (cuerpos[1] != null ? Jsoup.parse(cuerpos[1]).text() : "");
        f.put("cuerpo_texto", recortar(texto, MAX_TEXTO));
        if (html != null) f.put("cuerpo_html", html);

        f.put("fecha", (fecha == null ? Instant.now() : fecha.toInstant()).atOffset(ZoneOffset.UTC).toString());
        f.put("leido", false);
        f.put("estrella", false);
        f.put("archivado", false);
        f.put("etiqueta", "inbox");
        f.put("created_at", Instant.now().toString());
        return f;
    }

    /** Busca el primer texto plano y el primer HTML del correo, saltando adjuntos. */
    private static void leerCuerpo(Part p, String[] cuerpos) throws Exception {
        if (Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition())) return;
        if (p.isMimeType("text/plain") && cuerpos[0] == null) {
            cuerpos[0] = String.valueOf(p.getContent());
        } else if (p.isMimeType("text/html") && cuerpos[1] == null) {
            cuerpos[1] = String.valueOf(p.getContent());
        } else if (p.isMimeType("multipart/*") && p.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) leerCuerpo(mp.getBodyPart(i), cuerpos);
        } else if (p.isMimeType("message/rfc822") && p.getContent() instanceof Part reenviado) {
            leerCuerpo(reenviado, cuerpos);
        }
    }

    private static List<String> direcciones(Address[] a) {
        List<String> r = new ArrayList<>();
        if (a != null) for (Address x : a) r.add(x instanceof InternetAddress ia ? ia.getAddress() : x.toString());
        return r;
    }

    private static String recortar(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** Mensaje para el usuario según el fallo. */
    static String motivoLegible(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && !(t instanceof MessagingException)) t = t.getCause();
        if (t instanceof AuthenticationFailedException)
            return "Usuario o contraseña incorrectos. En Gmail hace falta una «contraseña de aplicación»; "
                    + "Outlook/Hotmail ya no permite entrar por IMAP con contraseña.";
        Throwable causa = t.getCause();
        if (causa instanceof java.net.UnknownHostException)
            return "No se encuentra el servidor IMAP: revisa el host.";
        if (causa instanceof java.net.SocketTimeoutException || causa instanceof java.net.ConnectException)
            return "El servidor IMAP no responde: revisa el host y el puerto (normalmente 993).";
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? "No se pudo leer el correo" : (msg.length() > 200 ? msg.substring(0, 200) : msg);
    }
}
