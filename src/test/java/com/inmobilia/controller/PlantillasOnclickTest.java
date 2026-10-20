package com.inmobilia.controller;

import nz.net.ultraq.thymeleaf.layoutdialect.LayoutDialect;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Thymeleaf 3.1 prohíbe meter textos (p. ej. ids UUID) en th:onclick: lanza un error a mitad
 * de la página y el navegador se queda esperando. Estas pantallas pasan los ids por data-*.
 */
class PlantillasOnclickTest {

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
        // Enlaces @{/...} sin petición web: sin context path
        e.setLinkBuilder(new org.thymeleaf.linkbuilder.StandardLinkBuilder() {
            @Override
            protected String computeContextPath(org.thymeleaf.context.IExpressionContext ctx, String base,
                                                Map<String, Object> params) {
                return "";
            }
        });
        return e;
    }

    private String contenido(String plantilla, Context ctx) {
        return engine.process(plantilla, Set.of("div[layout:fragment='content']"), ctx);
    }

    private static Map<String, Object> fila(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** Ninguna plantilla mete un valor entre comillas en th:onclick (y similares): eso rompe la página. */
    @Test
    void ningunaPlantilla_meteTextosEnThOn() throws Exception {
        java.util.regex.Pattern peligroso = java.util.regex.Pattern.compile("th:on[a-z]+=\"[^\"]*'\\$\\{");
        java.nio.file.Path raiz = java.nio.file.Path.of("src/main/resources/templates");
        List<String> malas;
        try (var ficheros = java.nio.file.Files.walk(raiz)) {
            malas = ficheros.filter(f -> f.toString().endsWith(".html")).filter(f -> {
                try { return peligroso.matcher(java.nio.file.Files.readString(f)).find(); }
                catch (java.io.IOException e) { throw new RuntimeException(e); }
            }).map(f -> raiz.relativize(f).toString()).toList();
        }
        assertTrue(malas.isEmpty(), "Usar th:data-* + onclick=\"f(this.dataset.x)\" en: " + malas);
    }

    /**
     * Dentro de ${...}, SpEL solo acepta Boolean en un ternario o tras '!': con null, un texto o un
     * número revienta la página. Hay que usar #bools.isTrue(...) / #bools.isFalse(...).
     */
    @Test
    void ningunaPlantilla_usaGetComoBooleanoSinBools() throws Exception {
        java.util.regex.Pattern ternario = java.util.regex.Pattern.compile(
                "\\$\\{\\s*[A-Za-z_]\\w*\\.get\\('[^']*'\\)\\s*\\?(?![:.])");
        java.util.regex.Pattern negacion = java.util.regex.Pattern.compile(
                "!\\s*[A-Za-z_]\\w*\\.get\\('[^']*'\\)(?!\\s*[.\\[])");
        java.nio.file.Path raiz = java.nio.file.Path.of("src/main/resources/templates");
        List<String> malas;
        try (var ficheros = java.nio.file.Files.walk(raiz)) {
            malas = ficheros.filter(f -> f.toString().endsWith(".html")).filter(f -> {
                try {
                    String t = java.nio.file.Files.readString(f);
                    return ternario.matcher(t).find() || negacion.matcher(t).find();
                } catch (java.io.IOException e) { throw new RuntimeException(e); }
            }).map(f -> raiz.relativize(f).toString()).toList();
        }
        assertTrue(malas.isEmpty(), "Usar #bools.isTrue/isFalse en: " + malas);
    }

    @Test
    void plantillasDeDocumentos_conMuchasVariablesYFavoritaNula() {
        Context c = new Context();
        c.setVariable("plantillas", List.of(
                fila("id", UUID, "nombre", "Contrato de arrendamiento", "categoria", "contrato_alquiler",
                        "favorita", true, "es_sistema", true, "veces_usada", 3,
                        "variables_detectadas", List.of("inquilino.nombre", "inquilino.dni", "propiedad.direccion",
                                "contrato.renta", "contrato.fianza", "contrato.inicio")),
                fila("id", UUID.replace('3', '5'), "nombre", "Comunicación", "categoria", "comunicacion",
                        "favorita", null, "variables_detectadas", List.of("lead.nombre"))));
        String html = contenido("doc_templates_list", c);
        assertTrue(html.contains("Contrato de arrendamiento") && html.contains("Comunicación"));
        assertTrue(html.contains("inquilino.nombre") && html.contains("propiedad.direccion"));
        assertFalse(html.contains("contrato.inicio"), "con más de 4 variables solo se ven 3");
        assertTrue(html.contains("+más"));
        assertTrue(html.contains("★") && html.contains("☆"), "estrella llena si es favorita, vacía si no");
    }

    @Test
    void busquedasGuardadas_conIdUuidYActivaNula() {
        Context c = new Context();
        c.setVariable("busquedas", List.of(
                fila("id", UUID, "cliente_nombre", "Ana", "cliente_email", "ana@x.es", "activa", true,
                        "operacion", "venta", "precio_max", 250000),
                fila("id", UUID.replace('3', '4'), "cliente_nombre", "Luis", "cliente_email", "luis@x.es",
                        "activa", null)));
        String html = contenido("saved_searches", c);
        assertTrue(html.contains("data-id=\"" + UUID + "\""));
        assertTrue(html.contains("Ana") && html.contains("Luis"));
        assertTrue(html.contains("Pausada"), "activa nula se muestra como pausada");
    }
}
