package com.inmobilia.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Motor de automatizaciones: seguro, sin bloquear hilos y haciendo de verdad lo que dice. */
class WorkflowEngineTest {

    private final SupabaseService supabase = mock(SupabaseService.class);
    private final EmailService email = mock(EmailService.class);
    private final NotificationService notif = mock(NotificationService.class);
    private final WorkflowEngine motor = new WorkflowEngine(supabase, email, notif, mock(CampaignService.class),
            mock(PaisService.class));

    private static Map<String, Object> accion(String tipo, Map<String, Object> config) {
        return new HashMap<>(Map.of("tipo", tipo, "config", config));
    }

    @Test
    void noSeEscribeEnTablasNoPermitidasNiEnCamposProtegidos() {
        Map<String, Object> r = motor.ejecutarAccion(accion("change_status", Map.of("tabla", "users", "estado", "active", "registro_id", "u-otro")), new HashMap<>(), "org1");
        assertEquals(false, r.get("ok"));
        r = motor.ejecutarAccion(accion("update_field", Map.of("tabla", "users", "campo", "plan_status", "valor", "active", "registro_id", "org1")), new HashMap<>(), "org1");
        assertEquals(false, r.get("ok"));
        r = motor.ejecutarAccion(accion("update_field", Map.of("tabla", "leads", "campo", "user_id", "valor", "org2", "registro_id", "l1")), new HashMap<>(), "org1");
        assertEquals(false, r.get("ok"));
        verify(supabase, never()).update(anyString(), anyString(), anyMap(), any());
        verify(supabase, never()).update(anyString(), anyString(), anyMap());
    }

    @Test
    void lasEscriturasVanFiltradasPorLaInmobiliaria_yFacturasAntiguasFuncionan() {
        when(supabase.update(anyString(), anyString(), anyMap(), anyString())).thenReturn(true);
        motor.ejecutarAccion(accion("change_status", Map.of("tabla", "leads", "estado", "Contactado")), new HashMap<>(Map.of("id", "l1")), "org1");
        verify(supabase).update("leads", "l1", Map.of("etapa", "Contactado"), "org1");
        // Flujos antiguos: tabla "facturas" (no existe) y estado "morosa"
        motor.ejecutarAccion(accion("change_status", Map.of("tabla", "facturas", "estado", "morosa")), new HashMap<>(Map.of("id", "f1")), "org1");
        verify(supabase).update("invoices", "f1", Map.of("status", "Vencida"), "org1");
    }

    @Test
    void sinEmailDeDestino_noSeEnvia() {
        Map<String, Object> r = motor.ejecutarAccion(accion("send_email", Map.of("to", "{email}", "subject", "Hola", "body", "x")),
                new HashMap<>(Map.of("nombre", "Ana")), "org1");
        assertEquals(false, r.get("ok"));
        verifyNoInteractions(email);
    }

    @Test
    void completaLosDatosDelLeadQueFaltan() {
        when(supabase.selectOwned("leads", "l1", "org1")).thenReturn(List.of(Map.of("nombre", "Ana", "email", "ana@cliente.es")));
        Map<String, Object> p = new HashMap<>(Map.of("lead_id", "l1", "columna_nueva", "ganado"));
        motor.completarConLead(p, "org1");
        assertEquals("ana@cliente.es", p.get("email"));
        when(email.send(anyString(), anyString(), anyString())).thenReturn(new EmailService.SendResult(true, "ok"));
        Map<String, Object> r = motor.ejecutarAccion(accion("send_email", Map.of("to", "{email}", "subject", "Gracias {nombre}", "body", "x")), p, "org1");
        assertEquals(true, r.get("ok"));
        verify(email).send("ana@cliente.es", "Gracias Ana", "x");
    }

    @Test
    void laEsperaNoBloquea_seProgramaYSeReanudaDondeSeQuedo() {
        when(supabase.insert(anyString(), anyMap())).thenReturn(Map.of("id", 1));
        Map<String, Object> wf = new HashMap<>(Map.of("id", "w1", "nombre", "Seguimiento", "trigger_evento", "lead_creado",
                "acciones", List.of(accion("send_notification", Map.of("titulo", "1", "mensaje", "a")),
                        accion("wait_minutes", Map.of("minutos", 30)),
                        accion("send_notification", Map.of("titulo", "2", "mensaje", "b")))));
        long t0 = System.currentTimeMillis();
        motor.ejecutarWorkflow(wf, Map.of("id", "l1"), "org1");
        assertTrue(System.currentTimeMillis() - t0 < 5000, "no duerme el hilo");
        verify(notif, times(1)).create(eq("org1"), eq("1"), anyString(), anyString(), anyString());
        verify(notif, never()).create(eq("org1"), eq("2"), anyString(), anyString(), anyString());
        verify(supabase).insert(eq("workflow_ejecuciones"), argThat(m -> "pendiente".equals(m.get("estado"))
                && m.get("payload") instanceof Map<?, ?> p && Integer.valueOf(2).equals(p.get(WorkflowEngine.DESDE))));

        // Al reanudar: solo lo que quedaba
        reset(notif);
        motor.ejecutarWorkflow(wf, new HashMap<>(Map.of("id", "l1", WorkflowEngine.DESDE, 2)), "org1");
        verify(notif, never()).create(eq("org1"), eq("1"), anyString(), anyString(), anyString());
        verify(notif, times(1)).create(eq("org1"), eq("2"), anyString(), anyString(), anyString());
    }

    @Test
    void asignarAlAgenteConMenosCarga_buscaEnElEquipoReal() {
        when(supabase.selectFilter(eq("users"), contains("organization_id=eq.org1"))).thenReturn(List.of(Map.of("id", "a1"), Map.of("id", "a2")));
        when(supabase.selectFilter(eq("tareas"), contains("asignado_a=eq.a1"))).thenReturn(List.of(Map.of(), Map.of()));
        when(supabase.selectFilter(eq("tareas"), contains("asignado_a=eq.a2"))).thenReturn(List.of());
        assertEquals("a2", motor.pickLeastBusyAgent("org1"));
    }

    @Test
    void horarioLaboral_enLaZonaHorariaDeLaInmobiliaria() {
        com.inmobilia.util.PaisActual.set(com.inmobilia.util.Pais.de("CO"));
        ZoneId mad = ZoneId.of("America/Bogota");
        Instant sabado = ZonedDateTime.of(2026, 10, 24, 10, 0, 0, 0, mad).toInstant();
        assertEquals(ZonedDateTime.of(2026, 10, 26, 9, 0, 0, 0, mad).toInstant(), WorkflowEngine.siguienteHoraLaboral(sabado));
        Instant viernesNoche = ZonedDateTime.of(2026, 10, 23, 21, 30, 0, 0, mad).toInstant();
        assertEquals(ZonedDateTime.of(2026, 10, 26, 9, 0, 0, 0, mad).toInstant(), WorkflowEngine.siguienteHoraLaboral(viernesNoche));
        Instant martesTemprano = ZonedDateTime.of(2026, 10, 20, 7, 0, 0, 0, mad).toInstant();
        assertEquals(ZonedDateTime.of(2026, 10, 20, 9, 0, 0, 0, mad).toInstant(), WorkflowEngine.siguienteHoraLaboral(martesTemprano));
        Instant martesMediodia = ZonedDateTime.of(2026, 10, 20, 12, 0, 0, 0, mad).toInstant();
        assertEquals(martesMediodia, WorkflowEngine.siguienteHoraLaboral(martesMediodia));
        com.inmobilia.util.PaisActual.limpiar();
    }

    @Test
    void elEditorSoloOfreceEventosQueLaAppLanza() throws Exception {
        String editor = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/templates/workflows_form.html"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<option value=\"([a-z_]+)\"").matcher(editor);
        Set<String> eventos = new TreeSet<>();
        while (m.find()) if (m.group(1).contains("_") && !m.group(1).startsWith("send_") && !m.group(1).startsWith("create_")
                && !m.group(1).startsWith("change_") && !m.group(1).startsWith("update_") && !m.group(1).startsWith("assign_")
                && !m.group(1).startsWith("wait_")) eventos.add(m.group(1));
        assertFalse(eventos.isEmpty());
        assertTrue(WorkflowEngine.EVENTOS.containsAll(eventos), "eventos del editor que la app no lanza: " + eventos);
        // y cada evento se lanza desde algún sitio del código
        Set<String> lanzados = new TreeSet<>();
        try (var s = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            for (var f : s.filter(p -> p.toString().endsWith(".java")).toList()) {
                java.util.regex.Matcher t = java.util.regex.Pattern.compile("triggerEvent\\([^,]+,\\s*(?:[^,]*\\?\\s*)?\"([a-z_]+)\"").matcher(java.nio.file.Files.readString(f));
                while (t.find()) lanzados.add(t.group(1));
                java.util.regex.Matcher t2 = java.util.regex.Pattern.compile("\"(visita_realizada|visita_cancelada)\"").matcher(java.nio.file.Files.readString(f));
                while (t2.find()) if (f.toString().contains("VisitsController")) lanzados.add(t2.group(1));
                // CRUD: el evento sale de eventoAlActualizar() y se lanza con una variable
                java.util.regex.Matcher t3 = java.util.regex.Pattern.compile("return \"([a-z_]+)\";").matcher(java.nio.file.Files.readString(f));
                while (t3.find()) if (f.toString().contains("CrudController")) lanzados.add(t3.group(1));
            }
        }
        Set<String> sinLanzar = new TreeSet<>(eventos);
        sinLanzar.removeAll(lanzados);
        assertTrue(sinLanzar.isEmpty(), "eventos que nadie lanza: " + sinLanzar);
    }
}
