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

    /** PWA: health check para Render free tier (keep-alive). */
    @GetMapping("/healthz")
    @org.springframework.web.bind.annotation.ResponseBody
    public String health() {
        return "ok";
    }
}
