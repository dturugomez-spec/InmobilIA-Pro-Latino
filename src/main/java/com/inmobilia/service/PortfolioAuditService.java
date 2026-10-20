package com.inmobilia.service;

import com.inmobilia.util.PaisActual;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Auditor IA de Cartera: ejecuta ~15 chequeos sobre la cartera del usuario
 * cruzando datos internos (Supabase) con referencias de mercado.
 *
 * Devuelve una puntuacion 0-100, un desglose por areas y una lista priorizada
 * de hallazgos con impacto estimado en la moneda de la inmobiliaria y accion recomendada.
 *
 * Nicho unico: ningun competidor del mercado (Tokko, EasyBroker, Wasi, Stessa, Buildium)
 * ofrece una auditoria de cartera valorada economicamente.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PortfolioAuditService {

    private final SupabaseService supabase;

    public enum Severity { CRITICAL, WARNING, INFO, OK }
    public enum Area {
        CONTRATOS("Contratos", "📋"),
        SEGUROS("Seguros", "🛡️"),
        RENTABILIDAD("Rentabilidad", "💹"),
        INQUILINOS("Inquilinos", "👥"),
        MANTENIMIENTO("Mantenimiento", "🔧"),
        DOCUMENTACION("Documentacion", "📁"),
        FINANCIACION("Financiacion", "🏦"),
        REGULATORIO("Regulatorio", "⚖️");
        public final String label;
        public final String icon;
        Area(String l, String i) { this.label = l; this.icon = i; }
    }

    public record Finding(
        String code,
        Area area,
        Severity severity,
        String title,
        String detail,
        String action,
        String actionUrl,
        double impactEur,
        String entity
    ) {}

    public record AreaScore(Area area, int score, int findings) {}

    public record AuditReport(
        int globalScore,
        String verdict,
        String verdictColor,
        int nProps,
        int nCritical,
        int nWarning,
        int nInfo,
        double totalImpactEur,
        List<AreaScore> areas,
        List<Finding> findings,
        LocalDate generatedAt
    ) {}

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    public AuditReport audit(String uid) {
        List<Map<String, Object>> props     = safe(supabase.query("propiedades", uid));
        List<Map<String, Object>> tenants   = safe(supabase.query("inquilinos", uid));
        List<Map<String, Object>> contratos = safe(supabase.query("contratos", uid));
        List<Map<String, Object>> seguros   = safe(supabase.query("seguros", uid));
        List<Map<String, Object>> mant      = safe(supabase.query("mantenimiento", uid));
        List<Map<String, Object>> docs      = safe(supabase.query("documentos", uid));


        LocalDate today = LocalDate.now();
        List<Finding> findings = new ArrayList<>();

        // ══════════════ CONTRATOS ══════════════
        for (var c : contratos) {
            LocalDate end = parseDate(c.get("end_date"));
            String propName = str(c.get("property_name"));
            double rent = num(c.get("rent"));
            if (end != null) {
                long days = ChronoUnit.DAYS.between(today, end);
                if (days < 0) {
                    findings.add(new Finding("CONTRATO_VENCIDO", Area.CONTRATOS, Severity.CRITICAL,
                        "Contrato vencido sin renovar",
                        "Contrato de " + propName + " venció hace " + (-days) + " días. Riesgo de precario.",
                        "Renovar o formalizar nuevo contrato", "/crud/contratos",
                        rent * 3, propName));
                } else if (days <= 30) {
                    findings.add(new Finding("CONTRATO_30D", Area.CONTRATOS, Severity.CRITICAL,
                        "Contrato caduca en 30 días",
                        "El contrato de " + propName + " caduca el " + end + ". Negocia YA la renovación.",
                        "Abrir contrato y renovar", "/crud/contratos",
                        rent * 2, propName));
                } else if (days <= 90) {
                    findings.add(new Finding("CONTRATO_90D", Area.CONTRATOS, Severity.WARNING,
                        "Contrato caduca en 90 días",
                        "Contrato de " + propName + " caduca el " + end + ". Prepara renovación.",
                        "Preparar renovación", "/crud/contratos",
                        rent, propName));
                }
            }
            // Falta IPC
            String status = str(c.get("status"));
            if ("Activo".equalsIgnoreCase(status)) {
                LocalDate start = parseDate(c.get("start_date"));
                String ipc = str(c.get("ipc_revision"));
                if (start != null && ChronoUnit.MONTHS.between(start, today) >= 12
                        && (ipc == null || ipc.isBlank() || "No".equalsIgnoreCase(ipc))) {
                    double lostMonth = rent * 0.03; // asumiendo 3% anual no aplicado
                    findings.add(new Finding("CONTRATO_SIN_IPC", Area.CONTRATOS, Severity.WARNING,
                        "Actualización de renta pendiente",
                        "Contrato de " + propName + " lleva >12 meses sin revisión. Revisa la cláusula de ajuste "
                                + "(inflación, UF, UVA, UVR… según el contrato y la ley de tu país).",
                        "Revisar contrato", "/crud/contratos",
                        lostMonth * 12, propName));
                }
            }
        }

        // ══════════════ SEGUROS ══════════════
        Set<String> propsAseguradas = new HashSet<>();
        for (var s : seguros) {
            String propName = str(s.get("property_name"));
            if (propName != null && !propName.isBlank()) propsAseguradas.add(propName.toLowerCase());
            LocalDate exp = parseDate(s.get("expiry_date"));
            double cost = num(s.get("annual_cost"));
            if (exp != null) {
                long days = ChronoUnit.DAYS.between(today, exp);
                if (days < 0) {
                    findings.add(new Finding("SEGURO_VENCIDO", Area.SEGUROS, Severity.CRITICAL,
                        "Póliza vencida - sin cobertura",
                        "Póliza " + str(s.get("type")) + " de " + propName + " venció hace " + (-days) + " días.",
                        "Renovar póliza inmediatamente", "/crud/seguros",
                        Math.max(cost, 50000) * 0.05, propName));
                } else if (days <= 15) {
                    findings.add(new Finding("SEGURO_15D", Area.SEGUROS, Severity.WARNING,
                        "Póliza caduca en <15 días",
                        "Póliza " + str(s.get("type")) + " caduca el " + exp + ".",
                        "Negociar renovación", "/crud/seguros",
                        cost, propName));
                }
            }
        }
        for (var p : props) {
            String name = str(p.get("name"));
            String status = str(p.get("status"));
            if ("Ocupado".equalsIgnoreCase(status) && name != null
                    && !propsAseguradas.contains(name.toLowerCase())) {
                double value = num(p.get("current_value"));
                findings.add(new Finding("PROP_SIN_SEGURO", Area.SEGUROS, Severity.CRITICAL,
                    "Propiedad ocupada sin póliza",
                    "La propiedad '" + name + "' está ocupada pero no tiene seguro de hogar/impago asociado.",
                    "Contratar póliza", "/crud/seguros/new",
                    value * 0.03, name));
            }
        }

        // ══════════════ RENTABILIDAD ══════════════
        double valorCartera = 0, noiAnual = 0, mortgageAnual = 0, ingresosAnuales = 0;
        int nVacantes = 0;
        for (var p : props) {
            String name = str(p.get("name"));
            double value = num(p.get("current_value"));
            double income = num(p.get("rental_income")) * 12;
            double expenses = num(p.get("monthly_expenses")) * 12;
            double mortgage = num(p.get("mortgage_payment")) * 12;
            double noi = income - expenses;
            valorCartera += value; noiAnual += noi; mortgageAnual += mortgage; ingresosAnuales += income;

            double gy = value > 0 ? income / value * 100 : 0;
            String status = str(p.get("status"));

            if ("Vacante".equalsIgnoreCase(status)) {
                nVacantes++;
                double lossDay = (income > 0 ? income / 365 : (value * 0.05) / 365);
                findings.add(new Finding("VACANCIA_" + name, Area.RENTABILIDAD, Severity.WARNING,
                    "Propiedad vacante genera coste",
                    "'" + name + "' está vacante. Coste oportunidad ≈ " + fmtEur(lossDay) + "/día.",
                    "Publicar y captar inquilino", "/crud/reservas/new",
                    lossDay * 30, name));
            }

            if (value > 0 && gy > 0) {
                if (gy < 3.5) {
                    findings.add(new Finding("YIELD_BAJO_" + name, Area.RENTABILIDAD, Severity.WARNING,
                        "Yield bruto < 3.5%",
                        "'" + name + "' rinde " + fmtPct(gy) + " bruto, por debajo del benchmark.",
                        "Revisar renta, aplicar IRAV o valorar venta", "/crud/propiedades",
                        (3.5 - gy) / 100 * value, name));
                }
                if (gy > 10) {
                    findings.add(new Finding("YIELD_ALTO_" + name, Area.RENTABILIDAD, Severity.INFO,
                        "Yield atípicamente alto",
                        "'" + name + "' rinde " + fmtPct(gy) + ". Revisa valor actual o riesgo zona.",
                        "Actualizar valor con comparables (CMA)", "/finanzas/valoracion",
                        0, name));
                }
            }
        }

        // ══════════════ FINANCIACION ══════════════
        double dscr = mortgageAnual > 0 ? noiAnual / mortgageAnual : 999;
        if (mortgageAnual > 0) {
            if (dscr < 1.0) {
                findings.add(new Finding("DSCR_NEG", Area.FINANCIACION, Severity.CRITICAL,
                    "DSCR < 1.0 - cartera no cubre hipoteca",
                    "El NOI anual (" + fmtEur(noiAnual) + ") no cubre la hipoteca (" + fmtEur(mortgageAnual) + "). DSCR=" + fmtRatio(dscr),
                    "Refinanciar o desapalancar", "/analisis/stress",
                    mortgageAnual - noiAnual, null));
            } else if (dscr < 1.25) {
                findings.add(new Finding("DSCR_BAJO", Area.FINANCIACION, Severity.WARNING,
                    "DSCR ajustado",
                    "DSCR=" + fmtRatio(dscr) + ". Poca holgura ante subidas de tasas o vacancia.",
                    "Simular stress test", "/analisis/stress",
                    0, null));
            }
        }
        if (mortgageAnual > 0) {
            findings.add(new Finding("TASA_VARIABLE", Area.FINANCIACION, Severity.INFO,
                "Revisa los créditos con tasa variable",
                "Si algún crédito hipotecario es de tasa variable o indexado (UVA, UVR, UF), simula el impacto de una subida.",
                "Stress test de cartera", "/herramientas/stress-cartera",
                0, null));
        }

        // ══════════════ INQUILINOS ══════════════
        for (var t : tenants) {
            String name = str(t.get("name"));
            String status = str(t.get("status"));
            double rent = num(t.get("rent_amount"));
            double score = num(t.get("score"));
            if ("Moroso".equalsIgnoreCase(status)) {
                findings.add(new Finding("MOROSIDAD_" + name, Area.INQUILINOS, Severity.CRITICAL,
                    "Inquilino en mora",
                    "'" + name + "' figura como moroso. Riesgo de impago acumulado.",
                    "Iniciar procedimiento", "/crud/inquilinos",
                    rent * 3, name));
            }
            if (score > 0 && score < 60) {
                findings.add(new Finding("SCORING_BAJO_" + name, Area.INQUILINOS, Severity.WARNING,
                    "Scoring inquilino bajo (" + (int)score + "/100)",
                    "'" + name + "' tiene un scoring de riesgo bajo. Monitoriza cobros.",
                    "Re-evaluar y pedir aval", "/ia/scoring",
                    rent * 2, name));
            }
        }

        // ══════════════ MANTENIMIENTO ══════════════
        for (var m : mant) {
            String status = str(m.get("status"));
            LocalDate sched = parseDate(m.get("scheduled_date"));
            String title = str(m.get("title"));
            if (sched != null && !"Completado".equalsIgnoreCase(status)
                    && ChronoUnit.DAYS.between(sched, today) > 30) {
                double cost = num(m.get("cost"));
                findings.add(new Finding("MANT_RETRASO", Area.MANTENIMIENTO, Severity.WARNING,
                    "Orden de mantenimiento con >30 días de retraso",
                    "'" + title + "' programada para " + sched + " sigue pendiente.",
                    "Re-planificar", "/crud/mantenimiento",
                    cost * 0.2, title));
            }
        }

        // ══════════════ DOCUMENTACION ══════════════
        Set<String> propsConCertEnerg = new HashSet<>();
        for (var d : docs) {
            String type = str(d.get("doc_type"));
            String propName = str(d.get("property_name"));
            if (type != null && type.toLowerCase().contains("energ") && propName != null) {
                propsConCertEnerg.add(propName.toLowerCase());
            }
            LocalDate exp = parseDate(d.get("expiry_date"));
            if (exp != null && exp.isBefore(today)) {
                findings.add(new Finding("DOC_VENCIDO", Area.DOCUMENTACION, Severity.WARNING,
                    "Documento expirado",
                    "'" + str(d.get("name")) + "' venció el " + exp + ".",
                    "Renovar documento", "/crud/documentos",
                    0, str(d.get("name"))));
            }
        }
        for (var p : props) {
            String name = str(p.get("name"));
            if (name != null && !propsConCertEnerg.contains(name.toLowerCase())) {
                findings.add(new Finding("NO_CERT_ENERG_" + name, Area.DOCUMENTACION, Severity.INFO,
                    "Certificado energético no registrado",
                    "'" + name + "' no tiene certificado o constancia de eficiencia energética cargado (exigible en algunas ciudades y países).",
                    "Cargar certificado", "/crud/documentos/new",
                    300, name));
            }
        }

        // ══════════════ REGULATORIO ══════════════
        // Normativa de arrendamiento del país (informativo general)
        if (!props.isEmpty()) {
            com.inmobilia.util.Pais pais = com.inmobilia.util.PaisActual.get();
            findings.add(new Finding("NORMATIVA_ARRENDAMIENTO", Area.REGULATORIO, Severity.INFO,
                "Revisa la normativa de arrendamiento de " + pais.nombre(),
                "Plazos, depósitos en garantía, ajustes de renta y desalojos cambian según el país y, en algunos, "
                        + "según el estado o la provincia. Confirma tus contratos con un profesional local.",
                "Radar regulatorio", "/herramientas/radar",
                0, null));
        }

        // ══════════════ SCORING POR AREA ══════════════
        Map<Area, int[]> byArea = new EnumMap<>(Area.class);
        for (Area a : Area.values()) byArea.put(a, new int[]{100, 0}); // [score, findings]
        for (Finding f : findings) {
            int penalty = switch (f.severity()) {
                case CRITICAL -> 20;
                case WARNING  -> 8;
                case INFO     -> 2;
                default       -> 0;
            };
            int[] arr = byArea.get(f.area());
            arr[0] = Math.max(0, arr[0] - penalty);
            arr[1]++;
        }
        List<AreaScore> areas = new ArrayList<>();
        for (Area a : Area.values()) areas.add(new AreaScore(a, byArea.get(a)[0], byArea.get(a)[1]));

        int globalScore = (int) Math.round(areas.stream().mapToInt(AreaScore::score).average().orElse(100));
        long nCrit = findings.stream().filter(f -> f.severity() == Severity.CRITICAL).count();
        long nWarn = findings.stream().filter(f -> f.severity() == Severity.WARNING).count();
        long nInfo = findings.stream().filter(f -> f.severity() == Severity.INFO).count();
        double totalImpact = findings.stream().mapToDouble(Finding::impactEur).sum();

        String verdict, color;
        if (globalScore >= 85)      { verdict = "EXCELENTE"; color = "#05CD99"; }
        else if (globalScore >= 70) { verdict = "SALUDABLE"; color = "#8BC34A"; }
        else if (globalScore >= 55) { verdict = "ACEPTABLE"; color = "#FFB547"; }
        else if (globalScore >= 40) { verdict = "REVISAR";   color = "#FF8A65"; }
        else                         { verdict = "CRITICO";   color = "#EE5D50"; }

        findings.sort(Comparator
            .comparing((Finding f) -> f.severity().ordinal())
            .thenComparing(Comparator.comparingDouble(Finding::impactEur).reversed()));

        return new AuditReport(globalScore, verdict, color,
            props.size(), (int)nCrit, (int)nWarn, (int)nInfo, totalImpact,
            areas, findings, today);
    }

    // ──────── helpers ────────
    private List<Map<String, Object>> safe(List<Map<String, Object>> l) {
        return l == null ? List.of() : l;
    }
    private String str(Object o) { return o == null ? null : String.valueOf(o); }
    private double num(Object o) {
        try { return o == null ? 0 : Double.parseDouble(String.valueOf(o)); }
        catch (Exception e) { return 0; }
    }
    private LocalDate parseDate(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        if (s.isBlank() || "null".equals(s)) return null;
        try { return LocalDate.parse(s.substring(0, Math.min(10, s.length())), ISO); }
        catch (Exception e) { return null; }
    }
    private String fmtEur(double v) {
        return String.format(Locale.GERMANY, ("%,.0f " + PaisActual.moneda()), v);
    }
    private String fmtPct(double v) {
        return String.format(Locale.US, "%.2f%%", v);
    }
    private String fmtRatio(double v) {
        return String.format(Locale.US, "%.2fx", v);
    }
}
