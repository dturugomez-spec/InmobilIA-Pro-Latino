package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Servicio de firma electrónica con trazabilidad jurídica.
 *
 * Firma electrónica simple con registro de evidencias:
 *  - Identificación del firmante (email + documento de identidad + opcional OTP SMS)
 *  - Voluntad clara expresada (botón "FIRMAR")
 *  - Integridad del documento (SHA-256 inmutable)
 *  - Cadena hash de auditoría (cada evento enlazado al anterior)
 *  - Sello de tiempo automático
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignatureService {

    private final SupabaseService supabase;
    private final EmailService emailService;

    /** SHA-256 hex */
    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Registra evento de auditoría con cadena hash. */
    public void audit(String documentoId, String firmanteId, String evento, String detalle,
                      String ip, String userAgent, Map<String, Object> metadata) {
        try {
            // Obtener último hash de la cadena
            List<Map<String, Object>> prev = supabase.selectFilter(
                    "firmas_auditoria",
                    "documento_id=eq." + documentoId + "&order=id.desc&limit=1");
            String hashAnterior = prev.isEmpty() ? "GENESIS" : String.valueOf(prev.get(0).get("hash_actual"));

            String concat = hashAnterior + "|" + documentoId + "|" + evento + "|" + OffsetDateTime.now() + "|" + ip;
            String hashActual = sha256(concat);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("documento_id", documentoId);
            if (firmanteId != null) row.put("firmante_id", firmanteId);
            row.put("evento", evento);
            row.put("detalle", detalle);
            row.put("ip", ip);
            row.put("user_agent", userAgent);
            if (metadata != null) row.put("metadata", metadata);
            row.put("hash_anterior", hashAnterior);
            row.put("hash_actual", hashActual);
            row.put("created_at", OffsetDateTime.now().toString());

            supabase.insert("firmas_auditoria", row);
        } catch (Exception e) {
            log.warn("Error audit: {}", e.getMessage());
        }
    }

    /** Verifica integridad de la cadena de auditoría. */
    public boolean verificarIntegridad(String documentoId) {
        List<Map<String, Object>> auditoria = supabase.selectFilter(
                "firmas_auditoria",
                "documento_id=eq." + documentoId + "&order=id.asc");

        String esperado = "GENESIS";
        for (Map<String, Object> evento : auditoria) {
            if (!Objects.equals(esperado, evento.get("hash_anterior"))) {
                return false;
            }
            esperado = String.valueOf(evento.get("hash_actual"));
        }
        return true;
    }

    /** Genera OTP de 6 dígitos. */
    public String generarOTP() {
        return String.format("%06d", new Random().nextInt(1000000));
    }

    /** Envía OTP por email al firmante. */
    public void enviarOTPEmail(Map<String, Object> firmante, String otp) {
        String email = String.valueOf(firmante.get("email"));
        String html = String.format(
            "<h2>🔐 Código de verificación</h2>" +
            "<p>Hola %s, tu código para firmar el documento es:</p>" +
            "<p style='font-size:32px;font-weight:bold;letter-spacing:8px;color:#3b82f6;background:#eff6ff;padding:20px;border-radius:8px;text-align:center'>%s</p>" +
            "<p style='color:#64748b;font-size:12px'>Este código caduca en 10 minutos. Si no lo solicitaste, ignora este email.</p>",
            firmante.get("nombre"), otp);
        emailService.send(email, "🔐 Código de verificación InmobilIA Pro", html);
    }

    /** Marca documento como completado cuando todos los firmantes han firmado. */
    public void verificarCompletado(String documentoId) {
        List<Map<String, Object>> firmantes = supabase.selectFilter(
                "firmas_firmante", "documento_id=eq." + documentoId);

        boolean todosFirmados = !firmantes.isEmpty() &&
                firmantes.stream().allMatch(f -> "firmado".equals(f.get("estado")));

        if (todosFirmados) {
            supabase.update("firmas_documento", documentoId, Map.of(
                    "estado", "firmado",
                    "fecha_completado", OffsetDateTime.now().toString(),
                    "updated_at", OffsetDateTime.now().toString()
            ));
            audit(documentoId, null, "documento_completado",
                    "Todos los firmantes han firmado", null, null, null);
        } else {
            // Estado intermedio
            long firmados = firmantes.stream().filter(f -> "firmado".equals(f.get("estado"))).count();
            if (firmados > 0) {
                supabase.update("firmas_documento", documentoId, Map.of(
                        "estado", "parcialmente_firmado",
                        "updated_at", OffsetDateTime.now().toString()
                ));
            }
        }
    }
}
