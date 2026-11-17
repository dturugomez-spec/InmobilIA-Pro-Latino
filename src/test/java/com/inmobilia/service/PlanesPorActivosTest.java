package com.inmobilia.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Planes por activos (hoja de precios de oct-2026): límites, prueba, módulos y precios de Stripe. */
class PlanesPorActivosTest {

    private final PlanService planes = new PlanService(null);
    { ReflectionTestUtils.setField(planes, "adminEmailsRaw", "dueno@inmobiliapro.es"); }

    private static Map<String, Object> cuenta(String plan, String estado, Object... extra) {
        Map<String, Object> u = new HashMap<>(Map.of("id", "org", "email", "cliente@empresa.es", "plan", plan, "plan_status", estado));
        for (int i = 0; i < extra.length; i += 2) u.put((String) extra[i], extra[i + 1]);
        return u;
    }

    @Test
    void limitesDeLaHoja() {
        var free = PlanService.PLANS.get("free");
        var pro = PlanService.PLANS.get("pro");
        var business = PlanService.PLANS.get("business");
        var enterprise = PlanService.PLANS.get("enterprise");
        assertEquals(List.of(3, 1, 20), List.of(free.maxProperties(), free.maxTeamMembers(), free.aiCreditsPerMonth()));
        assertEquals(List.of(15, 2, 200, 49, 39), List.of(pro.maxProperties(), pro.maxTeamMembers(), pro.aiCreditsPerMonth(), pro.monthlyPriceEur(), pro.annualMonthlyPriceEur()));
        assertEquals(List.of(50, 5, 1000, 149, 119), List.of(business.maxProperties(), business.maxTeamMembers(), business.aiCreditsPerMonth(), business.monthlyPriceEur(), business.annualMonthlyPriceEur()));
        assertEquals(List.of(-1, -1, 399), List.of(enterprise.maxProperties(), enterprise.maxTeamMembers(), enterprise.monthlyPriceEur()));
        assertEquals(4, PlanService.PRECIO_ACTIVO_EXTRA);
        assertEquals(29, PlanService.FUTUREPROOF.precioMensual());
    }

    @Test
    void laPruebaEsBusinessYAlAcabarPasaAFree() {
        var enPrueba = cuenta("business", "trialing", "trial_ends_at", OffsetDateTime.now().plusDays(5).toString());
        assertEquals("business", planes.planEfectivo(enPrueba));
        var acabada = cuenta("business", "trialing", "trial_ends_at", OffsetDateTime.now().minusDays(1).toString());
        assertEquals("free", planes.planEfectivo(acabada), "aunque el cron aún no haya cambiado users.plan");
        assertEquals(3, planes.maxActivos(acabada));
        var cancelada = cuenta("free", "canceled");
        assertEquals("free", planes.planEfectivo(cancelada));
        assertFalse(planes.tieneSuscripcionActiva(cuenta("free", "active")), "Free no es una suscripción");
        assertTrue(planes.tieneSuscripcionActiva(cuenta("pro", "active", "stripe_subscription_id", "sub_1")));
        assertEquals("business", planes.planEfectivo(cuenta("agency", "active")), "el plan antiguo por usuario cuenta como Business");
        assertEquals("enterprise", planes.planEfectivo(Map.of("email", "dueno@inmobiliapro.es", "plan", "free")), "el propietario no tiene límites");
    }

    @Test
    void activosAdicionalesSoloEnPlanesDePago() {
        assertEquals(15 + 5, planes.maxActivos(cuenta("pro", "active", "activos_extra", 5)));
        assertEquals(3, planes.maxActivos(cuenta("free", "active", "activos_extra", 5)), "en Free no valen");
        assertEquals(-1, planes.maxActivos(cuenta("enterprise", "active")));
    }

    @Test
    void cadaHerramientaEnSuPlanYLosModulosEnPro() {
        var esg = PlanService.herramientaDe("/herramientas/esg");
        var futuro = PlanService.herramientaDe("/herramientas/future-proof/calcular");
        var portal = PlanService.herramientaDe("/inquilinos/1b2c/invite-portal");
        assertEquals("business", esg.planMinimo());
        assertEquals("pro", portal.planMinimo());
        assertNull(PlanService.herramientaDe("/herramientas/euribor"), "Euríbor está en Free");
        assertNull(PlanService.herramientaDe("/herramientas/datos-oficiales"), "Catastro está en Free");
        assertNull(PlanService.herramientaDe("/inquilinos"), "la lista de inquilinos no es el portal");
        assertNull(PlanService.herramientaDe("/api-keysx"));
        assertEquals("enterprise", PlanService.herramientaDe("/api-keys/3/revoke").planMinimo());

        var free = cuenta("free", "active");
        var pro = cuenta("pro", "active");
        var proConModulo = cuenta("pro", "active", "modulos", List.of("futureproof"));
        assertFalse(planes.puedeUsar(free, portal));
        assertTrue(planes.puedeUsar(pro, portal));
        assertFalse(planes.puedeUsar(pro, futuro));
        assertTrue(planes.puedeUsar(proConModulo, futuro), "módulo comprado en Pro");
        assertFalse(planes.puedeUsar(proConModulo, esg), "el módulo no abre otras herramientas de Business");
        assertTrue(planes.puedeUsar(cuenta("business", "active"), futuro), "en Business viene incluido");
        assertEquals(Set.of("futureproof"), PlanService.modulosComprados(Map.of("modulos", "{futureproof,holding,inventado}")));
    }

    @Test
    void preciosDeStripeSalenDeLaHoja() {
        // Un precio por concepto con todas las monedas (céntimos; CLP y PYG sin decimales)
        assertEquals(5400L, StripeService.catalogo("plan_pro_mensual").importes().get("usd"));
        assertEquals(100000L, StripeService.catalogo("plan_pro_mensual").importes().get("mxn"));
        assertEquals(53900L, StripeService.catalogo("plan_pro_mensual").importes().get("clp"));
        assertEquals(14, StripeService.catalogo("plan_pro_mensual").importes().size());
        assertEquals("inmobilia_latam_plan_pro_mensual", StripeService.catalogo("plan_pro_mensual").lookupKey());
        assertEquals("month", StripeService.catalogo("plan_pro_mensual").intervalo());
        assertNull(StripeService.catalogo("plan_pro_anual"), "solo pago mensual");
        assertEquals(16400L, StripeService.catalogo("plan_business_mensual").importes().get("usd"));
        assertEquals(450L, StripeService.catalogo("activo_extra_mensual").importes().get("usd"));
        assertNull(StripeService.catalogo("activo_extra_anual"));
        assertEquals(3200L, StripeService.catalogo("modulo_futureproof_mensual").importes().get("usd"));
        assertNull(StripeService.catalogo("modulo_holding_mensual"), "el holding es solo de la versión española");
        assertEquals(1100L, StripeService.catalogo("creditos_100").importes().get("usd"));
        assertEquals(68200L, StripeService.catalogo("creditos_100").importes().get("pyg"));
        assertNull(StripeService.catalogo("creditos_100").intervalo(), "pago único");
        assertNull(StripeService.catalogo("plan_enterprise_mensual"), "Enterprise se pacta por correo");
        assertNull(StripeService.catalogo("plan_free_mensual"));
        assertEquals("inmobilia_latam_plan_business_mensual", StripeService.catalogo("plan_business_mensual").lookupKey(), "claves propias de Latino");
    }

    @Test
    void siSeSuscribeEnLaPruebaNoPagaHastaQueAcabe() {
        long fin = OffsetDateTime.now().plusDays(10).toEpochSecond();
        assertEquals(fin, StripeService.finDePruebaParaStripe(Map.of("trial_ends_at", OffsetDateTime.now().plusDays(10).toString())), 2);
        assertNull(StripeService.finDePruebaParaStripe(Map.of("trial_ends_at", OffsetDateTime.now().plusDays(1).toString())), "Stripe pide al menos 48 h");
        assertNull(StripeService.finDePruebaParaStripe(Map.of("trial_ends_at", OffsetDateTime.now().plusDays(10).toString(),
                "plan_canceled_at", "2026-01-01")), "quien ya se suscribió no repite prueba");
    }
}
