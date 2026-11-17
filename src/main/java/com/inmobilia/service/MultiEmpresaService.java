package com.inmobilia.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Gestión multi-empresa: las sociedades de la cuenta y a cuál pertenece cada activo
 * (propiedades.sociedad_id), con la vista consolidada de todo el patrimonio y de cada sociedad.
 * Business incluye 3 sociedades (más las adicionales compradas); Enterprise, ilimitadas.
 */
@Service
@RequiredArgsConstructor
public class MultiEmpresaService {

    private final SupabaseService supabase;
    private final PlanService planes;

    public List<Map<String, Object>> sociedades(String org) {
        return supabase.selectFilter("sociedades", "user_id=eq." + org + "&order=nombre.asc&limit=500");
    }

    public List<Map<String, Object>> activos(String org) {
        return supabase.selectFilter("propiedades", "user_id=eq." + org + "&order=name.asc&limit=5000");
    }

    /** Totales de un grupo de activos. */
    public record Resumen(String id, String nombre, String cif, int activos, double valor, double rentaMensual,
                          double gastosMensuales, double rentabilidadBruta) {
        public double netoMensual() { return rentaMensual - gastosMensuales; }
    }

    /** Una línea por sociedad, otra para los activos sin sociedad (id null) y el total (id "total"). */
    public List<Resumen> resumen(String org, List<Map<String, Object>> sociedades, List<Map<String, Object>> activos) {
        Map<String, List<Map<String, Object>>> porSociedad = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> s : sociedades) ids.add(String.valueOf(s.get("id")));
        for (Map<String, Object> a : activos) {
            String s = str(a.get("sociedad_id"));
            porSociedad.computeIfAbsent(ids.contains(s) ? s : "", k -> new ArrayList<>()).add(a);
        }
        List<Resumen> out = new ArrayList<>();
        for (Map<String, Object> s : sociedades) {
            String id = String.valueOf(s.get("id"));
            out.add(totales(id, str(s.get("nombre")), str(s.get("cif")), porSociedad.getOrDefault(id, List.of())));
        }
        List<Map<String, Object>> sin = porSociedad.getOrDefault("", List.of());
        if (!sin.isEmpty()) out.add(totales(null, "Sin sociedad", "", sin));
        out.add(totales("total", "Todo el patrimonio", "", activos));
        return out;
    }

    static Resumen totales(String id, String nombre, String cif, List<Map<String, Object>> activos) {
        double valor = 0, renta = 0, gastos = 0;
        for (Map<String, Object> a : activos) {
            double v = num(a.get("current_value"));
            valor += v > 0 ? v : num(a.get("purchase_price"));
            renta += num(a.get("rental_income"));
            gastos += num(a.get("monthly_expenses")) + num(a.get("mortgage_payment"));
        }
        double rent = valor > 0 ? renta * 12 / valor * 100 : 0;
        return new Resumen(id, nombre, cif, activos.size(), valor, renta, gastos, Math.round(rent * 10) / 10.0);
    }

    public record Resultado(boolean ok, String mensaje) {}

    public Resultado crear(String org, String nombre, String cif, String notas) {
        if (nombre == null || nombre.isBlank()) return new Resultado(false, "Pon el nombre de la sociedad.");
        Map<String, Object> user = titular(org);
        int max = planes.maxSociedades(user);
        int ahora = sociedades(org).size();
        if (max >= 0 && ahora >= max) {
            return new Resultado(false, max == 0 ? "La gestión multi-empresa está en el plan Business."
                    : "Tu plan incluye " + max + " sociedades y ya las tienes. Añade sociedades adicionales ("
                      + com.inmobilia.util.PreciosPlataforma.texto(com.inmobilia.util.PreciosPlataforma.SOCIEDAD_EXTRA) + "/mes cada una) en Facturación.");
        }
        Map<String, Object> fila = new LinkedHashMap<>();
        fila.put("user_id", org);
        fila.put("nombre", recortar(nombre, 150));
        if (cif != null && !cif.isBlank()) fila.put("cif", recortar(cif.trim().toUpperCase(), 20));
        if (notas != null && !notas.isBlank()) fila.put("notas", recortar(notas, 1000));
        fila.put("created_at", OffsetDateTime.now().toString());
        return supabase.insert("sociedades", fila) != null ? new Resultado(true, "Sociedad creada: " + nombre.trim() + ".")
                : new Resultado(false, "No se pudo guardar (¿falta ejecutar la migración V93 en Supabase?).");
    }

    public Resultado editar(String org, String id, String nombre, String cif, String notas) {
        if (nombre == null || nombre.isBlank()) return new Resultado(false, "Pon el nombre de la sociedad.");
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("nombre", recortar(nombre, 150));
        datos.put("cif", cif == null || cif.isBlank() ? SupabaseService.VACIAR : recortar(cif.trim().toUpperCase(), 20));
        datos.put("notas", notas == null || notas.isBlank() ? SupabaseService.VACIAR : recortar(notas, 1000));
        return supabase.update("sociedades", id, datos, org) ? new Resultado(true, "Sociedad guardada.") : new Resultado(false, "No se pudo guardar.");
    }

    /** Borra la sociedad; sus activos quedan sin sociedad (no se borra ningún activo). */
    public Resultado borrar(String org, String id) {
        if (!esSuya(org, id)) return new Resultado(false, "Esa sociedad no existe.");
        for (Map<String, Object> a : supabase.selectFilter("propiedades", "select=id&user_id=eq." + org + "&sociedad_id=eq." + id + "&limit=5000"))
            supabase.update("propiedades", String.valueOf(a.get("id")), Map.of("sociedad_id", SupabaseService.VACIAR), org);
        return supabase.delete("sociedades", id, org) ? new Resultado(true, "Sociedad borrada. Sus activos quedan sin sociedad.")
                : new Resultado(false, "No se pudo borrar.");
    }

    /** Asigna activos a sociedades ("" = sin sociedad). Solo toca los que cambian. */
    public int asignar(String org, Map<String, String> activoASociedad) {
        Set<String> validas = new HashSet<>();
        for (Map<String, Object> s : sociedades(org)) validas.add(String.valueOf(s.get("id")));
        Map<String, String> actuales = new HashMap<>();
        for (Map<String, Object> a : supabase.selectFilter("propiedades", "select=id,sociedad_id&user_id=eq." + org + "&limit=5000"))
            actuales.put(String.valueOf(a.get("id")), str(a.get("sociedad_id")));
        int cambiados = 0;
        for (var e : activoASociedad.entrySet()) {
            String activo = e.getKey(), sociedad = e.getValue() == null ? "" : e.getValue().trim();
            if (!actuales.containsKey(activo) || actuales.get(activo).equals(sociedad)) continue;   // no es suyo o no cambia
            if (!sociedad.isEmpty() && !validas.contains(sociedad)) continue;
            Object valor = sociedad.isEmpty() ? SupabaseService.VACIAR : sociedad;
            if (supabase.update("propiedades", activo, Map.of("sociedad_id", valor), org)) cambiados++;
        }
        return cambiados;
    }

    private boolean esSuya(String org, String id) {
        return !supabase.selectFilter("sociedades", "select=id&id=eq." + id + "&user_id=eq." + org + "&limit=1").isEmpty();
    }

    private Map<String, Object> titular(String org) {
        List<Map<String, Object>> r = supabase.selectFilter("users", "id=eq." + org + "&limit=1");
        return r.isEmpty() ? null : r.get(0);
    }

    private static String recortar(String s, int max) {
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static double num(Object o) {
        Double d = com.inmobilia.util.Numeros.decimal(o);
        return d == null ? 0 : d;
    }

    private static String str(Object o) {
        return o == null || "null".equals(String.valueOf(o)) ? "" : String.valueOf(o).trim();
    }
}
