package com.inmobilia.controller;

import com.inmobilia.util.PaisActual;

import com.inmobilia.service.PermissionService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Búsqueda global: busca un texto en propiedades, inquilinos, leads, contratos, facturas.
 */
@Controller
@RequiredArgsConstructor
public class SearchController {

    private final SupabaseService supabase;
    private final PermissionService permissions;

    @GetMapping("/search")
    public String searchPage(@RequestParam(required = false) String q,
                             HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Búsqueda global");
        model.addAttribute("q", q != null ? q : "");
        if (q != null && !q.isBlank()) {
            model.addAttribute("results", search(q, SessionUtils.uid(session), SessionUtils.role(session)));
        }
        return "search";
    }

    @GetMapping("/api/search")
    @ResponseBody
    public Map<String, Object> apiSearch(@RequestParam String q, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return Map.of("ok", false);
        if (q == null || q.length() < 2) return Map.of("ok", true, "results", List.of());
        return Map.of("ok", true, "results", search(q, SessionUtils.uid(session), SessionUtils.role(session)));
    }

    /** Solo busca en las secciones que esta persona puede ver (antes un técnico encontraba DNIs y facturas). */
    private List<Map<String, Object>> search(String q, String orgId, String role) {
        String qLower = q.toLowerCase().trim();
        List<Map<String, Object>> hits = new ArrayList<>();

        // Propiedades
        if (permissions.canRead(role, "propiedades")) try {
            for (var p : supabase.query("propiedades", orgId)) {
                String hay = (str(p.get("name")) + " " + str(p.get("address")) + " "
                        + str(p.get("city")) + " " + str(p.get("reference"))).toLowerCase();
                if (hay.contains(qLower)) {
                    hits.add(hit("propiedad", "🏠",
                            str(p.get("name")),
                            str(p.get("address")) + " · " + str(p.get("city")),
                            "/entity/propiedad/" + p.get("id")));
                }
            }
        } catch (Exception ignored) {}

        // Inquilinos
        if (permissions.canRead(role, "inquilinos")) try {
            for (var i : supabase.query("inquilinos", orgId)) {
                String hay = (str(i.get("name")) + " " + str(i.get("email")) + " "
                        + str(i.get("phone")) + " " + str(i.get("id_number"))).toLowerCase();
                if (hay.contains(qLower)) {
                    hits.add(hit("inquilino", "👤",
                            str(i.get("name")),
                            str(i.get("email")) + " · " + str(i.get("property_name")),
                            "/entity/inquilino/" + i.get("id")));
                }
            }
        } catch (Exception ignored) {}

        // Leads
        if (permissions.canRead(role, "leads")) try {
            for (var l : supabase.query("leads", orgId)) {
                String hay = (str(l.get("nombre")) + " " + str(l.get("email")) + " "
                        + str(l.get("telefono")) + " " + str(l.get("interes"))).toLowerCase();
                if (hay.contains(qLower)) {
                    hits.add(hit("lead", "💼",
                            str(l.get("nombre")),
                            str(l.get("interes")) + " · " + str(l.get("etapa")),
                            "/entity/lead/" + l.get("id")));
                }
            }
        } catch (Exception ignored) {}

        // Contratos
        if (permissions.canRead(role, "contratos")) try {
            for (var c : supabase.query("contratos", orgId)) {
                String hay = (str(c.get("property_name")) + " " + str(c.get("tenant_name"))).toLowerCase();
                if (hay.contains(qLower)) {
                    hits.add(hit("contrato", "📄",
                            str(c.get("tenant_name")) + " — " + str(c.get("property_name")),
                            "Renta " + str(c.get("rent")) + (" " + PaisActual.moneda() + "/mes"),
                            "/crud/contratos/" + c.get("id") + "/edit"));
                }
            }
        } catch (Exception ignored) {}

        // Facturas
        if (permissions.canRead(role, "transacciones")) try {
            for (var f : supabase.query("invoices", orgId)) {
                String hay = (str(f.get("full_number")) + " " + str(f.get("cliente_nombre")) + " "
                        + str(f.get("concepto"))).toLowerCase();
                if (hay.contains(qLower)) {
                    hits.add(hit("factura", "🧾",
                            str(f.get("full_number")) + " — " + str(f.get("cliente_nombre")),
                            str(f.get("total")) + (" " + PaisActual.moneda() + " · ") + str(f.get("status")),
                            "/invoices/" + f.get("id")));
                }
            }
        } catch (Exception ignored) {}

        return hits;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static Map<String, Object> hit(String type, String icon, String title, String subtitle, String url) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("icon", icon);
        m.put("title", title);
        m.put("subtitle", subtitle);
        m.put("url", url);
        return m;
    }
}
