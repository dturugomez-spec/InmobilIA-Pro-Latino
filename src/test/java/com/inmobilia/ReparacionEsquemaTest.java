package com.inmobilia;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Genera tools/reparar_esquema.sql: crea en Supabase las tablas y columnas que el código espera
 * y falten, sin tocar nada de lo que ya hay (todo IF NOT EXISTS, sin NOT NULL nuevos). Como no
 * hay Flyway, una migración olvidada dejaba pantallas vacías: la consulta fallaba en silencio.
 * La prueba falla si el script se ha quedado atrás respecto a las migraciones.
 */
class ReparacionEsquemaTest {

    static final Path SALIDA = Path.of("tools", "reparar_esquema.sql");

    private static final Pattern CREAR = Pattern.compile("(?is)create table (?:if not exists )?(?:public\\.)?\"?(\\w+)\"?\\s*\\((.*?)\\)\\s*;");
    private static final Pattern ALTERAR = Pattern.compile("(?is)alter table (?:if exists )?(?:only )?(?:public\\.)?\"?(\\w+)\"?\\s+(.*?);");
    private static final Pattern ANADIR_TROZO = Pattern.compile("(?is)add column (?:if not exists )?\"?(\\w+)\"?\\s+(.+)");

    /** Separa por comas que no estén dentro de paréntesis, corchetes (ARRAY['es', 'en']) ni comillas. */
    static List<String> trocear(String s) {
        List<String> out = new ArrayList<>();
        int nivel = 0;
        boolean comilla = false;
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch == '\'') comilla = !comilla;
            if (!comilla && (ch == '(' || ch == '[')) nivel++;
            if (!comilla && (ch == ')' || ch == ']')) nivel--;
            if (!comilla && ch == ',' && nivel == 0) { out.add(b.toString()); b.setLength(0); } else b.append(ch);
        }
        out.add(b.toString());
        return out;
    }
    private static final Set<String> NO_COLUMNA = Set.of("primary", "unique", "constraint", "foreign", "check", "exclude");
    private static final Pattern FIN_TIPO = Pattern.compile("(?i)\\s+(not\\s+null|null|default|primary\\s+key|references|unique|check|generated|constraint|collate)\\b");

    /** Migraciones primero (por número de versión), luego los esquemas completos de la raíz. */
    static List<Path> ficheros() throws Exception {
        List<Path> v = new ArrayList<>();
        try (var s = Files.list(Path.of("src/main/resources/db/migration"))) { s.filter(p -> p.toString().endsWith(".sql")).forEach(v::add); }
        v.sort(Comparator.comparingInt(p -> Integer.parseInt(p.getFileName().toString().replaceAll("^V(\\d+)__.*", "$1"))));
        List<Path> raiz = new ArrayList<>();
        try (var s = Files.list(Path.of("."))) { s.filter(p -> p.toString().endsWith(".sql")).forEach(raiz::add); }
        // El más completo primero: sus CREATE TABLE son los que se usan si la tabla no está en una migración
        raiz.sort(Comparator.comparing((Path p) -> !p.getFileName().toString().startsWith("FULL_SCHEMA"))
                .thenComparing(p -> !p.getFileName().toString().startsWith("FRESH_INSTALL"))
                .thenComparing(p -> p.getFileName().toString()));
        List<Path> out = new ArrayList<>(v);
        out.addAll(raiz);
        return out;
    }

    /** Tipo y DEFAULT de una definición de columna, sin NOT NULL, claves ni referencias. */
    static String tipoYDefecto(String def) {
        String resto = def.trim().replaceFirst("^\"?\\w+\"?\\s+", "");
        Matcher m = FIN_TIPO.matcher(resto);
        String tipo = m.find() ? resto.substring(0, m.start()).trim() : resto.trim();
        Matcher d = Pattern.compile("(?i)\\bdefault\\s+").matcher(resto);
        String defecto = "";
        if (d.find()) {
            int i = d.end(), nivel = 0;
            StringBuilder b = new StringBuilder();
            boolean comilla = false;
            for (; i < resto.length(); i++) {
                char c = resto.charAt(i);
                if (c == '\'') comilla = !comilla;
                if (!comilla) {
                    if (c == '(' || c == '[') nivel++;
                    if (c == ')' || c == ']') nivel--;
                    if (nivel == 0 && Character.isWhitespace(c)) {
                        String sig = resto.substring(i).trim().toLowerCase();
                        if (sig.matches("(?s)^(not\\s+null|null|primary|references|unique|check|generated|constraint|collate).*")) break;
                    }
                }
                b.append(c);
            }
            defecto = " DEFAULT " + b.toString().trim();
        }
        if (tipo.equalsIgnoreCase("serial")) tipo = "INTEGER";
        if (tipo.equalsIgnoreCase("bigserial")) tipo = "BIGINT";
        return tipo + defecto;
    }

    static String generar() throws Exception {
        Map<String, String> crear = new LinkedHashMap<>();             // tabla → CREATE TABLE
        Map<String, Map<String, String>> columnas = new LinkedHashMap<>(); // tabla → columna → tipo+default
        for (Path f : ficheros()) {
            String sql = Files.readString(f).replaceAll("--[^\\n]*", "");
            Matcher m = CREAR.matcher(sql);
            while (m.find()) {
                String tabla = m.group(1).toLowerCase();
                crear.putIfAbsent(tabla, "CREATE TABLE IF NOT EXISTS " + tabla + " (" + m.group(2).trim() + ")");
                Map<String, String> cols = columnas.computeIfAbsent(tabla, k -> new LinkedHashMap<>());
                for (String p : trocear(m.group(2))) {
                    String[] w = p.trim().split("\\s+");
                    // Restricciones de tabla, también sin espacio: UNIQUE(user_id, slug)
                    if (w.length < 2 || NO_COLUMNA.contains(w[0].toLowerCase().replaceAll("\\(.*", ""))
                            || !w[0].matches("\"?[A-Za-z_][A-Za-z0-9_]*\"?")) continue;
                    if (p.toLowerCase().contains("primary key") || p.toLowerCase().contains("generated")) continue;
                    cols.putIfAbsent(w[0].toLowerCase().replace("\"", ""), tipoYDefecto(p));
                }
            }
            Matcher a = ALTERAR.matcher(sql);
            while (a.find()) {
                // Trozos separados por comas de primer nivel: NUMERIC(10,2) y textos con comas enteros
                for (String trozo : trocear(a.group(2))) {
                    Matcher c = ANADIR_TROZO.matcher(trozo.trim());
                    if (c.matches()) columnas.computeIfAbsent(a.group(1).toLowerCase(), k -> new LinkedHashMap<>())
                            .putIfAbsent(c.group(1).toLowerCase(), tipoYDefecto(c.group(1) + " " + c.group(2)));
                }
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("-- Reparación del esquema de InmobilIA Pro (generado por ReparacionEsquemaTest; no editar a mano)\n");
        out.append("-- Crea las tablas y columnas que la aplicación usa y falten. NO borra ni cambia nada de lo\n");
        out.append("-- que ya existe: todo es IF NOT EXISTS y las columnas nuevas no son obligatorias.\n");
        out.append("-- Se puede ejecutar todas las veces que haga falta. Supabase > SQL Editor > pegar entero > Run.\n");
        out.append("-- Cada sentencia va protegida: si una falla, se avisa (NOTICE) y sigue con las demás.\n\n");
        for (var e : crear.entrySet()) {
            out.append("DO $rep$ BEGIN ").append(e.getValue().replaceAll("\\s+", " ")).append("; ")
               .append("EXCEPTION WHEN others THEN RAISE NOTICE 'tabla ").append(e.getKey()).append(": %', SQLERRM; END $rep$;\n");
        }
        out.append("\n");
        for (var t : columnas.entrySet()) {
            for (var c : t.getValue().entrySet()) {
                out.append("DO $rep$ BEGIN ALTER TABLE ").append(t.getKey()).append(" ADD COLUMN IF NOT EXISTS ")
                   .append(c.getKey()).append(' ').append(c.getValue()).append("; ")
                   .append("EXCEPTION WHEN others THEN RAISE NOTICE '").append(t.getKey()).append('.').append(c.getKey())
                   .append(": %', SQLERRM; END $rep$;\n");
            }
        }
        out.append("\n-- Que la API de Supabase vea ya las tablas y columnas nuevas\nNOTIFY pgrst, 'reload schema';\n");
        out.append("SELECT 'Esquema reparado' AS resultado;\n");
        return out.toString();
    }

    @Test
    void elScriptDeReparacionEstaAlDia() throws Exception {
        String nuevo = generar();
        String actual = Files.exists(SALIDA) ? Files.readString(SALIDA) : "";
        if (!nuevo.equals(actual)) Files.writeString(SALIDA, nuevo);
        assertTrue(nuevo.contains("ADD COLUMN IF NOT EXISTS hash_actual"));
        assertFalse(nuevo.toLowerCase().contains("drop "), "nunca borra nada");
        assertFalse(nuevo.contains("NOT NULL;"), "no añade columnas obligatorias");
        // Cada sentencia completa y equilibrada: un solo fallo de sintaxis tumbaba el script entero
        List<String> rotas = new ArrayList<>();
        for (String linea : nuevo.split("\n")) {
            if (!linea.startsWith("DO $rep$")) continue;
            String cuerpo = linea.substring("DO $rep$".length(), Math.max("DO $rep$".length(), linea.length() - "END $rep$;".length()));
            if (!linea.endsWith("END $rep$;") || !equilibrada(linea) || cuerpo.contains("$") || puntosYComa(cuerpo) != 2) rotas.add(linea);
        }
        assertTrue(rotas.isEmpty(), "Sentencias mal formadas:\n" + String.join("\n", rotas));
        assertTrue(nuevo.contains("ARRAY['es', 'en']"), "los arrays con comas no se parten");
        assertEquals(nuevo, Files.readString(SALIDA));
    }

    /** Punto y coma fuera de comillas: la sentencia y el RAISE NOTICE, nada más. */
    static int puntosYComa(String s) {
        int n = 0;
        boolean comilla = false;
        for (char c : s.toCharArray()) {
            if (c == '\'') comilla = !comilla;
            else if (!comilla && c == ';') n++;
        }
        return n;
    }

    /** Paréntesis y corchetes cerrados y comillas pares (fuera de comillas). */
    static boolean equilibrada(String s) {
        int par = 0, cor = 0;
        boolean comilla = false;
        for (char c : s.toCharArray()) {
            if (c == '\'') { comilla = !comilla; continue; }
            if (comilla) continue;
            if (c == '(') par++;
            if (c == ')') par--;
            if (c == '[') cor++;
            if (c == ']') cor--;
            if (par < 0 || cor < 0) return false;
        }
        return par == 0 && cor == 0 && !comilla;
    }

    @Test
    void tiposYDefectos() {
        assertEquals("NUMERIC(12,2) DEFAULT 0", tipoYDefecto("precio NUMERIC(12,2) DEFAULT 0 NOT NULL"));
        assertEquals("UUID", tipoYDefecto("user_id UUID REFERENCES users(id) ON DELETE CASCADE"));
        assertEquals("TIMESTAMPTZ DEFAULT NOW()", tipoYDefecto("created_at TIMESTAMPTZ DEFAULT NOW()"));
        assertEquals("TEXT DEFAULT 'pendiente'", tipoYDefecto("estado TEXT DEFAULT 'pendiente' "));
        assertEquals("JSONB DEFAULT '{}'::jsonb", tipoYDefecto("meta JSONB DEFAULT '{}'::jsonb"));
        assertEquals("TEXT[]", tipoYDefecto("tags TEXT[]"));
    }
}
