package com.inmobilia.controller;

import com.inmobilia.service.AIService;
import com.inmobilia.service.CobroManualService;
import com.inmobilia.service.PlanService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * Clientes y cobros: solo para los propietarios de la plataforma (ADMIN_EMAILS). Aquí se activa el
 * plan de cada cliente, se apuntan los cobros por transferencia o en efectivo y se abre la factura
 * ya rellena. Ver CobroManualService.
 */
@Controller
@RequiredArgsConstructor
public class AdminClientesController {

    private final SupabaseService supabase;
    private final PlanService planService;
    private final CobroManualService cobros;
    private final AIService aiService;
    private final com.inmobilia.service.Promociones promociones;

    private boolean propietario(HttpSession session) {
        return SessionUtils.isLoggedIn(session) && planService.esAdminPlataforma(SessionUtils.email(session));
    }

    @GetMapping("/admin/clientes")
    public String lista(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (!propietario(session)) return "redirect:/dashboard";

        List<Map<String, Object>> filas = new ArrayList<>();
        LocalDate hoy = LocalDate.now();
        Set<String> fundadores = new HashSet<>(com.inmobilia.service.Promociones.fundadores(supabase));
        for (Map<String, Object> u : supabase.selectFilter("users",
                "select=id,email,name,empresa,plan,plan_status,trial_ends_at,pagado_hasta,periodo_facturacion,stripe_subscription_id,created_at"
                + "&organization_id=is.null&order=created_at.desc&limit=2000")) {
            String email = String.valueOf(u.get("email"));
            if (PlanService.esCuentaDemo(email) || planService.esAdminPlataforma(email)) continue;
            Map<String, Object> f = new LinkedHashMap<>(u);
            f.put("nombre", CobroManualService.nombreCliente(u, null));
            f.put("planEfectivo", PlanService.PLANS.get(planService.planEfectivo(u)).label());
            f.put("estado", estado(u, hoy));
            f.put("fundador", fundadores.contains(String.valueOf(u.get("id"))));
            filas.add(f);
        }
        List<Map<String, Object>> peticiones = supabase.selectFilter("peticiones_plan",
                "estado=eq.pendiente&order=created_at.desc&limit=200");
        Map<String, String> nombres = new HashMap<>();
        filas.forEach(f -> nombres.put(String.valueOf(f.get("id")), String.valueOf(f.get("nombre"))));
        for (Map<String, Object> p : peticiones) {
            p.put("cliente", nombres.getOrDefault(String.valueOf(p.get("cliente_id")), "—"));
            p.put("resumen", resumen(p));
        }
        List<Map<String, Object>> ultimos = supabase.selectFilter("cobros_plataforma", "order=fecha.desc,created_at.desc&limit=30");
        double mes = 0, anio = 0;
        for (Map<String, Object> c : supabase.selectFilter("cobros_plataforma",
                "select=fecha,total&fecha=gte." + hoy.withDayOfYear(1) + "&limit=10000")) {
            double t = numero(c.get("total"));
            anio += t;
            if (String.valueOf(c.get("fecha")).startsWith(hoy.toString().substring(0, 7))) mes += t;
        }
        for (Map<String, Object> c : ultimos) c.put("cliente", nombres.getOrDefault(String.valueOf(c.get("cliente_id")), "—"));

        model.addAttribute("page", "Clientes y cobros");
        model.addAttribute("clientes", filas);
        model.addAttribute("peticiones", peticiones);
        model.addAttribute("ultimos", ultimos);
        model.addAttribute("cobradoMes", mes);
        model.addAttribute("cobradoAnio", anio);
        model.addAttribute("datosCobro", cobros.datosCobro());
        model.addAttribute("cobroOnline", cobros.cobroOnline());
        model.addAttribute("fundadoresUsados", fundadores.size());
        return "admin_clientes";
    }

    @GetMapping("/admin/clientes/{id}")
    public String ficha(@PathVariable String id, HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (!propietario(session)) return "redirect:/dashboard";
        Map<String, Object> u = cobros.cliente(id);
        if (u == null) {
            SessionUtils.flash(session, "Ese cliente no existe.");
            return "redirect:/admin/clientes";
        }
        Map<String, Object> negocio = cobros.negocio(id);
        boolean anual = "anual".equals(String.valueOf(u.get("periodo_facturacion")));
        Set<String> modulos = PlanService.modulosComprados(u);
        LocalDate hasta = PlanService.pagadoHasta(u);
        LocalDate desde = hasta == null || hasta.isBefore(LocalDate.now()) ? LocalDate.now() : hasta.plusDays(1);

        List<Map<String, Object>> peticiones = supabase.selectFilter("peticiones_plan",
                "cliente_id=eq." + id + "&order=created_at.desc&limit=50");
        for (Map<String, Object> p : peticiones) p.put("resumen", resumen(p));

        model.addAttribute("page", "Cliente · " + CobroManualService.nombreCliente(u, negocio));
        model.addAttribute("c", u);
        model.addAttribute("nombre", CobroManualService.nombreCliente(u, negocio));
        model.addAttribute("negocio", negocio == null ? Map.of() : negocio);
        model.addAttribute("estado", estado(u, LocalDate.now()));
        model.addAttribute("planActual", PlanService.normalizar(String.valueOf(u.get("plan"))));
        model.addAttribute("planEfectivo", PlanService.PLANS.get(planService.planEfectivo(u)).label());
        model.addAttribute("anual", anual);
        model.addAttribute("modulosCliente", modulos);
        model.addAttribute("activosExtra", PlanService.activosExtra(u));
        model.addAttribute("sociedadesExtra", PlanService.sociedadesExtra(u));
        model.addAttribute("pagadoHasta", hasta);
        model.addAttribute("activosUsados", planService.activosUsados(id));
        model.addAttribute("maxActivos", planService.maxActivos(u));
        model.addAttribute("usuarios", planService.usuariosActivos(id));
        model.addAttribute("creditos", aiService.getCredits(id));
        model.addAttribute("plans", PlanService.PLANS);
        model.addAttribute("modulos", PlanService.MODULOS);
        model.addAttribute("metodos", CobroManualService.METODOS);
        model.addAttribute("baseSugerida", PlanService.importeBase(String.valueOf(u.get("plan")), anual,
                PlanService.activosExtra(u), PlanService.sociedadesExtra(u), modulos));
        model.addAttribute("mesesSugeridos", anual ? PlanService.MESES_ANUAL : 1);
        model.addAttribute("periodoDesde", desde);
        model.addAttribute("pagosCliente", supabase.selectFilter("cobros_plataforma",
                "cliente_id=eq." + id + "&order=fecha.desc,created_at.desc&limit=100"));
        model.addAttribute("peticiones", peticiones);
        model.addAttribute("hoy", LocalDate.now());
        model.addAttribute("fundador", com.inmobilia.service.Promociones.esFundador(u));
        model.addAttribute("fundadoresUsados", com.inmobilia.service.Promociones.fundadores(supabase).size());
        model.addAttribute("yaSuscrito", PlanService.haSuscritoAlgunaVez(u));
        return "admin_cliente";
    }

    @PostMapping("/admin/clientes/{id}/plan")
    public String plan(@PathVariable String id,
                       @RequestParam String plan,
                       @RequestParam(defaultValue = "mensual") String periodo,
                       @RequestParam(required = false) String pagadoHasta,
                       @RequestParam(defaultValue = "0") int activosExtra,
                       @RequestParam(defaultValue = "0") int sociedadesExtra,
                       @RequestParam(required = false) List<String> modulos,
                       HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (!propietario(session)) return "redirect:/dashboard";
        LocalDate hasta = fecha(pagadoHasta);
        boolean ok = cobros.activarPlan(id, new CobroManualService.Plan(plan, periodo, hasta, activosExtra, sociedadesExtra, modulos));
        SessionUtils.flash(session, ok ? "Plan guardado." : "No se pudo guardar el plan. " + Objects.toString(supabase.lastError(), ""));
        return "redirect:/admin/clientes/" + id;
    }

    @PostMapping("/admin/clientes/{id}/cobros")
    public String cobro(@PathVariable String id,
                        @RequestParam(required = false) String fecha,
                        @RequestParam(defaultValue = "transferencia") String metodo,
                        @RequestParam(required = false) String concepto,
                        @RequestParam double base,
                        @RequestParam(defaultValue = "0") double ivaPct,
                        @RequestParam(required = false) String referencia,
                        @RequestParam(defaultValue = "0") int meses,
                        @RequestParam(defaultValue = "false") boolean factura,
                        HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (!propietario(session)) return "redirect:/dashboard";
        Map<String, Object> c = cobros.registrarCobro(id, SessionUtils.realUid(session),
                new CobroManualService.Cobro(fecha(fecha), metodo, concepto, base, ivaPct, referencia, meses));
        if (c == null) {
            SessionUtils.flash(session, "No se pudo apuntar el cobro. Revisa el importe y el método. " + Objects.toString(supabase.lastError(), ""));
            return "redirect:/admin/clientes/" + id;
        }
        if (factura) {
            cobros.prepararFactura(session, c);
            SessionUtils.flash(session, "Cobro apuntado. Revisa la factura y pulsa Guardar para emitirla.");
            return "redirect:/invoices/new";
        }
        SessionUtils.flash(session, "Cobro apuntado.");
        return "redirect:/admin/clientes/" + id;
    }

    @PostMapping("/admin/clientes/{id}/cobros/{cobroId}/factura")
    public String factura(@PathVariable String id, @PathVariable String cobroId, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (!propietario(session)) return "redirect:/dashboard";
        if (!cobroId.matches("[0-9a-fA-F-]{36}")) return "redirect:/admin/clientes/" + id;
        List<Map<String, Object>> r = supabase.selectFilter("cobros_plataforma", "id=eq." + cobroId + "&cliente_id=eq." + id);
        if (r.isEmpty()) return "redirect:/admin/clientes/" + id;
        cobros.prepararFactura(session, r.get(0));
        SessionUtils.flash(session, "Revisa la factura y pulsa Guardar para emitirla.");
        return "redirect:/invoices/new";
    }

    /** Borrar un cobro apuntado por error (si aún no tiene factura). No cambia la fecha de pagado. */
    @PostMapping("/admin/clientes/{id}/cobros/{cobroId}/borrar")
    public String borrarCobro(@PathVariable String id, @PathVariable String cobroId, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (!propietario(session)) return "redirect:/dashboard";
        if (!cobroId.matches("[0-9a-fA-F-]{36}")) return "redirect:/admin/clientes/" + id;
        List<Map<String, Object>> r = supabase.selectFilter("cobros_plataforma", "id=eq." + cobroId + "&cliente_id=eq." + id);
        if (r.isEmpty()) return "redirect:/admin/clientes/" + id;
        Object fac = r.get(0).get("factura_id");
        if (fac != null && !String.valueOf(fac).isBlank() && !"null".equals(String.valueOf(fac))) {
            SessionUtils.flash(session, "Ese cobro ya tiene factura: no se puede borrar. Si fue un error, anula o rectifica la factura.");
            return "redirect:/admin/clientes/" + id;
        }
        supabase.delete("cobros_plataforma", cobroId);
        SessionUtils.flash(session, "Cobro borrado. Si había alargado el plan, corrige la fecha «Pagado hasta».");
        return "redirect:/admin/clientes/" + id;
    }

    @PostMapping("/admin/clientes/{id}/creditos")
    public String creditos(@PathVariable String id, @RequestParam int cantidad, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (!propietario(session)) return "redirect:/dashboard";
        if (cobros.cliente(id) == null || cantidad < 1 || cantidad > 100_000) {
            SessionUtils.flash(session, "Cantidad no válida.");
            return "redirect:/admin/clientes/" + id;
        }
        aiService.addCredits(id, cantidad);
        SessionUtils.flash(session, cantidad + " créditos de IA añadidos.");
        return "redirect:/admin/clientes/" + id;
    }

    @PostMapping("/admin/peticiones/{pid}/atendida")
    public String atendida(@PathVariable String pid, @RequestParam(required = false) String volver, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (!propietario(session)) return "redirect:/dashboard";
        if (pid.matches("[0-9a-fA-F-]{36}"))
            supabase.update("peticiones_plan", pid, Map.of("estado", "atendida", "atendida_at", java.time.OffsetDateTime.now().toString()));
        return volver != null && volver.matches("/admin/clientes(/[0-9a-fA-F-]{36})?") ? "redirect:" + volver : "redirect:/admin/clientes";
    }

    /** Texto corto del estado del plan para las tablas. */
    static String estado(Map<String, Object> u, LocalDate hoy) {
        String st = String.valueOf(u.get("plan_status")).toLowerCase();
        String plan = PlanService.normalizar(String.valueOf(u.get("plan")));
        boolean stripe = PlanService.tieneSuscripcionStripe(u);
        if ("trialing".equals(st) && !stripe) {
            java.time.OffsetDateTime fin = com.inmobilia.util.Fechas.leer(u.get("trial_ends_at"));
            if (fin == null) return "En prueba";
            long d = java.time.temporal.ChronoUnit.DAYS.between(hoy, fin.toLocalDate());
            return d < 0 ? "Prueba acabada (Free)" : "En prueba · " + d + (d == 1 ? " día" : " días");
        }
        if (stripe) return "Stripe · " + st;
        if (PlanService.FREE.equals(plan)) return "Free";
        LocalDate hasta = PlanService.pagadoHasta(u);
        if (hasta == null) return "Activo · sin fecha de fin";
        if (PlanService.pagoCaducado(u, hoy)) return "Sin pagar desde el " + dmy(hasta) + " (Free)";
        if (hoy.isAfter(hasta)) return "Pendiente de pago · venció el " + dmy(hasta);
        long d = java.time.temporal.ChronoUnit.DAYS.between(hoy, hasta);
        return "Pagado hasta el " + dmy(hasta) + (d <= 7 ? " (renovar)" : "");
    }

    private static String resumen(Map<String, Object> p) {
        List<String> partes = new ArrayList<>();
        Object plan = p.get("plan");
        if (plan != null && !"null".equals(String.valueOf(plan)) && !String.valueOf(plan).isBlank())
            partes.add("Plan " + PlanService.PLANS.get(PlanService.normalizar(String.valueOf(plan))).label()
                    + ("anual".equals(p.get("periodo")) ? " anual" : " mensual"));
        if (p.get("activos_extra") != null) partes.add(p.get("activos_extra") + " activos adicionales");
        if (p.get("sociedades_extra") != null) partes.add(p.get("sociedades_extra") + " sociedades adicionales");
        if (p.get("modulos") instanceof Collection<?> m && !m.isEmpty()) {
            List<String> n = new ArrayList<>();
            for (Object x : m) { PlanService.Modulo mod = PlanService.modulo(String.valueOf(x)); if (mod != null) n.add(mod.nombre()); }
            partes.add(String.join(", ", n));
        }
        int packs = (int) numero(p.get("packs_creditos"));
        if (packs > 0) partes.add(packs + " × " + PlanService.PACK_CREDITOS + " créditos");
        return partes.isEmpty() ? "Consulta" : String.join(" · ", partes);
    }

    private static LocalDate fecha(String s) {
        try { return s == null || s.isBlank() ? null : LocalDate.parse(s.trim()); } catch (Exception e) { return null; }
    }

    private static double numero(Object o) {
        try { return o == null ? 0 : Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    /**
     * Marca o quita a un cliente como socio fundador (máximo Promociones.FUNDADOR_PLAZAS). El descuento lo pone
     * Stripe al suscribirse; a quien ya tenía suscripción hay que ponérselo a mano en Stripe.
     */
    @PostMapping("/admin/clientes/{id}/fundador")
    public String fundador(@PathVariable String id, @RequestParam(defaultValue = "false") boolean activar, HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        if (!propietario(session)) return "redirect:/dashboard";
        Map<String, Object> u = cobros.cliente(id);
        if (u == null) {
            SessionUtils.flash(session, "Ese cliente no existe.");
            return "redirect:/admin/clientes";
        }
        if (!activar) {
            boolean ok = supabase.update("users", id, new LinkedHashMap<>(Map.of("socio_fundador", false)));
            SessionUtils.flash(session, ok ? "Ya no es socio fundador. Si ya se había suscrito, el descuento sigue en Stripe hasta que acabe."
                    : "No se pudo guardar. " + Objects.toString(supabase.lastError(), ""));
            return "redirect:/admin/clientes/" + id;
        }
        if (com.inmobilia.service.Promociones.esFundador(u)) {
            SessionUtils.flash(session, "Ya es socio fundador.");
            return "redirect:/admin/clientes/" + id;
        }
        if (com.inmobilia.service.Promociones.fundadores(supabase).size() >= com.inmobilia.service.Promociones.FUNDADOR_PLAZAS) {
            SessionUtils.flash(session, "Las " + com.inmobilia.service.Promociones.FUNDADOR_PLAZAS + " plazas de socio fundador ya están ocupadas.");
            return "redirect:/admin/clientes/" + id;
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("socio_fundador", true);
        d.put("socio_fundador_at", java.time.OffsetDateTime.now().toString());
        if (!supabase.update("users", id, d)) {
            SessionUtils.flash(session, "No se pudo guardar (¿falta ejecutar la migración V120 en Supabase?). " + Objects.toString(supabase.lastError(), ""));
        } else if (PlanService.haSuscritoAlgunaVez(u)) {
            SessionUtils.flash(session, "Marcado como socio fundador. Ya tenía una suscripción en Stripe, así que el descuento no se aplica solo: "
                    + "en Stripe, abre su suscripción y añádele el cupón " + com.inmobilia.service.Promociones.cuponId(com.inmobilia.service.Promociones.FUNDADOR_COBROS)
                    + " (si aún no existe, créalo: " + com.inmobilia.service.Promociones.FUNDADOR_PCT + " %, duración " + com.inmobilia.service.Promociones.FUNDADOR_COBROS + " meses).");
        } else {
            SessionUtils.flash(session, "Marcado como socio fundador: al suscribirse tendrá un " + com.inmobilia.service.Promociones.FUNDADOR_PCT
                    + " % de descuento en sus " + com.inmobilia.service.Promociones.FUNDADOR_COBROS + " primeros cobros.");
        }
        return "redirect:/admin/clientes/" + id;
    }

    private static final java.time.format.DateTimeFormatter DMY = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static String dmy(LocalDate d) { return d == null ? "" : d.format(DMY); }
}
