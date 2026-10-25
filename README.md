# Mi Visor TV · v11

Visor de canales para Android TV (también móvil y tablet), compatible con los códigos de MyLinksPaste.

```
código → <código>.elcano.top → DNS TXT → (Base64 → GZIP) → JSON → filas de canales → reproductor externo
```

## Qué hace

- **Categorías**: cada una guarda un nombre y un código, y aparece en el menú lateral (se pueden reordenar, renombrar y eliminar).
- **Abrir código**: abre cualquier código sin guardarlo.
- **Referencias recursivas** (`type: category` + `ref`): se resuelven en paralelo, con protección contra ciclos.
- **Caché local**: la categoría aparece al instante y se actualiza en segundo plano. Sin conexión se muestra la última copia guardada, avisando de su antigüedad.
- **Reproductores**: HLS, vídeo, AceStream (con plantilla HTTP opcional), magnet (plantilla remota opcional), TVGram y Telegram. "Abrir con…" permite recordar la app por canal, por grupo o por tipo de enlace.
- **Deep links**: `mylinkspaste://CODIGO` y `mivisor://CODIGO`.

## Diseño y dispositivos

Toda la app usa el estilo "neón" de la Agenda: azul noche con luces de fondo, paneles con borde, foco cian con halo (dibujado con contornos translúcidos, sin sombras ni desenfoques) y violeta para las acciones principales. El código está en `ui/neon/`.

| Dispositivo | Navegación | Diseño |
|---|---|---|
| TV y tablet horizontal (≥ 900 dp) | Pestañas arriba (Canales · Agenda · Historial · Ajustes) | Columna de categorías/filtros, contenido y, en la Agenda, columna de destacados |
| Tablet vertical (600–900 dp) | Pestañas arriba | Columna de categorías/filtros y contenido (sin destacados) |
| Móvil (< 600 dp) | Barra inferior | Categorías y filtros en filas de chips; tarjetas apiladas; detalle del partido en una sola columna |

- **Pantalla completa** en móvil y tablet: se ocultan la barra de estado y la de navegación, que aparecen temporalmente al deslizar desde el borde. Se respeta el notch o la cámara.
- **Táctil y mando**: todo elemento funciona con toque, pulsación larga, OK del mando y OK mantenido.

## Agenda deportiva

Fútbol, tenis, Fórmula 1, MotoGP y UFC de hoy y de los próximos 14 días, con los canales de TV de España y retratos de los deportistas.
- **Partidos ya jugados:** los de hoy se mantienen todo el día; los de días anteriores desaparecen en la siguiente actualización.
- **Deportes sin eventos:** cada deporte con fuente aparece como categoría aunque no tenga eventos ("No hay eventos de MotoGP para hoy").

```
Football-Data ─── Fútbol          ┐
Live Tennis API ── Tenis           │
Jolpica ────────── Fórmula 1       ├─▶ generate_agenda.py (GitHub Actions, cada hora, minuto 17)
Blacktop ───────── MotoGP          │        │
Cito UFC ───────── UFC             ┘        ├── futbolenlatv.es/deporte ─▶ canales de TV (1 descarga)
                                            ├── TheSportsDB ─────────────▶ retratos (+ tvId de respaldo)
                                            ▼
                 agenda.json · services.json · athletes.json · ufc-cache.json ─▶ rama `agenda-data`
                                            ▼
APK: agenda.json como mucho cada 15 min (caché + ETag → 304); services.json al abrir Servicios (10 min)
```

- **Sin servidor propio y sin claves en la app.** Todas las llamadas con credenciales se hacen en GitHub Actions. La app solo lee los JSON publicados. El generador usa solo la biblioteca estándar de Python.

### Agenda (fuentes autoritativas)

Deciden qué eventos existen, quién participa, la competición, la fecha y la hora.

| Deporte (`sport`) | Fuente | Qué se incluye | Frecuencia |
|---|---|---|---|
| `futbol` | Football-Data.org v4 | LaLiga, Champions, Premier, Serie A, Bundesliga, Ligue 1 (y Eurocopa/Mundial). Nunca segundas divisiones. | cada hora |
| `tenis` | Live Tennis API | Grand Slams y ATP/WTA principales. Sin Challenger, ITF, juniors, dobles ni rondas de previa. | cada hora |
| `formula1` | Jolpica (sin clave) | Clasificación, Sprint y Carrera. | cada hora |
| `motogp` | Blacktop | Clasificación, Sprint y Carrera de MotoGP. | cada hora |
| `ufc` | Cito UFC (ufcapi.dev) | Cada velada como un evento: "UFC 333 · Volkanovski - Evloev", con su cartelera (`card`). | cada 6 h (caché) |

- **UFC:** el plan gratuito de Cito permite 500 peticiones al mes. Por eso el listado de veladas se consulta como mucho cada 6 horas y cada cartelera se reutiliza 24 horas: unas 180 peticiones al mes.
- **Horas que no se inventan:** si Cito solo da el día, el evento lleva `"timeTbd": true` y la app muestra "Hora por confirmar". Tampoco se inventan horas para los combates de la cartelera.

### Canales de TV: futbolenlatv.es/deporte

- **Solo enriquecimiento:** nunca decide qué eventos existen. Los canales se asignan a los eventos que ya han llegado de las APIs.
- **Una sola descarga agregada** de `/deporte` por ejecución (unas 24 al día), con el User-Agent `MiVisor/4`. Todo se analiza en local. No se recorren `/deporte/futbol`, `/deporte/tenis`…
- **robots.txt en cada ejecución:** si `/deporte` deja de estar permitido, no se descarga y el servicio queda `unavailable` (`robots_disallowed`). La agenda sigue igual.
- **Parser** (`tv_futbolenlatv.py`, solo biblioteca estándar): lee las tablas de la página.
  - Fechas: de las cabeceras, en formato `24/10/2026` o `lunes 26 de octubre`.
  - Deporte: de las celdas, de los iconos o de las cabeceras de sección.
  - De cada fila: hora, competición, local/visitante (o un único evento "A - B") y canales con dial ("M+ LALIGA (M54 O110)" → nombre + dial) o nota ("DAZN (Ver en directo)").
  - Se descarta "Canal por confirmar"; los canales "Bar" se mantienen.
  - Las imágenes junto a tenistas o luchadores suelen ser **banderas**: nunca se usan como logo ni como retrato.
- **Matching por deporte** con señales combinadas; nunca por igualdad literal. Los nombres se normalizan: acentos, mayúsculas, signos, FC/CF, "At. Madrid" = "Atlético de Madrid" = "Atleti", "Marsella" = "Marseille"…

  | Deporte | Señales |
  |---|---|
  | Fútbol | local y visitante (uno inequívoco y el otro compatible) + hora (±30 min) |
  | Tenis | apellidos (+ inicial) de los dos jugadores, en cualquier orden + mismo día (±4 h) |
  | F1 / MotoGP | tipo de sesión (clasificación / sprint / carrera; "clasificación sprint" no es el sprint) + hora (±45 min) |
  | UFC | número de la velada ("UFC 333") y/o apellidos de los luchadores + fecha |

  Si el match es dudoso (dos filas distintas igual de buenas, o un solo equipo coincide) **no se asignan canales**: `"channels": []`.
- **Si la fuente falla** (timeout, error HTTP, HTML cambiado o menos de 5 filas): los eventos conservan los canales de la agenda anterior (`stale`) o quedan sin canales (`unavailable`). La agenda se publica igual.
- **Formato de canal:** `{"name": "M+ LALIGA", "dial": "M54 O110"}`. `name` es solo el nombre, que es lo que usa `ChannelMatcher` para buscarlo en tus códigos. Estos canales tienen prioridad sobre cualquier otra fuente de TV.
- **Respaldo TheSportsDB:** si un partido de fútbol no trae canales, la app puede pedirlos a TheSportsDB al abrirlo (`tvId`, `data/TvChannelsRepository.kt`). Esa consulta nunca sobrescribe los canales de `/deporte`.

### Retratos (athletes.json)

- **Tenis:** TheSportsDB (`searchplayers`). Se usa `strCutout`; si no hay, `strThumb`; luego `strRender`; si no, placeholder con iniciales.
  - El jugador devuelto tiene que ser del mismo deporte y tener el mismo apellido (e inicial). Si no, no hay foto: mejor el placeholder que la cara de otra persona.
- **UFC:** la imagen del luchador que da Cito. Si no hay, TheSportsDB. Si tampoco, placeholder.
- **F1 y MotoGP:** los retratos de los pilotos se preparan en el catálogo. Los pilotos de F1 se sacan de Jolpica; los de MotoGP, de la lista editable `tools/agenda/athletes_seed.json`. Sirven para vistas de pilotos o el detalle de un GP. **No** se ponen dos caras en la tarjeta de una carrera, porque no es un 1 contra 1.
- **Caché larga:** una cara encontrada vale 30 días y una búsqueda sin resultado no se repite en 7. La clave gratuita de TheSportsDB corta tras unas 10 peticiones seguidas, así que cada hora se buscan como mucho unas pocas caras nuevas y se para al primer 429.
- **En la app:** los eventos de tenis y UFC llevan `homeImage` / `awayImage` (opcionales) y se muestran como retratos redondos. Fútbol sigue con escudos. Una agenda antigua sin estos campos funciona igual.

### Diagnóstico: Ajustes → Servicios y diagnóstico

`services.json` se publica junto a `agenda.json` y la app lo descarga de la misma dirección. El botón "Actualizar" **solo** vuelve a descargar `services.json`: no ejecuta GitHub Actions ni llama a las APIs. Sin conexión se muestra la última copia, avisando de que puede no estar actualizada.

```json
{
  "version": 1, "generatedAt": "2026-10-24T20:17:00Z", "overall": "degraded",
  "services": [
    {"id": "live_tennis", "name": "Live Tennis API", "role": "Agenda de tenis", "kind": "core",
     "status": "stale", "lastAttempt": "2026-10-24T20:17:00Z", "lastSuccess": "2026-10-24T18:17:00Z",
     "consecutiveFailures": 2, "lastError": {"code": "timeout", "message": "Tiempo de espera agotado", "at": "…"}}
  ],
  "history": [
    {"service": "live_tennis", "at": "2026-10-24T19:17:00Z", "type": "error", "status": "stale", "code": "timeout",
     "message": "Sincronización fallida"},
    {"service": "live_tennis", "at": "2026-10-24T21:17:00Z", "type": "recovery", "status": "ok", "message": "Servicio recuperado"}
  ]
}
```

- **Servicios:**
  - **Esenciales (`core`):** Football-Data, Live Tennis, Jolpica, Blacktop, Cito UFC.
  - **Auxiliares (`aux`):** Fútbol en la TV, TheSportsDB.
  - **Además:** "Agenda GitHub", la generación y publicación global.
- **Estados:** `ok` (Operativo) · `stale` (Datos anteriores) · `unavailable` (No disponible) · `disabled`.
  - Responder bien con 0 eventos **no** es un fallo.
  - Los estados de las fuentes de agenda salen de los mismos `stats` de `agenda.json`, así que la pantalla de Servicios y los avisos de la Agenda nunca se contradicen.
- **`overall`:**
  - `ok`: todo funciona.
  - `degraded`: algo falla o usa datos anteriores, pero hay agenda. Un fallo de TV o de retratos nunca pasa de aquí.
  - `down`: no se ha podido generar la agenda.
- **Errores:** códigos genéricos con su mensaje en español: `timeout`, `rate_limited`, `authentication_error`, `invalid_response`, `robots_disallowed`, `provider_unavailable`, `parse_error`. Nunca hay claves, cabeceras, URLs con tokens ni trazas.
- **Historial:**
  - Qué se guarda: solo fallos y recuperaciones (no una entrada "OK" cada hora).
  - Retención: como mucho 30 días y 100 entradas por servicio.
  - Continuidad: se recupera el `services.json` anterior de la rama `agenda-data` antes de generar el nuevo, así que no se pierde entre ejecuciones.
- **Si fallan todas las fuentes de agenda:** no se sobrescribe la última `agenda.json` válida. Se publica igualmente `services.json` (con `overall: "down"`) y el workflow queda en rojo.

### Estados por deporte en la agenda

Cada fuente de agenda tiene su estado en `stats` de `agenda.json`:
- `stale`: la app muestra *"La agenda de tenis no se ha podido actualizar. Se muestran los últimos datos disponibles."*
- `unavailable`: la app muestra *"No se ha podido cargar la agenda de tenis."*

- **URL**: la app la recibe al compilarse en Actions (`BuildConfig.AGENDA_URL`, a partir de `GITHUB_REPOSITORY`). Se puede cambiar en Ajustes → Agenda deportiva. El repositorio debe ser **público** para que `raw.githubusercontent.com` sirva el JSON.
- **Diseño**: pantalla completa con estilo propio (azul noche, foco cian con halo, columnas de filtros, destacados y "Más partidos de hoy"). Código en `ui/screens/agenda/`. El halo se dibuja con contornos translúcidos, sin sombras ni desenfoques, para que vaya fluido en TVs modestas.
- **Logos**: escudos y emblemas de Football-Data cuando los trae y, por encima de ellos, los de `tools/agenda/logos.json` (nombre → URL PNG/WebP), que controlas tú. La app los carga con un cargador propio (`RemoteImage`): los reduce al tamaño en que se pintan y usa caché en memoria (máx. 6 MB) y en disco. Sin logo se muestran las iniciales sobre un escudo de color.
- **Filtros por competición (Fútbol)**: al elegir Fútbol aparece una segunda fila de chips con el mismo estilo: **Todos** · LaLiga · Champions · Premier · Serie A · Bundesliga · Ligue 1 · Europa League · Conference League · Copa del Rey · Supercopa · Otras.
  - Solo se muestran las competiciones que tienen eventos en la agenda cargada, en ese orden de prioridad. "Todos" está siempre y es la opción por defecto.
  - El filtro es **local** sobre `agenda.json`, sin peticiones de red. Se combina con Hoy / Mañana / En vivo / Próximos y con las franjas horarias.
  - La competición elegida se recuerda mientras la pantalla siga abierta, aunque cambies a Tenis y vuelvas.
  - Los nombres se normalizan en un único sitio (`model/AgendaFilters.kt`, `CompetitionKey.of`): "LaLiga EA Sports", "Primera División" y "La Liga" son LaLiga; "UEFA Champions League" y "UCL" son Champions. Las segundas divisiones, el fútbol femenino y otros países van a "Otras".
  - Añadir subfiltros a otro deporte (ATP/WTA, GP…) es añadir una entrada en `AgendaFilters`.
- **Opciones por canal**: cada canal de la agenda ("DAZN LaLiga") se busca en **todos tus códigos**. Si varios códigos traen ese canal, el evento muestra todas las opciones ("Opción 1 · Deportes · ACE", "Opción 2 · Fútbol · HLS"…) y OK abre la elegida. El mismo enlace repetido en dos códigos cuenta como una sola opción. El emparejado (`ChannelMatcher`) ignora acentos, mayúsculas, orden de palabras y etiquetas de calidad (HD/FHD/1080…), y aplica alias (M+ → Movistar), pero es estricto con los números: "DAZN LaLiga 2" no se mezcla con "DAZN LaLiga", ni "DAZN" a secas con "DAZN 1".

### Secrets

En **Settings → Secrets and variables → Actions** (solo los nombres; los valores nunca van en el proyecto):

| Secret | Proveedor |
|---|---|
| `FOOTBALL_DATA_TOKEN` | Football-Data.org (cabecera `X-Auth-Token`) |
| `LIVE_TENNIS_API_KEY` | Live Tennis API (`Authorization: Bearer`) |
| `BLACKTOP_API_KEY` | Blacktop (cabecera `x-api-key`) |
| `CITO_API_KEY` | Cito UFC (cabecera `x-api-key`) — ya configurado |
| `THESPORTSDB_API_KEY` | Opcional. Sin él se usa la clave pública gratuita de TheSportsDB |

- **Jolpica y futbolenlatv.es** no necesitan clave.
- **Si falta un secret**, ese servicio queda `unavailable` (`authentication_error`) y el resto se publica igual.

### Formato de `agenda.json` (v1)

Los campos nuevos son **opcionales**. La app los ignora si no están:
- `homeImage` / `awayImage`
- `timeTbd`
- `card` (UFC)
- `tvId`

```json
{
  "version": 1, "source": "MiVisor Sports APIs", "generatedAt": "2026-10-24T18:17:00Z", "timezone": "Europe/Madrid",
  "stats": {"football": {"provider": "football-data", "status": "ok", "events": 12}, "ufc": {"provider": "cito", "status": "ok", "cached": true}},
  "events": [
    {"id": "football-564722", "sport": "futbol", "competition": "LaLiga", "start": "2026-10-24T21:00:00+02:00",
     "home": "Atleti", "away": "Deportivo", "title": "Atleti - Deportivo", "homeLogo": "https://…",
     "channels": [{"name": "M+ LALIGA", "dial": "M54 O110"}, {"name": "Orange Fútbol 1"}, {"name": "LaLiga TV Bar"}]},
    {"id": "tennis-987654", "sport": "tenis", "competition": "ATP · Paris Masters · Semi-finals",
     "start": "2026-10-31T15:00:00+01:00", "home": "Carlos Alcaraz", "away": "Jannik Sinner", "title": "Carlos Alcaraz - Jannik Sinner",
     "homeImage": "https://r2.thesportsdb.com/…", "awayImage": "https://r2.thesportsdb.com/…", "channels": [{"name": "Movistar Plus+"}]},
    {"id": "ufc-ufc-333", "sport": "ufc", "competition": "UFC 333", "start": "2026-10-25T03:00:00+01:00",
     "home": "Alexander Volkanovski", "away": "Movsar Evloev", "title": "Alexander Volkanovski - Movsar Evloev",
     "homeImage": "https://…", "awayImage": "https://…", "card": [{"red": "…", "blue": "…", "weightClass": "…", "main": true}],
     "channels": [{"name": "DAZN", "note": "PPV"}]}
  ]
}
```

### Probar en local

```bash
python -m unittest discover -s tools/agenda -v          # sin Internet: todo simulado (incluido el HTML de /deporte)
export FOOTBALL_DATA_TOKEN=… LIVE_TENNIS_API_KEY=… BLACKTOP_API_KEY=… CITO_API_KEY=…
python tools/agenda/generate_agenda.py --out out/agenda.json --state-dir state --debug-dir debug
```

**Fixtures de `/deporte`:**
- `fixtures/deporte_real_extracto.html` es un **extracto real saneado** de la página (24/10/2026): solo la tabla, sin scripts ni enlaces de afiliados. Con él se comprueba el parser contra el HTML real.
- `deporte_tablas.html` usa el mismo marcado con partidos inventados, para probar el matching con nuestras competiciones.
- Estructura real:
  - Fecha: `tr.cabeceraTabla` → "Partidos de hoy sábado, 24/10/2026".
  - Hora: `td.hora`.
  - Competición: `td.detalles`. Su icono `alt` da el deporte ("Fútbol", "Tenis", "Automovilismo", "Motociclismo", "Golf"…). En automovilismo y motociclismo, la competición decide: "F1 Academy", "Moto2" y "Moto3" son `motor`, nunca F1 ni MotoGP.
  - Participantes: `td.local` / `td.visitante`, o `td.eventoUnaColumna > span.eventoUnico` para eventos sin rivales.
  - Canales: `td.canales > ul.listaCanales > li[title]`, con dial "M54 O110" o solo números de Movistar ("63", "8 y 50").
- Cada ejecución guarda además el HTML completo en el artifact `agenda-debug-N`.

## Servicios rápidos: Ace Stream y VPN/WARP

En lo alto de la columna lateral (TV/tablet), **antes de las categorías**, hay dos filas. En móvil son los dos primeros chips de la fila de categorías.

```
SERVICIOS
● Ace Stream        Motor activo
● VPN / WARP   ON   Conectada
──────────────
Categorías…
```

- **Mando y foco:** cada fila es un único control enfocable; no hay interruptores pequeños aparte. OK hace lo que indica la fila: iniciar, ON/OFF, autorizar o abrir WARP. El orden con el D-pad es Ace Stream → VPN/WARP → primera categoría.
- **Indicadores:** ● correcto (verde) · ○ desconectada (neutro) · ! requiere atención (ámbar) o error (rojo) · spinner mientras dura una operación.
- **Cuándo se comprueba el estado:** al abrir MiVisor y al volver a primer plano (`ON_RESUME`). No hay sondeo permanente. En segundo plano se sueltan el receptor de WARP y el enlace con Ace Stream; ninguno de los dos se apaga por ello.
- **Código:** `services/QuickServices.kt` (estados, protocolo y textos, con tests), `AceStreamEngineController`, `WarpController`, `QuickServicesViewModel` y `ui/neon/QuickServicesPanel.kt`. La UI nunca envía broadcasts.

### Ace Stream Engine (integración oficial)

- **Cliente oficial:** `ServiceClient` y la AIDL de [acestream-engine-client](https://github.com/acestream/acestream-engine-client) (MIT), integrados como código fuente en `org.acestream.engine…` (`buildFeatures.aidl true`).
- **Detección:** `ServiceClient.getServicePackage()` elige la app con mayor versión entre `org.acestream.media`, `.media.atv`, `.core`, `.core.atv` u otra que exponga el servicio. Nunca se miran procesos. Los paquetes están en `<queries>`.
- **Al abrir MiVisor no se arranca el motor.** Solo se consulta `GET /webui/api/service?method=get_version` en el último puerto HTTP que dio el servicio (6878 si nunca lo dio). Si responde, "Motor activo"; si no, "Motor detenido".
- **Iniciar:**
  1. `ServiceClient.startEngine()`.
  2. Llega `onConnected` con el **puerto HTTP real** del servicio.
  3. Se repite `get_version` en ese puerto hasta que responda (READY con evidencia real) o pasen 15 s (ERROR, "Ace Stream Engine no responde").
  4. Si el servicio falla, "No se pudo iniciar" con REINTENTAR.

  Nada bloquea el hilo principal.
- **Apagar:** Ace Stream **no ofrece un método oficial y verificable** para parar el motor: la AIDL no tiene `stopEngine` y la HTTP API no documenta un apagado. Por eso **no hay interruptor OFF**. `release()` solo suelta el enlace del cliente, no apaga el motor.
- **Red local:** `network_security_config.xml` permite HTTP sin cifrar solo a `127.0.0.1` y `localhost`; todo lo demás sigue siendo HTTPS.
- **Para la reproducción:** `AceStreamEngineController.ensureReady()` (READY → continuar; detenido → iniciar y esperar). Está listo para usarse, pero aún no se ha conectado al flujo de reproducción.

### VPN / WARP (WARP modificada)

Se usa el **mismo protocolo** que `MiVisor-WARP-TestClient-1.2` (`WarpControlContract`); no hay un segundo protocolo.

| | |
|---|---|
| Paquete | `com.cloudflare.onedotonedotonedotone` (en `<queries>`) |
| Permiso | `com.mivisor.permission.CONTROL_WARP` (`signature`) |
| Estado | ContentProvider `content://com.cloudflare.onedotonedotonedotone.mivisor.control`, método `STATUS`. Si no está disponible, broadcast ordenado `com.mivisor.warp.STATUS` |
| Comandos | broadcasts ordenados `com.mivisor.warp.CONNECT` (`request_permission=false`) y `com.mivisor.warp.DISCONNECT`, con `request_id`; el ACK llega en los *result extras* |
| Transiciones | `com.mivisor.warp.STATE_CHANGED`, registrado exigiendo `CONTROL_WARP` al emisor (exported en Android 13+) |
| Autorización | `com.mivisor.warp.control.MiVisorVpnPermissionActivity` con `connect_after_grant=true` |

- **Estados:** DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, PERMISSION_REQUIRED y ERROR. Además: no instalada, sin permiso por firma y "requiere configuración" (detalles `MISSING_CERTIFICATE`, `SETUP_FAILED`, `FGS_START_NOT_ALLOWED` → botón ABRIR WARP).
- **Conectar y desconectar:** se muestra CONNECTING/DISCONNECTING y se espera la confirmación por `STATE_CHANGED`. Nunca se da por hecho solo por haber enviado el comando. Si no llega la confirmación (45 s conectando, 20 s desconectando), se vuelve a consultar STATUS; si sigue igual, se muestra error. Nunca hay un spinner infinito.
- **Autorización VPN:**
  - Si falta (`PERMISSION_REQUIRED`, `VPN_CONSENT_MISSING` o `vpn_permission_granted=false`), la fila muestra AUTORIZAR VPN.
  - Ese botón abre la Activity de WARP, que pide el consentimiento **oficial de Android** (`VpnService.prepare`). MiVisor no lo acepta por ti ni usa Accessibility.
  - Al volver, `onResume` consulta STATUS automáticamente: DISCONNECTED → OFF listo para usar; CONNECTED (por `connect_after_grant`) → ON.

### Firma: imprescindible para controlar WARP

`CONTROL_WARP` es un permiso **signature**. MiVisor solo lo obtiene si está firmado con **la misma clave que la WARP modificada**: el certificado `CN=MiVisor, O=MiVisor`, SHA-256 `B2:9F:F0:71:C5:03:95:D6:6F:17:CE:3A:5B:C2:42:A6:EF:C0:04:88:F6:57:F5:43:7E:51:5D:C7:24:E0:46:32`, el mismo que el cliente de prueba.

- **Configurar la clave:** sin secrets, el proyecto firma con `app/debug.keystore` (CN=Android Debug), que **no** es compatible. Hay que poner la clave de MiVisor en `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` y `SIGNING_KEY_PASSWORD`.
- **Comprobación en CI:** `build.yml` verifica la huella de la release con `apksigner`.
  - Si coincide, OK.
  - Si hay clave propia pero no coincide, la build falla.
  - Con la clave de desarrollo, aviso: todo funciona menos WARP.
  - Si WARP se firma con otra clave, cambia la variable de repositorio `WARP_SIGNER_SHA256`.
- **Declaración del permiso:** solo las releases con clave propia declaran el `<permission>` (`app/src/warpSigned`), igual que el cliente de prueba, para que el orden de instalación no importe. Con la clave de desarrollo no se declara, para evitar el conflicto de permiso duplicado.
- **Cambiar de clave obliga a desinstalar:** Android no permite actualizar una app firmada con otra clave, así que habrá que desinstalar la versión firmada con la clave de desarrollo. Se pierden las categorías e historial guardados.
- **Si la firma no coincide:** la fila muestra "Sin permiso de control (firma)" y queda registrado en el log (`MiVisor/Services`). Nunca se rebaja el permiso para "hacerlo funcionar".

## Interfaz (v11, según el handoff de UX)

Navegación lateral: **Inicio · En directo · Agenda · Canales · Historial · Ajustes**. Abajo, **Servicios de reproducción**
(Ace Stream y VPN/WARP) con su estado real: discretos cuando todo va bien, con el botón en la propia fila cuando hay que actuar
(Iniciar, Conectar, Autorizar, Configurar, Reintentar, Cómo instalar). OK en una fila correcta abre el panel rápido.

- **Inicio** (sin scroll en una TV 16:9): Hero con el directo principal (o el próximo relevante) y *Ver ahora* / *N fuentes*;
  *Tus competiciones* (LaLiga, Champions, Premier, F1, MotoGP siempre; el resto si tiene eventos) con "3 hoy · 1 en directo",
  "Fin de semana", "Próxima carrera…"; *En directo ahora*; *Empieza pronto* (próximas horas) y *Ver calendario completo*.
  El hueco de imagen del Hero usa escudos/retratos sobre un fondo de estadio (preparado para una imagen real).
- **Página de competición**: Ligas → En directo · Hoy · Próximos; Motor → Ahora · Este fin de semana · Próximos grandes premios.
- **En directo**: solo lo que se emite ahora, filtros Todos/Fútbol/Tenis/F1/MotoGP (+ otros si hay).
- **Calendario** (antes Agenda): Hoy · Mañana · Esta semana · Elegir fecha, chips de competición, listado compacto y detalle del
  evento enfocado. Se abre desde Inicio, competiciones y En directo.
- **Canales del encuentro**: filas grandes; toda la fila es el control y OK reproduce.
- **Canales por proveedor** (antes "categorías"): Todos + cada proveedor. Los canales repetidos entre proveedores se agrupan
  ("DAZN 1 · 3 fuentes", calidad deducida del nombre: 4K / Full HD / HD / SD). Con una fuente, OK reproduce; con varias, se elige.
- **Añadir proveedor**: Código + Nombre (opcional) y, en TV, el QR para escribir desde el móvil siempre visible.
  Lo recibido va al **campo activo** ("Campo activo: Código / Nombre del proveedor") y no se guarda solo.
- **Ver ahora / OK = quiero ver esto**: una fuente → se reproduce; varias → selector. Si el enlace es de Ace Stream y el motor
  está parado, o el proveedor está marcado "Usar VPN" y la VPN está desconectada, MiVisor lo prepara todo a la vez
  ("Preparando reproducción…") y solo pregunta si hace falta una persona (autorizar la VPN, un fallo). Al resolverlo, continúa
  con el mismo contenido. VPN por proveedor: Canales → Gestionar → "VPN: sí/no".
- **Historial**: reproducciones (evento, canal, competición, proveedor y cuándo). OK lo vuelve a abrir.
- **Ajustes**: General · Reproductor · Interfaz · Servicios (Ace/VPN, inicio automático, *vista previa de estados* para revisar
  todos los estados sin tocar nada real, y las fuentes de datos de la agenda) · Canales · Datos · Sistema (registro de errores).
- Sin `focusRestorer` en listas anidadas (sospechoso del cierre al pulsar ▶).

## Importación de canales (v13)

Pipeline: elemento → limpiar la URL (solo extremos) → **¿Ace Stream válido?** → NO: no es canal → SÍ: normalizar nombre →
alias → catálogo → ChannelMatcher / UI / reproducción. El nombre y la URL no se mezclan nunca.

- **`data/ChannelNameNormalizer`** (único punto para todos los nombres y títulos de grupo): letras decorativas por rangos
  Unicode (🄰 🅰 🅐 🇦, versalitas) → A–Z, **NFKC** (Ⓓ, ＤＡＺＮ, 𝐃𝐀𝐙𝐍…), fuera invisibles (Cf, selectores de variación, keycaps),
  emojis/símbolos/separadores → espacio, espacios y saltos colapsados, separadores colgantes fuera. `matchKey` añade minúsculas y
  sin acentos. Los alias (M+ = Movistar, La Liga = LaLiga…) siguen en `ChannelMatcher`, **después** de normalizar.
- **`data/AceStreamLink`**: canal = `acestream://` + Content ID de 40 hex (o el Content ID puro, validado). http/https nunca se
  convierte. Solo se limpian espacios, `\r`, `\n` e invisibles de los extremos; el Content ID no se toca.
- Separadores, títulos, webs, URL vacía y otros enlaces se descartan (Ajustes → Canales → "Importar solo canales Ace Stream",
  activado por defecto). Resumen en Logcat `MiVisor/Import`: entradas, válidos, ignorados, nombres normalizados (sin IDs).
- Mismo nombre con distinto Content ID = varias fuentes del mismo canal (no se borra ninguna).

## Apertura de Ace Stream (v13)

Ahora es idéntica a MyLinksPaste 4.6 (comparado con su bytecode): `Intent(VIEW, Uri.parse(url))` + `FLAG_ACTIVITY_NEW_TASK`,
sin MIME ni extras, URI exacta. MiVisor ya no arranca el motor por su cuenta antes de abrir `acestream://` (lo hace la app de
Ace Stream, como antes); solo lo prepara si está activado el "motor local (HTTP)". Cada apertura deja un diagnóstico de 15 puntos
(Content IDs enmascarados) en Ajustes → Sistema → Registro de errores → "Última apertura de canal".

## Editar con móvil (QR)

En el formulario **Añadir / Editar proveedor** (Título + Código), columna derecha: **Editar con móvil**.

- MiVisor genera un QR temporal. El móvil abre una página servida **por el propio televisor** en la red local
  (móvil → red local → TV; sin servidor externo ni Internet) con los mismos campos: **Título** y **Código**, ya rellenos con lo que
  tenga el formulario de la TV en ese momento.
- **ENVIAR AL TELEVISOR** actualiza al instante los dos campos del formulario. **No guarda**: el usuario revisa en la TV y pulsa
  Guardar (o Guardar y abrir) cuando esté conforme.
- La sesión dura **5 minutos** y admite **varios envíos** (corregir → reenviar → corregir…; máx. 100). No se invalida tras el primero.
- Al caducar: se invalida el token, se apaga el servidor y el móvil muestra
  "La sesión ha caducado. Genera un nuevo QR desde MiVisor". En la TV: "La sesión ha caducado" + *Generar nuevo QR*.
- Cerrar el QR, cancelar o salir del formulario también invalidan la sesión.
- Seguridad: token aleatorio de 192 bits, solo la IP de la LAN (nunca la de WARP/VPN ni 127.0.0.1), puerto 8765 o siguiente
  (el QR lleva el real), cuerpo máximo 24 KB (título ≤ 1 KB, código ≤ 16 KB), valores escapados en la página.
  **Los títulos y códigos recibidos no se registran en ningún log.**
- Rutas: `GET /edit/<token>`, `POST /edit/<token>/send` (`{"title","code"}`). Siguen existiendo `/pair/…` (un solo envío)
  y `/log/…` (registro de errores en el móvil).
- Permisos: `INTERNET` y `ACCESS_NETWORK_STATE`. Sin servicio en primer plano.

## Actualización de canales

- Al abrir MiVisor, al volver a ella y al entrar en **Canales** se comprueba cada proveedor: si su copia tiene más de 10 min se
  vuelve a descargar (si no, se usa la caché). Como mucho una comprobación cada 2 min por proveedor (`data/RefreshPolicy`).
- Botón **Actualizar** (TV y móvil): fuerza la descarga del proveedor seleccionado o de todos.
- Si falla, se **conservan los últimos datos válidos**: la lista no se vacía. La cabecera dice
  "no se pudo actualizar: se muestran los últimos datos de hace…" y el proveedor lleva un punto ámbar.

## Nombres largos en TV (v14)

Antes los nombres iban en **una sola línea** dentro de columnas de ancho fijo (Calendario: ~95 dp por equipo con el panel
lateral abierto; tarjetas de Inicio: "Local vs Visitante" compartiendo línea con la hora), así que salía "Atlético de Mad…".

Ahora se usa `FitText` (`ui/kit/Kit.kt`) con la lógica pura de `ui/kit/TextFit.kt`:
1. hasta **2 líneas** (partiendo por palabras);
2. si aún no cabe, **reduce la letra** en pasos de 0,5 sp hasta un mínimo legible (Calendario 14→11,5; En directo 13→11;
   Canales del encuentro 17→13; Inicio 12→10);
3. la elipsis solo aparece si ni así cabe.

Mide el ancho real (`BoxWithConstraints`) y tiene en cuenta el escalado de fuente del sistema, así que se adapta a 720p,
1080p, 4K y a "texto grande". En Inicio la hora pasó junto a los escudos para dar todo el ancho de la tarjeta a los nombres.
Las competiciones usan 2 líneas en el Calendario. Pruebas: `V14Test` (Atlético de Madrid, Deportivo de La Coruña,
Borussia Mönchengladbach, Wolverhampton Wanderers, UEFA Europa Conference League…).

## Modal Añadir / Editar canal (v14)

- Al abrirse **ya muestra el QR**: la sesión se crea sola, sin botón "Usar móvil" ni segunda ventana.
- Cabecera fija · cuerpo (campos | QR en pantallas anchas, uno debajo de otro en estrechas; si hace falta, el cuerpo hace scroll)
  · pie fijo con **Salir** y **Guardar** siempre visibles. Tamaños calculados con el tamaño real de la pantalla (máx. 94 %).
- D-pad: Título → Código → (QR / Generar nuevo QR) → Salir / Guardar.
- Móvil: la página muestra Título y Código actuales; cada "Enviar al televisor" actualiza el formulario ("Actualizado desde el
  móvil (envío N)"). **No guarda**: guarda el usuario en la TV.
- Debajo del QR, discreto, "Sesión: 04:32". Al caducar se invalida el token, el móvil dice que genere un QR nuevo y en la TV
  aparece "Sesión terminada" + **Generar nuevo QR**, sin cerrar el formulario ni perder lo escrito.

## Ace Stream: "motor abierto" ≠ "se está reproduciendo" (v14)

Opciones de un canal Ace → **Comprobar si se reproduce (Ace Stream)** (`services/AceStreamProbe.kt`). Habla con el motor
local por su API HTTP (`/ace/getstream?id=…&format=json` → `stat_url` cada segundo → `method=stop` al acabar) y dice:
- **Se reproduce**: estado `dl` con datos llegando (velocidad > 0 o descargado creciendo), con peers y KB/s → "Ver ahora".
- **Solo prebuffer**, **sin datos** (sin peers), **error del motor** (rechaza el Content ID) o **motor no responde**.

Nunca da por bueno un canal solo porque Ace Stream se abra. El Content ID se envía tal cual. Junto con
**Ajustes → Registro de errores → Última apertura de canal** (intent, paquete destino, URI enmascarada) sirve para comparar
con la app antigua: ambas mandan el mismo `Intent(VIEW, acestream://…)` + NEW_TASK sin extras ni MIME.

## Registro de errores

**Ajustes → Registro de errores**. Si MiVisor se cierra inesperadamente, al volver a abrirla avisa y aquí aparece el detalle:
fecha, versión, dispositivo, últimos pasos (pantallas y teclas del mando) y la traza. Se guardan los 10 últimos, limpios de
URLs, códigos, hashes y tokens (`diagnostics/LogSanitizer`).

- **Ver en el móvil (QR)**: abre el texto en el móvil (solo lectura, botón COPIAR) para enviarlo a quien lo vaya a revisar.
- **Borrar registro**.
- Red de seguridad del mando: si la búsqueda de foco falla al pulsar una flecha, la app ya no se cierra; se ignora esa pulsación
  y se guarda como "Error recuperado" con su traza.

## Mando

| Tecla | Acción |
|---|---|
| OK | Reproducir / seleccionar |
| OK mantenido · MENU · INFO | Opciones del canal (abrir con…, compartir, olvidar app) |
| ◀ desde la primera columna | Menú lateral |
| Atrás | Subpantalla → catálogo → menú → salir |

## Rendimiento (TVs con poca RAM)

- APK **release con R8** y recursos recortados (Compose en debug es varias veces más lento).
- Sin `tv-material` ni `dnsjava`: componentes TV propios y un cliente DNS propio de ~150 líneas.
- `profileinstaller`: perfiles de arranque de Compose también en APKs instalados a mano.
- Foco sin sombras ni brillos: la tarjeta enfocada se invierte (fondo claro). Las animaciones se pueden desactivar en Ajustes y vienen apagadas por defecto en dispositivos *low-RAM*.
- El catálogo se calcula una sola vez fuera del hilo principal, y las listas usan claves únicas y estables.
- Caché en binario (sin JSON intermedio), escritura atómica y como máximo 60 entradas.
- Límite de 4 MB al descomprimir: un payload enorme no tumba la app.

## DNS

1. 8.8.8.8 y 1.1.1.1 **en paralelo** (UDP con EDNS0; si la respuesta llega truncada, se repite por TCP).
2. Si el puerto 53 está bloqueado: DNS-over-HTTPS de Google y Cloudflare, también en paralelo.

Un NXDOMAIN se considera definitivo ("Este código no existe") y no se prueban más servidores.

## Estructura

```
app/src/main/java/com/mivisor/app/
├── MainActivity.kt          deep links (singleTask + onNewIntent)
├── MainViewModel.kt         estado de la UI, caché → red, errores
├── model/                   StreamItem (JSON), Channel/Section (UI), historial, categorías
├── network/                 DnsMessage, PlainDnsResolver (UDP/TCP), DohResolver, CompositeDnsResolver
├── data/                    PayloadDecoder, CatalogBuilder, RawCache, StreamRepository, Stores (DataStore)
├── player/                  StreamLauncher, PlayerPreferences
└── ui/
    ├── theme/               paleta, tipografía, medidas (márgenes de overscan)
    ├── components/          TvClickable (foco/OK/pulsación larga), botones, campos, diálogos
    ├── screens/             Catálogo, Historial, Categorías, Ajustes
    └── MiVisorApp.kt        menú lateral, navegación y "Atrás"
app/src/test/                tests unitarios (DNS, decodificador, repositorio, catálogo, agenda)
tools/agenda/                generador: agenda_core, providers, provider_ufc, tv_futbolenlatv, tv_thesportsdb,
                             artwork, services, matching, generate_agenda (+ tests y fixtures)
```

## Compatibilidad con versiones anteriores

- Se conservan el historial, las categorías y las preferencias de Mi Visor v3 (mismos DataStore y claves) y de MyLinksPaste (`StreamsPrefs`).
- Mismo `applicationId` y misma clave de firma, así que **se instala encima** de la v3 sin desinstalar.
- `versionCode = 1000 + número de ejecución`, siempre mayor que el de cualquier build v3.

## GitHub Actions

`.github/workflows/build.yml`, en cada push, PR o ejecución manual:

1. Tests unitarios (`testDebugUnitTest`).
2. APK **release** (`assembleRelease`) y lint (informativo, no bloquea).
3. Sube el APK y los informes como artifacts.
4. En `main`, además, publica una Release `build-N` marcada como *latest*.

`.github/workflows/agenda.yml`, cada hora (`17 * * * *`), a mano o al cambiar `tools/agenda/`: tests del generador → recupera el estado anterior de `agenda-data` (agenda, servicios, retratos, caché UFC) → genera con los secrets → publica todo en `agenda-data`. Si fallan todas las fuentes de agenda, publica `services.json` con la última agenda válida y termina en rojo.

### Firma con clave propia (opcional)

Sin configurar nada, se firma con `app/debug.keystore` (clave de desarrollo, la misma que la v3). Para usar una clave privada, crea estos *secrets* en el repositorio:

| Secret | Valor |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | `base64 -w0 mi-clave.jks` |
| `SIGNING_STORE_PASSWORD` | contraseña del keystore |
| `SIGNING_KEY_ALIAS` | alias de la clave |
| `SIGNING_KEY_PASSWORD` | contraseña de la clave |

> ⚠️ Cambiar de clave obliga a desinstalar la versión ya instalada (Android no deja actualizar entre firmas distintas).

## Desarrollo local

```bash
./gradlew testDebugUnitTest      # tests
./gradlew assembleRelease        # APK optimizado
./gradlew installDebug           # se instala como com.mivisor.app.debug, junto a la release
```

Requisitos: JDK 17 y Android SDK 35.
