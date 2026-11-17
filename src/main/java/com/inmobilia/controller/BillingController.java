package com.inmobilia.controller;

import com.inmobilia.service.AIService;
import com.inmobilia.service.PlanService;
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
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
@RequiredArgsConstructor
@Slf4j
public class BillingController {

    private final SupabaseService supabase;
    private final StripeService stripeService;
    private final PlanService planService;
    private final AIService aiService;
    private final com.inmobilia.service.CobroManualService cobroManual;

    /** Mientras se cobra por transferencia o en efectivo, nada se paga con tarjeta en la app. */
    private static final String SIN_PAGO_ONLINE = "Ahora el pago es por transferencia o en efectivo, con factura. "
            + "Pídenos el plan o el extra desde «Plan y facturación» y te enviamos los datos.";

    private boolean sinPagoOnline(HttpSession session) {
        if (cobroManual.cobroOnline()) return false;
        SessionUtils.flash(session, SIN_PAGO_ONLINE);
        return true;
    }

    /** Página de planes (upgrade desde dentro de la app). */
    @GetMapping("/upgrade")
    public String upgrade(@RequestParam(required = false) String herramienta,
                          @RequestParam(required = false) String limite,
                          HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        Map<String, Object> user = currentUser(session);
        String org = SessionUtils.uid(session);
        model.addAttribute("page", "Planes y precios");
        model.addAttribute("planStatus", planService.statusFor(user));
        model.addAttribute("plans", PlanService.PLANS);
        model.addAttribute("modulos", PlanService.MODULOS);
        model.addAttribute("precioActivoExtra", PlanService.PRECIO_ACTIVO_EXTRA);
        model.addAttribute("precioSociedadExtra", PlanService.PRECIO_SOCIEDAD_EXTRA);
        model.addAttribute("stripeConfigured", stripeService.isConfigured());
        model.addAttribute("cobroOnline", cobroManual.cobroOnline());
        model.addAttribute("datosCobro", cobroManual.datosCobro());
        model.addAttribute("suscrito", planService.tieneSuscripcionActiva(user));
        model.addAttribute("planActual", planService.planEfectivo(user));
        model.addAttribute("activosUsados", planService.activosUsados(org));
        model.addAttribute("usuarios", planService.usuariosActivos(org));
        model.addAttribute("anual", "anual".equals(user == null ? null : String.valueOf(user.get("periodo_facturacion"))));
        // Viene de una herramienta que su plan no incluye: decir qué plan (o módulo) la trae
        PlanService.Herramienta h = PlanService.HERRAMIENTAS.stream()
                .filter(x -> x.nombre().equals(herramienta)).findFirst().orElse(null);
        if (h != null) {
            model.addAttribute("herramienta", h.nombre());
            model.addAttribute("herramientaPlan", PlanService.PLANS.get(h.planMinimo()).label());
            model.addAttribute("herramientaModulo", h.modulo() == null ? null : PlanService.modulo(h.modulo()));
        }
        boolean titular = org != null && org.equals(SessionUtils.realUid(session));
        model.addAttribute("limiteUsuarios", "usuarios".equals(limite) && !titular);
        return "billing/upgrade";
    }

    /** Página /billing: plan, uso, extras y portal de cliente. */
    @GetMapping("/billing")
    public String billing(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        Map<String, Object> user = currentUser(session);
        String org = SessionUtils.uid(session);
        String plan = planService.planEfectivo(user);
        model.addAttribute("page", "Facturación y plan");
        model.addAttribute("user", user);
        model.addAttribute("planStatus", planService.statusFor(user));
        model.addAttribute("activosUsados", planService.activosUsados(org));
        model.addAttribute("usuarios", planService.usuariosActivos(org));
        model.addAttribute("creditosMes", PlanService.creditosMensuales(plan));
        model.addAttribute("precioActivoExtra", PlanService.PRECIO_ACTIVO_EXTRA);
        model.addAttribute("modulos", PlanService.MODULOS);
        // Extras: sobre una suscripción de Stripe a Pro o Business
        boolean suscrito = planService.tieneSuscripcionActiva(user) && PlanService.tieneSuscripcionStripe(user);
        model.addAttribute("puedeExtras", suscrito && PlanService.PLANES_DE_PAGO.contains(plan));
        model.addAttribute("modulosIncluidos", PlanService.alcanza(plan, PlanService.BUSINESS));
        model.addAttribute("precioSociedadExtra", PlanService.PRECIO_SOCIEDAD_EXTRA);
        model.addAttribute("sociedadesBusiness", PlanService.SOCIEDADES_BUSINESS);
        model.addAttribute("planActual", plan);
        model.addAttribute("plans", PlanService.PLANS);
        model.addAttribute("cobroOnline", cobroManual.cobroOnline());
        model.addAttribute("datosCobro", cobroManual.datosCobro());
        // Cobros recibidos de esta cuenta y peticiones aún sin atender
        List<Map<String, Object>> misCobros = supabase.selectFilter("cobros_plataforma",
                "select=id,fecha,concepto,total,metodo,periodo_desde,periodo_hasta,factura_id&cliente_id=eq." + org
                + "&order=fecha.desc&limit=50");
        for (Map<String, Object> c : misCobros) c.put("fecha_txt", com.inmobilia.service.CobroManualService.dmy(c.get("fecha")));
        model.addAttribute("misCobros", misCobros);
        model.addAttribute("misPeticiones", supabase.selectFilter("peticiones_plan",
                "select=id,plan,periodo,created_at&cliente_id=eq." + org + "&estado=eq.pendiente&order=created_at.desc&limit=10"));
        return "billing/index";
    }

    /**
     * Pedir un plan, extras o créditos: no se paga en la web. Nos llega por correo; activamos el plan
     * y enviamos la factura con los datos para pagar por transferencia o en efectivo.
     */
    @PostMapping("/billing/solicitar")
    public String solicitar(@RequestParam(required = false) String plan,
                            @RequestParam(required = false) String periodo,
                            @RequestParam(required = false) Integer activosExtra,
                            @RequestParam(required = false) Integer sociedadesExtra,
                            @RequestParam(required = false) List<String> modulos,
                            @RequestParam(defaultValue = "0") int packsCreditos,
                            @RequestParam(required = false) String mensaje,
                            @RequestParam(defaultValue = "/billing") String volver,
                            HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String destino = Set.of("/billing", "/upgrade", "/creditos").contains(volver) ? volver : "/billing";
        String error = cobroManual.pedir(SessionUtils.uid(session), SessionUtils.realUid(session),
                new com.inmobilia.service.CobroManualService.Peticion(plan, periodo, activosExtra, sociedadesExtra,
                        modulos, packsCreditos, mensaje));
        SessionUtils.flash(session, error != null ? error
                : "Petición enviada. Te escribimos en menos de 24 horas laborables con la factura y los datos para pagar "
                  + "por transferencia o en efectivo. Lo activamos en cuanto recibamos el pago.");
        return "redirect:" + (error != null ? destino : "/billing");
    }

    /**
     * Factura que la plataforma ha emitido a esta cuenta por uno de sus cobros. Solo la de un cobro
     * suyo: el cobro lo apunta el propietario de la plataforma y la factura queda vinculada al emitirla.
     */
    @GetMapping("/billing/facturas/{cobroId}")
    public String miFactura(@PathVariable String cobroId, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (!cobroId.matches("[0-9a-fA-F-]{36}")) return "redirect:/billing";
        String org = SessionUtils.uid(session);
        List<Map<String, Object>> c = supabase.selectFilter("cobros_plataforma",
                "select=factura_id&id=eq." + cobroId + "&cliente_id=eq." + org + "&limit=1");
        Object facturaId = c.isEmpty() ? null : c.get(0).get("factura_id");
        if (facturaId == null || !String.valueOf(facturaId).matches("[0-9a-fA-F-]{36}")) {
            SessionUtils.flash(session, "Esa factura aún no está disponible.");
            return "redirect:/billing";
        }
        List<Map<String, Object>> f = supabase.selectFilter("invoices", "id=eq." + facturaId + "&limit=1");
        if (f.isEmpty()) return "redirect:/billing";
        Map<String, Object> inv = f.get(0);
        model.addAttribute("canWrite", false);
        model.addAttribute("volver", "/billing");
        model.addAttribute("page", "Factura " + inv.get("full_number"));
        model.addAttribute("inv", inv);
        return "invoice_view";
    }

    /** Página de compra del pack de créditos de IA. */
    @GetMapping("/creditos")
    public String creditos(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        model.addAttribute("page", "Créditos de IA");
        model.addAttribute("saldo", aiService.getCredits(SessionUtils.uid(session)));
        model.addAttribute("packCreditos", PlanService.PACK_CREDITOS);
        model.addAttribute("packPrecio", PlanService.PRECIO_PACK_CREDITOS);
        model.addAttribute("stripeConfigured", stripeService.isConfigured());
        model.addAttribute("cobroOnline", cobroManual.cobroOnline());
        return "billing/creditos";
    }

    /** Comprar el pack de créditos (pago único vía Stripe Checkout). */
    @PostMapping("/billing/credits/{pack}")
    public String buyCredits(@PathVariable String pack, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (sinPagoOnline(session)) return "redirect:/creditos";
        if (!stripeService.isConfigured()) {
            SessionUtils.flash(session, "Stripe no está configurado. Contacta con soporte.");
            return "redirect:/creditos";
        }
        try {
            return "redirect:" + stripeService.createCreditCheckoutSession(currentUser(session));
        } catch (Exception e) {
            log.error("Error creando checkout de créditos: {}", e.getMessage());
            SessionUtils.flash(session, "ERROR: " + e.getMessage());
            return "redirect:/creditos";
        }
    }

    /** Suscribirse a Pro o Business, mensual o anual (Stripe Checkout). */
    @PostMapping("/billing/checkout/{plan}")
    public String checkout(@PathVariable String plan,
                           @RequestParam(defaultValue = "mensual") String periodo,
                           HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (sinPagoOnline(session)) return "redirect:/upgrade";
        if (!stripeService.isConfigured()) {
            SessionUtils.flash(session, "Stripe no está configurado. Contacta con soporte.");
            return "redirect:/upgrade";
        }
        if (!PlanService.PLANES_DE_PAGO.contains(plan)) {
            SessionUtils.flash(session, "Enterprise se contrata a medida: escríbenos a inmobilia.support@gmail.com.");
            return "redirect:/upgrade";
        }
        Map<String, Object> user = currentUser(session);
        // Nunca crear una segunda suscripción: se cobrarían las dos.
        if (PlanService.tieneSuscripcionStripe(user) && planService.tieneSuscripcionActiva(user)) {
            SessionUtils.flash(session, "Ya tienes una suscripción activa. Para cambiar de plan, de tarjeta o cancelar, usa el portal de cliente.");
            return "redirect:/billing";
        }
        try {
            return "redirect:" + stripeService.createCheckoutSession(user, plan, "anual".equals(periodo));
        } catch (Exception e) {
            log.error("Error creando checkout: {}", e.getMessage());
            SessionUtils.flash(session, "ERROR: " + e.getMessage());
            return "redirect:/upgrade";
        }
    }

    /** Activos adicionales (4 USD/mes cada uno) sobre la suscripción. 0 los quita. */
    @PostMapping("/billing/extras/activos")
    public String activosExtra(@RequestParam int cantidad, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (sinPagoOnline(session)) return "redirect:/billing";
        Map<String, Object> user = currentUser(session);
        String sub = suscripcionParaExtras(user, session);
        if (sub == null) return "redirect:/billing";
        int n = Math.max(0, Math.min(1000, cantidad));
        String org = SessionUtils.uid(session);
        // No quitar huecos que ya están ocupados por activos
        int base = planService.limitsFor(user).maxProperties();
        if (n < PlanService.activosExtra(user) && planService.activosUsados(org) > base + n) {
            SessionUtils.flash(session, "Tienes " + planService.activosUsados(org) + " activos: necesitas al menos "
                    + (planService.activosUsados(org) - base) + " adicionales. Borra activos antes de quitar huecos.");
            return "redirect:/billing";
        }
        try {
            stripeService.ajustarExtra(sub, StripeService.ACTIVO_EXTRA, n);
            supabase.update("users", org, Map.of("activos_extra", n), org);
            SessionUtils.flash(session, n == 0 ? "Activos adicionales quitados."
                    : "Ahora tienes " + n + " activos adicionales (" + n * PlanService.PRECIO_ACTIVO_EXTRA
                      + " USD/mes + impuestos, prorrateado este mes).");
        } catch (Exception e) {
            log.error("Error ajustando activos adicionales: {}", e.getMessage());
            SessionUtils.flash(session, "ERROR: " + e.getMessage());
        }
        return "redirect:/billing";
    }

    /** Sociedades adicionales de la gestión multi-empresa (15 USD/mes cada una, sobre Business). */
    @PostMapping("/billing/extras/sociedades")
    public String sociedadesExtra(@RequestParam int cantidad, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (sinPagoOnline(session)) return "redirect:/billing";
        Map<String, Object> user = currentUser(session);
        if (!PlanService.BUSINESS.equals(planService.planEfectivo(user))) {
            SessionUtils.flash(session, "Las sociedades adicionales se añaden al plan Business (en Enterprise son ilimitadas).");
            return "redirect:/billing";
        }
        String sub = suscripcionParaExtras(user, session);
        if (sub == null) return "redirect:/billing";
        int n = Math.max(0, Math.min(100, cantidad));
        String org = SessionUtils.uid(session);
        int tiene = supabase.selectFilter("sociedades", "select=id&user_id=eq." + org + "&limit=500").size();
        if (tiene > PlanService.SOCIEDADES_BUSINESS + n) {
            SessionUtils.flash(session, "Tienes " + tiene + " sociedades: necesitas al menos " + (tiene - PlanService.SOCIEDADES_BUSINESS)
                    + " adicionales. Borra sociedades antes de quitar huecos.");
            return "redirect:/billing";
        }
        try {
            stripeService.ajustarExtra(sub, StripeService.SOCIEDAD_EXTRA, n);
            supabase.update("users", org, Map.of("sociedades_extra", n), org);
            SessionUtils.flash(session, n == 0 ? "Sociedades adicionales quitadas."
                    : "Ahora tienes " + n + " sociedades adicionales (" + n * PlanService.PRECIO_SOCIEDAD_EXTRA
                      + " USD/mes + impuestos, prorrateado este mes).");
        } catch (Exception e) {
            log.error("Error ajustando sociedades adicionales: {}", e.getMessage());
            SessionUtils.flash(session, "ERROR: " + e.getMessage());
        }
        return "redirect:/billing";
    }

    /** Añadir o quitar un módulo (solo en Pro: en Business ya vienen incluidos). */
    @PostMapping("/billing/modulos/{clave}")
    public String modulo(@PathVariable String clave, @RequestParam boolean activo, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (sinPagoOnline(session)) return "redirect:/billing";
        PlanService.Modulo m = PlanService.modulo(clave);
        Map<String, Object> user = currentUser(session);
        if (m == null) return "redirect:/billing";
        if (!PlanService.PRO.equals(planService.planEfectivo(user))) {
            SessionUtils.flash(session, "Los módulos se añaden al plan Pro. En Business ya vienen incluidos.");
            return "redirect:/billing";
        }
        String sub = suscripcionParaExtras(user, session);
        if (sub == null) return "redirect:/billing";
        String org = SessionUtils.uid(session);
        try {
            stripeService.ajustarExtra(sub, "modulo_" + m.clave(), activo ? 1 : 0);
            Set<String> modulos = new LinkedHashSet<>(PlanService.modulosComprados(user));
            if (activo) modulos.add(m.clave()); else modulos.remove(m.clave());
            supabase.update("users", org, Map.of("modulos", new ArrayList<>(modulos)), org);
            SessionUtils.flash(session, activo ? m.nombre() + " añadido (" + m.precioMensual() + " USD/mes + impuestos, prorrateado este mes)."
                                               : m.nombre() + " quitado.");
        } catch (Exception e) {
            log.error("Error con el módulo {}: {}", clave, e.getMessage());
            SessionUtils.flash(session, "ERROR: " + e.getMessage());
        }
        return "redirect:/billing";
    }

    /** Id de la suscripción de Stripe a la que añadir extras, o null (con el motivo en el flash). */
    private String suscripcionParaExtras(Map<String, Object> user, HttpSession session) {
        if (!stripeService.isConfigured()) {
            SessionUtils.flash(session, "Stripe no está configurado. Contacta con soporte.");
            return null;
        }
        if (!PlanService.tieneSuscripcionStripe(user) || !planService.tieneSuscripcionActiva(user)
                || !PlanService.PLANES_DE_PAGO.contains(planService.planEfectivo(user))) {
            SessionUtils.flash(session, "Los extras se añaden a una suscripción de Pro o Business. Elige primero tu plan.");
            return null;
        }
        return String.valueOf(user.get("stripe_subscription_id"));
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
