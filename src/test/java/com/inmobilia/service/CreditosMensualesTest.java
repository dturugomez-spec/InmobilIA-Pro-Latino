package com.inmobilia.service;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/** Reposición mensual de créditos de IA: cuándo toca y cuánto queda. */
class CreditosMensualesTest {

    private static final OffsetDateTime AHORA = OffsetDateTime.of(2026, 10, 13, 4, 15, 0, 0, ZoneOffset.UTC);

    @Test
    void asignacionPorPlanYUsuario() {
        assertEquals(100, PlanService.creditosMensuales("pro"));
        assertEquals(300, PlanService.creditosMensuales("agency"));
        assertEquals(0, PlanService.creditosMensuales("free"));
        assertEquals(0, PlanService.creditosMensuales(null));
        assertEquals(300, PlanService.creditosMensuales("pro", 3));
        assertEquals(1500, PlanService.creditosMensuales("agency", 5));
        assertEquals(300, PlanService.creditosMensuales("agency", 0), "mínimo el titular");
    }

    @Test
    void precioPorUsuario() {
        assertEquals(PlanService.PRECIO_PRO_USD, PlanService.precioMensual("pro", 1));
        assertEquals(PlanService.PRECIO_PRO_USD * 5, PlanService.precioMensual("pro", 5));
        assertEquals(PlanService.PRECIO_AGENCY_USD * 5, PlanService.precioMensual("agency", 5));
        assertEquals(0, PlanService.precioMensual("free", 3));
    }

    @Test
    void sinPacksElSaldoVuelveALaAsignacionYNoSeAcumula() {
        assertEquals(new AIService.Saldo(200, 0), AIService.saldoTrasReponer(3, 0, 200));
        assertEquals(new AIService.Saldo(200, 0), AIService.saldoTrasReponer(0, 0, 200));
        assertEquals(new AIService.Saldo(200, 0), AIService.saldoTrasReponer(180, 0, 200), "lo no gastado del mes no se acumula");
        assertEquals(new AIService.Saldo(1000, 0), AIService.saldoTrasReponer(50, 0, 1000), "los 50 de bienvenida se sustituyen");
    }

    @Test
    void loCompradoNoCaducaYSeGastaDespuesDelPlan() {
        // Pro (200) + pack de 500 = 700; gasta 100 → quedan 600: los 500 comprados siguen intactos
        assertEquals(new AIService.Saldo(700, 500), AIService.saldoTrasReponer(600, 500, 200));
        // Gasta 400 de 700 → quedan 300: se gastaron los 200 del plan y 200 de los comprados
        assertEquals(new AIService.Saldo(500, 300), AIService.saldoTrasReponer(300, 500, 200));
        // Lo gastó todo
        assertEquals(new AIService.Saldo(200, 0), AIService.saldoTrasReponer(0, 500, 200));
    }

    @Test
    void tocaReponerSoloCuandoHaPasadoUnMes() {
        assertTrue(CreditosMensualesService.tocaReponer(null, AHORA), "nunca repuesto → toca");
        assertTrue(CreditosMensualesService.tocaReponer("2026-09-13T04:15:00+00:00", AHORA), "justo un mes");
        assertTrue(CreditosMensualesService.tocaReponer("2026-08-01T10:00:00.123456+00:00", AHORA));
        assertFalse(CreditosMensualesService.tocaReponer("2026-09-20T10:00:00.123456+00:00", AHORA));
        assertFalse(CreditosMensualesService.tocaReponer("2026-10-13T04:00:00+00:00", AHORA));
    }
}
