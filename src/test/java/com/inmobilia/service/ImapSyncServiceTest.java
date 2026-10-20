package com.inmobilia.service;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import nz.net.ultraq.thymeleaf.layoutdialect.LayoutDialect;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Bandeja de entrada: lectura de correos por IMAP. */
class ImapSyncServiceTest {

    private MimeMessage correo() throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress("ana@cliente.es", "Ana López"));
        m.setRecipients(Message.RecipientType.TO, "info@mi-agencia.es");
        m.setRecipients(Message.RecipientType.CC, "jefe@mi-agencia.es");
        m.setSubject("Interesada en el piso de la calle Mayor");
        m.setSentDate(new Date(1_790_000_000_000L));
        m.setHeader("Message-ID", "<abc123@cliente.es>");

        MimeMultipart alternativa = new MimeMultipart("alternative");
        MimeBodyPart texto = new MimeBodyPart();
        texto.setText("Hola, ¿sigue disponible?", "UTF-8");
        MimeBodyPart html = new MimeBodyPart();
        html.setContent("<p>Hola, <b>¿sigue disponible?</b></p><script>robar()</script>", "text/html; charset=UTF-8");
        alternativa.addBodyPart(texto);
        alternativa.addBodyPart(html);
        MimeBodyPart cuerpo = new MimeBodyPart();
        cuerpo.setContent(alternativa);

        MimeBodyPart adjunto = new MimeBodyPart();
        adjunto.setText("contenido del adjunto que no es el cuerpo");
        adjunto.setFileName("nomina.txt");
        adjunto.setDisposition(MimeBodyPart.ATTACHMENT);

        MimeMultipart mixto = new MimeMultipart("mixed");
        mixto.addBodyPart(cuerpo);
        mixto.addBodyPart(adjunto);
        m.setContent(mixto);
        m.saveChanges();
        m.setHeader("Message-ID", "<abc123@cliente.es>"); // saveChanges lo regenera
        return m;
    }

    @Test
    void unCorreoPasaABandejaConTextoHtmlLimpioYSinAdjuntos() throws Exception {
        Map<String, Object> f = ImapSyncService.aFila(correo());
        assertEquals("<abc123@cliente.es>", f.get("message_id"));
        assertEquals("ana@cliente.es", f.get("de_email"));
        assertEquals("Ana López", f.get("de_nombre"));
        assertEquals(List.of("info@mi-agencia.es"), f.get("para"));
        assertEquals(List.of("jefe@mi-agencia.es"), f.get("cc"));
        assertEquals("Interesada en el piso de la calle Mayor", f.get("asunto"));
        assertEquals("Hola, ¿sigue disponible?", f.get("cuerpo_texto"));
        String html = String.valueOf(f.get("cuerpo_html"));
        assertTrue(html.contains("<b>¿sigue disponible?</b>"));
        assertFalse(html.contains("script"), "el HTML se guarda ya limpio");
        assertFalse(String.valueOf(f).contains("contenido del adjunto"), "los adjuntos no son el cuerpo");
        assertTrue(String.valueOf(f.get("fecha")).startsWith("2026-"));
        assertEquals(false, f.get("leido"));
    }

    @Test
    void correoSoloHtml_sacaElTextoDelHtml() throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress("portal@idealista.com"));
        m.setContent("<div>Nuevo contacto: <b>Luis</b></div>", "text/html; charset=UTF-8");
        m.saveChanges();
        Map<String, Object> f = ImapSyncService.aFila(m);
        assertEquals("Nuevo contacto: Luis", f.get("cuerpo_texto"));
        assertEquals("(sin asunto)", f.get("asunto"));
    }

    @Test
    void desde_unDiaAntesDelUltimoSyncOCatorceDiasLaPrimeraVez() {
        Instant ahora = Instant.parse("2026-10-20T10:00:00Z");
        assertEquals(Instant.parse("2026-10-19T09:30:00Z"),
                ImapSyncService.desde("2026-10-20T09:30:00+00:00", ahora));
        assertEquals(ahora.minus(14, ChronoUnit.DAYS), ImapSyncService.desde(null, ahora));
        assertEquals(ahora.minus(14, ChronoUnit.DAYS), ImapSyncService.desde("basura", ahora));
    }

    @Test
    void erroresExplicadosAlUsuario() {
        assertTrue(ImapSyncService.motivoLegible(new AuthenticationFailedException("AUTHENTICATE failed"))
                .contains("contraseña de aplicación"));
        assertTrue(ImapSyncService.motivoLegible(new MessagingException("x", new java.net.UnknownHostException("imap.mal")))
                .contains("revisa el host"));
        assertTrue(ImapSyncService.motivoLegible(new MessagingException("x", new java.net.ConnectException("refused")))
                .contains("puerto"));
    }

    @Test
    void sinContrasena_quedaApuntadoElMotivo() {
        SupabaseService supabase = mock(SupabaseService.class);
        ImapSyncService s = new ImapSyncService(supabase, new CifradoService(""));
        Map<String, Object> cuenta = new HashMap<>(Map.of("id", "c1", "user_id", "org1", "email", "a@b.es",
                "imap_host", "imap.b.es", "imap_port", 993));
        Exception e = assertThrows(IllegalStateException.class, () -> s.sincronizar(cuenta));
        assertTrue(e.getMessage().contains("Falta la contraseña"));
        verify(supabase).update(eq("imap_cuentas"), eq("c1"), argThat(m -> String.valueOf(m.get("ultimo_error")).contains("Falta la contraseña")));
        verify(supabase, never()).insert(anyString(), anyMap());
    }

    @Test
    void pantallaBandeja_conCuentaConError() {
        ClassLoaderTemplateResolver r = new ClassLoaderTemplateResolver();
        r.setPrefix("templates/");
        r.setSuffix(".html");
        r.setTemplateMode(TemplateMode.HTML);
        r.setCharacterEncoding("UTF-8");
        SpringTemplateEngine e = new SpringTemplateEngine();
        e.setTemplateResolver(r);
        e.addDialect(new LayoutDialect());
        e.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder() {
            @Override
            protected String computeContextPath(org.thymeleaf.context.IExpressionContext ctx, String base,
                                                Map<String, Object> params) { return ""; }
        });
        Context c = new Context();
        Map<String, Object> cuenta = new HashMap<>(Map.of("id", "c1", "email", "info@mi-agencia.es",
                "ultima_lectura", "20/10 12:05", "ultimo_error", "Usuario o contraseña incorrectos"));
        c.setVariable("cuentas", List.of(cuenta));
        c.setVariable("emails", List.of(Map.of("id", "e1", "de_email", "ana@cliente.es", "asunto", "Hola",
                "cuerpo_texto", "texto", "fecha", "2026-10-20T10:00:00+00:00", "leido", false)));
        c.setVariable("noLeidos", 1);
        c.setVariable("emailActivo", null);
        c.setVariable("carpeta", "inbox");
        String html = e.process("inbox", Set.of("div[layout:fragment='content']"), c);
        assertTrue(html.contains("Sincronizar ahora"));
        assertTrue(html.contains("Última lectura: 20/10 12:05"));
        assertTrue(html.contains("Usuario o contraseña incorrectos"));
        assertTrue(html.contains("/inbox/cuenta/c1/quitar"));

        cuenta.remove("ultima_lectura");
        cuenta.put("ultimo_error", "");
        html = e.process("inbox", Set.of("div[layout:fragment='content']"), c);
        assertTrue(html.contains("Aún sin leer"));
        assertFalse(html.contains("⚠"));

        c.setVariable("cuentas", List.of());
        html = e.process("inbox", Set.of("div[layout:fragment='content']"), c);
        assertTrue(html.contains("contraseña de aplicación"));
        assertFalse(html.contains("Sincronizar ahora"));
    }
}
