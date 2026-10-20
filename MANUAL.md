# 📖 Manual completo · InmobilIA Pro

> Guía exhaustiva de cada funcionalidad de la plataforma.
> Útil para **exponer el producto a clientes** y para **entender qué hace cada cosa**.

---

## Índice

1. [Visión general del producto](#1-visión-general-del-producto)
2. [Inicio rápido: tu primer día](#2-inicio-rápido-tu-primer-día)
3. [Módulos por área de negocio](#3-módulos-por-área-de-negocio)
   - [🏠 Cartera e inmuebles](#-cartera-e-inmuebles)
   - [👥 Clientes y leads](#-clientes-y-leads)
   - [💼 Comercial y operaciones](#-comercial-y-operaciones)
   - [📞 Comunicación](#-comunicación)
   - [📣 Marketing](#-marketing)
   - [📄 Documentos y firmas](#-documentos-y-firmas)
   - [💰 Finanzas y facturación](#-finanzas-y-facturación)
   - [🤖 Inteligencia artificial](#-inteligencia-artificial)
   - [⚡ Automatización](#-automatización)
   - [🌐 Presencia online](#-presencia-online)
   - [🛠️ Herramientas y calculadoras](#-herramientas-y-calculadoras)
   - [🔗 Integraciones](#-integraciones)
   - [⚙️ Administración](#-administración)
4. [Cumplimiento legal](#4-cumplimiento-legal)
5. [Planes y limitaciones](#5-planes-y-limitaciones)
6. [Preguntas frecuentes](#6-preguntas-frecuentes)

---

## 1. Visión general del producto

**InmobilIA Pro** es una plataforma integral para inmobiliarias en España que sustituye 8+ herramientas tradicionales (CRM, ERP, web, email marketing, contabilidad, firma electrónica, agenda...) en un único sistema diseñado específicamente para el sector.

### ¿Para quién es?

- **Agencias inmobiliarias** de cualquier tamaño (desde autónomos hasta franquicias con 50+ usuarios)
- **API Personal Shoppers** y **gestores de patrimonio inmobiliario**
- **Administradores de fincas** que también hacen venta/alquiler
- **Promotores de obra nueva**

### ¿Qué problemas resuelve?

| Problema típico | Cómo lo resolvemos |
|---|---|
| Datos dispersos en Excel, Drive y emails | Todo centralizado en un único sistema |
| Leads que se pierden por falta de seguimiento | AI Lead Scoring + workflows automáticos |
| Contratos que se firman a mano y se pierden | Firma electrónica eIDAS con validez legal |
| Verifactu y la AEAT son un dolor | Facturas con cadena hash + modelos automáticos |
| Web inmobiliaria cara y desactualizada | Microsite SEO incluido + sindicación a portales |
| Cobros de alquiler manuales | SEPA Direct Debit con conciliación automática |
| Tener que duplicar la propiedad en 4 portales | Feed XML automático a Idealista/Fotocasa/Habitaclia/Pisos |

---

## 2. Inicio rápido: tu primer día

Pasos que recomendamos seguir nada más entrar:

### Paso 1 (5 min): Configura tu empresa
**Ajustes → Mi empresa**
- Nombre comercial
- NIF
- Dirección fiscal
- Logo (sube tu logo)
- Datos para facturación

### Paso 2 (5 min): Configura SMTP
**Ajustes → Email SMTP**
- Servidor (ej: smtp-relay.brevo.com)
- Usuario y contraseña SMTP
- Email "From" (el que ven tus clientes)

> Sin esto, no se envían los emails de confirmación de visitas ni recordatorios.

### Paso 3 (10 min): Importa tu cartera
**Herramientas → Importador CSV**
- Sube un Excel/CSV con propiedades, leads o inquilinos
- La primera fila debe ser cabecera
- El sistema detecta tipos automáticamente

### Paso 4 (15 min): Da de alta tu primer equipo
**Equipo → Añadir miembro**
- Email, nombre, rol (admin/comercial/contable/...)
- El sistema le envía email con credenciales

### Paso 5: Listo para vender
Ya puedes:
- Crear leads desde la cartera
- Programar visitas con confirmación automática
- Generar contratos con plantillas pre-rellenadas

---

## 3. Módulos por área de negocio

### 🏠 Cartera e inmuebles

#### Propiedades
**Ruta:** `/propiedades`

Donde gestionas toda tu cartera de inmuebles.

**Qué puedes hacer:**
- Crear propiedad con fotos, plano, vídeo, tour 360º
- Marcar como venta/alquiler/traspaso
- Asociar a propietario
- Histórico de precios (cambios trazados)
- Eficiencia energética (CEE)
- Datos catastrales (auto-rellenable con API Catastro)
- Etiquetas (destacada, exclusiva, urgente...)

**Cuándo se usa:** cada vez que captas un inmueble.

#### Obra nueva / Promociones
**Ruta:** `/promociones`

Para promotores que venden bloques de viviendas nuevas.

**Qué puedes hacer:**
- Crear promoción con plantas, fases comerciales
- Cada unidad con su precio, estado (libre/reservada/vendida)
- Página pública SEO de la promoción
- Reservas online con depósito Stripe

**Cuándo se usa:** vendes obra nueva o cooperativas.

#### Comparador de propiedades
**Ruta:** `/comparar`

Pones 2-4 propiedades y las compara lado a lado.

**Cuándo se usa:** cuando un cliente duda entre varios inmuebles y quieres mostrarle la comparativa profesionalmente.

#### Mapa global
**Ruta:** `/mapa-global`

Vista geográfica de toda tu cartera + análisis de precios medios por zona.

**Cuándo se usa:**
- Reuniones con propietarios para enseñar tu presencia en su zona
- Detectar zonas donde tienes poca o mucha cartera

#### Tours virtuales 360º
**Ruta:** `/tours-360`

Embeber tours de Matterport, Kuula o equivalentes en cada propiedad.

**Beneficio:** propiedades con tour 360º tienen **40% más engagement** y filtran visitas presenciales innecesarias.

#### Certificados energéticos (CEE)
**Ruta:** `/cee`

Gestión de los certificados energéticos obligatorios (RD 390/2021).

**Características:**
- Asociado a la propiedad
- Calificación A-G
- Fecha de caducidad
- **Alertas automáticas a 90, 30 y 7 días** antes de caducar

---

### 👥 Clientes y leads

#### Leads
**Ruta:** `/leads`

Captura y seguimiento de potenciales clientes (comprador/inquilino).

**Datos que recogemos:**
- Datos personales (nombre, email, teléfono, DNI)
- Criterios de búsqueda (zona, tipo, presupuesto, habitaciones)
- Origen del lead (microsite, portal, referido, etc.)
- Plazo de decisión (urgente / 1 mes / 3 meses / no urgente)
- Notas y interacciones

**Estados del lead:**
- Nuevo
- Contactado
- Calificado
- Con visita
- Con oferta
- Ganado / Perdido

#### AI Lead Scoring
**Ruta:** `/leads-scoring`

**La estrella del sistema.** Cada lead recibe automáticamente:

- **Puntuación 0-100** basada en 6 factores ponderados:
  - Completitud del perfil (20 pts)
  - Calidad económica (20 pts)
  - Urgencia (15 pts)
  - Engagement con tus comunicaciones (25 pts)
  - Confianza (DNI, datos verificados) (10 pts)
  - Recency / actividad reciente (10 pts)

- **Temperatura:**
  - 🔥 **HOT (80-100)**: llama HOY, va a cerrar
  - 🟠 **Caliente (60-79)**: contactar esta semana
  - 🟡 **Tibio (40-59)**: drip marketing
  - 🔵 **Frío (<40)**: deja descansar

- **Probabilidad de conversión** (%) calculada estadísticamente
- **Análisis IA bajo demanda**: la IA escribe en texto natural por qué este lead es bueno/malo y qué hacer

**Cuándo se usa:** cada mañana, mira los HOT del día y empieza por ahí.

#### Cartera 360º
**Ruta:** `/cartera`

Timeline unificado de cada cliente: todas sus interacciones, visitas, propiedades vistas, comunicaciones, documentos, contratos...

**Cuándo se usa:** antes de una llamada importante, para tener TODO el contexto del cliente en una pantalla.

#### Inquilinos y propietarios
**Rutas:** `/inquilinos` · `/propietarios`

Bases de datos separadas con histórico completo de cada uno.

**Inquilinos:**
- Datos del contrato actual
- Histórico de pagos
- Devoluciones de fianza
- Comunicaciones

**Propietarios:**
- Inmuebles de su propiedad
- Liquidaciones mensuales
- Datos bancarios (para SEPA)
- Documentación legal

#### Búsqueda inversa
**Ruta:** `/busquedas-guardadas`

Configuras criterios para clientes específicos. Cuando llega una propiedad que encaja, el sistema:
1. **Avisa al lead automáticamente por email**
2. Te avisa a ti

**Cuándo se usa:** cliente que busca algo específico que no tienes en cartera ahora mismo.

---

### 💼 Comercial y operaciones

#### Pipeline Kanban
**Ruta:** `/pipelines`

Tablero visual estilo Trello con tus operaciones en curso.

**Pipelines pre-configurados:**
- **Leads**: Nuevo → Contactado → Calificado → Con visita → Con oferta → Ganado/Perdido
- **Captación**: Prospecto → Visita técnica → Valoración → Negociación → Captado/Perdido
- **Ofertas**: Recibida → Presentada → Negociando → Aceptada → Arras → Escriturada

**Drag & drop** entre columnas. Cada movimiento dispara workflows si los tienes configurados.

#### Visitas
**Rutas:** `/visitas` · `/visitas/calendario`

Programación de visitas con:
- Calendario vista mes/semana/día
- Sincronización con Google Calendar (opcional)
- **Recordatorios automáticos al cliente** 24h y 1h antes
- **Feedback post-visita**: enlace público para que el cliente puntúe la visita
- Tipos: presencial, virtual, open house

#### Open House
**Ruta:** `/open-house`

Jornadas de puertas abiertas con:
- Página pública de registro
- Captura de leads automática
- Estadísticas de asistencia
- Co-broking con otras agencias

#### Reservas online
**Ruta:** `/reservas-online`

El cliente reserva un inmueble desde tu microsite pagando una señal:
- Pago con Stripe (tarjeta)
- Reserva válida 15 días
- Auto-generación de contrato de arras

#### Co-broking MLS
**Ruta:** `/cobroking`

**Único en el mercado español.** Comparte cartera con agencias asociadas y divide comisiones automáticamente.

**Funcionalidades:**
- Decides qué propiedades compartes
- Configura % de reparto por agencia
- Al cerrarse una operación cruzada, el sistema reparte la comisión
- Histórico de operaciones compartidas

---

### 📞 Comunicación

#### WhatsApp Business
**Ruta:** `/whatsapp`

API Cloud oficial de Meta integrada.

**Qué puedes hacer:**
- Plantillas pre-aprobadas (recordatorio visita, ficha propiedad, etc.)
- Envíos masivos personalizados a leads
- Conversaciones 1:1 desde el CRM (sin abrir WhatsApp en el móvil)
- Adjuntar fotos de propiedades, plantillas de contrato, etc.

**Cuándo se usa:** todo el contacto comercial. WhatsApp tiene **98% de tasa de apertura** vs ~22% del email.

#### SMS
**Ruta:** `/sms`

Pasarela conectada (Twilio o equivalente) para:
- Recordatorios de visita
- Confirmaciones rápidas
- Campañas masivas

#### Inbox unificado
**Ruta:** `/inbox`

Bandeja de entrada de tu email (IMAP) integrada con la ficha del cliente. Cuando llega un email de Juan Pérez, queda asociado a su lead/cliente.

---

### 📣 Marketing

#### Campañas email
**Ruta:** `/campanas`

Email marketing con:
- Segmentación dinámica (todos los leads HOT, todos los propietarios de Madrid, etc.)
- A/B testing de asuntos
- Tracking de aperturas y clicks
- Auto-respuesta si el cliente contesta

#### Email builder visual
**Ruta:** `/email-builder`

Editor drag & drop estilo Mailchimp para crear plantillas profesionales sin tocar HTML.

#### Newsletter
**Ruta:** `/newsletter`

Boletín mensual con:
- Suscripción desde tu microsite (doble opt-in RGPD)
- Plantillas pre-diseñadas
- Lista filtrable

#### Feeds a portales
**Ruta:** `/feeds`

Genera el XML que los portales inmobiliarios consumen para mostrar tus propiedades.

**Portales soportados:**
- Idealista
- Fotocasa
- Habitaclia
- Pisos.com

**Cómo funciona:**
1. Marcas las propiedades como "publicar en portales"
2. La URL del feed (ej: `https://tu-dominio.com/feeds/idealista.xml`) se da al portal
3. El portal sincroniza tu cartera automáticamente cada hora

#### Microsite SEO
**Ruta:** `/microsite/admin`

Tu propia web inmobiliaria con:
- Dominio personalizable (`tu-agencia.com`)
- SEO técnico optimizado
- Velocidad de carga premium
- Blog integrado
- Formularios de contacto que crean leads automáticamente
- Página dedicada por propiedad

#### Reviews
**Ruta:** `/reviews`

Sistema de reseñas públicas de clientes:
- Envías link al cliente tras cerrar operación
- Cliente puntúa 1-5 estrellas + comentario
- Tú apruebas antes de publicar
- Los reviews aprobados salen en tu microsite

#### Referidos
**Ruta:** `/referidos`

Programa de "tráete a un amigo":
- Cliente comparte su código único
- Al cerrarse operación del referido, cliente original recibe X € o comisión
- Trazabilidad completa

#### NPS (Net Promoter Score)
**Ruta:** `/nps`

Mide la satisfacción de tus clientes:
- Tras operación cerrada, email automático con pregunta única ("Recomendarías nuestra inmobiliaria del 0 al 10")
- Sistema clasifica: Promotores (9-10) / Pasivos (7-8) / Detractores (0-6)
- Ves tu NPS global y por agente

---

### 📄 Documentos y firmas

#### Firma electrónica eIDAS
**Ruta:** `/firmas`

**Otro punto fuerte del sistema.** Firma electrónica con validez legal según RD 6/2020 (eIDAS).

**Cómo funciona:**
1. Subes el documento PDF (o usas plantilla)
2. Añades los firmantes (cliente, propietario, etc.)
3. Cada uno recibe email con link único
4. Verifican identidad por OTP (código a su móvil)
5. Firman dibujando con dedo/ratón
6. Sistema genera **cadena hash blockchain** con cada firma para auditoría
7. PDF final firmado + auditoría descargable

**Validez legal:** plena. Vale en juicios.

#### Plantillas de documentos
**Ruta:** `/plantillas-doc`

Plantillas con **variables auto-rellenables**:

**Plantillas incluidas:**
- Contrato de arrendamiento LAU
- Contrato de arrendamiento de temporada
- Contrato de compraventa
- Contrato de arras
- Mandato de venta
- Autorización de visita
- Hoja de encargo

**Variables que se rellenan solas:**
- `{cliente.nombre}`, `{cliente.dni}`, `{cliente.direccion}`
- `{propiedad.direccion}`, `{propiedad.precio}`, `{propiedad.referencia_catastral}`
- `{fecha}`, `{precio_letras}` (precio en letra para arras)

#### OCR DNI
**Ruta:** `/ocr-dni`

Foto del DNI/NIE del cliente desde el móvil → todos los datos rellenan automáticamente la ficha.

**Datos extraídos:**
- Nombre y apellidos
- Número DNI/NIE (con validación de letra)
- Fecha de nacimiento
- Nacionalidad

---

### 💰 Finanzas y facturación

#### Facturación Verifactu
**Ruta:** `/invoices`

Facturas conformes al **RD 1007/2023 (Verifactu)** obligatorio desde 2026.

**Características:**
- Cadena hash SHA-256 (cada factura enlazada con la anterior)
- QR conforme a AEAT
- Numeración secuencial inmutable
- Envío automático por email al cliente
- PDF profesional con tu marca

#### SEPA Direct Debit
**Ruta:** `/sepa`

Cobros automáticos de alquileres y servicios:
1. Inquilino firma mandato SEPA (digital, con eIDAS)
2. Cada mes, el sistema genera fichero XML **pain.008.001.02**
3. Lo subes a tu banco
4. Banco cobra a todos los inquilinos automáticamente

**Beneficio:** cero impagos por olvido del inquilino.

#### Conciliación bancaria
**Ruta:** `/conciliacion`

Importas el extracto bancario en CSV y el sistema:
- Asocia cada movimiento a su factura correspondiente
- Marca facturas como pagadas
- Detecta pagos pendientes o sobrantes

#### Caja chica
**Ruta:** `/caja`

Control de movimientos en efectivo (no bancarios):
- Ingresos y gastos
- Justificantes adjuntos (foto del ticket)
- Cierre de caja mensual

#### Gastos del agente
**Ruta:** `/gastos-agente`

Reembolsos al equipo:
- Kilometraje (con cálculo automático)
- Dietas
- Adjuntar tickets
- Aprobación por el manager

#### Extractos propietarios
**Ruta:** `/extractos`

Liquidación mensual automática a propietarios:
- Rentas cobradas
- Gastos pagados (comunidad, IBI, mantenimiento...)
- Tu comisión retenida
- Total a transferir al propietario
- PDF firmable

#### Multi-divisa
**Ruta:** `/divisas`

Trabajar con clientes internacionales:
- Cotizaciones del **Banco Central Europeo** actualizadas diariamente
- Facturas en EUR, USD, GBP, etc.
- Conversión automática para reporting

#### Comisiones
**Ruta:** `/commissions`

Cálculo automático de comisiones:
- Por agente
- Por propiedad
- Por operación
- Liquidaciones mensuales

---

### 🤖 Inteligencia artificial

#### Descripciones SEO automáticas
**Ruta:** `/descripcion-ia`

Le das los datos del inmueble y la IA escribe:
- Título atractivo y SEO-friendly
- Descripción de 200-400 palabras
- Lista de bullets con highlights
- En español, inglés o catalán

**Cuándo se usa:** cada vez que publicas una propiedad nueva. Ahorra 15-20 min por anuncio.

#### Chatbot AI 24/7
**Ruta:** `/chatbot`

Widget que embeber en tu microsite. Conversa con visitantes y:
- Responde dudas sobre propiedades
- Agenda visitas
- Captura datos del visitante (lead)
- Conectado a tu inventario real
- En 25 idiomas

#### Notas inteligentes
**Ruta:** `/notas-ia`

Tras una visita, dictas notas y la IA:
- Las transcribe
- Las resume
- Las asocia al cliente y propiedad

#### Notas de voz
**Ruta:** `/notas-voz`

Grabas un audio (en una visita, en el coche...) y queda transcrito automáticamente.

#### Reconocimiento de habitaciones
**Ruta:** `/reconocer-habitaciones`

Subes fotos del inmueble y la IA:
- Detecta tipo de habitación (salón, cocina, dormitorio, baño...)
- Evalúa estado (a reformar, buen estado, lujo)
- Sugiere características destacables para el anuncio

#### Mejora de imágenes
**Ruta:** `/imagenes-ia`

IA que mejora fotos del inmueble:
- Corrige exposición
- Endereza líneas
- Mejora la iluminación
- Elimina objetos no deseados (cables, residuos)

#### Staging digital
**Ruta:** `/staging-digital`

IA que "decora" virtualmente pisos vacíos. Tu cliente ve cómo quedaría amueblado sin gastar en home staging real.

---

### ⚡ Automatización

#### Workflows
**Ruta:** `/workflows`

Editor visual de reglas "cuando X → haz Y, Z, W".

**Ejemplos de workflows típicos:**

1. **Asignación automática de leads**:
   - Cuando entre un lead nuevo
   - Si presupuesto > 500.000€ → asignar al director + email urgente
   - Si presupuesto < 200.000€ → round robin entre comerciales

2. **Seguimiento post-visita**:
   - Cuando se haga una visita
   - 24h después → enviar email con propiedades similares
   - Si no responde en 48h → tarea para llamar

3. **Reciclaje de leads fríos**:
   - Cada 30 días
   - Si lead lleva 30 días sin actividad → enviar email "¿Sigues buscando?"
   - Si no responde → archivar
   - Si responde → reactivar como caliente

**Triggers disponibles (13):**
- Lead nuevo / actualizado
- Visita programada / realizada / cancelada
- Factura emitida / pagada / impagada
- Contrato firmado
- Propiedad publicada / vendida / alquilada
- Email recibido / enviado

**Acciones disponibles (7):**
- Enviar email / WhatsApp / SMS
- Asignar agente
- Crear tarea
- Mover en kanban
- Notificar
- Etiquetar
- Cambiar de estado

---

### 🌐 Presencia online

#### Microsite admin
**Ruta:** `/microsite/admin`

Tu web inmobiliaria con dominio propio:
- Diseño responsive
- SEO técnico optimizado (sitemap, schema.org, meta tags)
- Velocidad +90 en Google PageSpeed
- Página dedicada por propiedad
- Blog integrado

#### Página pública de propiedad
**Cada propiedad genera automáticamente una URL pública:**
`https://tu-agencia.com/propiedad/{slug}`

Con:
- Fotos en alta resolución
- Plano
- Tour 360º
- Mapa con ubicación
- Datos catastrales
- Eficiencia energética
- Formulario de contacto (crea lead automáticamente)

---

### 🛠️ Herramientas y calculadoras

#### Importador masivo CSV
**Ruta:** `/importar`

Sube Excel/CSV de propiedades, leads, inquilinos o propietarios.

#### Catastro
**Ruta:** `/catastro`

Consulta el Catastro español por referencia catastral o por dirección. Auto-rellena la ficha de propiedad.

#### Calendario fiscal AEAT
**Ruta:** `/calendario-fiscal`

Recordatorios automáticos de los plazos fiscales: modelos 100, 130, 180, 184, 190, 200, 303, 347, 390...

#### Calculadora de hipoteca
**Ruta:** `/calculadora-hipoteca`

Para mostrar a tus clientes la cuota mensual de un inmueble en función de:
- Precio
- Capital aportado
- Plazo
- Tipo de interés

#### Calculadora de plusvalía municipal
**Ruta:** `/calculadora-plusvalia`

Cálculo del IIVTNU según la nueva normativa.

#### Calculadora de rentabilidad
**Ruta:** `/rentabilidad`

Rentabilidad bruta y neta de un inmueble como inversión.

---

### 🔗 Integraciones

#### Google Calendar
**Ruta:** `/google-calendar`

Sincroniza tus visitas con Google Calendar bidireccional.

#### Holded / A3 / Sage
**Ruta:** `/holded`

Exporta facturas y movimientos a tu software contable.

#### API REST (plan Enterprise)
**Ruta:** `/api-keys`

Tu propio token para conectar herramientas externas.

---

### ⚙️ Administración

#### Equipo
**Ruta:** `/equipo`

Gestión de usuarios de tu inmobiliaria:
- Añadir/eliminar usuarios
- Asignar roles
- Activar/desactivar

**Roles disponibles:**
- **Admin**: acceso total
- **Director**: todo excepto facturación/finanzas
- **Comercial**: solo su cartera y leads
- **Contable**: solo facturación
- **RRHH**: solo gestión de equipo
- **Técnico**: configuración del sistema
- **Lectura**: solo ver, no editar

#### Permisos granulares
**Ruta:** `/permisos-granulares`

Más allá de los roles, configura permisos por módulo:
- "Comercial Juan SÍ puede crear contratos pero NO ver facturas"
- "Contable Marta SÍ ve facturas pero NO accede a la cartera"

#### Auditoría RGPD
**Ruta:** `/auditoria`

Log completo de quién hace qué en el sistema:
- Quién entró
- Qué vio
- Qué modificó
- Cuándo

Cumple los requisitos de auditoría del RGPD.

#### Whitelabel
**Ruta:** `/whitelabel` (plan Agency / Enterprise)

Personaliza la apariencia:
- Tu logo en la cabecera
- Tus colores corporativos
- Tu subdominio (`tuagencia.app`)
- Tus emails con tu marca

---

## 4. Cumplimiento legal

InmobilIA Pro cumple desde el día 1 con:

| Norma | Qué cubre |
|---|---|
| **RD 1007/2023 (Verifactu)** | Facturación electrónica con cadena hash |
| **RD 6/2020 (eIDAS)** | Firma electrónica con validez legal |
| **RGPD (UE 2016/679)** | Protección de datos personales |
| **LOPDGDD (LO 3/2018)** | Adaptación española del RGPD |
| **LAU (Ley 29/1994)** | Arrendamientos urbanos |
| **AEAT (15+ modelos)** | Declaraciones fiscales automáticas |
| **SEPA pain.008** | Cobros bancarios europeos |
| **RD 390/2021** | Certificados energéticos |

**Cada cambio normativo** se aplica automáticamente en la plataforma.

---

## 5. Planes y limitaciones

| Característica | Starter | Pro | Agency | Enterprise |
|---|:-:|:-:|:-:|:-:|
| **Precio** | 0€ (10 días) | 39€/mes | 79€/mes | 99€/mes |
| Propiedades | 3 | 10 | 50 | ∞ |
| Leads | 20 | 200 | ∞ | ∞ |
| Usuarios | 1 | 1 | 5 | ∞ |
| Verifactu | ✅ | ✅ | ✅ | ✅ |
| Firma eIDAS | ❌ | ✅ | ✅ | ✅ |
| Pipeline kanban | ❌ | ✅ | ✅ | ✅ |
| Calendario visitas | ❌ | ✅ | ✅ | ✅ |
| AI Lead Scoring | ❌ | ❌ | ✅ | ✅ |
| Chatbot 24/7 | ❌ | ❌ | ✅ | ✅ |
| WhatsApp Business | ❌ | ❌ | ✅ | ✅ |
| Microsite SEO | ❌ | ❌ | ✅ | ✅ |
| Co-broking MLS | ❌ | ❌ | ❌ | ✅ |
| Whitelabel | ❌ | ❌ | ❌ | ✅ |
| API REST | ❌ | ❌ | ❌ | ✅ |
| Multi-idioma | ❌ | ❌ | ❌ | ✅ |
| Soporte | Email | Email | Prioritario | 24/7 |

---

## 6. Preguntas frecuentes

### ¿Puedo cancelar cuando quiera?
Sí, sin penalización. Tus datos se conservan 30 días por si te reactivas.

### ¿Tengo que instalar algo?
No. Es 100% web. Funciona en cualquier navegador moderno. Hay app móvil opcional para Android e iOS.

### ¿Mis datos están seguros?
Sí. Servidores en UE (Frankfurt). Cifrado en tránsito (TLS 1.3) y en reposo. Backups diarios.

### ¿Puedo migrar mi cartera actual?
Sí. El importador acepta CSV/Excel de prácticamente cualquier CRM (Idealista CRM, Witei, Sooprema, hojas propias).

### ¿Cómo funciona el soporte?
- **Plan Starter / Pro**: email a inmobilia.support@gmail.com (respuesta <48h)
- **Plan Agency**: email prioritario (<8h)
- **Plan Enterprise**: chat 24/7 + WhatsApp directo con el equipo

### ¿Hay sesgo de marca?
En plan Starter y Pro, hay un footer discreto "Powered by InmobilIA Pro".
En plan Agency y Enterprise, **whitelabel total** (parece tu propio software).

### ¿Puedo facturar a mis clientes desde la app?
Sí. Verifactu incluido en todos los planes. Genera factura, se envía al cliente por email, queda registrada en la cadena hash AEAT.

### ¿Y si quiero algo personalizado?
Plan Enterprise → desarrollos a medida bajo presupuesto.

---

## 📞 Contacto

- **Email**: inmobilia.support@gmail.com
- **Web**: https://inmobilia-pro.onrender.com

> **Última actualización del manual**: mayo 2026
