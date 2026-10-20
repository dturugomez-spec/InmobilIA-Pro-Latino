package com.inmobilia.controller;

import com.inmobilia.service.EmailService;
import com.inmobilia.service.SignatureService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.HtmlSeguro;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/firmas")
@Slf4j
public class SignatureController {

    private final SupabaseService supabase;
    private final SignatureService signatureService;
    private final EmailService emailService;

    private static final String BASE_URL = com.inmobilia.util.UrlBase.BASE;

    @GetMapping
    public String list(HttpSession session, Model model,
                       @RequestParam(required = false) String estado) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        StringBuilder q = new StringBuilder("user_id=eq.").append(uid).append("&order=created_at.desc");
        if (estado != null && !estado.isBlank()) q.append("&estado=eq.").append(estado);
        List<Map<String, Object>> docs = supabase.selectFilter("firmas_documento", q.toString());

        // Stats
        long pendientes = docs.stream().filter(d -> "enviado".equals(d.get("estado")) || "parcialmente_firmado".equals(d.get("estado"))).count();
        long firmados = docs.stream().filter(d -> "firmado".equals(d.get("estado"))).count();
        long rechazados = docs.stream().filter(d -> "rechazado".equals(d.get("estado"))).count();

        model.addAttribute("documentos", docs);
        model.addAttribute("pendientes", pendientes);
        model.addAttribute("firmados", firmados);
        model.addAttribute("rechazados", rechazados);
        return "signatures_list";
    }

    @GetMapping("/nueva")
    public String form(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        model.addAttribute("contratos", supabase.selectFilter("contratos", "user_id=eq." + uid));
        return "signatures_form";
    }

    @PostMapping("/guardar")
    public String save(@RequestParam Map<String, String> form, HttpSession session, HttpServletRequest req) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        // Se limpia ANTES de calcular la huella: lo que se firma es exactamente lo que se ve
        String contenido = HtmlSeguro.limpiar(form.get("contenido_html"));
        String hash = SignatureService.sha256(contenido);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        data.put("titulo", form.get("titulo"));
        data.put("tipo", form.getOrDefault("tipo", "otros"));
        data.put("contenido_html", contenido);
        data.put("documento_hash", hash);
        if (form.get("contrato_id") != null && !form.get("contrato_id").isBlank()) data.put("contrato_id", form.get("contrato_id"));
        data.put("estado", "borrador");
        data.put("requiere_otp_email", "on".equals(form.getOrDefault("requiere_otp_email", "on")));
        data.put("requiere_dni", "on".equals(form.getOrDefault("requiere_dni", "on")));
        data.put("fecha_expiracion", OffsetDateTime.now().plusDays(30).toString());
        data.put("created_at", OffsetDateTime.now().toString());
        data.put("updated_at", OffsetDateTime.now().toString());

        Map<String, Object> doc = supabase.insert("firmas_documento", data);
        String docId = String.valueOf(doc.get("id"));

        // Crear firmantes
        String[] nombres = form.getOrDefault("firmante_nombre", "").split("\\|");
        String[] emails = form.getOrDefault("firmante_email", "").split("\\|");
        String[] dnis = form.getOrDefault("firmante_dni", "").split("\\|");
        String[] roles = form.getOrDefault("firmante_rol", "").split("\\|");

        for (int i = 0; i < nombres.length; i++) {
            if (nombres[i].isBlank()) continue;
            Map<String, Object> fd = new LinkedHashMap<>();
            fd.put("documento_id", docId);
            fd.put("nombre", nombres[i]);
            fd.put("email", i < emails.length ? emails[i] : "");
            fd.put("dni", i < dnis.length ? dnis[i] : "");
            fd.put("rol", i < roles.length ? roles[i] : "");
            fd.put("orden", i + 1);
            fd.put("estado", "pendiente");
            fd.put("created_at", OffsetDateTime.now().toString());
            supabase.insert("firmas_firmante", fd);
        }

        signatureService.audit(docId, null, "creado",
                "Documento creado por " + uid, req.getRemoteAddr(), req.getHeader("User-Agent"),
                Map.of("hash", hash));

        SessionUtils.flash(session, "✅ Documento creado");
        return "redirect:/firmas/" + docId;
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable String id, HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";
        List<Map<String, Object>> docs = supabase.selectFilter("firmas_documento", "id=eq." + id + "&user_id=eq." + uid);
        if (docs.isEmpty()) return "redirect:/firmas";

        Map<String, Object> doc = docs.get(0);
        List<Map<String, Object>> firmantes = supabase.selectFilter(
                "firmas_firmante", "documento_id=eq." + id + "&order=orden.asc");
        List<Map<String, Object>> auditoria = supabase.selectFilter(
                "firmas_auditoria", "documento_id=eq." + id + "&order=id.asc");

        model.addAttribute("documento", conHtmlLimpio(doc));
        model.addAttribute("firmantes", firmantes);
        model.addAttribute("auditoria", auditoria);
        model.addAttribute("baseUrl", BASE_URL);
        model.addAttribute("integridad", signatureService.verificarIntegridad(id));
        return "signatures_detail";
    }

    @PostMapping("/{id}/enviar")
    public String send(@PathVariable String id, HttpSession session, HttpServletRequest req) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        List<Map<String, Object>> docs = supabase.selectOwned("firmas_documento", id, uid);
        if (docs.isEmpty()) return "redirect:/firmas";
        Map<String, Object> doc = docs.get(0);

        // Enviar email a cada firmante
        List<Map<String, Object>> firmantes = supabase.selectFilter(
                "firmas_firmante", "documento_id=eq." + id);

        for (Map<String, Object> f : firmantes) {
            String email = String.valueOf(f.get("email"));
            if (email == null || email.isBlank()) continue;
            String token = String.valueOf(f.get("token_firma"));
            String url = BASE_URL + "/firmas/firmar/" + token;

            String html = String.format(
                "<h2>📄 Tienes un documento para firmar</h2>" +
                "<p>Hola %s,</p>" +
                "<p>Te enviamos el documento <strong>%s</strong> para tu firma electrónica.</p>" +
                "<p><a href='%s' style='display:inline-block;padding:12px 24px;background:#3b82f6;color:white;text-decoration:none;border-radius:8px;font-weight:bold'>📝 Firmar documento</a></p>" +
                "<p style='color:#64748b;font-size:12px'>Este enlace caduca el %s. Firma electrónica simple con registro de evidencias (email, IP, fecha y hora).</p>",
                HtmlSeguro.escapar(String.valueOf(f.get("nombre"))), HtmlSeguro.escapar(String.valueOf(doc.get("titulo"))),
                url, doc.get("fecha_expiracion"));
            emailService.send(email, "📄 " + doc.get("titulo") + " - Firma requerida", html);
        }

        supabase.update("firmas_documento", id, Map.of(
                "estado", "enviado",
                "fecha_envio", OffsetDateTime.now().toString(),
                "updated_at", OffsetDateTime.now().toString()
        ));

        signatureService.audit(id, null, "enviado",
                "Enviado a " + firmantes.size() + " firmantes",
                req.getRemoteAddr(), req.getHeader("User-Agent"), null);

        SessionUtils.flash(session, "📧 Documento enviado a " + firmantes.size() + " firmantes");
        return "redirect:/firmas/" + id;
    }

    // ===== ENDPOINTS PÚBLICOS PARA EL FIRMANTE =====

    @GetMapping("/firmar/{token}")
    public String publicFirma(@PathVariable String token, Model model, HttpServletRequest req) {
        List<Map<String, Object>> firmantes = supabase.selectFilter(
                "firmas_firmante", "token_firma=eq." + token);
        if (firmantes.isEmpty()) {
            model.addAttribute("error", "Enlace no válido o expirado");
            return "signatures_public_error";
        }
        Map<String, Object> firmante = firmantes.get(0);

        List<Map<String, Object>> docs = supabase.selectFilter(
                "firmas_documento", "id=eq." + firmante.get("documento_id"));
        if (docs.isEmpty()) return "redirect:/";
        Map<String, Object> doc = docs.get(0);

        // Verificar expiración
        try {
            OffsetDateTime exp = OffsetDateTime.parse(String.valueOf(doc.get("fecha_expiracion")));
            if (exp.isBefore(OffsetDateTime.now())) {
                model.addAttribute("error", "Este documento ha expirado");
                return "signatures_public_error";
            }
        } catch (Exception ignored) {}

        // Marcar como visto
        if ("pendiente".equals(firmante.get("estado"))) {
            supabase.update("firmas_firmante", String.valueOf(firmante.get("id")), Map.of(
                    "estado", "visto",
                    "visto_at", OffsetDateTime.now().toString()
            ));
            signatureService.audit(String.valueOf(doc.get("id")), String.valueOf(firmante.get("id")),
                    "visto", firmante.get("nombre") + " ha abierto el documento",
                    req.getRemoteAddr(), req.getHeader("User-Agent"), null);
        }

        // Si ya firmó
        if ("firmado".equals(firmante.get("estado"))) {
            model.addAttribute("yaFirmado", true);
        }

        model.addAttribute("documento", conHtmlLimpio(doc));
        model.addAttribute("firmante", firmante);
        return "signatures_public_sign";
    }

    /** Copia del documento con el HTML limpio para mostrarlo (los guardados antes de limpiar al crear). */
    private static Map<String, Object> conHtmlLimpio(Map<String, Object> doc) {
        Map<String, Object> copia = new LinkedHashMap<>(doc);
        Object html = doc.get("contenido_html");
        if (html != null) copia.put("contenido_html", HtmlSeguro.limpiar(String.valueOf(html)));
        return copia;
    }

    @PostMapping("/firmar/{token}/otp/solicitar")
    @ResponseBody
    public Map<String, Object> requestOTP(@PathVariable String token, HttpServletRequest req) {
        List<Map<String, Object>> firmantes = supabase.selectFilter("firmas_firmante", "token_firma=eq." + token);
        if (firmantes.isEmpty()) return Map.of("ok", false);
        Map<String, Object> firmante = firmantes.get(0);

        String otp = signatureService.generarOTP();
        supabase.update("firmas_firmante", String.valueOf(firmante.get("id")), Map.of(
                "otp_codigo", otp,
                "otp_enviado_at", OffsetDateTime.now().toString(),
                "otp_intentos", 0
        ));

        signatureService.enviarOTPEmail(firmante, otp);
        signatureService.audit(String.valueOf(firmante.get("documento_id")), String.valueOf(firmante.get("id")),
                "otp_enviado", "OTP enviado a " + firmante.get("email"),
                req.getRemoteAddr(), req.getHeader("User-Agent"), null);

        return Map.of("ok", true);
    }

    @PostMapping("/firmar/{token}/firmar")
    @ResponseBody
    public Map<String, Object> sign(@PathVariable String token,
                                    @RequestBody Map<String, Object> body,
                                    HttpServletRequest req) {
        List<Map<String, Object>> firmantes = supabase.selectFilter("firmas_firmante", "token_firma=eq." + token);
        if (firmantes.isEmpty()) return Map.of("ok", false, "error", "Token no válido");
        Map<String, Object> firmante = firmantes.get(0);
        String fid = String.valueOf(firmante.get("id"));
        String docId = String.valueOf(firmante.get("documento_id"));

        if ("firmado".equals(firmante.get("estado"))) return Map.of("ok", false, "error", "Ya firmado");

        // Verificar OTP si está habilitado
        List<Map<String, Object>> docs = supabase.selectFilter("firmas_documento", "id=eq." + docId);
        if (docs.isEmpty()) return Map.of("ok", false);
        Map<String, Object> doc = docs.get(0);

        if (Boolean.TRUE.equals(doc.get("requiere_otp_email"))) {
            String otpEsperado = String.valueOf(firmante.get("otp_codigo"));
            String otpRecibido = String.valueOf(body.get("otp"));
            int intentos = ((Number) firmante.getOrDefault("otp_intentos", 0)).intValue();

            if (intentos >= 5) {
                return Map.of("ok", false, "error", "Demasiados intentos. Solicita un nuevo código.");
            }
            if (otpEsperado == null || !otpEsperado.equals(otpRecibido)) {
                supabase.update("firmas_firmante", fid, Map.of("otp_intentos", intentos + 1));
                return Map.of("ok", false, "error", "Código incorrecto");
            }

            // Verificar caducidad 10 min
            try {
                OffsetDateTime enviado = OffsetDateTime.parse(String.valueOf(firmante.get("otp_enviado_at")));
                if (enviado.plusMinutes(10).isBefore(OffsetDateTime.now())) {
                    return Map.of("ok", false, "error", "Código caducado");
                }
            } catch (Exception ignored) {}
        }

        // Verificar documento de identidad si está habilitado
        if (Boolean.TRUE.equals(doc.get("requiere_dni"))) {
            String dniEsperado = String.valueOf(firmante.get("dni"));
            String dniRecibido = String.valueOf(body.get("dni"));
            if (dniEsperado != null && !dniEsperado.isBlank() && !dniEsperado.equalsIgnoreCase(dniRecibido)) {
                return Map.of("ok", false, "error", "El documento de identidad no coincide");
            }
        }

        // Guardar firma
        String firmaImagen = String.valueOf(body.get("firma_imagen"));
        String firmaHash = SignatureService.sha256(firmaImagen + fid + OffsetDateTime.now());

        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("estado", "firmado");
        upd.put("firmado_at", OffsetDateTime.now().toString());
        upd.put("firma_imagen", firmaImagen);
        upd.put("firma_hash", firmaHash);
        upd.put("ip_firma", req.getRemoteAddr());
        upd.put("user_agent", req.getHeader("User-Agent"));
        upd.put("otp_verificado", true);
        if (body.get("geolocalizacion") != null) upd.put("geolocalizacion", body.get("geolocalizacion"));
        supabase.update("firmas_firmante", fid, upd);

        signatureService.audit(docId, fid, "firmado",
                firmante.get("nombre") + " ha firmado el documento. Hash firma: " + firmaHash,
                req.getRemoteAddr(), req.getHeader("User-Agent"),
                Map.of("firma_hash", firmaHash, "geolocalizacion", String.valueOf(body.getOrDefault("geolocalizacion", "n/a"))));

        // Verificar si todos firmaron
        signatureService.verificarCompletado(docId);

        return Map.of("ok", true, "firmaHash", firmaHash);
    }

    @PostMapping("/firmar/{token}/rechazar")
    @ResponseBody
    public Map<String, Object> reject(@PathVariable String token,
                                      @RequestBody Map<String, Object> body,
                                      HttpServletRequest req) {
        List<Map<String, Object>> firmantes = supabase.selectFilter("firmas_firmante", "token_firma=eq." + token);
        if (firmantes.isEmpty()) return Map.of("ok", false);
        Map<String, Object> firmante = firmantes.get(0);
        String fid = String.valueOf(firmante.get("id"));
        String docId = String.valueOf(firmante.get("documento_id"));

        supabase.update("firmas_firmante", fid, Map.of("estado", "rechazado"));
        supabase.update("firmas_documento", docId, Map.of(
                "estado", "rechazado",
                "motivo_rechazo", String.valueOf(body.getOrDefault("motivo", "")),
                "updated_at", OffsetDateTime.now().toString()
        ));

        signatureService.audit(docId, fid, "rechazado",
                "Documento rechazado por " + firmante.get("nombre"),
                req.getRemoteAddr(), req.getHeader("User-Agent"),
                Map.of("motivo", String.valueOf(body.getOrDefault("motivo", ""))));

        return Map.of("ok", true);
    }
}
