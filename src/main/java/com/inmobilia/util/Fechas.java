package com.inmobilia.util;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Lectura robusta de las fechas que devuelve Supabase/PostgREST. */
public final class Fechas {
    private Fechas() {}

    /**
     * Lee "2026-10-17T10:15:30.123456+00:00" y variantes (espacio en vez de 'T', zona "+00",
     * sin zona = UTC). null si no se entiende. Nunca recortar el texto: se pierde la zona horaria.
     */
    public static OffsetDateTime leer(Object valor) {
        if (valor == null) return null;
        String s = String.valueOf(valor).trim().replace(' ', 'T');
        if (s.isEmpty() || "null".equals(s)) return null;
        if (s.matches(".*[+-]\\d{2}$")) s = s + ":00";            // "+00" → "+00:00"
        try {
            return OffsetDateTime.parse(s);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(s).atOffset(ZoneOffset.UTC);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    /** Ahora en UTC con 'Z' (sin '+', que en una URL se leería como espacio). */
    public static String ahoraUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC).toString();
    }
}
