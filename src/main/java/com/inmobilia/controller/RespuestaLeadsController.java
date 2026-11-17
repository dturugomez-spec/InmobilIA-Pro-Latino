package com.inmobilia.controller;

import com.inmobilia.service.LeadAutoResponderService;
import com.inmobilia.service.RespuestaLeadsService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/** Respuesta a leads: la respuesta automática y el tiempo que tarda la agencia en contestar. */
@Controller
@RequiredArgsConstructor
public class RespuestaLeadsController {

    private final SupabaseService supabase;
    private final RespuestaLeadsService respuestas;

    @GetMapping("/respuesta-leads")
    public String pantalla(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String org = SessionUtils.uid(session);
        List<Map<String, Object>> u = supabase.selectFilter("users", "id=eq." + org + "&limit=1");
        RespuestaLeadsService.Metricas m = respuestas.metricas(org);
        model.addAttribute("page", "Respuesta a leads");
        model.addAttribute("m", m);
        model.addAttribute("mediana", RespuestaLeadsService.duracion(m.medianaMin()));
        model.addAttribute("objetivo", RespuestaLeadsService.OBJETIVO_MIN);
        model.addAttribute("activa", !u.isEmpty() && LeadAutoResponderService.activa(u.get(0).get("auto_respuesta_leads")));
        model.addAttribute("huecos", respuestas.huecosVisita(org, ZonedDateTime.now(RespuestaLeadsService.zona()), 3)
                .stream().map(RespuestaLeadsService::textoHueco).toList());
        String rol = SessionUtils.role(session);
        model.addAttribute("puedeCambiar", "admin".equals(rol) || "director".equals(rol));
        return "respuesta_leads";
    }

    @PostMapping("/respuesta-leads/auto")
    public String cambiar(@RequestParam(defaultValue = "false") boolean activar, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String rol = SessionUtils.role(session);
        if (!"admin".equals(rol) && !"director".equals(rol)) return "redirect:/respuesta-leads";
        String org = SessionUtils.uid(session);
        boolean ok = supabase.update("users", org, Map.of("auto_respuesta_leads", activar), org);
        // Por separado: si aún falta la migración V96, el interruptor funciona igual
        supabase.update("users", org, Map.of("auto_respuesta_cambiada_at", Instant.now().toString()), org);
        SessionUtils.flash(session, !ok ? "No se pudo guardar. " + java.util.Objects.toString(supabase.lastError(), "")
                : activar ? "Respuesta automática encendida: los contactos nuevos reciben respuesta al instante."
                : "Respuesta automática apagada. Los contactos nuevos esperarán a que alguien les conteste.");
        return "redirect:/respuesta-leads";
    }
}
