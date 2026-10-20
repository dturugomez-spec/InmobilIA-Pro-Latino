package com.inmobilia.controller;

import com.inmobilia.service.BrandingService;
import com.inmobilia.service.LeadAutoResponderService;
import com.inmobilia.service.StorageService;
import com.inmobilia.service.SupabaseService;
import com.inmobilia.util.SessionUtils;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Fotos de inmuebles: el almacenamiento es público, así que solo fotos y solo las de la propia inmobiliaria. */
class FotosInmuebleTest {

    private final SupabaseService supabase = mock(SupabaseService.class);

    @Test
    void paginaPublica_soloEnsenaFotosDeLaInmobiliaria() {
        when(supabase.selectFilter(anyString(), anyString())).thenAnswer(inv ->
                "propiedades".equals(inv.getArgument(0)) && inv.getArgument(1, String.class).startsWith("id=eq.p1")
                        ? List.of(Map.of("id", "p1", "user_id", "org1", "name", "Piso")) : List.of());
        BrandingService branding = mock(BrandingService.class);
        when(branding.branding(anyString())).thenReturn(Map.of());

        new PublicPropertyPageController(supabase, branding, mock(LeadAutoResponderService.class))
                .publica("p1", new ExtendedModelMap());

        verify(supabase).selectFilter(eq("attachments"), argThat(f ->
                f.contains("entity_id=eq.p1") && f.contains("org_id=eq.org1") && f.contains("file_type=eq.image")));
    }

    @Test
    void noSeSubenFotosAlInmuebleDeOtraInmobiliaria() {
        StorageService storage = mock(StorageService.class);
        when(supabase.findBy(eq("propiedades"), anyMap())).thenReturn(List.of());
        MockHttpSession session = new MockHttpSession();
        SessionUtils.login(session, "org2", "a@b.es", "A");

        Map<String, Object> r = new BulkPhotoController(storage, supabase).upload("p1",
                new MultipartFile[]{new MockMultipartFile("files", "a.jpg", "image/jpeg", new byte[]{1})}, false, session);

        assertEquals(false, r.get("ok"));
        verify(storage, never()).upload(any(), anyString());
        verify(supabase, never()).insert(eq("attachments"), anyMap());
    }

    @Test
    void soloSeAceptanFotos() {
        assertTrue(StorageService.esImagen(new MockMultipartFile("f", "salon.JPG", "image/jpeg", new byte[]{1})));
        assertTrue(StorageService.esImagen(new MockMultipartFile("f", "cocina.webp", "image/webp", new byte[]{1})));
        assertFalse(StorageService.esImagen(new MockMultipartFile("f", "dni.pdf", "application/pdf", new byte[]{1})));
        assertFalse(StorageService.esImagen(new MockMultipartFile("f", "logo.svg", "image/svg+xml", new byte[]{1})));
        assertFalse(StorageService.esImagen(new MockMultipartFile("f", "foto.jpg.html", "image/jpeg", new byte[]{1})));
    }
}
