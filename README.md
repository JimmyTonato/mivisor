# Mi Visor TV · v4

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

Fútbol, tenis, Fórmula 1 y MotoGP de los próximos 14 días, generados a partir de APIs deportivas estructuradas.

```
Football-Data.org · Live Tennis API · Blacktop (MotoGP) · Jolpica (F1)
        │  GitHub Actions, una vez por hora (minuto 17) + ejecución manual
        ▼
tools/agenda/generate_agenda.py  ──(canales: TheSportsDB, opcional)──▶  agenda.json (v1)  ──▶  rama `agenda-data`
        ▼
APK: descarga raw.githubusercontent.com/<usuario>/<repo>/agenda-data/agenda.json
     como mucho cada 15 min (caché + ETag / If-None-Match → 304), y sin conexión usa la última copia
```

- **La app nunca llama a las APIs deportivas** ni tiene claves: solo lee `agenda.json`. Cada TV hace como mucho una petición pequeña cada 15 minutos, y casi siempre recibe un 304 sin cuerpo.
- **Sin servidor propio**: todo ocurre en GitHub Actions. El generador usa solo la biblioteca estándar de Python (sin dependencias).
- **Fuentes y filtros**:

  | Deporte (`sport`) | Fuente | Qué se incluye |
  |---|---|---|
  | `futbol` | Football-Data.org v4 (`/matches` por rango de fechas) | Solo una lista cerrada: LaLiga, Premier League, Serie A, Bundesliga, Ligue 1, Champions League (y Eurocopa/Mundial cuando los hay). Nunca segundas divisiones. |
  | `tenis` | Live Tennis API (`/fixtures` de ATP y WTA) | Grand Slams y torneos ATP/WTA principales. Se excluyen Challenger, ITF, Futures, juniors, exhibiciones, dobles y partidos aún sin hora. |
  | `formula1` | Jolpica F1 (calendario de la temporada, sin clave) | Clasificación, Sprint y Carrera. Sin libres ni tiempos en directo. |
  | `motogp` | Orange Cat Blacktop (`/moto-gp/events`) | Clasificación, Sprint y Carrera de MotoGP. Sin libres, warm up, Moto2 ni Moto3. |

  `motor` y `baloncesto` se siguen aceptando por compatibilidad. Fórmula 1 y MotoGP aparecen como filtros separados.
- **Canales (TheSportsDB)**: los partidos de fútbol se completan con los broadcasters que devuelve [TheSportsDB](https://www.thesportsdb.com/) **de cualquier país** (DAZN, Movistar Plus+, Sky Sports, TNT Sports, beIN Sports, ESPN, Canal+…). No se filtra por país.
  - Cada canal es `{"name": "DAZN LaLiga", "note": "Spain"}`. `name` lleva solo el nombre, que es lo que usa `ChannelMatcher` para buscarlo en tus códigos. El país va en `note`, o se omite si la fuente no lo da.
  - Cruce: una petición `eventsround` por liga y jornada. El partido se identifica por la hora (±3 h) y los equipos, con nombres normalizados ("Barça" = "Barcelona", "Atleti" = "Atlético de Madrid"…). Después, una petición `lookuptv` por partido.
  - Duplicados: mismo canal y mismo país cuentan una vez. El mismo canal en otro país es otra entrada.
  - Plan gratuito: hasta 2 broadcasters por partido y 30 peticiones por minuto. Se consultan los partidos de los próximos 4 días, con un máximo de 80 peticiones espaciadas por ejecución. El resto conserva los canales de ejecuciones anteriores. Si no hay broadcasters, `"channels": []` y el evento aparece igual.
  - **Nunca se inventan canales** y un fallo de TheSportsDB nunca afecta a los eventos. Solo se ve en el log (`TheSportsDB: OK - …` / `PARTIAL` / `FAILED`) y no cambia los estados `ok`/`stale`/`unavailable`.
  - Logos de canal: no se toman de TheSportsDB (sus imágenes no son de uso libre). Se pueden añadir en `logos.json`.
  - Tenis, F1 y MotoGP llegan de momento con `channels: []`.
- **Horas**: las APIs dan UTC; `agenda.json` las presenta en Europe/Madrid con su desfase real (`+01:00` en invierno, `+02:00` en verano), calculado con `zoneinfo`.
- **IDs estables**: `football-123456`, `tennis-987654`, `formula1-2026-20-race`, `motogp-2026-malaysia-race`.
- **Si un proveedor falla**, los demás siguen publicándose. Cada uno tiene su estado en `stats`:
  - `ok`: datos nuevos (0 eventos también es correcto, por ejemplo un parón de liga).
  - `stale`: ha fallado y se conservan sus eventos de la última agenda publicada que sigue dentro de la ventana. La app muestra: *"La agenda de tenis no se ha podido actualizar. Se muestran los últimos datos disponibles."*
  - `unavailable`: ha fallado y no hay datos anteriores válidos. La app muestra: *"No se ha podido cargar la agenda de tenis."*
  - Códigos de error: `provider_unavailable`, `rate_limited`, `authentication_error`, `invalid_response`, `timeout`. En el JSON y en los logs nunca aparecen claves, cabeceras, URLs privadas ni trazas.
  - **Si fallan todos**, no se genera `agenda.json`, el workflow falla antes de publicar y la app sigue con la última agenda válida.
- **Depuración**: cada ejecución guarda las respuestas crudas de las APIs (sin cabeceras ni claves) como artifact `agenda-debug-N` durante 3 días. Si una API cambia su formato, sirven para ajustar el parser.
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

### Secrets necesarios

En **Settings → Secrets and variables → Actions → New repository secret**, crea estos tres (los nombres deben ser exactos):

| Secret | Proveedor |
|---|---|
| `FOOTBALL_DATA_TOKEN` | Football-Data.org (cabecera `X-Auth-Token`) |
| `LIVE_TENNIS_API_KEY` | Live Tennis API (`Authorization: Bearer`) |
| `BLACKTOP_API_KEY` | Orange Cat Blacktop (cabecera `x-api-key`) |

Opcional: `THESPORTSDB_API_KEY` (clave de pago de TheSportsDB). Sin ella se usa su clave pública gratuita. Jolpica no necesita clave. Las claves solo existen en GitHub Secrets: no van en el código, ni en la APK, ni en `agenda.json`. Si falta un secret, ese deporte queda `unavailable` (`authentication_error`) y el resto se publica igual.

### Formato de `agenda.json` (v1)

```json
{
  "version": 1, "source": "MiVisor Sports APIs", "generatedAt": "2026-10-23T10:17:00Z",
  "timezone": "Europe/Madrid",
  "stats": {
    "football": {"provider": "football-data", "status": "ok", "lastAttempt": "2026-10-23T10:17:00Z",
                 "lastSuccess": "2026-10-23T10:17:00Z", "events": 12},
    "tennis":   {"provider": "live-tennis-api", "status": "stale", "error": "timeout",
                 "lastAttempt": "2026-10-23T10:17:00Z", "lastSuccess": "2026-10-23T09:17:00Z", "events": 9}
  },
  "events": [{
    "id": "football-123456", "sport": "futbol", "competition": "LaLiga",
    "start": "2026-10-24T18:00:00+02:00", "home": "Real Madrid", "away": "Sevilla",
    "title": "Real Madrid - Sevilla", "homeLogo": "https://…", "competitionLogo": "https://…",
    "channels": [{"name": "DAZN LaLiga", "note": "Spain"}, {"name": "beIN Sports", "note": "France"}]
  }, {
    "id": "formula1-2026-20-race", "sport": "formula1", "competition": "Fórmula 1",
    "start": "2026-10-25T21:00:00+01:00", "title": "Mexico City Grand Prix · Carrera", "channels": []
  }]
}
```

`stats` es opcional para la app: si falta, simplemente no se muestran avisos. Cada canal admite `name`, `dial`, `note` y `logo`.

### Probar en local

```bash
python -m unittest discover -s tools/agenda -v          # sin Internet: las APIs están simuladas
export FOOTBALL_DATA_TOKEN=… LIVE_TENNIS_API_KEY=… BLACKTOP_API_KEY=…
python tools/agenda/generate_agenda.py --out agenda.json --debug-dir debug
```

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
tools/agenda/                generador de agenda.json (agenda_core, providers, tv_thesportsdb, generate_agenda) y sus tests
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

`.github/workflows/agenda.yml`, cada hora (`17 * * * *`), a mano o al cambiar `tools/agenda/`: tests del generador → recupera la última `agenda.json` de `agenda-data` → genera la nueva con los tres secrets → publica (si al menos un proveedor ha respondido).

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
