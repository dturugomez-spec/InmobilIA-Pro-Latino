package com.inmobilia.controller;

import com.inmobilia.service.MultiEmpresaService;
import com.inmobilia.service.PlanService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Gestión multi-empresa (ver MultiEmpresaService). */
@Controller
@RequestMapping("/herramientas/multi-empresa")
@RequiredArgsConstructor
public class MultiEmpresaController {

    private final MultiEmpresaService multi;
    private final PlanService planes;
    private final SupabaseService supabase;

    @GetMapping
    public String pantalla(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String org = SessionUtils.uid(session);
        List<Map<String, Object>> sociedades = multi.sociedades(org);
        List<Map<String, Object>> activos = multi.activos(org);
        var usuarios = supabase.findBy("users", Map.of("id", org));
        int max = planes.maxSociedades(usuarios.isEmpty() ? null : usuarios.get(0));
        model.addAttribute("page", "Gestión multi-empresa");
        model.addAttribute("sociedades", sociedades);
        model.addAttribute("activos", activos);
        model.addAttribute("resumen", multi.resumen(org, sociedades, activos));
        model.addAttribute("maxSociedades", max);
        model.addAttribute("puedeCrear", max < 0 || sociedades.size() < max);
        model.addAttribute("precioSociedadExtra", PlanService.PRECIO_SOCIEDAD_EXTRA);
        return "herramientas/multi_empresa";
    }

    @PostMapping("/sociedades")
    public String crear(@RequestParam String nombre, @RequestParam(required = false) String cif,
                        @RequestParam(required = false) String notas, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        var r = multi.crear(SessionUtils.uid(session), nombre, cif, notas);
        SessionUtils.flash(session, (r.ok() ? "✅ " : "❌ ") + r.mensaje());
        return "redirect:/herramientas/multi-empresa";
    }

    @PostMapping("/sociedades/{id}/editar")
    public String editar(@PathVariable String id, @RequestParam String nombre, @RequestParam(required = false) String cif,
                         @RequestParam(required = false) String notas, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        var r = multi.editar(SessionUtils.uid(session), id, nombre, cif, notas);
        SessionUtils.flash(session, (r.ok() ? "✅ " : "❌ ") + r.mensaje());
        return "redirect:/herramientas/multi-empresa";
    }

    @PostMapping("/sociedades/{id}/borrar")
    public String borrar(@PathVariable String id, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        var r = multi.borrar(SessionUtils.uid(session), id);
        SessionUtils.flash(session, (r.ok() ? "✅ " : "❌ ") + r.mensaje());
        return "redirect:/herramientas/multi-empresa";
    }

    /** Asignación de activos: campos "activo_<id>" = id de sociedad ("" = sin sociedad). */
    @PostMapping("/asignar")
    public String asignar(@RequestParam Map<String, String> form, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        Map<String, String> cambios = new LinkedHashMap<>();
        form.forEach((k, v) -> { if (k.startsWith("activo_")) cambios.put(k.substring(7), v); });
        int n = multi.asignar(SessionUtils.uid(session), cambios);
        SessionUtils.flash(session, n == 0 ? "No había cambios." : "✅ " + n + (n == 1 ? " activo cambiado de sociedad." : " activos cambiados de sociedad."));
        return "redirect:/herramientas/multi-empresa";
    }
}
