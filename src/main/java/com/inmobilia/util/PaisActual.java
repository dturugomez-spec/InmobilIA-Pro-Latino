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

    /** Código de la moneda de la inmobiliaria ("MXN", "COP"…) para textos e importes. */
    public static String moneda() { return get().moneda(); }

    public static void set(Pais p) { ACTUAL.set(p); }

    public static void limpiar() { ACTUAL.remove(); }

    /**
     * Para tareas en otro hilo (@Async, consultas en paralelo del panel): la tarea lleva el país de
     * quien la lanza y, al acabar, el hilo vuelve a como estaba (con CallerRunsPolicy puede ser el mismo).
     */
    public static Runnable propagar(Runnable tarea) {
        Pais pais = ACTUAL.get();
        return () -> {
            Pais antes = ACTUAL.get();
            if (pais != null) ACTUAL.set(pais); else ACTUAL.remove();
            try {
                tarea.run();
            } finally {
                if (antes != null) ACTUAL.set(antes); else ACTUAL.remove();
            }
        };
    }
}
