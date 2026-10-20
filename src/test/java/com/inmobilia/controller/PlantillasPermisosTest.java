package com.inmobilia.controller;

import com.inmobilia.service.PermissionService;
import nz.net.ultraq.thymeleaf.layoutdialect.LayoutDialect;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Pantallas de permisos por persona, Mi negocio y seguros; y el menú lateral por permisos. */
class PlantillasPermisosTest {

    private static final String UUID = "3f2b8c1e-9a4d-4e7f-b2c1-0d5e6f7a8b9c";
    private final SpringTemplateEngine engine = motor();

    private static SpringTemplateEngine motor() {
        ClassLoaderTemplateResolver r = new ClassLoaderTemplateResolver();
        r.setPrefix("templates/");
        r.setSuffix(".html");
        r.setTemplateMode(TemplateMode.HTML);
        r.setCharacterEncoding("UTF-8");
        SpringTemplateEngine e = new SpringTemplateEngine();
        e.setTemplateResolver(r);
        e.addDialect(new LayoutDialect());
        e.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder() {
            @Override
            protected String computeContextPath(org.thymeleaf.context.IExpressionContext ctx, String base,
                                                Map<String, Object> params) { return ""; }
        });
        return e;
    }

    private String contenido(String plantilla, Context ctx) {
        conPais(ctx);
        return engine.process(plantilla, Set.of("div[layout:fragment='content']"), ctx);
    }

    /** Lo que PaisInterceptor añade a todas las vistas (aquí, una inmobiliaria de Colombia). */
    static void conPais(Context ctx) {
        if (!ctx.containsVariable("pais")) ctx.setVariable("pais", com.inmobilia.util.Pais.de("CO"));
        if (!ctx.containsVariable("mon")) ctx.setVariable("mon", "COP");
        ctx.setLocale(java.util.Locale.forLanguageTag("es-CO"));
    }

    private static Map<String, Object> fila(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void listaDePermisos_conAjustesYTablaPorRol() {
        Context c = new Context();
        c.setVariable("usuarios", List.of(
                fila("id", UUID, "name", "Luis", "email", "luis@x.es", "rolNombre", "Comercial", "esAdmin", false, "numAjustes", 2),
                fila("id", UUID.replace('3', '4'), "name", "Eva", "email", "eva@x.es", "rolNombre", "Administrador", "esAdmin", true, "numAjustes", 0)));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nombre", "Seguros");
        Map<String, String> porRol = new LinkedHashMap<>();
        for (String r : PermissionService.ALL_ROLES) porRol.put(r, "comercial".equals(r) ? "NONE" : "WRITE");
        m.put("porRol", porRol);
        c.setVariable("matriz", List.of(m));
        c.setVariable("roles", PermissionService.ALL_ROLES);
        c.setVariable("roleLabels", PermissionService.ROLE_LABELS);
        String html = contenido("granular_permissions", c);
        assertTrue(html.contains("2 secciones ajustadas"));
        assertTrue(html.contains("Acceso total"));
        assertTrue(html.contains("/permisos-granulares/" + UUID));
        assertFalse(html.contains("/permisos-granulares/" + UUID.replace('3', '4')), "a un administrador no se le ajusta");
        assertTrue(html.contains("Modificar"));
    }

    @Test
    void fichaDePermisos_unSelectorPorSeccion() {
        Context c = new Context();
        c.setVariable("usuario", fila("id", UUID, "name", "Luis", "email", "luis@x.es"));
        c.setVariable("rolNombre", "Comercial");
        List<Map<String, Object>> filas = new ArrayList<>();
        filas.add(fila("seccion", "seguros", "nombre", "Seguros", "descripcion", "Pólizas", "delRol", "NONE", "ajuste", "WRITE"));
        filas.add(fila("seccion", "leads", "nombre", "Leads", "descripcion", "Bandeja", "delRol", "WRITE", "ajuste", "rol"));
        c.setVariable("filas", filas);
        String html = contenido("granular_permissions_edit", c);
        assertTrue(html.contains("name=\"s_seguros\"") && html.contains("name=\"s_leads\""));
        assertTrue(html.matches("(?s).*<option value=\"WRITE\" selected=\"selected\">Ver y modificar</option>.*"));
        assertTrue(html.contains("Sin acceso"));
    }

    @Test
    void miNegocio_soloLecturaParaQuienNoPuedeCambiarlo() {
        Context c = new Context();
        c.setVariable("profile", fila("legal_name", "Inmo SL", "iban", "ES9121000418450200051332"));
        c.setVariable("puedeEditar", false);
        String html = contenido("business_profile", c);
        assertTrue(html.matches("(?s).*<fieldset[^>]*disabled=\"disabled\".*"), "campos bloqueados");
        assertFalse(html.contains("Guardar configuración"));
        assertTrue(html.contains("no modificarlos"));

        c.setVariable("puedeEditar", true);
        html = contenido("business_profile", c);
        assertFalse(html.contains("disabled=\"disabled\""));
        assertTrue(html.contains("Guardar configuración"));
        assertTrue(html.contains("se avisa por email al titular"));
    }

    @Test
    void seguros_formularioYFichaConCargos() {
        Context c = new Context();
        c.setVariable("poliza", new LinkedHashMap<>());
        c.setVariable("propiedades", List.of());
        c.setVariable("contratos", List.of());
        c.setVariable("ibanEmpresa", "ES9121000418450200051332");
        String form = contenido("insurance_form", c);
        assertTrue(form.contains("name=\"cuenta_cargo\"") && form.contains("name=\"domiciliado\"") && form.contains("name=\"proximo_cargo\""));
        assertTrue(form.contains("data-iban=\"ES9121000418450200051332\""));

        Context d = new Context();
        d.setVariable("poliza", fila("id", UUID, "tipo", "hogar", "aseguradora", "Mapfre", "prima_anual", 600,
                "forma_pago", "mensual", "proximo_cargo", "2026-11-01", "domiciliado", true, "estado", "activa",
                "fecha_inicio", "2026-01-01", "fecha_vencimiento", "2027-01-01", "renovacion_automatica", null));
        d.setVariable("siniestros", List.of());
        d.setVariable("propiedades", List.of());
        d.setVariable("contratos", List.of());
        d.setVariable("importePorCargo", 50.0);
        d.setVariable("cuentaCargo", "ES91 •••• 1332");
        String ficha = contenido("insurance_detail", d);
        assertTrue(ficha.contains("50,00 COP"));
        assertTrue(ficha.contains("ES91 •••• 1332"));
        assertTrue(ficha.contains("2026-11-01"));
    }

    @Test
    void guardarPermisos_soloLoQueDifiereDelRol() {
        var ctrl = new GranularPermissionsController(null, new PermissionService(), null);
        Map<String, String> form = new HashMap<>();
        form.put("s_seguros", "WRITE");   // el comercial no lo tiene: se guarda
        form.put("s_leads", "WRITE");     // ya lo da su rol: no se guarda
        form.put("s_empresa", "rol");     // según su rol
        form.put("s_ia", "NONE");         // se le quita
        form.put("s_inventada", "WRITE"); // no existe: fuera
        assertEquals(Map.of("seguros", "WRITE", "ia", "NONE"), ctrl.ajustesDelFormulario(form, "comercial"));
        assertEquals("Seguros: según su rol → ver y modificar",
                GranularPermissionsController.resumenCambios(Map.of(), Map.of("seguros", "WRITE")));
    }

    /** Cada enlace del menú lateral lleva su condición: nadie ve enlaces que no puede abrir. */
    @Test
    void menuLateral_todosLosEnlacesConCondicion() throws Exception {
        String layout = Files.readString(Path.of("src/main/resources/templates/fragments/layout.html"));
        int ini = layout.indexOf("<nav class=\"flex-1");
        String nav = layout.substring(ini, layout.indexOf("</nav>", ini));
        Matcher m = Pattern.compile("<a [^>]*href=\"/[^\"]*\"[^>]*>").matcher(nav);
        int enlaces = 0;
        List<String> sinCondicion = new ArrayList<>();
        while (m.find()) {
            enlaces++;
            if (!m.group().contains("th:if=")) sinCondicion.add(m.group());
        }
        assertTrue(enlaces > 80, "esperaba el menú completo");
        assertTrue(sinCondicion.isEmpty(), "Enlaces sin th:if: " + sinCondicion);
        assertFalse(nav.contains("userRole == 'comercial'"), "los grupos ya no van por rol fijo");
    }

    /** La página entera (con el menú, que va en todas) se genera bien y enseña lo que toca a cada rol. */
    @Test
    void paginaCompletaConMenu_segunElRol() {
        PermissionService p = new PermissionService();
        String comercial = paginaCon(p, "comercial");
        assertTrue(comercial.contains("href=\"/bandeja\""));
        assertTrue(comercial.contains("href=\"/seguridad\""), "Seguridad (2FA) visible para todos");
        assertTrue(comercial.contains("href=\"/account\""));
        assertFalse(comercial.contains("href=\"/billing\""), "un comercial no ve Plan y facturación");
        assertFalse(comercial.contains("href=\"/seguros\""));
        assertFalse(comercial.contains("href=\"/permisos-granulares\""));
        assertTrue(comercial.contains("data-nav-group=\"finanzas\""), "el grupo se oculta en el navegador si queda vacío");

        String contable = paginaCon(p, "contable");
        assertTrue(contable.contains("href=\"/seguros\"") && contable.contains("href=\"/business\""));
        assertFalse(contable.contains("href=\"/bandeja\""));

        String admin = paginaCon(p, "admin");
        assertTrue(admin.contains("href=\"/permisos-granulares\"") && admin.contains("href=\"/billing\""));
    }

    private String paginaCon(PermissionService p, String rol) {
        Context c = new Context();
        c.setVariable("logged", true);
        c.setVariable("userRole", rol);
        c.setVariable("userName", "Ana");
        c.setVariable("userRoleLabel", p.label(rol));
        c.setVariable("userRoleIcon", "");
        c.setVariable("credits", 10);
        c.setVariable("platformAiEnabled", true);
        c.setVariable("paletteTools", List.of());
        c.setVariable("nav", new GlobalModelAdvice.NavPermisos(p, rol));
        return engine.process("help", c);
    }

    @Test
    void ayudanteDelMenu_usaLasMismasReglasQueElGuardian() {
        PermissionService p = new PermissionService();
        var comercial = new GlobalModelAdvice.NavPermisos(p, "comercial");
        assertTrue(comercial.ve("/bandeja"));
        assertTrue(comercial.ve("/account"));
        assertTrue(comercial.ve("/seguridad"));
        assertTrue(comercial.ve("/help"));
        assertFalse(comercial.ve("/billing"));
        assertFalse(comercial.ve("/seguros"));
        assertFalse(comercial.ve("/invoices"));
        var rrhh = new GlobalModelAdvice.NavPermisos(p, "rrhh");
        assertTrue(rrhh.ve("/equipo"), "RR. HH. por fin ve Equipo en el menú");
        assertFalse(rrhh.ve("/bandeja"));
    }
}
