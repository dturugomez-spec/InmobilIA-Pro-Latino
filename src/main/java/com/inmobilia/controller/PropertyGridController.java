package com.inmobilia.controller;

import com.inmobilia.service.PermissionService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.*;

/**
 * Vista grid pro (estilo Sooprema/Idealista) para la cartera de propiedades.
 * Cards con foto destacada, badges, filtros avanzados.
 */
@Controller
@RequestMapping("/propiedades")
@RequiredArgsConstructor
@Slf4j
public class PropertyGridController {

    private final SupabaseService supabase;
    private final PermissionService permissions;

    @GetMapping("/grid")
    public String grid(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String role = SessionUtils.role(session);
        if (!permissions.canRead(role, "propiedades")) return "redirect:/dashboard";

        String uid = SessionUtils.uid(session);
        var rows = supabase.query("propiedades", uid);

        // KPIs
        long total = rows.size();
        long publicadas = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("publicada")) || "true".equalsIgnoreCase(String.valueOf(r.get("publicada")))).count();
        long ocupadas   = rows.stream().filter(r -> "Ocupado".equalsIgnoreCase(String.valueOf(r.get("status")))).count();
        long libres     = rows.stream().filter(r -> "Libre".equalsIgnoreCase(String.valueOf(r.get("status")))).count();

        // Filtros disponibles (tipos, ciudades, estados únicos)
        Set<String> tiposSet     = new LinkedHashSet<>();
        Set<String> ciudadesSet  = new LinkedHashSet<>();
        Set<String> estadosSet   = new LinkedHashSet<>();
        for (var r : rows) {
            String t = strOrNull(r.get("type"));     if (t != null && !t.isBlank()) tiposSet.add(t);
            String c = strOrNull(r.get("city"));     if (c != null && !c.isBlank()) ciudadesSet.add(c);
            String s = strOrNull(r.get("status"));   if (s != null && !s.isBlank()) estadosSet.add(s);
        }

        model.addAttribute("page", "Cartera de propiedades");
        model.addAttribute("rows", rows);
        model.addAttribute("total", total);
        model.addAttribute("publicadas", publicadas);
        model.addAttribute("ocupadas", ocupadas);
        model.addAttribute("libres", libres);
        model.addAttribute("tipos", tiposSet);
        model.addAttribute("ciudades", ciudadesSet);
        model.addAttribute("estados", estadosSet);
        model.addAttribute("canWrite", permissions.canWrite(role, "propiedades"));
        return "propiedades_grid";
    }

    private static String strOrNull(Object o) { return o == null ? null : String.valueOf(o); }
}
