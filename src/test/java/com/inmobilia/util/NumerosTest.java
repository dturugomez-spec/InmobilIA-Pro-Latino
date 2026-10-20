package com.inmobilia.util;

import com.inmobilia.controller.OnlineReservationControllerAcceso;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Números escritos a mano en formularios (formato del país) sin romper la página. */
class NumerosTest {

    @org.junit.jupiter.api.AfterEach
    void limpiar() { PaisActual.limpiar(); }

    @Test
    void comaDecimal_colombiaArgentinaChile() {
        PaisActual.set(Pais.de("CO"));
        assertEquals(1250.5, Numeros.decimal("1.250,50"));
        assertEquals(250000.0, Numeros.decimal("250.000"));
        assertEquals(250000.0, Numeros.decimal("$ 250.000 COP"));
        assertEquals(1250.5, Numeros.decimal("1250,5"));
        assertEquals(1.25, Numeros.decimal("1,25"));
        assertEquals(1250.5, Numeros.decimal("1250.5"), "lo que manda un <input type=number>");
        assertEquals(1250.0, Numeros.decimal("1 250"));
        assertEquals(1250.5, Numeros.decimal("1,250.50"), "con los dos separadores manda el último");
        assertEquals(-3.0, Numeros.decimal("-3"));
    }

    @Test
    void puntoDecimal_mexicoPeru() {
        PaisActual.set(Pais.de("MX"));
        assertEquals(1250.5, Numeros.decimal("1,250.50"));
        assertEquals(250000.0, Numeros.decimal("250,000"), "en México la coma separa miles");
        assertEquals(1.5, Numeros.decimal("1.500"), "y el punto es decimal");
        assertEquals(1250.5, Numeros.decimal("1250.5"));
        assertEquals(2.5, Numeros.decimal("2,5"), "una coma que no tiene forma de miles sigue siendo decimal");
        PaisActual.set(Pais.de("PE"));
        assertEquals(300.0, Numeros.decimal("S/ 300"));
        assertEquals(1250.5, Numeros.decimal("1.250,50"), "con los dos separadores manda el último");
    }

    @Test
    void vacioOTextoRaro_esNull() {
        assertNull(Numeros.decimal(null));
        assertNull(Numeros.decimal(""));
        assertNull(Numeros.decimal("   "));
        assertNull(Numeros.decimal("abc"));
        assertNull(Numeros.decimal("NaN"));
        assertNull(Numeros.entero("tres"));
        assertNull(Numeros.entero("2,5"), "un entero con decimales no vale");
        assertEquals(5, Numeros.entero(" 5 "));
        assertEquals(28, Numeros.entero("28"));
    }

    @Test
    void senalDeReserva() {
        assertEquals(2500.0, OnlineReservationControllerAcceso.senalSugerida(250_000));
        assertEquals(1000.0, OnlineReservationControllerAcceso.senalSugerida(0), "mínimo por defecto");
        assertEquals(3000.0, OnlineReservationControllerAcceso.senal(3000.0, 250_000));
        assertEquals(2500.0, OnlineReservationControllerAcceso.senal(1.0, 250_000), "por debajo del mínimo: la sugerida");
        assertEquals(2500.0, OnlineReservationControllerAcceso.senal(null, 250_000));
        assertEquals(250_000.0, OnlineReservationControllerAcceso.senal(9_000_000.0, 250_000), "nunca más que el precio");
    }
}
