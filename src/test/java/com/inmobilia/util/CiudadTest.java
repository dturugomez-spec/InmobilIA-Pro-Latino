package com.inmobilia.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** La inmobiliaria elige su ciudad y de ella salen país, moneda, leyes y zona horaria. */
class CiudadTest {

    @Test
    void laCiudadDecideElPaisYLaZonaHoraria() {
        Ciudad cancun = Ciudad.leer("MX:Cancún").orElseThrow();
        Pais p = Pais.de(cancun.pais()).enCiudad(cancun);
        assertEquals("MX", p.codigo());
        assertEquals("MXN", p.moneda());
        assertEquals("IVA 16 %", p.impuestoConTipo());
        assertEquals("America/Cancun", p.zona(), "Cancún no tiene la hora de la capital");
        assertEquals("Cancún, México", p.ubicacion());

        Pais gdl = Pais.de("MX").enCiudad(Ciudad.leer("MX:Guadalajara").orElseThrow());
        assertEquals("America/Mexico_City", gdl.zona());
        Pais medellin = Pais.de("CO").enCiudad(Ciudad.leer("CO:Medellín").orElseThrow());
        assertEquals("COP", medellin.moneda());
        assertEquals("Ley 1581 de 2012 de protección de datos personales", medellin.leyDatosTexto());
    }

    @Test
    void lecturaTolerante() {
        assertEquals("MX", Ciudad.leer("guadalajara").orElseThrow().pais(), "sin país: se busca en el catálogo");
        assertEquals("Bogotá", Ciudad.leer("co:bogota").orElseThrow().nombre(), "sin tildes ni mayúsculas");
        Ciudad otra = Ciudad.leer("PE:").orElseThrow();
        assertEquals("PE", otra.pais());
        assertNull(Pais.de("PE").enCiudad(otra).ciudad(), "otra ciudad del país: sin nombre");
        Ciudad pasto = Ciudad.leer("CO:Pasto").orElseThrow();
        assertEquals("Pasto", pasto.nombre(), "una ciudad que no está en la lista se conserva");
        assertEquals("America/Bogota", pasto.zonaEfectiva());
        assertTrue(Ciudad.leer("ES:Madrid").isEmpty(), "España no es un país de esta versión");
        assertTrue(Ciudad.leer("Ciudad inventada").isEmpty(), "sin país no se adivina");
        assertTrue(Ciudad.leer(null).isEmpty());
    }

    @Test
    void laMonedaEnUsdConservaLaCiudad() {
        Pais ba = Pais.de("AR").enCiudad(Ciudad.leer("AR:Córdoba").orElseThrow()).conMoneda("USD");
        assertEquals("USD", ba.moneda());
        assertEquals("Córdoba", ba.ciudad());
        assertEquals("Córdoba", PreciosReferencia.ciudadPrincipal(ba), "las valoraciones parten de su ciudad");
        assertEquals("Buenos Aires", PreciosReferencia.ciudadPrincipal(Pais.de("AR")));
    }

    @Test
    void todasLasCiudadesTienenZonaValidaYPaisConocido() {
        for (var e : Ciudad.porPais().entrySet()) {
            assertTrue(Pais.existe(e.getKey()), e.getKey());
            for (Ciudad c : e.getValue()) assertNotNull(java.time.ZoneId.of(c.zonaEfectiva()), c.clave());
        }
    }
}
