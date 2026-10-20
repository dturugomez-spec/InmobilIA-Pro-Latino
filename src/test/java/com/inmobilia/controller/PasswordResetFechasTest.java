package com.inmobilia.controller;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/** Las fechas de caducidad de los enlaces llegan de Supabase en varios formatos: todos deben leerse. */
class PasswordResetFechasTest {

    private static final OffsetDateTime ESPERADA = OffsetDateTime.of(2026, 10, 17, 10, 15, 30, 0, ZoneOffset.UTC);

    @Test
    void formatoDeSupabaseConMicrosegundosYZona() {
        OffsetDateTime f = PasswordResetController.leerFecha("2026-10-17T10:15:30.123456+00:00");
        assertNotNull(f, "el formato habitual de PostgREST debe leerse (antes fallaba y rechazaba todos los enlaces)");
        assertEquals(ESPERADA.toInstant().getEpochSecond(), f.toInstant().getEpochSecond());
    }

    @Test
    void otrosFormatos() {
        assertNotNull(PasswordResetController.leerFecha("2026-10-17T10:15:30+00:00"));
        assertNotNull(PasswordResetController.leerFecha("2026-10-17 10:15:30.5+00"));
        assertNotNull(PasswordResetController.leerFecha("2026-10-17T12:15:30.123456789+02:00"));
        assertEquals(ESPERADA.toInstant(), PasswordResetController.leerFecha("2026-10-17T10:15:30").toInstant());
        assertNull(PasswordResetController.leerFecha(null));
        assertNull(PasswordResetController.leerFecha("null"));
        assertNull(PasswordResetController.leerFecha("no es una fecha"));
    }
}
