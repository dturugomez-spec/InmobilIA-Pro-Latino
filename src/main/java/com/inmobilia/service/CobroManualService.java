package com.inmobilia.service;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Cobro manual: mientras haya pocos clientes se cobra por transferencia o en efectivo y se hace
 * factura desde la propia app (la cuenta del propietario de la plataforma).
 *
 *  1. El cliente pide un plan, extras o créditos desde Plan y facturación (peticiones_plan). No
 *     paga nada en la web: nos llega un correo.
 *  2. El propietario de la plataforma activa el plan en /admin/clientes y apunta los cobros
 *     (cobros_plataforma). Cada cobro alarga users.pagado_hasta y puede abrir la factura ya rellena.
 *  3. Cada mañana se avisa a los clientes cuyo plan vence (7 días antes, el día, al día siguiente y
 *     cuando pasan a Free) y a los propietarios de la plataforma con el resumen.
 *
 * Con COBRO_ONLINE=true vuelve el pago con tarjeta (Stripe) en la app.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CobroManualService {

    /** Datos de la factura que hay que abrir rellena (en la sesión del propietario de la plataforma). */
    public static final String PREFILL = "factura_prefill";
    /** Cobro al que se vinculará la próxima factura que se emita en esta sesión. */
    public static final String COBRO_PENDIENTE = "factura_de_cobro";
    private static final String PREFILL_EN = "factura_prefill_en";
    private static final long PREFILL_VALIDO_MS = 30 * 60 * 1000L;

    public static final List<String> METODOS = List.of("transferencia", "efectivo", "bizum", "otro");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final SupabaseService supabase;
    private final EmailService emailService;
    private final PlanService planService;
    private final AIService aiService;

    @Value("${app.cobro-online:false}")
    private boolean cobroOnline;

    @Value("${ADMIN_EMAILS:}")
    private String adminEmailsRaw;

    /** ¿Se paga con tarjeta en la app (Stripe)? Por defecto no: transferencia o efectivo. */
    public boolean cobroOnline() { return cobroOnline; }

    public List<String> emailsAdmin() {
        List<String> out = new ArrayList<>();
        if (adminEmailsRaw != null) for (String e : adminEmailsRaw.split(",")) if (!e.isBlank()) out.add(e.trim().toLowerCase());
        return out;
    }

    // ── Datos para pagar ─────────────────────────────────────────────────

    private volatile Map<String, Object> datosCache;
    private volatile long datosEn;

    /**
     * Titular, identificador fiscal, cuenta y correo para pagar: los de «Mi negocio» de la cuenta del propietario de la
     * plataforma (la que emite las facturas). Vacío si aún no los ha rellenado.
     */
    public Map<String, Object> datosCobro() {
        if (datosCache != null && System.currentTimeMillis() - datosEn < 5 * 60_000) return datosCache;
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            for (String email : emailsAdmin()) {
                List<Map<String, Object>> u = supabase.findBy("users", Map.of("email", email));
                if (u.isEmpty()) continue;
                List<Map<String, Object>> bp = supabase.selectFilter("business_profile",
                        "org_id=eq." + u.get(0).get("id") + "&limit=1");
                if (bp.isEmpty()) continue;
                Map<String, Object> p = bp.get(0);
                if (!presente(p.get("iban")) && !presente(p.get("legal_name"))) continue;
                out.put("titular", texto(p.get("legal_name")));
                out.put("nif", texto(p.get("tax_id")));
                out.put("iban", AvisoCuentaBancariaService.normalizar(p.get("iban")));
                out.put("email", presente(p.get("contact_email")) ? texto(p.get("contact_email")) : email);
                break;
            }
        } catch (Exception e) {
            log.warn("No se pudieron leer los datos de cobro: {}", e.getMessage());
        }
        datosCache = out;
        datosEn = System.currentTimeMillis();
        return out;
    }

    // ── 1. Peticiones de los clientes ────────────────────────────────────

    public record Peticion(String plan, String periodo, Integer activosExtra, Integer sociedadesExtra,
                           List<String> modulos, int packsCreditos, String mensaje) {}

    /** Valida y guarda lo que pide la cuenta y avisa por correo. Devuelve un error o null. */
    public String pedir(String org, String solicitanteId, Peticion p) {
        String plan = p.plan() == null || p.plan().isBlank() ? null : p.plan().trim().toLowerCase();
        if (plan != null && !PlanService.PLANS.containsKey(plan)) return "Plan no válido.";
        String periodo = "anual".equals(p.periodo()) ? "anual" : "mensual";
        Integer activos = p.activosExtra() == null ? null : Math.max(0, Math.min(1000, p.activosExtra()));
        Integer sociedades = p.sociedadesExtra() == null ? null : Math.max(0, Math.min(100, p.sociedadesExtra()));
        List<String> modulos = new ArrayList<>();
        if (p.modulos() != null) for (String m : p.modulos()) if (PlanService.modulo(m) != null && !modulos.contains(m)) modulos.add(m);
        int packs = Math.max(0, Math.min(20, p.packsCreditos()));
        String mensaje = p.mensaje() == null ? "" : p.mensaje().trim();
        if (mensaje.length() > 1000) mensaje = mensaje.substring(0, 1000);
        if (plan == null && activos == null && sociedades == null && modulos.isEmpty() && packs == 0 && mensaje.isEmpty())
            return "Indica qué necesitas.";

        Map<String, Object> fila = new LinkedHashMap<>();
        fila.put("cliente_id", org);
        fila.put("solicitante_id", solicitanteId);
        fila.put("plan", plan);
        fila.put("periodo", periodo);
        fila.put("activos_extra", activos);
        fila.put("sociedades_extra", sociedades);
        fila.put("modulos", modulos);
        fila.put("packs_creditos", packs);
        fila.put("mensaje", mensaje.isEmpty() ? null : mensaje);
        fila.put("estado", "pendiente");
        Map<String, Object> guardada = supabase.insert("peticiones_plan", fila);
        if (guardada == null || guardada.isEmpty()) return "No se pudo guardar la petición. Inténtalo de nuevo o escríbenos.";

        Map<String, Object> cliente = usuario(org);
        String quien = cliente == null ? org : nombreCliente(cliente, null) + " (" + texto(cliente.get("email")) + ")";
        String html = "<h2>Nueva petición de plan</h2>"
                + "<p><strong>" + esc(quien) + "</strong> pide:</p><ul>" + resumenHtml(fila) + "</ul>"
                + (mensaje.isEmpty() ? "" : "<p><em>" + esc(mensaje) + "</em></p>")
                + "<p><a href='" + com.inmobilia.util.UrlBase.BASE + "/admin/clientes/" + org + "'>Abrir la ficha del cliente</a>"
                + " para activar el plan, apuntar el cobro y hacer la factura.</p>";
        for (String a : emailsAdmin()) enviar(a, "Petición de plan: " + quien, html);
        return null;
    }

    static String resumenHtml(Map<String, Object> f) {
        StringBuilder b = new StringBuilder();
        if (presente(f.get("plan"))) {
            PlanService.PlanLimits l = PlanService.PLANS.get(PlanService.normalizar(texto(f.get("plan"))));
            b.append("<li>Plan ").append(l.label()).append(" (").append("anual".equals(f.get("periodo")) ? "pago anual" : "pago mensual").append(")</li>");
        }
        if (f.get("activos_extra") != null) b.append("<li>Activos adicionales: ").append(f.get("activos_extra")).append("</li>");
        if (f.get("sociedades_extra") != null) b.append("<li>Sociedades adicionales: ").append(f.get("sociedades_extra")).append("</li>");
        Object m = f.get("modulos");
        if (m instanceof Collection<?> c && !c.isEmpty()) {
            List<String> n = new ArrayList<>();
            for (Object x : c) { PlanService.Modulo mod = PlanService.modulo(String.valueOf(x)); if (mod != null) n.add(mod.nombre()); }
            b.append("<li>Módulos: ").append(esc(String.join(", ", n))).append("</li>");
        }
        int packs = entero(f.get("packs_creditos"));
        if (packs > 0) b.append("<li>").append(packs).append(packs == 1 ? " pack" : " packs").append(" de ")
                .append(PlanService.PACK_CREDITOS).append(" créditos de IA</li>");
        return b.toString();
    }

    // ── 2. Lo que hace el propietario de la plataforma ───────────────────

    /** Cuenta titular de un cliente (no un empleado ni una cuenta demo), o null. */
    public Map<String, Object> cliente(String id) {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) return null;
        Map<String, Object> u = usuario(id);
        if (u == null || presente(u.get("organization_id")) && !id.equals(texto(u.get("organization_id")))) return null;
        if (PlanService.esCuentaDemo(texto(u.get("email")))) return null;
        return u;
    }

    public record Plan(String plan, String periodo, LocalDate pagadoHasta, int activosExtra, int sociedadesExtra, List<String> modulos) {}

    /** Activa (o cambia) el plan de un cliente. Termina la prueba si la tenía. */
    public boolean activarPlan(String clienteId, Plan p) {
        Map<String, Object> antes = cliente(clienteId);
        if (antes == null || !PlanService.PLANS.containsKey(p.plan())) return false;
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("plan", p.plan());
        upd.put("plan_status", "active");
        upd.put("pagado_hasta", PlanService.FREE.equals(p.plan()) || p.pagadoHasta() == null
                ? SupabaseService.VACIAR : p.pagadoHasta().toString());
        boolean ok = supabase.update("users", clienteId, upd, clienteId);
        if (!ok) return false;
        Map<String, Object> extras = new LinkedHashMap<>();
        boolean free = PlanService.FREE.equals(p.plan());
        extras.put("periodo_facturacion", "anual".equals(p.periodo()) ? "anual" : "mensual");
        extras.put("activos_extra", free ? 0 : Math.max(0, Math.min(1000, p.activosExtra())));
        extras.put("sociedades_extra", PlanService.BUSINESS.equals(p.plan()) ? Math.max(0, Math.min(100, p.sociedadesExtra())) : 0);
        List<String> modulos = new ArrayList<>();
        if (PlanService.PRO.equals(p.plan()) && p.modulos() != null)
            for (String m : p.modulos()) if (PlanService.modulo(m) != null && !modulos.contains(m)) modulos.add(m);
        extras.put("modulos", modulos);
        supabase.update("users", clienteId, extras, clienteId);
        planService.olvidarExceso(clienteId);
        // Al subir de plan, el saldo de IA sube ya a la asignación del nuevo plan
        int nuevo = PlanService.creditosMensuales(p.plan());
        if (nuevo > PlanService.creditosMensuales(planService.planEfectivo(antes)))
            aiService.reponerCreditos(clienteId, nuevo, true);
        log.info(">>> Plan de {} activado a mano: {} hasta {}", clienteId, p.plan(), upd.get("pagado_hasta"));
        return true;
    }

    public record Cobro(LocalDate fecha, String metodo, String concepto, double base, double ivaPct,
                        String referencia, int meses) {}

    /**
     * Apunta un cobro. Con meses > 0 alarga el plan: desde el día siguiente a lo ya pagado (o desde
     * hoy, si ya había vencido) hasta ese número de meses después.
     */
    public Map<String, Object> registrarCobro(String clienteId, String adminUid, Cobro c) {
        Map<String, Object> cli = cliente(clienteId);
        if (cli == null || c.base() < 0 || c.base() > 1_000_000 || !METODOS.contains(c.metodo())) return null;
        double iva = c.ivaPct() < 0 || c.ivaPct() > 30 ? 0 : c.ivaPct();   // impuesto local que pone el propietario
        double total = Math.round(c.base() * (1 + iva / 100.0) * 100.0) / 100.0;
        LocalDate hoy = LocalDate.now();
        Map<String, Object> fila = new LinkedHashMap<>();
        fila.put("cliente_id", clienteId);
        fila.put("fecha", (c.fecha() == null ? hoy : c.fecha()).toString());
        fila.put("metodo", c.metodo());
        fila.put("concepto", c.concepto() == null || c.concepto().isBlank() ? conceptoPorDefecto(cli) : c.concepto().trim());
        fila.put("base", Math.round(c.base() * 100.0) / 100.0);
        fila.put("iva_pct", iva);
        fila.put("total", total);
        fila.put("referencia", c.referencia() == null || c.referencia().isBlank() ? null : c.referencia().trim());
        fila.put("registrado_por", adminUid);
        int meses = Math.max(0, Math.min(36, c.meses()));
        if (meses > 0) {
            LocalDate hasta = PlanService.pagadoHasta(cli);
            LocalDate desde = hasta == null || hasta.isBefore(hoy) ? hoy : hasta.plusDays(1);
            LocalDate nuevoHasta = desde.plusMonths(meses).minusDays(1);
            fila.put("periodo_desde", desde.toString());
            fila.put("periodo_hasta", nuevoHasta.toString());
        }
        Map<String, Object> guardado = supabase.insert("cobros_plataforma", fila);
        if (guardado == null || guardado.isEmpty()) return null;
        if (meses > 0) {
            Map<String, Object> upd = new LinkedHashMap<>();
            upd.put("pagado_hasta", fila.get("periodo_hasta"));
            // Si estaba en la prueba o caducado, el cobro lo deja activo con su plan
            upd.put("plan_status", "active");
            supabase.update("users", clienteId, upd, clienteId);
            planService.olvidarExceso(clienteId);
        }
        return guardado;
    }

    static String conceptoPorDefecto(Map<String, Object> cli) {
        PlanService.PlanLimits l = PlanService.PLANS.get(PlanService.normalizar(texto(cli.get("plan"))));
        boolean anual = "anual".equals(texto(cli.get("periodo_facturacion")));
        return "InmobilIA Pro · plan " + l.label() + (anual ? " (anual)" : " (mensual)");
    }

    /**
     * Prepara la factura de un cobro: los datos del cliente salen de su «Mi negocio» (o de su
     * cuenta). Se guarda en la sesión y /invoices/new la abre rellena para revisarla y emitirla.
     */
    public void prepararFactura(HttpSession session, Map<String, Object> cobro) {
        String clienteId = texto(cobro.get("cliente_id"));
        Map<String, Object> cli = usuario(clienteId);
        Map<String, Object> negocio = negocio(clienteId);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("cliente_nombre", nombreCliente(cli, negocio));
        f.put("cliente_nif", negocio == null ? "" : texto(negocio.get("tax_id")));
        if (negocio != null) {
            String dir = String.join(", ", List.of(texto(negocio.get("address")), texto(negocio.get("postal_code")), texto(negocio.get("city"))))
                    .replaceAll("(, )+$|^(, )+", "").replaceAll("(, ){2,}", ", ");
            f.put("cliente_direccion", dir);
        }
        f.put("cliente_email", negocio != null && presente(negocio.get("contact_email")) ? texto(negocio.get("contact_email"))
                : cli == null ? "" : texto(cli.get("email")));
        String concepto = texto(cobro.get("concepto"));
        if (presente(cobro.get("periodo_desde")) && presente(cobro.get("periodo_hasta")))
            concepto += " · del " + dmy(cobro.get("periodo_desde")) + " al " + dmy(cobro.get("periodo_hasta"));
        f.put("concepto", concepto);
        f.put("base_imponible", cobro.get("base"));
        f.put("iva_pct", cobro.get("iva_pct"));
        f.put("status", "Pagada");
        f.put("metodo_pago", switch (texto(cobro.get("metodo"))) {
            case "efectivo" -> "Efectivo";
            case "bizum" -> "Bizum";
            case "transferencia" -> "Transferencia";
            default -> "";
        });
        f.put("notas", "Cobrado el " + dmy(cobro.get("fecha"))
                + (presente(cobro.get("referencia")) ? ". Referencia: " + texto(cobro.get("referencia")) : "") + ".");
        session.setAttribute(PREFILL, f);
        session.setAttribute(PREFILL_EN, System.currentTimeMillis());
        session.setAttribute(COBRO_PENDIENTE, texto(cobro.get("id")));
    }

    /** Datos para rellenar la factura nueva, si hay una preparada hace menos de 30 minutos. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> facturaPreparada(HttpSession session) {
        Object f = session.getAttribute(PREFILL);
        Object en = session.getAttribute(PREFILL_EN);
        if (!(f instanceof Map) || !(en instanceof Long t) || System.currentTimeMillis() - t > PREFILL_VALIDO_MS) return null;
        return (Map<String, Object>) f;
    }

    /** Tras emitir la factura preparada: queda vinculada a su cobro. */
    public void facturaEmitida(HttpSession session, String org, String email, Object facturaId) {
        Object cobroId = session.getAttribute(COBRO_PENDIENTE);
        boolean preparada = facturaPreparada(session) != null;
        session.removeAttribute(PREFILL);
        session.removeAttribute(PREFILL_EN);
        session.removeAttribute(COBRO_PENDIENTE);
        if (!preparada || cobroId == null || facturaId == null || !planService.esAdminPlataforma(email)) return;
        supabase.update("cobros_plataforma", String.valueOf(cobroId), Map.of("factura_id", String.valueOf(facturaId)));
        log.info(">>> Factura {} vinculada al cobro {} (emitida por {})", facturaId, cobroId, org);
    }

    // ── 3. Avisos de vencimiento ─────────────────────────────────────────

    /** Cada mañana: avisa a quien vence y manda el resumen a los propietarios de la plataforma. */
    @Scheduled(cron = "0 0 9 * * *", zone = "${app.zona-horaria:America/Mexico_City}")
    public void avisarVencimientos() {
        try { avisar(LocalDate.now()); }
        catch (Exception e) { log.error("[CRON] Avisos de cobro: {}", e.getMessage()); }
    }

    /** Devuelve las líneas del resumen enviado (vacío si hoy no tocaba avisar a nadie). */
    public List<String> avisar(LocalDate hoy) {
        List<Map<String, Object>> cuentas = supabase.selectFilter("users",
                "select=id,email,name,empresa,plan,plan_status,pagado_hasta,periodo_facturacion,stripe_subscription_id"
                + "&pagado_hasta=not.is.null&organization_id=is.null&stripe_subscription_id=is.null&limit=5000");
        List<String> resumen = new ArrayList<>();
        Map<String, Object> pago = datosCobro();
        for (Map<String, Object> u : cuentas) {
            if (PlanService.FREE.equals(PlanService.normalizar(texto(u.get("plan"))))) continue;
            LocalDate hasta = PlanService.pagadoHasta(u);
            if (hasta == null) continue;
            long dias = ChronoUnit.DAYS.between(hoy, hasta);
            String plan = PlanService.PLANS.get(PlanService.normalizar(texto(u.get("plan")))).label();
            String email = texto(u.get("email"));
            String asunto = null, cuerpo = null, linea = null;
            if (dias == 7 || dias == 0) {
                asunto = dias == 0 ? "Tu plan " + plan + " vence hoy" : "Tu plan " + plan + " vence en 7 días";
                cuerpo = "<p>Tu plan <strong>" + plan + "</strong> está pagado hasta el <strong>" + hasta.format(DMY) + "</strong>.</p>"
                        + "<p>Para renovarlo, haz el pago por transferencia o en efectivo. Te enviaremos la factura.</p>" + datosPagoHtml(pago);
                linea = nombreCliente(u, null) + ": vence el " + hasta.format(DMY);
            } else if (dias == -1) {
                asunto = "Tu plan " + plan + " está pendiente de pago";
                cuerpo = "<p>Tu plan <strong>" + plan + "</strong> venció el " + hasta.format(DMY) + ". Lo mantenemos hasta el <strong>"
                        + hasta.plusDays(PlanService.DIAS_GRACIA).format(DMY) + "</strong>; si para entonces no hemos recibido el pago, "
                        + "tu cuenta pasará al plan Free y conservarás todos tus datos.</p>" + datosPagoHtml(pago);
                linea = nombreCliente(u, null) + ": venció el " + hasta.format(DMY) + " (pendiente de pago)";
            } else if (dias == -(PlanService.DIAS_GRACIA + 1)) {
                asunto = "Tu cuenta ha pasado al plan Free";
                cuerpo = "<p>No hemos recibido el pago del plan <strong>" + plan + "</strong> y tu cuenta funciona ahora como Free "
                        + "(3 activos y 1 usuario). <strong>No se ha borrado nada.</strong> En cuanto recibamos el pago, vuelves a tener tu plan.</p>"
                        + datosPagoHtml(pago);
                linea = nombreCliente(u, null) + ": pasa a Free (sin pago desde el " + hasta.format(DMY) + ")";
            }
            if (asunto == null) continue;
            enviar(email, asunto, "<h2>Hola</h2>" + cuerpo + "<p>— Equipo InmobilIA Pro</p>");
            resumen.add(linea);
        }
        if (!resumen.isEmpty()) {
            StringBuilder html = new StringBuilder("<h2>Cobros: avisos de hoy</h2><ul>");
            for (String l : resumen) html.append("<li>").append(esc(l)).append("</li>");
            html.append("</ul><p><a href='").append(com.inmobilia.util.UrlBase.BASE).append("/admin/clientes'>Abrir clientes y cobros</a></p>");
            for (String a : emailsAdmin()) enviar(a, "Cobros: " + resumen.size() + (resumen.size() == 1 ? " aviso" : " avisos") + " hoy", html.toString());
        }
        return resumen;
    }

    /** Bloque del correo con cómo pagar. */
    static String datosPagoHtml(Map<String, Object> pago) {
        StringBuilder b = new StringBuilder("<div style='background:#f1f5f9;padding:14px 18px;border-radius:10px;margin:16px 0'>");
        if (pago != null && presente(pago.get("iban"))) {
            b.append("<p style='margin:2px 0'><strong>Transferencia</strong> a ").append(esc(texto(pago.get("titular")))).append("</p>")
             .append("<p style='margin:2px 0'>Cuenta para transferencias: <strong>").append(esc(texto(pago.get("iban")))).append("</strong></p>")
             .append("<p style='margin:2px 0'>Concepto: el nombre de tu empresa</p>");
        } else {
            b.append("<p style='margin:2px 0'>Contesta a este correo y te enviamos los datos para la transferencia.</p>");
        }
        b.append("<p style='margin:8px 0 2px'>¿Prefieres pagar en efectivo? Contesta a este correo y quedamos.</p></div>");
        return b.toString();
    }

    // ── Utilidades ───────────────────────────────────────────────────────

    public Map<String, Object> negocio(String org) {
        try {
            List<Map<String, Object>> r = supabase.selectFilter("business_profile", "org_id=eq." + org + "&limit=1");
            return r.isEmpty() ? null : r.get(0);
        } catch (Exception e) { return null; }
    }

    private Map<String, Object> usuario(String id) {
        try {
            List<Map<String, Object>> r = supabase.findBy("users", Map.of("id", id));
            return r.isEmpty() ? null : r.get(0);
        } catch (Exception e) { return null; }
    }

    /** Razón social de «Mi negocio», o la empresa, o el nombre, o el correo. */
    public static String nombreCliente(Map<String, Object> u, Map<String, Object> negocio) {
        if (negocio != null && presente(negocio.get("legal_name"))) return texto(negocio.get("legal_name"));
        if (u == null) return "";
        for (String k : List.of("empresa", "name", "email")) if (presente(u.get(k))) return texto(u.get(k));
        return "";
    }

    private void enviar(String to, String asunto, String html) {
        try { emailService.send(to, asunto, html); } catch (Exception e) { log.warn("Correo de cobro a {} falló: {}", to, e.getMessage()); }
    }

    /** dd/MM/yyyy de una fecha de la base de datos. */
    public static String dmy(Object fecha) {
        try { return LocalDate.parse(texto(fecha).substring(0, 10)).format(DMY); } catch (Exception e) { return texto(fecha); }
    }

    static boolean presente(Object o) {
        return o != null && !String.valueOf(o).isBlank() && !"null".equals(String.valueOf(o));
    }

    static String texto(Object o) { return presente(o) ? String.valueOf(o).trim() : ""; }

    static int entero(Object o) {
        try { return presente(o) ? (int) Double.parseDouble(String.valueOf(o)) : 0; } catch (Exception e) { return 0; }
    }

    private static String esc(String s) { return HtmlUtils.htmlEscape(s == null ? "" : s); }

    /** Momento actual, para los registros. */
    static String ahora() { return OffsetDateTime.now().toString(); }
}
