package com.inmobilia.controller;

import com.inmobilia.service.AgentBriefingService;
import com.inmobilia.service.HealthScoreService;
import com.inmobilia.service.NewsService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.FormatUtils;
import com.inmobilia.util.SessionUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final SupabaseService supabase;
    private final HealthScoreService healthService;
    private final NewsService newsService;
    private final AgentBriefingService briefingService;

    @jakarta.annotation.Resource(name = "dashboardExecutor")
    private Executor dashExec;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Lanza una consulta a Supabase en el pool del dashboard, con fallback a lista vacía. */
    private CompletableFuture<List<Map<String, Object>>> q(String table, String uid) {
        return CompletableFuture
                .supplyAsync(() -> supabase.query(table, uid), dashExec)
                .exceptionally(ex -> List.of());
    }

    @GetMapping("/dashboard")
    public String dashboard(HttpSession session, Model model) {
        if (!SessionUtils.isLoggedIn(session)) return "redirect:/login";
        String uid = SessionUtils.uid(session);

        // Fan-out: todas las consultas independientes se lanzan en paralelo (pool dedicado)
        var fProps     = q("propiedades", uid);
        var fTenants   = q("inquilinos", uid);
        var fTxns      = q("transacciones", uid);
        var fTasks     = q("tareas", uid);
        var fMaint     = q("mantenimiento", uid);
        var fBookings  = q("reservas", uid);
        var fDeals     = q("oportunidades", uid);
        var fSeguros   = q("seguros", uid);
        var fContratos = q("contratos", uid);
        var fLeads     = q("leads", uid);
        var fAcciones  = q("agente_acciones", uid);

        var props     = fProps.join();
        var tenants   = fTenants.join();
        var txns      = fTxns.join();
        var tasks     = fTasks.join();
        var maint     = fMaint.join();
        var bookings  = fBookings.join();
        var deals     = fDeals.join();
        var seguros   = fSeguros.join();
        var contratos = fContratos.join();

        boolean hasData = !props.isEmpty() || !tenants.isEmpty() || !txns.isEmpty();
        model.addAttribute("hasData", hasData);

        // ─── Resumen del Agente IA para la home ───
        try {
            long leadsCalientes = fLeads.join().stream().filter(l -> {
                String et = String.valueOf(l.getOrDefault("etapa", ""));
                return scoreOf(l) >= 60 && ("Nuevo".equals(et) || "Contactado".equals(et));
            }).count();
            long autopilotoPend = fAcciones.join().stream()
                    .filter(a -> "propuesta".equals(a.get("estado"))).count();
            model.addAttribute("agLeadsCalientes", leadsCalientes);
            model.addAttribute("agAutopiloto", autopilotoPend);
        } catch (Exception e) {
            model.addAttribute("agLeadsCalientes", 0L);
            model.addAttribute("agAutopiloto", 0L);
        }

        // "Tu día" se carga de forma asíncrona vía /api/tu-dia (no bloquea el dashboard).

        // ─── KPIs de cartera ──────────────────────────────────────────
        int nProps = props.size();
        long nOcc  = props.stream().filter(p -> "Ocupado".equals(p.get("status"))).count();
        long nVac  = props.stream().filter(p -> "Vacante".equals(p.get("status"))).count();
        long nMor  = tenants.stream().filter(t -> "Moroso".equals(t.get("status"))).count();
        double occRate = nProps > 0 ? nOcc * 100.0 / nProps : 0;

        double valorCartera  = sumDouble(props, "current_value");
        double precioCompra  = sumDouble(props, "purchase_price");
        double plusvalia     = valorCartera - precioCompra;
        double plusvaliaPct  = precioCompra > 0 ? plusvalia / precioCompra * 100 : 0;
        double ingresosMensual = sumDouble(props, "rental_income");
        double gastosMensual   = sumDouble(props, "monthly_expenses");
        double mortgageMes     = sumDouble(props, "mortgage_payment");
        double noiMes          = ingresosMensual - gastosMensual;
        double cashflowMes     = noiMes - mortgageMes;
        double capRate         = valorCartera > 0 ? noiMes * 12 / valorCartera * 100 : 0;
        double grossYield      = valorCartera > 0 ? ingresosMensual * 12 / valorCartera * 100 : 0;
        double dscr            = mortgageMes > 0 ? noiMes / mortgageMes : 0;
        double precioMedio     = nProps > 0 ? valorCartera / nProps : 0;

        // ─── KPIs de tesoreria real (de transacciones) ────────────────
        LocalDate today = LocalDate.now();
        LocalDate mesInicio = today.withDayOfMonth(1);
        LocalDate anoInicio = today.withDayOfYear(1);
        double revMes = 0, expMes = 0, revYtd = 0, expYtd = 0;
        double revUlt12 = 0, expUlt12 = 0;
        LocalDate ult12 = today.minusMonths(12);
        for (var t : txns) {
            LocalDate d = parseDate(t.get("date"));
            if (d == null) continue;
            double amt = num(t, "amount");
            boolean ingreso = "Ingreso".equalsIgnoreCase(String.valueOf(t.get("type")));
            if (!d.isBefore(mesInicio)) { if (ingreso) revMes += amt; else expMes += amt; }
            if (!d.isBefore(anoInicio)) { if (ingreso) revYtd += amt; else expYtd += amt; }
            if (!d.isBefore(ult12))     { if (ingreso) revUlt12 += amt; else expUlt12 += amt; }
        }
        double cashflowYtd = revYtd - expYtd;
        double cashflowUlt12 = revUlt12 - expUlt12;

        // ─── Alertas estructuradas ────────────────────────────────────
        List<Map<String, Object>> alertsCrit = new ArrayList<>();
        List<Map<String, Object>> alertsWarn = new ArrayList<>();
        List<Map<String, Object>> alertsInfo = new ArrayList<>();

        tenants.stream().filter(t -> "Moroso".equals(t.get("status"))).forEach(t ->
                alertsCrit.add(alert("MOROSIDAD",
                        str(t.get("name")) + " — " + FormatUtils.dinero(num(t, "rent_amount")),
                        "/crud/inquilinos")));

        for (var c : contratos) {
            LocalDate d = parseDate(c.get("end_date"));
            if (d == null) continue;
            long days = ChronoUnit.DAYS.between(today, d);
            String prop = str(c.get("property_name"));
            if (days <= 0) alertsCrit.add(alert("CONTRATO VENCIDO", prop + " · hace " + Math.abs(days) + "d", "/crud/contratos"));
            else if (days <= 30) alertsWarn.add(alert("CONTRATO", prop + " · vence en " + days + "d", "/crud/contratos"));
            else if (days <= 90) alertsInfo.add(alert("CONTRATO", prop + " · vence en " + days + "d", "/crud/contratos"));
        }
        for (var s : seguros) {
            LocalDate d = parseDate(s.get("expiry_date"));
            if (d == null) continue;
            long days = ChronoUnit.DAYS.between(today, d);
            String tipo = str(s.get("type"));
            if (days <= 0) alertsCrit.add(alert("SEGURO VENCIDO", tipo, "/crud/seguros"));
            else if (days <= 30) alertsWarn.add(alert("SEGURO", tipo + " · " + days + "d", "/crud/seguros"));
        }
        // Mantenimiento alta prioridad sin cerrar
        maint.stream()
                .filter(m -> !"Completado".equals(m.get("status")) && !"Cerrado".equals(m.get("status")))
                .filter(m -> "Urgente".equals(m.get("priority")) || "Alta".equals(m.get("priority")))
                .forEach(m -> alertsWarn.add(alert("MANTENIMIENTO",
                        str(m.get("title")) + " · " + str(m.get("property_name")),
                        "/crud/mantenimiento")));
        // Tareas vencidas (esquema real V28: estado/fecha_limite/titulo)
        for (var t : tasks) {
            String est = str(t.get("estado")).toLowerCase();
            if (est.equals("completada") || est.equals("cancelada")) continue;
            LocalDate d = parseDate(t.get("fecha_limite"));
            if (d == null) continue;
            long days = ChronoUnit.DAYS.between(today, d);
            if (days < 0) alertsWarn.add(alert("TAREA VENCIDA",
                    str(t.get("titulo")) + " · hace " + Math.abs(days) + "d", "/crud/tareas"));
        }
        // Cashflow negativo
        if (hasData && cashflowMes < 0) {
            alertsWarn.add(alert("CASHFLOW NEGATIVO",
                    "Mes actual: " + FormatUtils.dinero(cashflowMes), "/crud/transacciones"));
        }
        // Vacancia alta
        if (nProps >= 3 && occRate < 70) {
            alertsInfo.add(alert("OCUPACION BAJA",
                    String.format(Locale.US, "%.0f%% · %d vacantes", occRate, nVac),
                    "/crud/propiedades"));
        }

        model.addAttribute("alertsCrit", alertsCrit);
        model.addAttribute("alertsWarn", alertsWarn);
        model.addAttribute("alertsInfo", alertsInfo);
        model.addAttribute("alertCountCrit", alertsCrit.size());
        model.addAttribute("alertCountWarn", alertsWarn.size());
        model.addAttribute("alertCountInfo", alertsInfo.size());
        model.addAttribute("alertTotal", alertsCrit.size() + alertsWarn.size() + alertsInfo.size());

        // ─── Actividad reciente: ultimas 8 transacciones ──────────────
        List<Map<String, Object>> recentTx = txns.stream()
                .sorted((a, b) -> {
                    LocalDate da = parseDate(a.get("date"));
                    LocalDate db = parseDate(b.get("date"));
                    if (da == null && db == null) return 0;
                    if (da == null) return 1;
                    if (db == null) return -1;
                    return db.compareTo(da);
                })
                .limit(8)
                .collect(Collectors.toList());
        model.addAttribute("recentTx", recentTx);

        // ─── Proximas acciones: top 5 vencimientos ────────────────────
        List<Map<String, Object>> nextLeases = contratos.stream()
                .filter(c -> parseDate(c.get("end_date")) != null)
                .sorted(Comparator.comparing(c -> parseDate(c.get("end_date"))))
                .limit(5).toList();
        List<Map<String, Object>> nextMaint = maint.stream()
                .filter(m -> parseDate(m.get("scheduled_date")) != null)
                .filter(m -> !"Completado".equals(m.get("status")))
                .sorted(Comparator.comparing(m -> parseDate(m.get("scheduled_date"))))
                .limit(5).toList();
        model.addAttribute("nextLeases", nextLeases);
        model.addAttribute("nextMaint", nextMaint);

        // ─── Ingresos/gastos por mes (ultimos 6 meses) para mini-grafico
        List<Map<String, Object>> cashflowChart = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            YearMonth ym = YearMonth.from(today).minusMonths(i);
            double r = 0, e = 0;
            for (var t : txns) {
                LocalDate d = parseDate(t.get("date"));
                if (d == null) continue;
                if (YearMonth.from(d).equals(ym)) {
                    double a = num(t, "amount");
                    if ("Ingreso".equalsIgnoreCase(String.valueOf(t.get("type")))) r += a;
                    else e += a;
                }
            }
            cashflowChart.add(Map.of(
                    "label", ym.getMonth().toString().substring(0, 3),
                    "rev", r,
                    "exp", e,
                    "net", r - e));
        }
        double maxBar = cashflowChart.stream().mapToDouble(m ->
                Math.max(num(m, "rev"), num(m, "exp"))).max().orElse(1);
        model.addAttribute("cashflowChart", cashflowChart);
        model.addAttribute("maxBar", maxBar);

        // ─── Health Score ─────────────────────────────────────────────
        var health = healthService.compute(props, tenants, contratos, maint, tasks);
        model.addAttribute("healthScore", health.score());
        model.addAttribute("healthDims", health.dimensions());
        model.addAttribute("healthInsights", health.insights());
        model.addAttribute("healthAvailable", health.available());

        // ─── KPIs al modelo ───────────────────────────────────────────
        model.addAttribute("page", "Dashboard");
        model.addAttribute("nProps", nProps);
        model.addAttribute("nOcc", nOcc);
        model.addAttribute("nVac", nVac);
        model.addAttribute("nMor", nMor);
        model.addAttribute("nTenants", tenants.size());
        model.addAttribute("nTasks", tasks.size());
        model.addAttribute("nTasksOpen", tasks.stream().filter(t -> {
            String est = str(t.get("estado")).toLowerCase();
            return !est.equals("completada") && !est.equals("cancelada");
        }).count());
        model.addAttribute("nMaint", maint.size());
        model.addAttribute("nMaintOpen", maint.stream().filter(m -> !"Completado".equals(m.get("status")) && !"Cerrado".equals(m.get("status"))).count());
        model.addAttribute("nBookings", bookings.size());
        model.addAttribute("nDeals", deals.size());
        model.addAttribute("nContratos", contratos.size());
        model.addAttribute("nSeguros", seguros.size());

        model.addAttribute("occRate", occRate);
        model.addAttribute("valorCartera", FormatUtils.dinero(valorCartera));
        model.addAttribute("precioCompra", FormatUtils.dinero(precioCompra));
        model.addAttribute("precioMedio", FormatUtils.dinero(precioMedio));
        model.addAttribute("plusvalia", FormatUtils.dinero(plusvalia));
        model.addAttribute("plusvaliaPct", plusvaliaPct);
        model.addAttribute("ingresosMensual", FormatUtils.dinero(ingresosMensual));
        model.addAttribute("gastosMensual", FormatUtils.dinero(gastosMensual));
        model.addAttribute("mortgageMes", FormatUtils.dinero(mortgageMes));
        model.addAttribute("noiMes", FormatUtils.dinero(noiMes));
        model.addAttribute("noiAnual", FormatUtils.dinero(noiMes * 12));
        model.addAttribute("cashflowMes", FormatUtils.dinero(cashflowMes));
        model.addAttribute("cashflowMesNum", cashflowMes);
        model.addAttribute("capRate", capRate);
        model.addAttribute("grossYield", grossYield);
        model.addAttribute("dscr", dscr);

        model.addAttribute("revMes", FormatUtils.dinero(revMes));
        model.addAttribute("expMes", FormatUtils.dinero(expMes));
        model.addAttribute("revYtd", FormatUtils.dinero(revYtd));
        model.addAttribute("expYtd", FormatUtils.dinero(expYtd));
        model.addAttribute("cashflowYtd", FormatUtils.dinero(cashflowYtd));
        model.addAttribute("cashflowYtdNum", cashflowYtd);
        model.addAttribute("revUlt12", FormatUtils.dinero(revUlt12));
        model.addAttribute("cashflowUlt12", FormatUtils.dinero(cashflowUlt12));

        // ─── Noticias de mercado (ticker compacto) ────────────────────
        try {
            model.addAttribute("newsTicker", newsService.marketNews(6));
        } catch (Exception e) { model.addAttribute("newsTicker", java.util.List.of()); }

        return "dashboard";
    }

    /** "Tu día": plan de acciones priorizadas. Se carga async para no ralentizar el dashboard. */
    @GetMapping("/api/tu-dia")
    @ResponseBody
    public List<Map<String, Object>> tuDia(HttpSession session) {
        if (!SessionUtils.isLoggedIn(session)) return List.of();
        try {
            var plan = briefingService.generarAhora(SessionUtils.uid(session)).planDelDia();
            return plan.size() > 6 ? plan.subList(0, 6) : plan;
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> alert(String cat, String text, String url) {
        Map<String, Object> m = new HashMap<>();
        m.put("cat", cat);
        m.put("text", text);
        m.put("url", url);
        return m;
    }

    private double sumDouble(List<Map<String, Object>> list, String key) {
        return list.stream().mapToDouble(m -> num(m, key)).sum();
    }

    private double num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
    }

    private String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private LocalDate parseDate(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        if (s.isBlank() || "null".equals(s)) return null;
        try { return LocalDate.parse(s.substring(0, Math.min(10, s.length())), ISO); }
        catch (Exception e) { return null; }
    }

    private static int scoreOf(Map<String, Object> l) {
        try { return (int) Double.parseDouble(String.valueOf(l.get("score"))); }
        catch (Exception e) { return 0; }
    }
}
