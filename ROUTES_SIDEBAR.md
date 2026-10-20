# 📚 Todas las rutas de la app (110 sprints)

Bloque listo para pegar en `src/main/resources/templates/fragments/layout.html` en el `<nav>` del sidebar.

---

## 🗂️ Sidebar HTML sugerido

```html
<!-- ============ DASHBOARD ============ -->
<a href="/dashboard" class="nav-item"><i data-lucide="home"></i> Dashboard</a>
<a href="/dashboards" class="nav-item"><i data-lucide="layout-dashboard"></i> Mis dashboards</a>

<!-- ============ INMUEBLES ============ -->
<div class="nav-group">🏠 Inmuebles</div>
<a href="/propiedades" class="nav-item">Propiedades</a>
<a href="/promociones" class="nav-item">Obra nueva / Promociones</a>
<a href="/comparar" class="nav-item">Comparador</a>
<a href="/mapa-global" class="nav-item">Mapa global</a>
<a href="/tours-360" class="nav-item">Tours 360°</a>
<a href="/staging-digital" class="nav-item">Staging digital IA</a>
<a href="/cee" class="nav-item">Certificados energéticos</a>
<a href="/imagenes-ia" class="nav-item">Mejora imágenes IA</a>
<a href="/reconocer-habitaciones" class="nav-item">Reconocer habitaciones IA</a>

<!-- ============ CLIENTES ============ -->
<div class="nav-group">👥 Clientes</div>
<a href="/leads" class="nav-item">Leads</a>
<a href="/leads-scoring" class="nav-item">AI Lead Scoring</a>
<a href="/inquilinos" class="nav-item">Inquilinos</a>
<a href="/propietarios" class="nav-item">Propietarios</a>
<a href="/cartera" class="nav-item">Cartera 360°</a>
<a href="/busquedas-guardadas" class="nav-item">Búsqueda inversa</a>

<!-- ============ COMERCIAL ============ -->
<div class="nav-group">💼 Comercial</div>
<a href="/pipelines" class="nav-item">Pipelines Kanban</a>
<a href="/visitas" class="nav-item">Visitas</a>
<a href="/visitas/calendario" class="nav-item">Calendario visitas</a>
<a href="/open-house" class="nav-item">Open House</a>
<a href="/reservas-online" class="nav-item">Reservas online</a>
<a href="/cobroking" class="nav-item">Co-broking MLS</a>

<!-- ============ COMUNICACIÓN ============ -->
<div class="nav-group">📞 Comunicación</div>
<a href="/whatsapp" class="nav-item">WhatsApp Business</a>
<a href="/sms" class="nav-item">SMS</a>
<a href="/inbox" class="nav-item">Inbox email</a>
<a href="/chatbot" class="nav-item">Chatbot web</a>

<!-- ============ MARKETING ============ -->
<div class="nav-group">📣 Marketing</div>
<a href="/campanas" class="nav-item">Campañas email</a>
<a href="/email-builder" class="nav-item">Email builder visual</a>
<a href="/newsletter" class="nav-item">Newsletter</a>
<a href="/feeds" class="nav-item">Feeds portales (Idealista...)</a>
<a href="/descripcion-ia" class="nav-item">Descripciones IA</a>
<a href="/reviews" class="nav-item">Reviews</a>
<a href="/referidos" class="nav-item">Referidos</a>
<a href="/nps" class="nav-item">NPS</a>
<a href="/microsite/admin" class="nav-item">Microsite web</a>

<!-- ============ DOCUMENTOS ============ -->
<div class="nav-group">📄 Documentos</div>
<a href="/contratos" class="nav-item">Contratos</a>
<a href="/plantillas-doc" class="nav-item">Plantillas con variables</a>
<a href="/firmas" class="nav-item">Firmas electrónicas</a>

<!-- ============ FINANZAS ============ -->
<div class="nav-group">💰 Finanzas</div>
<a href="/facturas" class="nav-item">Facturas Verifactu</a>
<a href="/caja" class="nav-item">Caja y gastos</a>
<a href="/gastos-agente" class="nav-item">Gastos agente</a>
<a href="/extractos" class="nav-item">Extractos propietarios</a>
<a href="/sepa" class="nav-item">SEPA cobros</a>
<a href="/conciliacion" class="nav-item">Conciliación bancaria</a>
<a href="/divisas" class="nav-item">Divisas</a>
<a href="/comisiones" class="nav-item">Comisiones</a>

<!-- ============ AUTOMATIZACIÓN ============ -->
<div class="nav-group">⚡ Automatización</div>
<a href="/workflows" class="nav-item">Workflows (Zapier)</a>
<a href="/notas-ia" class="nav-item">Notas IA</a>
<a href="/notas-voz" class="nav-item">Notas de voz</a>

<!-- ============ CALCULADORAS ============ -->
<div class="nav-group">🧮 Calculadoras</div>
<a href="/calculadora-hipoteca" class="nav-item">Hipoteca</a>
<a href="/comparador-hipotecas" class="nav-item">Comparar hipotecas</a>
<a href="/calculadora-plusvalia" class="nav-item">Plusvalía municipal</a>
<a href="/calculadora-rentabilidad" class="nav-item">Rentabilidad</a>
<a href="/calculadora-irpf" class="nav-item">IRPF venta vivienda</a>

<!-- ============ SEGUROS ============ -->
<div class="nav-group">🛡️ Seguros</div>
<a href="/seguros" class="nav-item">Pólizas</a>
<a href="/siniestros" class="nav-item">Siniestros</a>

<!-- ============ HERRAMIENTAS ============ -->
<div class="nav-group">🛠️ Herramientas</div>
<a href="/importar" class="nav-item">Importador CSV/Excel</a>
<a href="/catastro" class="nav-item">Consulta Catastro</a>
<a href="/ocr-dni" class="nav-item">OCR DNI</a>
<a href="/wiki" class="nav-item">Wiki interna</a>
<a href="/calendario-fiscal" class="nav-item">Calendario fiscal AEAT</a>

<!-- ============ INTEGRACIONES ============ -->
<div class="nav-group">🔗 Integraciones</div>
<a href="/holded" class="nav-item">Holded / A3 / Sage</a>
<a href="/google-calendar" class="nav-item">Google Calendar</a>
<a href="/api-keys" class="nav-item">API Keys</a>

<!-- ============ EQUIPO ============ -->
<div class="nav-group">👨‍💼 Equipo</div>
<a href="/team-chat" class="nav-item">Chat equipo</a>
<a href="/usuarios" class="nav-item">Usuarios</a>
<a href="/permisos-granulares" class="nav-item">Permisos granulares</a>
<a href="/tareas" class="nav-item">Tareas</a>

<!-- ============ ADMINISTRACIÓN ============ -->
<div class="nav-group">⚙️ Administración</div>
<a href="/auditoria" class="nav-item">Auditoría RGPD</a>
<a href="/whitelabel" class="nav-item">Whitelabel</a>
<a href="/suscripcion" class="nav-item">Mi plan</a>
<a href="/ajustes" class="nav-item">Ajustes</a>
```

---

## 📊 Estadísticas

- **88 rutas únicas** en el menú
- **15 grupos** lógicos
- **110 controllers** detrás
- **42 migraciones SQL**
- **~190 templates** Thymeleaf

---

## 🔓 Rutas públicas (sin login, accesibles desde fuera)

```
/m/{slug}                          Microsite inmobiliaria
/p/{slug-promocion}                Landing promoción
/propiedad/{id}                    Página pública de propiedad
/portal/{token}/idealista.xml      Feed Idealista
/portal/{token}/fotocasa.xml       Feed Fotocasa
/portal/{token}/habitaclia.xml     Feed Habitaclia
/portal/{token}/pisoscom.xml       Feed Pisos.com
/firmas/firmar/{token}             Firmar documento
/visitas/confirmar/{token}         Confirmar visita
/visitas/feedback-publico/{token}  Feedback post-visita
/open-house/publico/{token}        Registro evento
/reviews/publico/{token}           Dejar review
/nps/responder/{token}             Encuesta NPS
/referidos/codigo/{codigo}         Landing referido
/tours-360/publico/{token}         Ver tour 360
/newsletter/suscribir              Suscribirse
/reservas-online/p/{propId}        Reservar online
/chatbot/widget.js                 Script chatbot embed
/api/v2/properties                 API REST
/api/v2/leads                      API REST
/api/v2/stats                      API REST
/api/v2/openapi.json               Swagger spec
/whatsapp/webhook                  Webhook Meta
```
