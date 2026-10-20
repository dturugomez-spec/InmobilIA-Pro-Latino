package com.inmobilia.controller;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Las acciones que modifican datos (POST/PUT/DELETE) deben comprobar la sesión: el guardián de
 * sesión deja pasar a quien no la ha iniciado (cada pantalla decide), así que una acción sin esa
 * comprobación funciona sin login. Antes había 15 así (marcar facturas pagadas, editar tarjetas…).
 */
class AccionesProtegidasTest {

    /** Rutas públicas a propósito (formularios de clientes, enlaces con token, calculadoras, API con clave). */
    private static final Pattern PUBLICAS = Pattern.compile(
            "/chatbot/api|/descubre|/m/\\{|/p/\\{|/propiedad/\\{|/codigo/|/publico/|/responder/|/tasacion|/calculadora"
            + "|/comparador-hipotecas|/convertir|/suscribir|/forgot|/reset|/login|/register|/solicitar-cuenta|/email-action"
            + "|/webhook|/stripe|/firmar|/f/|/consultar-|/dcf|/future-proof|/calcular|/extraer|/track|/c/\\{|/h/|/v/"
            + "|/tenant|/portal/|/mi-inmueble|/informe/|/feedback-publico/|/api/v1/|/api/v2/");

    private static final Pattern COMPRUEBA_SESION = Pattern.compile(
            "isLoggedIn|uid\\(session\\)|realUid\\(session\\)|esAdminPlataforma|DEBUG_TOKEN|authenticate\\(req\\)");

    @Test
    void todasLasAccionesInternasCompruebanLaSesion() throws Exception {
        List<String> sinComprobar = new ArrayList<>();
        int revisadas = 0;
        Pattern mapping = Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping(\\(\\s*(?:value\\s*=\\s*)?\\{?\\s*\"([^\"]*)\")?");
        Pattern base = Pattern.compile("(?m)^@RequestMapping\\(\\s*\"([^\"]+)\"");
        try (var ficheros = Files.list(Path.of("src/main/java/com/inmobilia/controller"))) {
            for (Path f : ficheros.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                Matcher b = base.matcher(src);
                String prefijo = b.find() ? b.group(1) : "";
                Matcher m = mapping.matcher(src);
                List<int[]> pos = new ArrayList<>();
                List<String[]> info = new ArrayList<>();
                while (m.find()) {
                    pos.add(new int[]{m.start()});
                    info.add(new String[]{m.group(1), m.group(3) == null ? "" : m.group(3)});
                }
                for (int i = 0; i < pos.size(); i++) {
                    if ("Get".equals(info.get(i)[0])) continue;
                    String ruta = prefijo + info.get(i)[1];
                    if (PUBLICAS.matcher(ruta).find()) continue;
                    int fin = i + 1 < pos.size() ? pos.get(i + 1)[0] : src.length();
                    String cuerpo = src.substring(pos.get(i)[0], fin);
                    revisadas++;
                    if (!COMPRUEBA_SESION.matcher(cuerpo).find()) {
                        sinComprobar.add(f.getFileName() + " " + ruta);
                    }
                }
            }
        }
        assertTrue(revisadas > 200, "esperaba revisar todas las acciones de la app, solo " + revisadas);
        assertTrue(sinComprobar.isEmpty(), "Acciones sin comprobar la sesión: " + sinComprobar);
    }

    @Test
    void conciliacion_eligeLaFacturaMasCercanaYNoAdivinaEnEmpates() {
        var a = Map.<String, Object>of("id", "a", "fecha", "2026-10-01");
        var b = Map.<String, Object>of("id", "b", "fecha", "2026-09-01");
        var lejos = Map.<String, Object>of("id", "c", "fecha", "2026-01-01");
        assertEquals("a", BankReconciliationController.elegirFactura(List.of(a, b), "2026-10-05").get("id"));
        assertNull(BankReconciliationController.elegirFactura(List.of(lejos), "2026-10-05"), "más de 60 días: no");
        var gemela = Map.<String, Object>of("id", "d", "fecha", "2026-10-09");
        assertNull(BankReconciliationController.elegirFactura(List.of(a, gemela), "2026-10-05"), "empate: mejor a mano");
        assertNull(BankReconciliationController.elegirFactura(List.of(), "2026-10-05"));
    }

    @Test
    void promociones_camposNumericosVaciosNoRompen() {
        assertNull(PromocionesController.entero(""));
        assertNull(PromocionesController.entero("  "));
        assertNull(PromocionesController.entero("tres"));
        assertEquals(3, PromocionesController.entero(" 3 "));
        assertEquals(125.5, PromocionesController.decimal("125,5"));
        assertNull(PromocionesController.decimal(null));
    }

    @Test
    void pipeline_alEditarNoSePuedeCambiarDeQuienEsLaTarjeta() {
        assertFalse(PipelineController.CAMPOS_EDITABLES.contains("user_id"));
        assertFalse(PipelineController.CAMPOS_EDITABLES.contains("pipeline_id"));
        assertFalse(PipelineController.CAMPOS_EDITABLES.contains("movimientos"));
        assertTrue(PipelineController.CAMPOS_EDITABLES.contains("titulo"));
    }
}
