package com.inmobilia.util;

/**
 * País de la inmobiliaria que atiende la petición en curso (lo fija PaisInterceptor).
 * Fuera de una petición (tareas programadas, correos en segundo plano) devuelve el país por defecto.
 */
public final class PaisActual {
    private PaisActual() {}

    private static final ThreadLocal<Pais> ACTUAL = new ThreadLocal<>();

    public static Pais get() {
        Pais p = ACTUAL.get();
        return p != null ? p : Pais.de(null);
    }

    public static void set(Pais p) { ACTUAL.set(p); }

    public static void limpiar() { ACTUAL.remove(); }
}
