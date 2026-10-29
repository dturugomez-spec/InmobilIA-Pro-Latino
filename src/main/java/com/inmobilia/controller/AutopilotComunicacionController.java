package com.inmobilia.controller;

import com.inmobilia.service.AutopilotComunicacionService;
import com.inmobilia.service.AutopilotComunicacionService.Aviso;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Communication AutoPilot: avisos a inquilinos (ver AutopilotComunicacionService). */
@Controller
@RequestMapping(AutopilotComunicacionService.RUTA)
@RequiredArgsConstructor
public class AutopilotComunicacionController {

    private final AutopilotComunicacionService autopilot;

    @GetMapping
    public String pantalla(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String org = SessionUtils.uid(session);
        List<Aviso> avisos = autopilot.avisos(org);
        model.addAttribute("page", "Communication AutoPilot");
        model.addAttribute("pendientes", avisos.stream().filter(a -> a.enviadoEl() == null).toList());
        model.addAttribute("enviados", avisos.stream().filter(a -> a.enviadoEl() != null).toList());
        model.addAttribute("config", autopilot.config(org));
        model.addAttribute("historial", autopilot.historial(org));
        return "herramientas/autopilot_comunicacion";
    }

    @PostMapping("/enviar")
    public String enviar(@RequestParam String tipo, @RequestParam String ref,
                         @RequestParam String asunto, @RequestParam String cuerpo, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String org = SessionUtils.uid(session);
        Aviso a = autopilot.aviso(org, tipo, ref);
        if (a == null) {
            SessionUtils.flash(session, "Ese aviso ya no está pendiente.");
        } else {
            var r = autopilot.enviar(org, a, asunto, cuerpo, "manual");
            SessionUtils.flash(session, (r.ok() ? "✅ " : "❌ ") + r.mensaje());
        }
        return "redirect:" + AutopilotComunicacionService.RUTA;
    }

    /** La IA mejora el borrador que hay en pantalla (gasta 1 crédito). */
    @PostMapping("/ia")
    @ResponseBody
    public Map<String, Object> mejorar(@RequestParam String tipo, @RequestParam String ref, @RequestParam String cuerpo,
                                       @RequestParam(required = false) String instrucciones, HttpSession session) {
        String org = SessionUtils.uid(session);
        if (org == null) return Map.of("ok", false, "error", "Sesión caducada.");
        Aviso a = autopilot.aviso(org, tipo, ref);
        if (a == null) return Map.of("ok", false, "error", "Ese aviso ya no está pendiente.");
        String texto = autopilot.mejorarConIA(org, a, cuerpo, instrucciones);
        return texto == null ? Map.of("ok", false, "error", "La IA no ha respondido (o no quedan créditos). El borrador sigue como estaba.")
                             : Map.of("ok", true, "cuerpo", texto);
    }

    @PostMapping("/config")
    public String config(@RequestParam(defaultValue = "false") boolean renovacion,
                         @RequestParam(defaultValue = "false") boolean actualizacion,
                         @RequestParam(defaultValue = "false") boolean pago, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        boolean ok = autopilot.guardarConfig(SessionUtils.uid(session), renovacion, actualizacion, pago);
        SessionUtils.flash(session, ok ? "Envío automático guardado. Se revisa cada mañana a las 9:00."
                                       : "No se pudo guardar (¿falta ejecutar la migración V93 en Supabase?).");
        return "redirect:" + AutopilotComunicacionService.RUTA;
    }
}
