package com.inmobilia.controller;

import com.inmobilia.service.AIService;
import com.inmobilia.service.EmailService;
import com.inmobilia.service.EmailService.SendResult;
import com.inmobilia.service.EmailService.SmtpConfig;
import com.inmobilia.service.PaisService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;

import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/**
 * Configuracion del usuario: clave de IA personal, SMTP, email de prueba.
 */
@Controller
@RequestMapping("/config")
@RequiredArgsConstructor
public class ConfigController {

    private final AIService ai;
    private final EmailService email;
    private final SupabaseService supabase;
    private final PaisService paises;

    @GetMapping
    public String show(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Configuracion");
        SmtpConfig smtp = (SmtpConfig) session.getAttribute(SessionUtils.KEY_SMTP);
        model.addAttribute("smtp", smtp);
        model.addAttribute("platformAiEnabled", ai.hasPlatformKey());
        model.addAttribute("autoRespuesta", leerAutoRespuesta(SessionUtils.uid(session)));
        model.addAttribute("esAdmin", "admin".equals(SessionUtils.role(session)));
        return "config";
    }

    /** 🌎 País de la inmobiliaria: moneda, impuesto, documentos fiscales, prefijo y zona horaria. */
    @PostMapping("/pais")
    public String savePais(@RequestParam String pais, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (!"admin".equals(SessionUtils.role(session))) {
            SessionUtils.flash(session, "Solo el administrador de la inmobiliaria puede cambiar el país.");
            return "redirect:/config";
        }
        if (paises.guardar(SessionUtils.uid(session), pais)) {
            com.inmobilia.util.Pais p = com.inmobilia.util.Pais.de(pais);
            SessionUtils.flash(session, p.bandera() + " País guardado: " + p.nombre() + ". Importes en "
                    + p.moneda() + ", " + p.impuestoConTipo() + ", " + p.etiquetaIdFiscal() + ".");
        } else {
            SessionUtils.flash(session, "No se pudo guardar el país.");
        }
        return "redirect:/config";
    }

    /** ⚡ Activa/desactiva la respuesta automática instantánea a leads nuevos. */
    @PostMapping("/auto-respuesta")
    public String saveAutoRespuesta(@RequestParam(defaultValue = "false") boolean activar, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.uid(session);
        try {
            supabase.update("users", uid, Map.of("auto_respuesta_leads", activar));
        } catch (Exception ignored) {}
        SessionUtils.flash(session, activar
                ? "⚡ Auto-respuesta a leads ACTIVADA. Los nuevos contactos recibirán respuesta al instante."
                : "Auto-respuesta a leads desactivada.");
        return "redirect:/config";
    }

    private boolean leerAutoRespuesta(String uid) {
        try {
            List<Map<String, Object>> us = supabase.selectFilter("users",
                    "id=eq." + uid + "&select=auto_respuesta_leads&limit=1");
            if (!us.isEmpty()) {
                Object v = us.get(0).get("auto_respuesta_leads");
                return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
            }
        } catch (Exception ignored) {}
        return false;
    }

    @PostMapping("/api-key")
    public String saveApiKey(@RequestParam String apiKey, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        session.setAttribute(SessionUtils.KEY_API_KEY, apiKey == null ? "" : apiKey.trim());
        SessionUtils.flash(session, "Clave IA personal guardada");
        return "redirect:/config";
    }

    @PostMapping("/smtp")
    public String saveSmtp(@RequestParam String server,
                           @RequestParam int port,
                           @RequestParam String user,
                           @RequestParam String password,
                           @RequestParam(defaultValue = "InmobilIA Pro") String fromName,
                           HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        SmtpConfig cfg = new SmtpConfig(server, port, user, password, fromName);
        session.setAttribute(SessionUtils.KEY_SMTP, cfg);
        SessionUtils.flash(session, "SMTP guardado");
        return "redirect:/config";
    }

    @PostMapping("/smtp/test")
    public String testSmtp(@RequestParam String destinatario, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        SmtpConfig cfg = (SmtpConfig) session.getAttribute(SessionUtils.KEY_SMTP);
        SendResult res = email.send(cfg, destinatario, "Prueba SMTP - InmobilIA Pro",
                "Este es un email de prueba desde InmobilIA Pro. Si lo recibes, tu configuracion es correcta.");
        SessionUtils.flash(session, res.message());
        return "redirect:/config";
    }
}
