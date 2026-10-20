package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wizard de bienvenida tras registro: nombre completo, empresa, tipo de cartera, tipo de uso.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class OnboardingController {

    private final SupabaseService supabase;

    @GetMapping("/welcome")
    public String welcome(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Bienvenido a InmobilIA Pro");
        return "welcome";
    }

    @PostMapping("/welcome")
    public String save(@RequestParam(required = false) String name,
                       @RequestParam(required = false) String company,
                       @RequestParam(required = false) String position,
                       @RequestParam(required = false) String phone,
                       @RequestParam(required = false) String portfolioSize,
                       @RequestParam(required = false) String useCase,
                       HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.realUid(session);

        Map<String, Object> upd = new LinkedHashMap<>();
        if (name != null && !name.isBlank()) upd.put("name", name);
        if (phone != null && !phone.isBlank()) upd.put("phone", phone);
        if (position != null && !position.isBlank()) upd.put("position", position);

        // Guardar en notas/metadata si quieres (dejo simple por ahora)
        if (!upd.isEmpty()) {
            supabase.update("users", uid, upd, uid);
            // Refrescar sesión con nombre nuevo
            if (name != null && !name.isBlank()) {
                session.setAttribute(SessionUtils.KEY_NAME, name);
            }
        }
        SessionUtils.flash(session, "¡Cuenta configurada! Ya puedes empezar a usar InmobilIA Pro.");
        return "redirect:/dashboard";
    }
}
