# Precios de InmobilIA Pro Latino para Stripe

Son los precios de la versión española (Pro 49 €, Business 149 €, Enterprise desde 399 €, activo adicional 4 €, sociedad adicional 15 €, Future-Proof Score 29 €, 100 créditos 10 €, al mes y sin impuestos) pasados a cada moneda al cambio de XE del **16 de noviembre de 2026** y redondeados a tres cifras (el dólar, a la unidad o al medio dólar en los extras). En la app están en `src/main/java/com/inmobilia/util/PreciosPlataforma.java`: si cambias un precio, cámbialo allí y aquí.

Cada inmobiliaria paga en la moneda de su país; si trabaja en dólares (por ejemplo en Argentina, Uruguay o Perú), en USD.

## Cómo van en Stripe

- **Un precio por concepto con las 14 monedas**: la moneda principal es USD y las otras 13 van como «monedas adicionales» (currency options) del mismo precio. En total son 6 precios.
- Todos son **recurrentes cada mes**, salvo el pack de créditos (pago único). Sin impuestos incluidos.
- La **lookup key** tiene que ser exactamente la de la tabla: la app busca cada precio por ella y comprueba que los importes de todas las monedas coinciden. Si algo no coincide, la app crea un precio nuevo con los importes buenos y le pasa la clave.
- Las claves empiezan por `inmobilia_latam_` para no mezclarse con las de España aunque se use la misma cuenta.
- CLP y PYG no tienen decimales en Stripe: el importe se escribe tal cual. En el resto, en céntimos.
- American Express no admite la mayoría de estas monedas en Stripe; Visa y Mastercard, sí.

| Concepto | Producto en Stripe | Lookup key | Cobro |
|---|---|---|---|
| Plan Pro | InmobilIA Pro · Plan Pro | `inmobilia_latam_plan_pro_mensual` | mensual |
| Plan Business | InmobilIA Pro · Plan Business | `inmobilia_latam_plan_business_mensual` | mensual |
| Activo adicional | InmobilIA Pro · Activo adicional | `inmobilia_latam_activo_extra_mensual` | mensual |
| Sociedad adicional | InmobilIA Pro · Sociedad adicional | `inmobilia_latam_sociedad_extra_mensual` | mensual |
| Future-Proof Score | InmobilIA Pro · Future-Proof Score | `inmobilia_latam_modulo_futureproof_mensual` | mensual |
| 100 créditos de IA | InmobilIA Pro · 100 créditos de IA | `inmobilia_latam_creditos_100` | pago único |

## Importes por moneda (al mes, sin impuestos)

| Moneda | Plan Pro | Plan Business | Activo adicional | Sociedad adicional | Future-Proof Score | 100 créditos de IA | Enterprise (desde) |
|---|---|---|---|---|---|---|---|
| **USD** · Dólar (Ecuador, El Salvador, Panamá, Venezuela) | 54 | 164 | 4,50 | 16,50 | 32 | 11 | 439 |
| **MXN** · Peso mexicano | 1.000 | 3.050 | 82 | 307 | 593 | 204 | 8.160 |
| **COP** · Peso colombiano | 179.000 | 544.000 | 14.600 | 54.800 | 106.000 | 36.500 | 1.460.000 |
| **ARS** · Peso argentino | 83.500 | 254.000 | 6.820 | 25.600 | 49.400 | 17.000 | 680.000 |
| **CLP** · Peso chileno | 53.900 | 164.000 | 4.400 | 16.500 | 31.900 | 11.000 | 439.000 |
| **PEN** · Sol peruano | 184 | 560 | 15 | 56 | 109 | 38 | 1.500 |
| **UYU** · Peso uruguayo | 2.190 | 6.650 | 179 | 670 | 1.300 | 447 | 17.800 |
| **PYG** · Guaraní paraguayo | 334.000 | 1.020.000 | 27.300 | 102.000 | 198.000 | 68.200 | 2.720.000 |
| **BOB** · Boliviano | 628 | 1.910 | 51 | 192 | 372 | 128 | 5.120 |
| **CRC** · Colón costarricense | 24.200 | 73.600 | 1.980 | 7.410 | 14.300 | 4.940 | 197.000 |
| **GTQ** · Quetzal | 412 | 1.250 | 34 | 126 | 244 | 84 | 3.350 |
| **HNL** · Lempira | 1.450 | 4.410 | 118 | 444 | 859 | 296 | 11.800 |
| **NIO** · Córdoba | 1.980 | 6.030 | 162 | 607 | 1.170 | 405 | 16.200 |
| **DOP** · Peso dominicano | 3.270 | 9.950 | 267 | 1.000 | 1.940 | 668 | 26.700 |

Enterprise no va a Stripe: es «desde», se pacta con cada cliente.

## Lo que hay que escribir en Stripe (unit_amount)

| Moneda | `plan_pro` | `plan_business` | `activo_extra` | `sociedad_extra` | `modulo_futureproof` | `creditos_100` |
|---|---|---|---|---|---|---|
| usd | 5400 | 16400 | 450 | 1650 | 3200 | 1100 |
| mxn | 100000 | 305000 | 8200 | 30700 | 59300 | 20400 |
| cop | 17900000 | 54400000 | 1460000 | 5480000 | 10600000 | 3650000 |
| ars | 8350000 | 25400000 | 682000 | 2560000 | 4940000 | 1700000 |
| clp | 53900 | 164000 | 4400 | 16500 | 31900 | 11000 |
| pen | 18400 | 56000 | 1500 | 5600 | 10900 | 3800 |
| uyu | 219000 | 665000 | 17900 | 67000 | 130000 | 44700 |
| pyg | 334000 | 1020000 | 27300 | 102000 | 198000 | 68200 |
| bob | 62800 | 191000 | 5100 | 19200 | 37200 | 12800 |
| crc | 2420000 | 7360000 | 198000 | 741000 | 1430000 | 494000 |
| gtq | 41200 | 125000 | 3400 | 12600 | 24400 | 8400 |
| hnl | 145000 | 441000 | 11800 | 44400 | 85900 | 29600 |
| nio | 198000 | 603000 | 16200 | 60700 | 117000 | 40500 |
| dop | 327000 | 995000 | 26700 | 100000 | 194000 | 66800 |

## Tipos de cambio usados (unidades por 1 €, XE, 16-nov-2026)

USD 1.1004 · MXN 20.4420 · COP 3,651.4219 · ARS 1,703.9787 · CLP 1,100.5131 · PEN 3.7557 · UYU 44.6599 · PYG 6,824.3344 · BOB 12.8207 · CRC 493.9802 · GTQ 8.4049 · HNL 29.6187 · NIO 40.4908 · DOP 66.8099

Ojo con el peso argentino: con la inflación, el importe en ARS hay que revisarlo cada pocos meses (o cobrar en USD a las inmobiliarias argentinas).
