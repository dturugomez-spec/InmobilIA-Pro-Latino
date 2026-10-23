package com.inmobilia.controller;

import com.inmobilia.service.AIService;
import com.inmobilia.service.PlanService;
import com.inmobilia.service.PlazasService;
import com.inmobilia.service.StripeService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
@Slf4j
public class BillingController {

    private final SupabaseService supabase;
    private final StripeService stripeService;
    private final PlanService planService;
    private final AIService aiService;
    private final PlazasService plazas;

    /** Página de planes (upgrade desde dentro de la app). */
    @GetMapping("/upgrade")
    public String upgrade(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        Map<String, Object> user = currentUser(session);
        model.addAttribute("page", "Mejorar plan");
        model.addAttribute("planStatus", planService.statusFor(user));
        model.addAttribute("plans", PlanService.PLANS);
        model.addAttribute("stripeConfigured", stripeService.isConfigured());
        // Estado de cobro para pintar bien los botones (sin dobles suscripciones)
        model.addAttribute("requierePago", planService.requierePago(SessionUtils.uid(session)));
        model.addAttribute("suscrito", planService.tieneSuscripcionActiva(user));
        model.addAttribute("trialDisponible", !PlanService.haSuscritoAlgunaVez(user));
        model.addAttribute("planActual", user == null ? "" : String.valueOf(user.getOrDefault("plan", "")).toLowerCase());
        model.addAttribute("usuarios", plazas.contar(SessionUtils.uid(session)));
        return "billing/upgrade";
    }

    /** Página /billing: ver estado de suscripción + portal de cliente. */
    @GetMapping("/billing")
    public String billing(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        Map<String, Object> user = currentUser(session);
        model.addAttribute("page", "Facturación y plan");
        model.addAttribute("user", user);
        model.addAttribute("planStatus", planService.statusFor(user));
        int usuarios = plazas.contar(SessionUtils.uid(session));
        String plan = user == null ? "" : String.valueOf(user.getOrDefault("plan", ""));
        model.addAttribute("usuarios", usuarios);
        model.addAttribute("precioUsuario", PlanService.precioMensual(plan, 1));
        model.addAttribute("precioTotal", PlanService.precioMensual(plan, usuarios));
        model.addAttribute("creditosMes", PlanService.creditosMensuales(plan, usuarios));
        return "billing/index";
    }

    /** Página de compra de packs de créditos de IA. */
    @GetMapping("/creditos")
    public String creditos(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Créditos de IA");
        model.addAttribute("saldo", aiService.getCredits(SessionUtils.uid(session)));
        model.addAttribute("packs", stripeService.creditPacks());
        model.addAttribute("stripeConfigured", stripeService.isConfigured());
        return "billing/creditos";
    }

    /** Comprar un pack de créditos (pago único vía Stripe Checkout). */
    @PostMapping("/billing/credits/{pack}")
    public String buyCredits(@PathVariable String pack, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (!stripeService.isConfigured()) {
            SessionUtils.flash(session, "Stripe no está configurado. Contacta con soporte.");
            return "redirect:/creditos";
        }
        Map<String, Object> user = currentUser(session);
        try {
            String url = stripeService.createCreditCheckoutSession(user, pack);
            return "redirect:" + url;
        } catch (Exception e) {
            log.error("Error creando checkout de créditos: {}", e.getMessage());
            SessionUtils.flash(session, "ERROR: " + e.getMessage());
            return "redirect:/creditos";
        }
    }

    /** Crear sesión de Stripe Checkout y redirigir. */
    @PostMapping("/billing/checkout/{plan}")
    public String checkout(@PathVariable String plan, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (!stripeService.isConfigured()) {
            SessionUtils.flash(session, "Stripe no está configurado. Contacta con soporte.");
            return "redirect:/upgrade";
        }
        Map<String, Object> user = currentUser(session);
        // Nunca crear una segunda suscripción: se cobrarían las dos.
        if (planService.tieneSuscripcionActiva(user)) {
            SessionUtils.flash(session, "Ya tienes una suscripción activa. Para cambiar de plan, de tarjeta o cancelar, usa el portal de cliente.");
            return "redirect:/billing";
        }
        try {
            String url = stripeService.createCheckoutSession(user, plan, plazas.contar(SessionUtils.uid(session)));
            return "redirect:" + url;
        } catch (Exception e) {
            log.error("Error creando checkout: {}", e.getMessage());
            SessionUtils.flash(session, "ERROR: " + e.getMessage());
            return "redirect:/upgrade";
        }
    }

    /** Abrir portal de cliente Stripe (cambiar tarjeta, cancelar). */
    @PostMapping("/billing/portal")
    public String portal(HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        Map<String, Object> user = currentUser(session);
        Object cusId = user.get("stripe_customer_id");
        if (cusId == null || String.valueOf(cusId).isBlank() || "null".equals(String.valueOf(cusId))) {
            SessionUtils.flash(session, "No tienes una suscripción activa. Suscríbete primero.");
            return "redirect:/upgrade";
        }
        try {
            String url = stripeService.createPortalSession(String.valueOf(cusId));
            return "redirect:" + url;
        } catch (Exception e) {
            log.error("Error abriendo portal: {}", e.getMessage());
            SessionUtils.flash(session, "ERROR: " + e.getMessage());
            return "redirect:/billing";
        }
    }

    /** Tras checkout exitoso. */
    @GetMapping("/billing/success")
    public String success(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Suscripción activa");
        return "billing/success";
    }

    /** Tras cancelación de checkout. */
    @GetMapping("/billing/cancel")
    public String cancel(Model model) {
        model.addAttribute("page", "Suscripción cancelada");
        return "billing/cancel";
    }

    /**
     * La cuenta que paga es la de la inmobiliaria (su titular), no la de quien ha iniciado sesión:
     * antes, si pagaba un empleado, la suscripción y los créditos quedaban en su usuario y la
     * inmobiliaria seguía bloqueada.
     */
    private Map<String, Object> currentUser(HttpSession session) {
        String uid = SessionUtils.uid(session);
        List<Map<String, Object>> r = supabase.findBy("users", Map.of("id", uid));
        return r.isEmpty() ? null : r.get(0);
    }
}
