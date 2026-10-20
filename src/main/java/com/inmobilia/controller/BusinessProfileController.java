package com.inmobilia.controller;

import com.inmobilia.service.AvisoCuentaBancariaService;
import com.inmobilia.service.PermissionService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuración del negocio: datos fiscales, logo, branding, IBAN.
 * Los datos guardados aquí se usan automáticamente en facturas, recibos, contratos y emails.
 * Sección "empresa": la ve todo el equipo y la modifican admin, director y contable (el
 * guardián de sesión bloquea el POST al resto). Cada cambio de IBAN avisa al titular.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class BusinessProfileController {

    private final SupabaseService supabase;
    private final PermissionService permissions;
    private final AvisoCuentaBancariaService avisoCuenta;

    @GetMapping("/business")
    public String view(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String orgId = SessionUtils.uid(session);
        Map<String, Object> profile = getOrCreate(orgId);
        model.addAttribute("page", "Mi negocio");
        model.addAttribute("profile", profile);
        model.addAttribute("puedeEditar", permissions.canWrite(SessionUtils.role(session), "empresa"));
        return "business_profile";
    }

    @PostMapping("/business")
    public String save(HttpServletRequest req, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (!permissions.canWrite(SessionUtils.role(session), "empresa")) return "redirect:/business";
        String orgId = SessionUtils.uid(session);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("org_id", orgId);
        for (String f : List.of("legal_name","tax_id","address","city","postal_code","province","country",
                "contact_name","contact_email","contact_phone","website","logo_url","brand_color",
                "business_hours","notes_invoice","receipt_footer")) {
            String v = req.getParameter(f);
            if (v != null) data.put(f, v.isBlank() ? SupabaseService.VACIAR : v); // vaciado = se borra
        }
        String iban = AvisoCuentaBancariaService.normalizar(req.getParameter("iban"));
        if (!iban.isEmpty() && !AvisoCuentaBancariaService.cuentaValida(iban)) {
            SessionUtils.flash(session, "La cuenta bancaria no es válida. Revísala: no se ha guardado ningún cambio.");
            return "redirect:/business";
        }
        if (req.getParameter("iban") != null) data.put("iban", iban); // "" = quitarlo
        data.put("updated_at", OffsetDateTime.now().toString());

        var existing = supabase.findBy("business_profile", Map.of("org_id", orgId));
        Object ibanAntes = existing.isEmpty() ? null : existing.get(0).get("iban");
        boolean ok;
        if (existing.isEmpty()) {
            Map<String, Object> creado = supabase.insert("business_profile", data);
            ok = creado != null && !creado.isEmpty();
        } else {
            // La tabla va por org_id (no tiene user_id): el id ya sale filtrado por esta inmobiliaria.
            // Antes se filtraba por user_id y PostgREST rechazaba la actualización sin decir nada.
            ok = supabase.update("business_profile", String.valueOf(existing.get(0).get("id")), data, null);
        }
        if (!ok) {
            SessionUtils.flash(session, "No se pudieron guardar los datos del negocio. Inténtalo de nuevo.");
            return "redirect:/business";
        }

        boolean cambioCuenta = req.getParameter("iban") != null && avisoCuenta.siCambia(req, session,
                "Mi negocio (IBAN de facturas y recibos)", "business_profile", ibanAntes, iban);
        SessionUtils.flash(session, cambioCuenta
                ? "Datos del negocio guardados. Hemos avisado por email al titular de la cuenta del cambio de IBAN."
                : "Datos del negocio guardados.");
        return "redirect:/business";
    }

    private Map<String, Object> getOrCreate(String orgId) {
        try {
            var rows = supabase.findBy("business_profile", Map.of("org_id", orgId));
            if (!rows.isEmpty()) return rows.get(0);
        } catch (Exception ignored) {}
        return new LinkedHashMap<>();
    }
}
