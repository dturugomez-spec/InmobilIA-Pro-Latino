package com.inmobilia.controller;

import com.inmobilia.service.NegociacionService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Negotiation Co-Pilot (ver NegociacionService). */
@Controller
@RequestMapping("/herramientas/negociacion")
@RequiredArgsConstructor
public class NegociacionController {

    private final NegociacionService negociacion;
    private final SupabaseService supabase;

    @GetMapping
    public String pantalla(@RequestParam(required = false) String propiedad, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        rellenar(SessionUtils.uid(session), model);
        model.addAttribute("propiedadElegida", propiedad);
        return "herramientas/negociacion";
    }

    @PostMapping
    public String preparar(@RequestParam Map<String, String> form, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String org = SessionUtils.uid(session);
        model.addAttribute("resultado", negociacion.preparar(org, form));
        rellenar(org, model);
        model.addAttribute("propiedadElegida", form.get("propiedad_id"));
        return "herramientas/negociacion";
    }

    @GetMapping("/{id}")
    public String ver(@PathVariable String id, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String org = SessionUtils.uid(session);
        Map<String, Object> r = negociacion.ver(org, id);
        if (r == null) return "redirect:/herramientas/negociacion";
        model.addAttribute("resultado", r);
        rellenar(org, model);
        return "herramientas/negociacion";
    }

    private void rellenar(String org, Model model) {
        model.addAttribute("page", "Negotiation Co-Pilot");
        model.addAttribute("activos", supabase.selectFilter("propiedades", "select=id,name,city,area_m2&user_id=eq." + org + "&order=name.asc&limit=1000"));
        model.addAttribute("historial", negociacion.historial(org));
    }
}
