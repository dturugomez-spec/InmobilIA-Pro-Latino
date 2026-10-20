package com.inmobilia.controller;

/** Acceso desde tests de otros paquetes a los cálculos de la señal de reserva. */
public final class OnlineReservationControllerAcceso {
    private OnlineReservationControllerAcceso() {}

    public static double senalSugerida(double precio) { return OnlineReservationController.senalSugerida(precio); }

    public static double senal(Double propuesta, double precio) { return OnlineReservationController.senal(propuesta, precio); }
}
