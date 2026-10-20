package com.inmobilia.controller;

import com.inmobilia.service.AIService;
import com.inmobilia.service.AgentImpactService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.*;

/**
 * 🏅 Panel de Impacto del Agente IA (últimos 30 días) + narrativa.
 * La agregación vive en {@link AgentImpactService} (reutilizada por el email semanal).
 */
@Controller
@RequiredArgsConstructor
public class AgentImpactController {

    private final AgentImpactService impact;
    private final AIService aiService;

    @GetMapping("/impacto")
    public String impacto(HttpSession session, Model model) {
        String uid = SessionUtils.uid(session);
        if (uid == null) return "redirect:/login";

        Map<String, Object> r = impact.resumen(uid, 30);
        model.addAllAttributes(r);
        model.addAttribute("narrativa", narrativa(uid, r));
        return "impacto";
    }

    private String narrativa(String uid, Map<String, Object> r) {
        try {
            String prompt = String.format(Locale.ROOT,
                "En los últimos 30 días, el Agente IA de una inmobiliaria ha: enviado/preparado %s mensajes, " +
                "ejecutado %s acciones, captado %s leads automáticamente y mantiene %s leads en seguimiento, " +
                "ahorrando ~%s horas de trabajo manual.\n\n" +
                "Escribe 2-3 frases (máx 55 palabras) motivadoras y concretas para el dueño de la agencia, " +
                "destacando el valor aportado. Tono cercano y profesional. Español de España.",
                r.get("mensajesIA"), r.get("otrasAcciones"), r.get("captacionAuto"), r.get("enSeguimiento"), r.get("horas"));
            return aiService.call(null, prompt, "Eres el Agente IA de InmobilIA resumiendo tu propio impacto.", 160, uid);
        } catch (Exception e) {
            return null;
        }
    }
}
