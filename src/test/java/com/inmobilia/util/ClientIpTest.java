package com.inmobilia.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class ClientIpTest {

    @Test
    void ipPublicaResueltaPorTomcat_ignoraCabeceraFalsificada() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("88.12.34.56");
        req.addHeader("X-Forwarded-For", "1.2.3.4");
        assertEquals("88.12.34.56", ClientIp.of(req));
    }

    @Test
    void detrasDeProxyInterno_usaElUltimoValorQueAnadeElProxy() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("100.64.0.7");
        req.addHeader("X-Forwarded-For", "6.6.6.6, 88.12.34.56");
        assertEquals("88.12.34.56", ClientIp.of(req), "el primer valor lo pone el cliente y no es fiable");
    }

    @Test
    void sinProxy_usaLaDireccionRemota() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        assertEquals("127.0.0.1", ClientIp.of(req));
    }

    @Test
    void rangosPrivados() {
        assertTrue(ClientIp.esPrivada("10.1.2.3"));
        assertTrue(ClientIp.esPrivada("172.20.0.1"));
        assertFalse(ClientIp.esPrivada("172.32.0.1"));
        assertTrue(ClientIp.esPrivada("100.100.1.1"));
        assertFalse(ClientIp.esPrivada("100.200.1.1"));
        assertTrue(ClientIp.esPrivada("fd12::1"));
        assertFalse(ClientIp.esPrivada("88.12.34.56"));
    }
}
