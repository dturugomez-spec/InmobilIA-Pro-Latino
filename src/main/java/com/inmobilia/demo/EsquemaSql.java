package com.inmobilia.demo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Esquema de la base de datos leído de los .sql del proyecto (migraciones y esquemas completos),
 * para que la Supabase en memoria rechace lo mismo que la real: columnas inexistentes,
 * obligatorias que faltan, tipos que no encajan y valores fuera de un CHECK (... IN ...).
 * Si una tabla se define en varios ficheros, vale lo más permisivo.
 */
public class EsquemaSql {

    public record Columna(String tipo, boolean obligatoria, Set<String> permitidos) {}

    public final Map<String, Map<String, Columna>> tablas = new HashMap<>();
    /** Estricto: una columna es obligatoria si lo es en CUALQUIER definición (la base real puede venir de esa). */
    private boolean estricto;

    public static EsquemaSql estricto() throws Exception {
        EsquemaSql e = new EsquemaSql();
        e.estricto = true;
        return e.cargar();
    }

    /** Desde la carpeta del proyecto (pruebas). */
    public static EsquemaSql delProyecto() throws Exception {
        return new EsquemaSql().cargar();
    }

    private EsquemaSql cargar() throws Exception {
        List<Path> ficheros = new ArrayList<>();
        try (var s = Files.list(Path.of("src/main/resources/db/migration"))) { s.filter(p -> p.toString().endsWith(".sql")).forEach(ficheros::add); }
        try (var s = Files.list(Path.of("."))) { s.filter(p -> p.toString().endsWith(".sql")).forEach(ficheros::add); }
        for (Path f : ficheros) leer(Files.readString(f));
        return this;
    }

    /**
     * Desde dentro del .jar (demo local): las migraciones y los esquemas completos de la raíz,
     * que Maven copia a db/esquema.
     */
    public static EsquemaSql delClasspath() throws Exception {
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        EsquemaSql e = new EsquemaSql();
        for (String patron : List.of("classpath*:db/migration/*.sql", "classpath*:db/esquema/*.sql")) {
            var ficheros = new ArrayList<>(List.of(resolver.getResources(patron)));
            ficheros.sort(Comparator.comparing(r -> String.valueOf(r.getFilename())));
            for (var r : ficheros) e.leer(r.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (e.tablas.isEmpty()) throw new IllegalStateException("No hay esquema SQL dentro de la aplicación");
        return e;
    }

    private static final Pattern CREAR = Pattern.compile("(?is)create table (?:if not exists )?(?:public\\.)?\"?(\\w+)\"?\\s*\\((.*?)\\)\\s*;");
    private static final Pattern ALTERAR = Pattern.compile("(?is)alter table (?:if exists )?(?:only )?(?:public\\.)?\"?(\\w+)\"?\\s+(.*?);");
    private static final Pattern ANADIR = Pattern.compile("(?is)add column (?:if not exists )?\"?(\\w+)\"?\\s+([^,]+)");
    private static final Pattern CHECK_IN = Pattern.compile("(?is)check\\s*\\(\\s*\"?(\\w+)\"?\\s+in\\s*\\(([^)]*)\\)");
    private static final Set<String> NO_COLUMNA = Set.of("primary", "unique", "constraint", "foreign", "check", "exclude");

    private void leer(String sql) {
        sql = sql.replaceAll("--[^\\n]*", "");
        Matcher m = CREAR.matcher(sql);
        while (m.find()) {
            String tabla = m.group(1).toLowerCase();
            Map<String, Columna> nuevas = new HashMap<>();
            for (String parte : trocear(m.group(2))) {
                String p = parte.trim();
                String[] w = p.split("\\s+");
                if (w.length < 2 || w[0].isEmpty()) continue;
                if (NO_COLUMNA.contains(w[0].toLowerCase())) {
                    Matcher c = CHECK_IN.matcher(p);
                    if (c.find() && nuevas.containsKey(c.group(1).toLowerCase())) {
                        String col = c.group(1).toLowerCase();
                        Columna x = nuevas.get(col);
                        nuevas.put(col, new Columna(x.tipo(), x.obligatoria(), valores(c.group(2))));
                    }
                    continue;
                }
                nuevas.put(w[0].toLowerCase().replace("\"", ""), columna(p));
            }
            fusionar(tabla, nuevas, true);
        }
        Matcher a = ALTERAR.matcher(sql);
        while (a.find()) {
            String tabla = a.group(1).toLowerCase();
            Matcher c = ANADIR.matcher(a.group(2));
            while (c.find()) {
                Map<String, Columna> nueva = new HashMap<>();
                nueva.put(c.group(1).toLowerCase(), columna(c.group(1) + " " + c.group(2)));
                fusionar(tabla, nueva, false);
            }
        }
    }

    /**
     * Junta una definición con lo que ya había. Con dos CREATE TABLE distintos de la misma tabla
     * no se sabe cuál se ejecutó en la base real: lo que solo exige uno de ellos no se exige.
     */
    private void fusionar(String tabla, Map<String, Columna> nuevas, boolean esCreate) {
        Map<String, Columna> t = tablas.computeIfAbsent(tabla, k -> new HashMap<>());
        boolean yaHabia = esCreate && !t.isEmpty() && !estricto;
        if (estricto) {
            for (var e : nuevas.entrySet()) {
                Columna prev = t.get(e.getKey()), n = e.getValue();
                t.put(e.getKey(), prev == null ? n : new Columna(prev.tipo(), prev.obligatoria() || n.obligatoria(),
                        prev.permitidos() == null ? n.permitidos() : prev.permitidos()));
            }
            return;
        }
        for (var e : nuevas.entrySet()) {
            Columna prev = t.get(e.getKey());
            Columna n = e.getValue();
            if (prev == null) {
                t.put(e.getKey(), yaHabia ? new Columna(n.tipo(), false, n.permitidos()) : n);
            } else {
                Set<String> perm = prev.permitidos() == null || n.permitidos() == null ? null : union(prev.permitidos(), n.permitidos());
                t.put(e.getKey(), new Columna(prev.tipo(), prev.obligatoria() && n.obligatoria(), perm));
            }
        }
        if (yaHabia) {
            for (var e : new HashMap<>(t).entrySet())
                if (!nuevas.containsKey(e.getKey()) && e.getValue().obligatoria())
                    t.put(e.getKey(), new Columna(e.getValue().tipo(), false, e.getValue().permitidos()));
        }
    }

    private static Set<String> union(Set<String> a, Set<String> b) { Set<String> s = new HashSet<>(a); s.addAll(b); return s; }

    private static Columna columna(String def) {
        String d = def.toLowerCase();
        String[] w = def.trim().split("\\s+");
        String tipo = w.length > 1 ? w[1].toLowerCase().replaceAll("\\(.*", "") : "text";
        if (d.matches("(?s).*\\[\\].*")) tipo = "array";
        boolean pk = d.contains("primary key");
        boolean obligatoria = !pk && d.contains("not null") && !d.contains("default") && !tipo.contains("serial");
        Matcher c = CHECK_IN.matcher(def);
        return new Columna(tipo, obligatoria, c.find() ? valores(c.group(2)) : null);
    }

    private static Set<String> valores(String lista) {
        Set<String> s = new HashSet<>();
        for (String v : lista.split(",")) s.add(v.trim().replaceAll("^'|'$", "").replaceAll("::\\w+", "").replaceAll("^'|'$", ""));
        return s;
    }

    private static List<String> trocear(String s) {
        List<String> out = new ArrayList<>();
        int nivel = 0;
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch == '(') nivel++;
            if (ch == ')') nivel--;
            if (ch == ',' && nivel == 0) { out.add(b.toString()); b.setLength(0); } else b.append(ch);
        }
        out.add(b.toString());
        return out;
    }

    /** Motivo por el que Postgres rechazaría la fila, o null si la aceptaría. */
    public String rechazo(String tabla, Map<String, Object> fila) {
        Map<String, Columna> t = tablas.get(tabla);
        if (t == null) return "la tabla " + tabla + " no existe";
        for (var e : fila.entrySet()) {
            Columna c = t.get(e.getKey());
            if (c == null) return "Could not find the '" + e.getKey() + "' column of '" + tabla + "'";
            Object v = e.getValue();
            if (v == null) {
                if (c.obligatoria()) return "null value in column \"" + e.getKey() + "\" violates not-null constraint";
                continue;
            }
            String malo = tipoMalo(c.tipo(), v);
            if (malo != null) return "invalid input syntax for type " + c.tipo() + " (" + e.getKey() + "): \"" + malo + "\"";
            if (c.permitidos() != null && !c.permitidos().contains(String.valueOf(v)))
                return "new row violates check constraint on " + e.getKey() + " = " + v + " (permitidos " + c.permitidos() + ")";
        }
        for (var e : t.entrySet())
            if (e.getValue().obligatoria() && !fila.containsKey(e.getKey()))
                return "null value in column \"" + e.getKey() + "\" violates not-null constraint";
        return null;
    }

    /** Valor tal como lo devolvería Postgres por la API (texto como texto, números como números). */
    public Object comoPostgres(String tabla, String col, Object v) {
        Map<String, Columna> t = tablas.get(tabla);
        Columna c = t == null ? null : t.get(col);
        if (c == null || v == null) return v;
        String tipo = c.tipo();
        if (v instanceof Map || v instanceof List) return v;
        String s = String.valueOf(v);
        try {
            return switch (tipo) {
                case "int", "integer", "smallint", "int4", "serial" -> v instanceof Number n ? n.intValue() : Integer.parseInt(s);
                case "bigint", "int8", "bigserial" -> v instanceof Number n ? n.longValue() : Long.parseLong(s);
                case "numeric", "decimal", "real", "float", "float8", "double" -> v instanceof Number n ? n.doubleValue() : Double.parseDouble(s);
                case "boolean", "bool" -> v instanceof Boolean ? v : Boolean.parseBoolean(s);
                case "jsonb", "json", "array" -> v;
                default -> s;   // text, varchar, uuid, date, timestamptz…
            };
        } catch (Exception e) {
            return v;
        }
    }

    private static final Pattern UUID_RE = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static String tipoMalo(String tipo, Object v) {
        String s = String.valueOf(v);
        switch (tipo) {
            case "uuid": return UUID_RE.matcher(s).matches() ? null : s;
            case "int": case "integer": case "bigint": case "smallint": case "int4": case "int8": case "serial": case "bigserial":
                if (v instanceof Integer || v instanceof Long || v instanceof Short) return null;
                if (v instanceof Number n) return n.doubleValue() == Math.rint(n.doubleValue()) ? null : s;
                return s.matches("-?\\d+") ? null : s;
            case "numeric": case "decimal": case "real": case "float": case "float8": case "double":
                if (v instanceof Number) return null;
                return s.matches("-?\\d+(\\.\\d+)?") ? null : s;
            case "boolean": case "bool":
                return v instanceof Boolean || s.equals("true") || s.equals("false") ? null : s;
            case "date":
                try { LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s); return null; } catch (Exception e) { return s; }
            case "timestamptz": case "timestamp":
                try { OffsetDateTime.parse(s); return null; } catch (Exception ignored) { }
                try { LocalDateTime.parse(s); return null; } catch (Exception ignored) { }
                try { LocalDate.parse(s); return null; } catch (Exception ignored) { }
                try { OffsetDateTime.parse(s.replace(' ', 'T')); return null; } catch (Exception ignored) { }
                return s;
            case "array":
                return v instanceof List || s.startsWith("{") ? null : s;
            case "jsonb": case "json":
                return null;
            default:
                // text/varchar: Postgres convierte números y booleanos, pero no objetos ni listas
                return v instanceof Map || v instanceof List ? (tipo.startsWith("text") || tipo.startsWith("varchar") || tipo.startsWith("char") ? s : null) : null;
        }
    }
}
