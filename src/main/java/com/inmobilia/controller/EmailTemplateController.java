package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plantillas reutilizables: emails, cláusulas de contrato, descripciones para portales.
 */
@Controller
@RequiredArgsConstructor
public class EmailTemplateController {

    private final SupabaseService supabase;

    @GetMapping("/templates")
    public String list(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.realUid(session);
        var rows = supabase.findBy("templates", Map.of("user_id", uid));
        model.addAttribute("page", "Plantillas");
        model.addAttribute("rows", rows);
        return "templates_list";
    }

    @GetMapping("/templates/new")
    public String formNew(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Nueva plantilla");
        model.addAttribute("tpl", new java.util.HashMap<>());
        model.addAttribute("editing", false);
        return "templates_form";
    }

    @GetMapping("/templates/{id}/edit")
    public String formEdit(@PathVariable String id, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.realUid(session);
        var rows = supabase.findBy("templates", Map.of("id", id, "user_id", uid));
        if (rows.isEmpty()) return "redirect:/templates";
        model.addAttribute("page", "Editar plantilla");
        model.addAttribute("tpl", rows.get(0));
        model.addAttribute("editing", true);
        return "templates_form";
    }

    @PostMapping("/templates")
    public String save(HttpServletRequest req, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.realUid(session);
        String id = req.getParameter("id");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", uid);
        for (String f : List.of("type", "name", "subject", "body")) {
            String v = req.getParameter(f);
            if (v != null && !v.isBlank()) data.put(f, v);
        }
        data.put("created_at", OffsetDateTime.now().toString());

        if (id == null || id.isBlank()) supabase.insert("templates", data);
        else supabase.update("templates", id, data, uid);

        SessionUtils.flash(session, "Plantilla guardada");
        return "redirect:/templates";
    }

    @PostMapping("/templates/{id}/delete")
    public String delete(@PathVariable String id, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        supabase.delete("templates", id, SessionUtils.realUid(session));
        return "redirect:/templates";
    }
}
