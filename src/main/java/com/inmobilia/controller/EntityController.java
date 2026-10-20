package com.inmobilia.controller;

import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.*;

/**
 * Vista 360º de las entidades principales (inquilino, propiedad).
 * Recopila toda la informacion relacionada y la presenta con sub-pestanas.
 */
@Controller
@RequestMapping("/entity")
@RequiredArgsConstructor
@Slf4j
public class EntityController {

    private final SupabaseService supabase;

    // ─────────────────────────────────────────────────────────────────
    //   INQUILINO 360º
    // ─────────────────────────────────────────────────────────────────
    @GetMapping("/inquilino/{id}")
    public String inquilino(@PathVariable String id, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.uid(session);

        var rows = supabase.findBy("inquilinos", Map.of("id", id, "user_id", uid));
        if (rows.isEmpty()) return "redirect:/crud/inquilinos";
        Map<String, Object> tenant = rows.get(0);
        String tenantName = String.valueOf(tenant.getOrDefault("name", ""));

        // Contratos del inquilino (match por tenant_name)
        var contratos = supabase.query("contratos", uid).stream()
                .filter(c -> tenantName.equalsIgnoreCase(String.valueOf(c.getOrDefault("tenant_name", ""))))
                .toList();

        // Reservas del inquilino (match por client_name o client_email)
        String tenantEmail = String.valueOf(tenant.getOrDefault("email", ""));
        var reservas = supabase.query("reservas", uid).stream()
                .filter(r -> tenantName.equalsIgnoreCase(String.valueOf(r.getOrDefault("client_name", "")))
                          || (!tenantEmail.isBlank() && tenantEmail.equalsIgnoreCase(String.valueOf(r.getOrDefault("client_email", "")))))
                .toList();

        // Transacciones donde aparezca su nombre o su propiedad
        String propName = String.valueOf(tenant.getOrDefault("property_name", ""));
        var transacciones = supabase.query("transacciones", uid).stream()
                .filter(t -> propName.equalsIgnoreCase(String.valueOf(t.getOrDefault("property_name", "")))
                          || tenantName.equalsIgnoreCase(String.valueOf(t.getOrDefault("description", ""))))
                .toList();

        // Documentos relacionados (match por property_name)
        var documentos = supabase.query("documentos", uid).stream()
                .filter(d -> propName.equalsIgnoreCase(String.valueOf(d.getOrDefault("property_name", ""))))
                .toList();

        // Eventos relacionados
        var eventos = supabase.query("eventos", uid).stream()
                .filter(e -> propName.equalsIgnoreCase(String.valueOf(e.getOrDefault("property_name", "")))
                          || String.valueOf(e.getOrDefault("attendees", "")).toLowerCase().contains(tenantName.toLowerCase()))
                .toList();

        // KPIs del inquilino
        double rentTotal = 0;
        for (var c : contratos) {
            try { rentTotal += Double.parseDouble(String.valueOf(c.getOrDefault("rent", "0"))); } catch (Exception ignored) {}
        }
        double pagosTotal = 0;
        long pagosCount = transacciones.stream().filter(t -> "Ingreso".equalsIgnoreCase(String.valueOf(t.getOrDefault("type", "")))).count();
        for (var t : transacciones) {
            if ("Ingreso".equalsIgnoreCase(String.valueOf(t.getOrDefault("type", "")))) {
                try { pagosTotal += Double.parseDouble(String.valueOf(t.getOrDefault("amount", "0"))); } catch (Exception ignored) {}
            }
        }

        model.addAttribute("page", "Inquilino · " + tenantName);
        model.addAttribute("tenant", tenant);
        model.addAttribute("contratos", contratos);
        model.addAttribute("reservas", reservas);
        model.addAttribute("transacciones", transacciones);
        model.addAttribute("documentos", documentos);
        model.addAttribute("eventos", eventos);
        model.addAttribute("rentTotal", rentTotal);
        model.addAttribute("pagosTotal", pagosTotal);
        model.addAttribute("pagosCount", pagosCount);
        return "entity/inquilino";
    }

    // ─────────────────────────────────────────────────────────────────
    //   PROPIEDAD 360º
    // ─────────────────────────────────────────────────────────────────
    @GetMapping("/propiedad/{id}")
    public String propiedad(@PathVariable String id, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.uid(session);

        var rows = supabase.findBy("propiedades", Map.of("id", id, "user_id", uid));
        if (rows.isEmpty()) return "redirect:/crud/propiedades";
        Map<String, Object> prop = rows.get(0);
        String propName = String.valueOf(prop.getOrDefault("name", ""));

        // Contratos de la propiedad
        var contratos = supabase.query("contratos", uid).stream()
                .filter(c -> propName.equalsIgnoreCase(String.valueOf(c.getOrDefault("property_name", ""))))
                .toList();

        // Inquilinos asociados
        var inquilinos = supabase.query("inquilinos", uid).stream()
                .filter(i -> propName.equalsIgnoreCase(String.valueOf(i.getOrDefault("property_name", ""))))
                .toList();

        // Transacciones de la propiedad
        var transacciones = supabase.query("transacciones", uid).stream()
                .filter(t -> propName.equalsIgnoreCase(String.valueOf(t.getOrDefault("property_name", ""))))
                .toList();

        // Mantenimiento
        var mantenimiento = supabase.query("mantenimiento", uid).stream()
                .filter(m -> propName.equalsIgnoreCase(String.valueOf(m.getOrDefault("property_name", ""))))
                .toList();

        // Seguros
        var seguros = supabase.query("seguros", uid).stream()
                .filter(s -> propName.equalsIgnoreCase(String.valueOf(s.getOrDefault("property_name", ""))))
                .toList();

        // Documentos
        var documentos = supabase.query("documentos", uid).stream()
                .filter(d -> propName.equalsIgnoreCase(String.valueOf(d.getOrDefault("property_name", ""))))
                .toList();

        // Eventos
        var eventos = supabase.query("eventos", uid).stream()
                .filter(e -> propName.equalsIgnoreCase(String.valueOf(e.getOrDefault("property_name", ""))))
                .toList();

        // Reservas (visitas)
        var reservas = supabase.query("reservas", uid).stream()
                .filter(r -> propName.equalsIgnoreCase(String.valueOf(r.getOrDefault("property_name", ""))))
                .toList();

        // KPIs de la propiedad
        double ingresos = 0, gastos = 0;
        for (var t : transacciones) {
            try {
                double a = Double.parseDouble(String.valueOf(t.getOrDefault("amount", "0")));
                if ("Ingreso".equalsIgnoreCase(String.valueOf(t.getOrDefault("type", "")))) ingresos += a;
                else gastos += a;
            } catch (Exception ignored) {}
        }
        double cashflow = ingresos - gastos;

        double costeMantenimiento = 0;
        for (var m : mantenimiento) {
            try { costeMantenimiento += Double.parseDouble(String.valueOf(m.getOrDefault("cost", "0"))); } catch (Exception ignored) {}
        }

        long mantenimientoAbierto = mantenimiento.stream()
                .filter(m -> !"Completado".equalsIgnoreCase(String.valueOf(m.getOrDefault("status", "")))
                          && !"Cerrado".equalsIgnoreCase(String.valueOf(m.getOrDefault("status", ""))))
                .count();

        model.addAttribute("page", "Propiedad · " + propName);
        model.addAttribute("prop", prop);
        model.addAttribute("contratos", contratos);
        model.addAttribute("inquilinos", inquilinos);
        model.addAttribute("transacciones", transacciones);
        model.addAttribute("mantenimiento", mantenimiento);
        model.addAttribute("seguros", seguros);
        model.addAttribute("documentos", documentos);
        model.addAttribute("eventos", eventos);
        model.addAttribute("reservas", reservas);
        model.addAttribute("ingresos", ingresos);
        model.addAttribute("gastos", gastos);
        model.addAttribute("cashflow", cashflow);
        model.addAttribute("costeMantenimiento", costeMantenimiento);
        model.addAttribute("mantenimientoAbierto", mantenimientoAbierto);
        return "entity/propiedad";
    }

    // ─────────────────────────────────────────────────────────────────
    //   LEAD 360º
    // ─────────────────────────────────────────────────────────────────
    @GetMapping("/lead/{id}")
    public String lead(@PathVariable String id, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.uid(session);

        var rows = supabase.findBy("leads", Map.of("id", id, "user_id", uid));
        if (rows.isEmpty()) return "redirect:/crud/leads";
        Map<String, Object> lead = rows.get(0);
        String leadName = String.valueOf(lead.getOrDefault("nombre", ""));
        String leadEmail = String.valueOf(lead.getOrDefault("email", ""));

        // Visitas relacionadas
        var visitas = supabase.query("reservas", uid).stream()
                .filter(r -> leadName.equalsIgnoreCase(String.valueOf(r.getOrDefault("client_name", "")))
                          || (!leadEmail.isBlank() && leadEmail.equalsIgnoreCase(String.valueOf(r.getOrDefault("client_email", "")))))
                .toList();

        // Eventos relacionados (donde el lead aparezca en attendees)
        var eventos = supabase.query("eventos", uid).stream()
                .filter(e -> String.valueOf(e.getOrDefault("attendees", "")).toLowerCase().contains(leadName.toLowerCase()))
                .toList();

        // Oportunidades relacionadas (match por contact)
        var oportunidades = supabase.query("oportunidades", uid).stream()
                .filter(o -> String.valueOf(o.getOrDefault("contact", "")).toLowerCase().contains(leadName.toLowerCase()))
                .toList();

        model.addAttribute("page", "Lead · " + leadName);
        model.addAttribute("lead", lead);
        model.addAttribute("visitas", visitas);
        model.addAttribute("eventos", eventos);
        model.addAttribute("oportunidades", oportunidades);
        return "entity/lead";
    }
}
