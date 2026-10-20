package com.inmobilia.service;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;

/**
 * Envio de email via SMTP (equivalente a send_email_smtp).
 * La configuracion se lee de la sesion del usuario (SmtpConfig) con fallback a application.properties.
 */
@Service
@Slf4j
public class EmailService {

    @Value("${spring.mail.host:}")    private String defaultHost;
    @Value("${spring.mail.port:587}") private int    defaultPort;
    @Value("${spring.mail.username:}")private String defaultUser;
    @Value("${spring.mail.password:}")private String defaultPass;

    // Envío por API HTTP (Resend) — funciona donde el SMTP saliente está bloqueado (Railway, etc.)
    @Value("${resend.api-key:${RESEND_API_KEY:}}") private String resendKey;
    @Value("${resend.from:${MAIL_FROM:InmobilIA Pro <onboarding@resend.dev>}}") private String resendFrom;

    public record SmtpConfig(String server, int port, String user, String password, String fromName) {
        public boolean valid() {
            return server != null && !server.isBlank()
                && user   != null && !user.isBlank()
                && password != null && !password.isBlank();
        }
    }

    public record SendResult(boolean success, String message) {}

    /** Compat (Sprints 47-110): envío con config por defecto. */
    public SendResult send(String to, String subject, String body) {
        return send(null, to, subject, body);
    }

    public SendResult send(SmtpConfig cfg, String to, String subject, String body) {
        // Si hay Resend y no se pasó una config SMTP propia del cliente → enviar por API HTTP
        if ((cfg == null || !cfg.valid()) && resendKey != null && !resendKey.isBlank()) {
            return sendViaResend(to, subject, body);
        }
        SmtpConfig eff = cfg != null && cfg.valid() ? cfg : defaultConfig();
        if (!eff.valid()) {
            return new SendResult(false, "Configura el servidor SMTP en Configuracion > Email.");
        }
        try {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(eff.server());
            sender.setPort(eff.port());
            sender.setUsername(eff.user());
            sender.setPassword(eff.password());
            Properties props = sender.getJavaMailProperties();
            props.put("mail.transport.protocol", "smtp");
            props.put("mail.smtp.auth", "true");
            if (eff.port() == 465) {
                props.put("mail.smtp.ssl.enable", "true");
            } else {
                props.put("mail.smtp.starttls.enable", "true");
            }
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "10000");

            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, false, "UTF-8");
            String from = (eff.fromName() != null ? eff.fromName() : "InmobilIA Pro")
                        + " <" + eff.user() + ">";
            h.setFrom(from);
            h.setTo(to);
            h.setSubject(subject);
            h.setText(body, false);
            sender.send(msg);
            return new SendResult(true, "Email enviado a " + to);
        } catch (Exception e) {
            log.error("SMTP error: {}", e.getMessage());
            return new SendResult(false, "Error SMTP: " + e.getMessage());
        }
    }

    private SmtpConfig defaultConfig() {
        return new SmtpConfig(defaultHost, defaultPort, defaultUser, defaultPass, "InmobilIA Pro");
    }

    /** Envío vía API HTTP de Resend (https://resend.com) — puerto 443, sin SMTP. */
    private SendResult sendViaResend(String to, String subject, String body) {
        try {
            // Si el cuerpo no parece HTML, lo convertimos (saltos de línea → <br>) para que se lea bien
            String html = body != null && body.contains("<") ? body : ("<div style=\"font-family:sans-serif\">"
                    + (body == null ? "" : body.replace("\n", "<br>")) + "</div>");
            String payload = "{\"from\":\"" + jsonEsc(resendFrom) + "\","
                    + "\"to\":[\"" + jsonEsc(to) + "\"],"
                    + "\"subject\":\"" + jsonEsc(subject) + "\","
                    + "\"html\":\"" + jsonEsc(html) + "\"}";
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.resend.com/emails"))
                    .header("Authorization", "Bearer " + resendKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(payload, java.nio.charset.StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return new SendResult(true, "Email enviado (Resend) a " + to);
            }
            log.error("Resend {}: {}", resp.statusCode(), resp.body());
            return new SendResult(false, "Error Resend " + resp.statusCode() + ": " + resp.body());
        } catch (Exception e) {
            log.error("Resend error: {}", e.getMessage());
            return new SendResult(false, "Error Resend: " + e.getMessage());
        }
    }

    private static String jsonEsc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.toString();
    }
}
