package com.inmobilia;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lo que el código pide a la base de datos tiene que existir en el esquema:
 *  - update/delete "por inmobiliaria" (con uid) solo en tablas con columna user_id: si no,
 *    PostgREST da error y nada se guarda ni se borra, sin que nadie se entere;
 *  - selectFilter/findBy sobre tablas y columnas que existen (si no, la pantalla sale vacía).
 */
class ColumnasDelCodigoTest {

    private static final Pattern ESCRITURA = Pattern.compile("supabase\\.(update|delete)\\(\\s*\"(\\w+)\"\\s*,([^;]*?)\\);");
    private static final Pattern LECTURA = Pattern.compile("supabase\\.selectFilter\\(\\s*\"(\\w+)\"\\s*,\\s*\"([^\"]*)\"");
    private static final Pattern FINDBY = Pattern.compile("supabase\\.(?:findBy|query)\\(\\s*\"(\\w+)\"");

    static int argumentos(String s) {
        int nivel = 0, n = 1;
        for (char c : s.toCharArray()) {
            if (c == '(' || c == '{' || c == '[') nivel++;
            if (c == ')' || c == '}' || c == ']') nivel--;
            if (c == ',' && nivel == 0) n++;
        }
        return n;
    }

    @Test
    void escriturasYLecturasUsanTablasYColumnasQueExisten() throws Exception {
        EsquemaSql esquema = EsquemaSql.delProyecto();
        List<String> fallos = new ArrayList<>();
        try (var s = Files.walk(Path.of("src/main/java"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                String nombre = f.getFileName().toString();
                Matcher m = ESCRITURA.matcher(src);
                while (m.find()) {
                    String op = m.group(1), tabla = m.group(2), resto = m.group(3);
                    Map<String, EsquemaSql.Columna> cols = esquema.tablas.get(tabla);
                    if (cols == null) { fallos.add(nombre + ": " + op + " en tabla inexistente " + tabla); continue; }
                    int n = argumentos(resto);  // argumentos tras la tabla
                    boolean conUid = op.equals("update") ? n == 3 : n == 2;
                    String ultimo = resto.substring(resto.lastIndexOf(',') + 1).trim();
                    if (conUid && !ultimo.equals("null") && !tabla.equals("users") && !cols.containsKey("user_id"))
                        fallos.add(nombre + ": " + op + "(\"" + tabla + "\", …, uid) pero " + tabla + " no tiene user_id");
                }
                Matcher l = LECTURA.matcher(src);
                while (l.find()) {
                    String tabla = l.group(1);
                    Map<String, EsquemaSql.Columna> cols = esquema.tablas.get(tabla);
                    if (cols == null) { fallos.add(nombre + ": lee la tabla inexistente " + tabla); continue; }
                    for (String par : l.group(2).split("&")) {
                        int i = par.indexOf('=');
                        if (i <= 0) continue;
                        String col = par.substring(0, i);
                        if (Set.of("select", "order", "limit", "offset", "or", "and").contains(col)) {
                            if (col.equals("order")) for (String o : par.substring(i + 1).split(",")) {
                                String c = o.split("\\.")[0];
                                if (!c.isBlank() && !cols.containsKey(c)) fallos.add(nombre + ": ordena " + tabla + " por " + c + ", que no existe");
                            }
                            continue;
                        }
                        if (!col.contains("->") && !cols.containsKey(col)) fallos.add(nombre + ": filtra " + tabla + " por " + col + ", que no existe");
                    }
                }
                Matcher q = FINDBY.matcher(src);
                while (q.find()) if (!esquema.tablas.containsKey(q.group(1))) fallos.add(nombre + ": lee la tabla inexistente " + q.group(1));
            }
        }
        Files.writeString(Path.of("target", "columnas-del-codigo.txt"), String.join("\n", new TreeSet<>(fallos)));
        assertTrue(fallos.isEmpty(), "Consultas a tablas/columnas que no existen:\n" + String.join("\n", new TreeSet<>(fallos)));
    }
}
