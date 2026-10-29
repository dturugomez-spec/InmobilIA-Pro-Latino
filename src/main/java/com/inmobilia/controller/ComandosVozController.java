package com.inmobilia.controller;

import com.inmobilia.service.ComandosVozService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** Comandos de voz: el navegador transcribe y aquí se ejecuta la orden (ver ComandosVozService). */
@Controller
@RequestMapping("/herramientas/comandos-voz")
@RequiredArgsConstructor
public class ComandosVozController {

    private final ComandosVozService voz;

    @GetMapping
    public String pantalla(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Comandos de voz");
        return "herramientas/comandos_voz";
    }

    @PostMapping("/ejecutar")
    @ResponseBody
    public Map<String, Object> ejecutar(@RequestParam String texto, HttpSession session) {
        String org = SessionUtils.uid(session);
        if (org == null) return Map.of("ok", false, "texto", "Tu sesión ha caducado. Vuelve a entrar.");
        String frase = texto.length() > 500 ? texto.substring(0, 500) : texto;
        ComandosVozService.Respuesta r = voz.ejecutar(org, SessionUtils.realUid(session), frase);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("texto", r.texto());
        out.put("enlace", r.enlace());
        out.put("accion", r.accion());
        return out;
    }
}
