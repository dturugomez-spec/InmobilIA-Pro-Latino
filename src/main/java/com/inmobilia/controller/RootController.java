package com.inmobilia.controller;

import com.inmobilia.config.NavConfig;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Home redirection + launcher por categoria.
 */
@Controller
public class RootController {

    @GetMapping("/")
    public String home(HttpSession session) {
        if (SessionUtils.isLoggedIn(session)) return "redirect:/dashboard";
        return "landing";
    }

    @GetMapping("/launcher/{cat}")
    public String launcher(@PathVariable String cat, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        var category = NavConfig.CATEGORIES.get(cat);
        if (category == null) return "redirect:/dashboard";
        model.addAttribute("cat", category);
        model.addAttribute("catKey", cat);
        model.addAttribute("page", cat);
        return "launcher";
    }

    /** PWA: pagina offline servida cuando el SW no encuentra ni red ni cache. */
    @GetMapping("/offline")
    public String offline() {
        return "offline";
    }

    /**
     * Avisos de contratos (recordatorios de vencimiento, automatizaciones) enlazaban a /contratos,
     * que no existe: llevaba a la página de error. La gestión de contratos está en /crud/contratos.
     */
    @GetMapping("/contratos")
    public String contratos() {
        return "redirect:/crud/contratos";
    }

    /** Igual con /leads (avisos de lead nuevo del chatbot, marketplace, portales…): el tablero está en /kanban/leads. */
    @GetMapping("/leads")
    public String leads() {
        return "redirect:/kanban/leads";
    }

    /** PWA: health check para Render free tier (keep-alive). */
    @GetMapping("/healthz")
    @org.springframework.web.bind.annotation.ResponseBody
    public String health() {
        return "ok";
    }
}
