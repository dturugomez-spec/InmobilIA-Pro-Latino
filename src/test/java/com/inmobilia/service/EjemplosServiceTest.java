package com.inmobilia.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Ejemplos para demos. La prueba importante: cada tabla y columna que se siembra existe en el
 * esquema real (migraciones + esquema completo). Si no, Supabase rechazaría la fila y esa
 * herramienta saldría vacía en la demo.
 */
class EjemplosServiceTest {

    /** Columnas de cada tabla según los CREATE TABLE / ALTER TABLE ... ADD COLUMN de los .sql. */
    static Map<String, Set<String>> esquema() throws Exception {
        List<Path> ficheros = new ArrayList<>();
        try (var s = Files.list(Path.of("src/main/resources/db/migration"))) { s.filter(p -> p.toString().endsWith(".sql")).forEach(ficheros::add); }
        for (String f : List.of("FULL_SCHEMA_COMPLETO.sql", "supabase_schema.sql", "FRESH_INSTALL_SUPABASE.sql"))
            if (Files.exists(Path.of(f))) ficheros.add(Path.of(f));
        Map<String, Set<String>> cols = new HashMap<>();
        Pattern crear = Pattern.compile("(?is)create table (?:if not exists )?(?:public\\.)?(\\w+)\\s*\\((.*?)\\);");
        Pattern alterar = Pattern.compile("(?is)alter table (?:if exists )?(?:public\\.)?(\\w+)\\s+(.*?);");
        Pattern anadir = Pattern.compile("(?i)add column (?:if not exists )?(\\w+)");
        Set<String> noColumna = Set.of("primary", "unique", "constraint", "foreign", "check", "exclude");
        for (Path f : ficheros) {
            String t = Files.readString(f).replaceAll("--[^\\n]*", "");
            Matcher m = crear.matcher(t);
            while (m.find()) {
                Set<String> c = cols.computeIfAbsent(m.group(1).toLowerCase(), k -> new HashSet<>());
                int prof = 0;
                StringBuilder parte = new StringBuilder();
                List<String> partes = new ArrayList<>();
                for (char ch : m.group(2).toCharArray()) {
                    if (ch == '(') prof++;
                    if (ch == ')') prof--;
                    if (ch == ',' && prof == 0) { partes.add(parte.toString()); parte.setLength(0); } else parte.append(ch);
                }
                partes.add(parte.toString());
                for (String p : partes) {
                    String[] w = p.trim().split("\\s+");
                    if (w.length > 0 && !w[0].isEmpty() && !noColumna.contains(w[0].toLowerCase())) c.add(w[0].toLowerCase().replace("\"", ""));
                }
            }
            Matcher a = alterar.matcher(t);
            while (a.find()) {
                Matcher c = anadir.matcher(a.group(2));
                while (c.find()) cols.computeIfAbsent(a.group(1).toLowerCase(), k -> new HashSet<>()).add(c.group(1).toLowerCase());
            }
        }
        return cols;
    }

    private record Insercion(String tabla, Map<String, Object> fila) {}

    @Test
    void cadaTablaYColumnaSembradaExisteEnElEsquema() throws Exception {
        Map<String, Set<String>> esquema = esquema();
        SupabaseService supabase = mock(SupabaseService.class);
        List<Insercion> inserciones = new ArrayList<>();
        List<Insercion> actualizaciones = new ArrayList<>();
        List<Map<String, Object>> registro = new ArrayList<>();
        when(supabase.insert(anyString(), anyMap())).thenAnswer(inv -> {
            String tabla = inv.getArgument(0);
            Map<String, Object> fila = new HashMap<>(inv.getArgument(1));
            inserciones.add(new Insercion(tabla, fila));
            Map<String, Object> g = new HashMap<>(fila);
            g.put("id", UUID.randomUUID().toString());
            return g;
        });
        when(supabase.update(anyString(), anyString(), anyMap(), anyString())).thenAnswer(inv -> {
            actualizaciones.add(new Insercion(inv.getArgument(0), inv.getArgument(2)));
            return true;
        });
        when(supabase.insertarVarios(eq("ejemplos_demo"), anyList())).thenAnswer(inv -> {
            registro.addAll(inv.getArgument(1));
            return true;
        });
        when(supabase.selectFilter(anyString(), anyString())).thenReturn(List.of());

        EjemplosService s = new EjemplosService(supabase, new DemoDataService(supabase, new com.inmobilia.service.PaisService(supabase), new com.inmobilia.service.CurrencyService()));
        Map<String, Object> r = s.cargarAhora("org1");
        assertFalse(String.valueOf(r.get("mensaje")).startsWith("Error"), String.valueOf(r));

        List<String> fallos = new ArrayList<>();
        Set<String> tablas = new TreeSet<>();
        for (Insercion i : concat(inserciones, actualizaciones)) {
            if (i.tabla().equals("ejemplos_demo")) continue;
            tablas.add(i.tabla());
            Set<String> c = esquema.get(i.tabla());
            if (c == null) { fallos.add("tabla inexistente: " + i.tabla()); continue; }
            for (String k : i.fila().keySet()) if (!c.contains(k)) fallos.add(i.tabla() + "." + k);
        }
        assertTrue(fallos.isEmpty(), "Columnas que no existen en el esquema: " + new TreeSet<>(fallos));
        assertTrue(tablas.size() >= 60, "debe cubrir todas las herramientas; solo " + tablas.size() + ": " + tablas);

        // Todo lo guardado queda anotado para poder quitarlo (salvo la marca de inicio)
        long guardadas = inserciones.stream().filter(i -> !i.tabla().equals("ejemplos_demo")).count();
        assertEquals(guardadas, registro.size());
        assertTrue(registro.stream().allMatch(x -> "org1".equals(x.get("user_id"))));

        // Nada de ejemplo se publica en portales ni sale con emails reales
        assertTrue(actualizaciones.stream().filter(a -> a.tabla().equals("propiedades"))
                .allMatch(a -> Boolean.FALSE.equals(a.fila().get("publicar_portales"))));
        for (Insercion i : inserciones)
            for (Map.Entry<String, Object> e : i.fila().entrySet())
                if (e.getKey().contains("email") && !e.getKey().equals("de_email") // remitente de un email recibido: no se le escribe
                        && e.getValue() instanceof String v && v.contains("@"))
                    assertTrue(EmailService.esDireccionDeEjemplo(v), i.tabla() + "." + e.getKey() + " = " + v);
    }

    @Test
    void sinLaTablaDeRegistro_noSiembraNada() {
        SupabaseService supabase = mock(SupabaseService.class);
        when(supabase.selectFilter(anyString(), anyString())).thenReturn(List.of());
        when(supabase.insert(anyString(), anyMap())).thenReturn(null);
        when(supabase.lastError()).thenReturn("HTTP 404: relation \"public.ejemplos_demo\" does not exist");
        Map<String, Object> r = new EjemplosService(supabase, new DemoDataService(supabase, new com.inmobilia.service.PaisService(supabase), new com.inmobilia.service.CurrencyService())).cargarAhora("org1");
        assertTrue(String.valueOf(r.get("mensaje")).contains("V87"));
        verify(supabase, times(1)).insert(anyString(), anyMap());
    }

    @Test
    void sinConexion_noCulpaALaV87() {
        SupabaseService supabase = mock(SupabaseService.class);
        when(supabase.selectFilter(anyString(), anyString())).thenReturn(List.of());
        when(supabase.insert(anyString(), anyMap())).thenReturn(null);
        when(supabase.lastError()).thenReturn("HTTP 401: Invalid API key");
        String mensaje = String.valueOf(new EjemplosService(supabase, new DemoDataService(supabase, new com.inmobilia.service.PaisService(supabase), new com.inmobilia.service.CurrencyService())).cargarAhora("org1").get("mensaje"));
        assertFalse(mensaje.contains("V87"), mensaje);
        assertTrue(mensaje.contains("Invalid API key"), mensaje);
    }

    @Test
    void siYaHayEjemplos_noLosDuplica() {
        SupabaseService supabase = mock(SupabaseService.class);
        when(supabase.selectFilter(eq("ejemplos_demo"), anyString())).thenReturn(List.of(Map.of("id", 1, "tabla", "leads", "registro_id", "x")));
        Map<String, Object> r = new EjemplosService(supabase, new DemoDataService(supabase, new com.inmobilia.service.PaisService(supabase), new com.inmobilia.service.CurrencyService())).cargarAhora("org1");
        assertTrue(String.valueOf(r.get("mensaje")).contains("Ya hay ejemplos"));
        verify(supabase, never()).insert(anyString(), anyMap());
    }

    @Test
    void quitar_borraSoloLoAnotadoYLoQueCuelgaDeElloDeHijoAPadre() {
        SupabaseService supabase = mock(SupabaseService.class);
        String lead = "aaaaaaaa-0000-0000-0000-000000000001", prop = "bbbbbbbb-0000-0000-0000-000000000001";
        when(supabase.selectFilter(eq("ejemplos_demo"), anyString())).thenReturn(List.of(
                Map.of("id", 3, "tabla", "visitas", "registro_id", "v1"),
                Map.of("id", 2, "tabla", "leads", "registro_id", lead),
                Map.of("id", 1, "tabla", "propiedades", "registro_id", prop)));
        when(supabase.selectFilter(anyString(), argThat(f -> f != null && !f.contains("ejemplos_demo") && f.contains("=in.(")))).thenReturn(List.of());
        when(supabase.selectFilter(eq("tareas"), contains("lead_id=in.(" + lead))).thenReturn(List.of(Map.of("id", "t-creada-en-la-demo")));
        when(supabase.delete(anyString(), anyString())).thenReturn(true);
        when(supabase.delete(anyString(), anyString(), anyString())).thenReturn(true);

        Map<String, Object> r = new EjemplosService(supabase, new DemoDataService(supabase, new com.inmobilia.service.PaisService(supabase), new com.inmobilia.service.CurrencyService())).quitar("org1");
        assertEquals(3, r.get("quitadas"));
        assertEquals(1, r.get("relacionadas"));
        var orden = inOrder(supabase);
        orden.verify(supabase).delete("tareas", "t-creada-en-la-demo", "org1");
        orden.verify(supabase).delete("visitas", "v1");
        orden.verify(supabase).delete("leads", lead);
        orden.verify(supabase).delete("propiedades", prop);
        verify(supabase, never()).delete(eq("leads"), argThat(id -> !id.equals(lead)));
    }

    @Test
    void emailsDeEjemploNuncaSeEnvian() {
        assertTrue(EmailService.esDireccionDeEjemplo("ana.garcia@example.com"));
        assertTrue(EmailService.esDireccionDeEjemplo("info@invatocha.example.com"));
        assertTrue(EmailService.esDireccionDeEjemplo("x@prueba.test"));
        assertFalse(EmailService.esDireccionDeEjemplo("cliente@gmail.com"));
        assertFalse(EmailService.esDireccionDeEjemplo("info@examples.com"));
        assertFalse(EmailService.esDireccionDeEjemplo(null));
    }

    private static List<Insercion> concat(List<Insercion> a, List<Insercion> b) {
        List<Insercion> l = new ArrayList<>(a);
        l.addAll(b);
        return l;
    }
}
