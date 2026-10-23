package com.inmobilia.service;

import com.inmobilia.util.Pais;
import com.inmobilia.util.PaisActual;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * País y ciudad de cada inmobiliaria (users.pais / users.ciudad de la cuenta administradora =
 * organization_id). La ciudad decide la zona horaria; el país, moneda, impuesto, documentos y leyes.
 * Caché de 5 minutos para no consultar la BD en cada petición.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaisService {

    private static final long TTL_MS = 5 * 60_000;

    private record Entrada(Pais pais, long at) {}

    private final SupabaseService supabase;
    private final Map<String, Entrada> cache = new ConcurrentHashMap<>();

    /** País de la inmobiliaria; el de por defecto si no lo ha elegido o no se puede leer. */
    public Pais deOrganizacion(String orgUid) {
        if (orgUid == null || orgUid.isBlank()) return Pais.de(null);
        Entrada e = cache.get(orgUid);
        if (e != null && System.currentTimeMillis() - e.at() < TTL_MS) return e.pais();
        Pais p = Pais.de(null);
        try {
            List<Map<String, Object>> us = supabase.selectFilter("users",
                    "id=eq." + orgUid + "&select=pais,moneda,ciudad&limit=1");
            if (!us.isEmpty()) {
                Map<String, Object> u = us.get(0);
                Object mon = u.get("moneda");
                p = Pais.de(u.get("pais")).conMoneda(mon == null ? null : String.valueOf(mon));
                if (u.get("ciudad") != null) {
                    p = p.enCiudad(com.inmobilia.util.Ciudad.leer(p.codigo() + ":" + u.get("ciudad")).orElse(null));
                }
            }
        } catch (Exception ex) {
            // Fallo puntual de la BD: seguir con el país que ya sabíamos (no fijar el de por defecto 5 minutos)
            log.debug("No se pudo leer el país de {}: {}", orgUid, ex.getMessage());
            if (e != null) return e.pais();
            return p;
        }
        cache.put(orgUid, new Entrada(p, System.currentTimeMillis()));
        return p;
    }

    /** Para páginas públicas de una inmobiliaria (ficha, microsite, valoración…): usar su país. */
    public Pais usar(String orgUid) {
        Pais p = deOrganizacion(orgUid);
        PaisActual.set(p);
        return p;
    }

    /**
     * Guarda la ciudad ("MX:Guadalajara"), el país que sale de ella y la moneda de trabajo (la local
     * del país o USD). Devuelve el país resultante, o null si la ciudad no es válida o no se guardó.
     */
    public Pais guardar(String orgUid, String ciudad, String moneda) {
        var c = com.inmobilia.util.Ciudad.leer(ciudad);
        if (c.isEmpty()) return null;
        Pais p = Pais.de(c.get().pais()).conMoneda(moneda).enCiudad(c.get());
        Map<String, Object> datos = new java.util.HashMap<>();
        datos.put("pais", p.codigo());
        datos.put("moneda", p.moneda());
        datos.put("ciudad", p.ciudad()); // null = otra ciudad del país
        boolean ok = supabase.update("users", orgUid, datos);
        cache.remove(orgUid);
        return ok ? p : null;
    }
}
