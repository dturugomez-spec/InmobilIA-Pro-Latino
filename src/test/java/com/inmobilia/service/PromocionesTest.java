package com.inmobilia.service;

import com.inmobilia.util.Pais;
import com.inmobilia.util.PaisActual;
import com.inmobilia.util.PreciosPlataforma;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Oferta de lanzamiento (sin cobro hasta una fecha, a las 0:00 en la hora del país) y socio fundador
 * (20 % en los 4 primeros cobros, con el precio en la moneda de cada país). Igual que en España.
 */
class PromocionesTest {

    static final ZoneId MEXICO = ZoneId.of("America/Mexico_City");
    static final ZoneId SANTIAGO = ZoneId.of("America/Santiago");
    static final LocalDate FEB = LocalDate.of(2027, 2, 1);
    static final Instant NOV = ZonedDateTime.of(2026, 11, 20, 10, 0, 0, 0, MEXICO).toInstant();

    @AfterEach
    void limpiar() { PaisActual.limpiar(); }

    private static Map<String, Object> cuenta(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void laOfertaRetrasaElPrimerCobroAl1DeFebreroEnLaHoraDelPais() {
        long feb = FEB.atStartOfDay(MEXICO).toEpochSecond();
        assertEquals(feb, Promociones.finSinCobro(cuenta(), FEB, NOV, MEXICO), "cuenta sin prueba: hasta el día de la oferta");
        assertEquals(FEB.atStartOfDay(SANTIAGO).toEpochSecond(), Promociones.finSinCobro(cuenta(), FEB, NOV, SANTIAGO), "en Chile, a su medianoche");
        // Sin zona explícita, la del país de la petición
        PaisActual.set(Pais.de("CL"));
        assertEquals(FEB.atStartOfDay(SANTIAGO).toEpochSecond(), Promociones.finSinCobro(cuenta(), FEB, NOV));
        // Prueba de 30 días que acaba antes de la oferta: manda la oferta
        assertEquals(feb, Promociones.finSinCobro(cuenta("trial_ends_at", NOV.plus(Duration.ofDays(20)).toString()), FEB, NOV, MEXICO));
        // Prueba que acaba después de la oferta: manda la prueba
        Instant marzo = ZonedDateTime.of(2027, 3, 3, 12, 0, 0, 0, MEXICO).toInstant();
        assertEquals(marzo.getEpochSecond(), Promociones.finSinCobro(cuenta("trial_ends_at", marzo.toString()), FEB, NOV, MEXICO));
    }

    @Test
    void laOfertaNoSeDaAQuienYaTuvoSuscripcionNiFueraDePlazo() {
        assertNull(Promociones.finSinCobro(cuenta("stripe_subscription_id", "sub_123"), FEB, NOV, MEXICO), "ya pagó alguna vez");
        assertNull(Promociones.finSinCobro(cuenta("plan_canceled_at", "2026-10-01T00:00:00Z"), FEB, NOV, MEXICO), "canceló y vuelve");
        Instant casiFebrero = ZonedDateTime.of(2027, 1, 31, 9, 0, 0, 0, MEXICO).toInstant();
        assertNull(Promociones.finSinCobro(cuenta(), FEB, casiFebrero, MEXICO), "a menos de 48 h Stripe no lo acepta: se cobra al momento");
        assertFalse(Promociones.activa(FEB, casiFebrero, MEXICO));
        assertTrue(Promociones.activa(FEB, NOV, MEXICO));
        assertNull(Promociones.finSinCobro(cuenta(), null, NOV, MEXICO), "sin oferta (OFERTA_PRIMER_COBRO vacía)");
        assertNull(Promociones.fecha("  "));
        assertNull(Promociones.fecha("1 de febrero"));
    }

    @Test
    void elCuponDelFundadorCubreExactamenteLos4PrimerosCobros() {
        assertEquals(4, Promociones.mesesCupon(NOV, null), "sin periodo gratis: cobros hoy, +1, +2 y +3 meses");
        long feb = FEB.atStartOfDay(MEXICO).toEpochSecond();
        int meses = Promociones.mesesCupon(NOV, feb);
        assertEquals(6, meses, "se suscribe el 20-nov y paga desde el 1-feb: 1-feb, 1-mar, 1-abr y 1-may");
        ZonedDateTime fin = NOV.atZone(ZoneOffset.UTC).plusMonths(meses);
        assertTrue(fin.toInstant().isAfter(Instant.ofEpochSecond(feb).atZone(MEXICO).plusMonths(3).toInstant()), "el cuarto cobro lleva descuento");
        assertFalse(fin.toInstant().isAfter(Instant.ofEpochSecond(feb).atZone(MEXICO).plusMonths(4).toInstant()), "el quinto ya no");
        assertEquals(4, Promociones.mesesCupon(NOV, NOV.plus(Duration.ofDays(20)).getEpochSecond()));
        assertEquals("inmobilia_fundador_20_6m", Promociones.cuponId(6), "el mismo cupón que en España: vale para todas las monedas");
    }

    @Test
    void elPrecioDeFundadorSaleEnLaMonedaDelPais() {
        Promociones p = new Promociones("2027-02-01");
        PaisActual.set(Pais.de("MX"));
        assertEquals("800 MXN", p.precioFundador(PreciosPlataforma.PLAN_PRO));
        assertEquals("2,440 MXN", p.precioFundador(PreciosPlataforma.PLAN_BUSINESS));
        PaisActual.set(Pais.de("CO"));
        assertEquals("143.200 COP", p.precioFundador(PreciosPlataforma.PLAN_PRO));
        PaisActual.set(Pais.de("CL"));
        assertEquals("43.120 CLP", p.precioFundador(PreciosPlataforma.PLAN_PRO), "pesos chilenos sin decimales");
        PaisActual.set(Pais.de("EC"));
        assertEquals(PreciosPlataforma.formato(new BigDecimal("43.20"), "USD", Pais.de("EC").locale()), p.precioFundador(PreciosPlataforma.PLAN_PRO));
        assertEquals("1 de febrero de 2027", p.primerCobroTexto());
        assertTrue(Promociones.esFundador(cuenta("socio_fundador", true)));
        assertTrue(Promociones.esFundador(cuenta("socio_fundador", "true")));
        assertFalse(Promociones.esFundador(cuenta("socio_fundador", false)));
        assertFalse(Promociones.esFundador(cuenta()));
    }

    private static SessionCreateParams.LineItem linea() {
        return SessionCreateParams.LineItem.builder().setPrice("price_pro").setQuantity(1L).build();
    }

    @Test
    void alFundadorSeLePoneSuCuponYElCobroVaEnSuMoneda() {
        long feb = FEB.atStartOfDay(MEXICO).toEpochSecond();
        SessionCreateParams fundador = StripeService.parametrosCheckout(cuenta("id", "u1"), "pro", "cus_1", linea(), feb,
                "inmobilia_fundador_20_6m", "https://latino.example.com", "MXN");
        assertEquals(1, fundador.getDiscounts().size());
        assertEquals("inmobilia_fundador_20_6m", fundador.getDiscounts().get(0).getCoupon());
        assertNull(fundador.getAllowPromotionCodes(), "Stripe no admite cupón y campo de códigos a la vez");
        assertEquals(feb, fundador.getSubscriptionData().getTrialEnd());
        assertEquals("mxn", fundador.getCurrency());
        assertEquals(SessionCreateParams.Mode.SUBSCRIPTION, fundador.getMode());

        SessionCreateParams normal = StripeService.parametrosCheckout(cuenta("id", "u2"), "business", "cus_2", linea(), null, null,
                "https://latino.example.com", "COP");
        assertNull(normal.getDiscounts());
        assertEquals(Boolean.TRUE, normal.getAllowPromotionCodes());
        assertNull(normal.getSubscriptionData(), "sin periodo gratis: se cobra al suscribirse");
        assertEquals("cop", normal.getCurrency());
    }
}
