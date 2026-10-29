package com.inmobilia.service;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Las cuatro herramientas de la hoja de precios: piezas que no necesitan la app arrancada. */
class HerramientasHojaTest {

    private static final LocalDate MIERCOLES = LocalDate.of(2026, 10, 28);

    @Test
    void laVozEntiendeCuandoYQuitaLaFechaDelTitulo() {
        var c = ComandosVozService.cuando("llamar al fontanero mañana", MIERCOLES);
        assertEquals(MIERCOLES.plusDays(1), c.fecha());
        assertEquals("llamar al fontanero", c.resto());
        c = ComandosVozService.cuando("revisar la caldera de la nave el viernes", MIERCOLES);
        assertEquals(LocalDate.of(2026, 10, 30), c.fecha());
        assertEquals("revisar la caldera de la nave", c.resto());
        assertEquals(MIERCOLES.plusDays(3), ComandosVozService.cuando("pedir presupuesto en tres días", MIERCOLES).fecha());
        assertEquals(MIERCOLES.plusDays(2), ComandosVozService.cuando("firmar pasado mañana", MIERCOLES).fecha());
        assertEquals(DayOfWeek.MONDAY, ComandosVozService.cuando("cobrar la semana que viene", MIERCOLES).fecha().getDayOfWeek());
        assertEquals(MIERCOLES.plusDays(1), ComandosVozService.cuando("enviar el contrato", MIERCOLES).fecha(), "sin fecha: mañana");
    }

    @Test
    void elRangoDependeDeSiPagasOCobras() {
        var vendo = NegociacionService.rango(500_000.0, 560_000, "vendo", false, "el valor apuntado");
        assertEquals(560_000, vendo.apertura(), "sale en su precio si está por encima de la referencia");
        assertEquals(510_000, vendo.objetivo());
        assertEquals(475_000, vendo.limite());
        assertTrue(vendo.apertura() > vendo.objetivo() && vendo.objetivo() > vendo.limite());

        var compro = NegociacionService.rango(500_000.0, 560_000, "compro", false, "el valor apuntado");
        assertTrue(compro.apertura() < compro.objetivo() && compro.objetivo() <= compro.limite());
        assertTrue(compro.limite() <= 560_000, "quien compra nunca pasa del precio pedido");

        var alquiler = NegociacionService.rango(null, 1_250, "arriendo", true, "");
        assertEquals(1_100, alquiler.apertura(), "sin referencia, alrededor del precio dado; redondeado a 10 €");
        assertTrue(alquiler.base().contains("sin referencia"));
    }

    @Test
    void sinIALaPreparacionUsaLosNumerosDelRango() {
        var r = NegociacionService.rango(500_000.0, 560_000, "vendo", false, "el valor apuntado");
        Map<String, Object> plan = NegociacionService.plantilla("venta", "vendo", "nave", 400, 560_000, r);
        assertTrue(String.valueOf(plan.get("resumen")).contains("560000"));
        assertTrue(((List<?>) plan.get("argumentos")).stream().anyMatch(a -> String.valueOf(a).contains("12 %")), "precio un 12 % sobre la referencia");
        assertEquals(4, ((List<?>) plan.get("objeciones")).size());
        assertEquals("nave", NegociacionService.tipoDe(Map.of("type", "Industrial", "name", "Nave Getafe"), null));
        assertEquals("local", NegociacionService.tipoDe(Map.of("name", "Local Atocha"), null));
        assertEquals("vivienda", NegociacionService.tipoDe(Map.of("name", "Piso Gran Vía"), null));
    }

    @Test
    void laVistaMultiEmpresaSumaCadaSociedad() {
        var r = MultiEmpresaService.totales("s1", "Patrimonial", "B00000017", List.of(
                Map.of("current_value", 300_000, "rental_income", 1_500, "monthly_expenses", 200),
                Map.of("purchase_price", 100_000, "rental_income", 500, "mortgage_payment", 300)));
        assertEquals(2, r.activos());
        assertEquals(400_000, r.valor(), "sin valor actual, el de compra");
        assertEquals(2_000, r.rentaMensual());
        assertEquals(1_500, r.netoMensual());
        assertEquals(6.0, r.rentabilidadBruta(), "2.000 × 12 / 400.000");
    }

    @Test
    void sociedadesSegunElPlan() {
        PlanService planes = new PlanService(null);
        assertEquals(3, planes.maxSociedades(Map.of("email", "a@b.es", "plan", "business", "plan_status", "active")));
        assertEquals(5, planes.maxSociedades(Map.of("email", "a@b.es", "plan", "business", "plan_status", "active", "sociedades_extra", 2)));
        assertEquals(-1, planes.maxSociedades(Map.of("email", "a@b.es", "plan", "enterprise", "plan_status", "active")));
        assertEquals(0, planes.maxSociedades(Map.of("email", "a@b.es", "plan", "pro", "plan_status", "active")));
        assertEquals(1500, StripeService.catalogo("sociedad_extra_mensual").centimos());
        assertEquals("business", PlanService.herramientaDe("/herramientas/multi-empresa").planMinimo());
        assertEquals("business", PlanService.herramientaDe("/herramientas/negociacion/abc").planMinimo());
        assertEquals("pro", PlanService.herramientaDe("/herramientas/autopilot-comunicacion/enviar").planMinimo());
        assertEquals("pro", PlanService.herramientaDe("/herramientas/comandos-voz/ejecutar").planMinimo());
    }
}
