package com.inmobilia.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PermissionServiceTest {

    private final PermissionService p = new PermissionService();

    @Test
    void admin_hasFullAccess() {
        for (String section : PermissionService.SECTIONS) {
            assertEquals(PermissionService.Access.WRITE, p.access("admin", section));
            assertTrue(p.canRead("admin", section));
            assertTrue(p.canWrite("admin", section));
        }
    }

    @Test
    void lectura_canReadButNotWrite() {
        assertTrue(p.canRead("lectura", "propiedades"));
        assertFalse(p.canWrite("lectura", "propiedades"));
        assertFalse(p.canWrite("lectura", "equipo"));
    }

    @Test
    void comercial_canManageLeads_butNotFinance() {
        assertTrue(p.canWrite("comercial", "leads"));
        assertTrue(p.canWrite("comercial", "oportunidades"));
        assertFalse(p.canRead("comercial", "transacciones"));
    }

    @Test
    void rrhh_canManageTeam_butNotProperties() {
        assertTrue(p.canWrite("rrhh", "personal"));
        assertTrue(p.canWrite("rrhh", "equipo"));
        assertFalse(p.canRead("rrhh", "propiedades"));
        assertFalse(p.canRead("rrhh", "leads"));
    }

    @Test
    void contable_canManageInvoices_butNotLeads() {
        assertTrue(p.canWrite("contable", "transacciones"));
        assertFalse(p.canWrite("contable", "leads"));
    }

    @Test
    void unknownRole_hasNoAccess() {
        assertEquals(PermissionService.Access.NONE, p.access("unknown_role", "propiedades"));
        assertFalse(p.canRead("unknown_role", "anything"));
    }

    @Test
    void nullRole_returnsNone() {
        assertEquals(PermissionService.Access.NONE, p.access(null, "propiedades"));
    }
}
