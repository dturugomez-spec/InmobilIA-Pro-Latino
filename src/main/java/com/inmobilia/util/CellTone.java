package com.inmobilia.util;

import com.inmobilia.controller.CrudController;

import java.util.Map;
import java.util.Set;

/**
 * Helper expuesto en Thymeleaf como ${cellTone} para decidir el color de badge
 * segun el valor de status/priority/etapa. Evita ternarios anidados en templates.
 */
public class CellTone {

    private static final Set<String> OK = Set.of(
            "Ocupado", "Completada", "Completado", "Cerrado", "Pagado",
            "Activo", "Firmado", "Cualificado", "Ganado", "Aprobado"
    );
    private static final Set<String> ERR = Set.of(
            "Moroso", "Urgente", "Vencido", "Pendiente", "Rechazado", "Perdido"
    );
    private static final Set<String> WARN = Set.of(
            "Libre", "Reservado", "En Reforma", "Alta", "En curso",
            "Media", "Contactado", "Visita", "Oferta", "Nuevo"
    );

    /** Devuelve 'ok' | 'err' | 'warn' | '' */
    public String of(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (OK.contains(s))   return "ok";
        if (ERR.contains(s))  return "err";
        if (WARN.contains(s)) return "warn";
        return "";
    }

    /** True si el valor del campo debe formatearse como EUR. */
    public boolean isEur(String name) {
        if (name == null) return false;
        return switch (name) {
            case "purchase_price", "current_value", "rental_income",
                 "monthly_expenses", "mortgage_payment", "amount", "rent",
                 "rent_amount", "deposit", "salary", "cost", "price",
                 "annual_cost", "coverage", "presupuesto" -> true;
            default -> false;
        };
    }

    /** dd/MM/yyyy a partir de "yyyy-MM-dd..." */
    public String fmtDate(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (s.length() < 10) return s;
        return s.substring(8, 10) + "/" + s.substring(5, 7) + "/" + s.substring(0, 4);
    }

    /** Corta texto largo con elipsis. */
    public String clip(Object v, int max) {
        if (v == null) return "";
        String s = String.valueOf(v);
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    /** Render HTML de celda segun field.type y nombre. Devuelve HTML inofensivo (escape manual). */
    public String render(Map<String, Object> row, CrudController.Field field) {
        Object val = row.get(field.name());
        if (val == null) return "<span class='muted'>—</span>";

        String name = field.name();
        String type = field.type().name();

        switch (type) {
            case "BOOL" -> {
                boolean b = (val instanceof Boolean b2) ? b2 : "true".equalsIgnoreCase(String.valueOf(val));
                return b ? "<span class='badge badge-ok'>Si</span>"
                         : "<span class='badge'>No</span>";
            }
            case "DATE" -> { return escape(fmtDate(val)); }
            case "TIME" -> { return escape(String.valueOf(val)); }
            case "NUM" -> {
                String n = fmtNumber(val);
                return escape(isEur(name) ? n + (" " + PaisActual.moneda()) : n);
            }
            case "INT" -> { return escape(String.valueOf(val)); }
            default -> {
                if ("status".equals(name) || "etapa".equals(name) || "priority".equals(name)) {
                    String tone = of(val);
                    String cls = tone.isEmpty() ? "badge" : "badge badge-" + tone;
                    return "<span class='" + cls + "'>" + escape(String.valueOf(val)) + "</span>";
                }
                return escape(clip(val, 48));
            }
        }
    }

    private String fmtNumber(Object v) {
        try {
            double d = Double.parseDouble(String.valueOf(v));
            return String.format(java.util.Locale.GERMAN, "%,.2f", d);
        } catch (Exception e) { return String.valueOf(v); }
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
