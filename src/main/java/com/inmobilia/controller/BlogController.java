package com.inmobilia.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.Map;

/**
 * Blog SEO: artículos sobre Ley 12/2023, IRAV, modelos AEAT, etc.
 * Sin BBDD: artículos hardcoded en HTML para SEO orgánico estable.
 */
@Controller
public class BlogController {

    private static final List<Map<String, String>> POSTS = List.of(
        Map.of(
            "slug", "ley-12-2023-vivienda-resumen-arrendadores",
            "title", "Ley 12/2023 de vivienda: qué cambia para los arrendadores",
            "excerpt", "Resumen práctico de la nueva ley de vivienda en España. Topes de renta, zonas tensionadas, IRAV, gastos, fianza. Lo que cualquier propietario debe saber.",
            "category", "Legislación",
            "date", "2026-04-15"
        ),
        Map.of(
            "slug", "irav-mitma-como-aplicar",
            "title", "IRAV oficial MITMA: cómo se aplica al actualizar el alquiler",
            "excerpt", "El Índice de Referencia para Arrendamiento de Vivienda sustituye al IPC. Guía completa: dónde consultarlo, cómo aplicarlo, cuándo se actualiza.",
            "category", "Operativa",
            "date", "2026-04-22"
        ),
        Map.of(
            "slug", "modelo-303-iva-trimestral-inmobiliaria",
            "title", "Modelo 303 (IVA trimestral) para agencias inmobiliarias",
            "excerpt", "Cómo presentar el IVA trimestral si tienes una agencia o eres autónomo del sector inmobiliario. Casos especiales, exenciones, fechas.",
            "category", "Fiscal",
            "date", "2026-04-29"
        ),
        Map.of(
            "slug", "modelo-100-rentas-inmobiliarias-irpf",
            "title", "Modelo 100: cómo declarar rendimientos de alquiler en el IRPF",
            "excerpt", "Guía paso a paso para declarar tus alquileres en la renta. Reducción 60% vivienda habitual, gastos deducibles, amortización, ejemplos.",
            "category", "Fiscal",
            "date", "2026-05-06"
        ),
        Map.of(
            "slug", "verifactu-rd-1007-2023",
            "title", "Verifactu (RD 1007/2023): obligación desde 2026",
            "excerpt", "Todas las facturas en España deben cumplir el sistema verifactu desde 2026. Hash encadenado, QR, requisitos técnicos. Qué hacer si tu software no lo soporta.",
            "category", "Cumplimiento",
            "date", "2026-05-09"
        )
    );

    @GetMapping("/blog")
    public String index(Model model) {
        model.addAttribute("posts", POSTS);
        return "blog/index";
    }

    @GetMapping("/blog/{slug}")
    public String post(@PathVariable String slug, Model model) {
        Map<String, String> post = POSTS.stream()
                .filter(p -> slug.equals(p.get("slug")))
                .findFirst().orElse(null);
        if (post == null) return "redirect:/blog";
        model.addAttribute("post", post);
        return "blog/" + slug.replace("-", "_");
    }
}
