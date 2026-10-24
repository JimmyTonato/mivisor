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

## Agenda deportiva

Partidos del día y en qué canales se emiten, con datos de [futbolenlatv.es](https://www.futbolenlatv.es/).

```
futbolenlatv.es ──(GitHub Actions cada 30 min)──▶ tools/agenda/scrape_agenda.py
        ──▶ agenda.json en la rama `agenda-data` ──▶ la app lo descarga (pocos KB, con ETag y caché)
```

- **Por qué en Actions y no en la TV**: la TV solo descarga un JSON pequeño, sin analizar HTML (menos RAM). Si la web cambia, se corrige el scraper sin publicar una nueva versión de la app, y la fuente recibe 4 peticiones cada 30 min en vez de una por cada TV.
- **Scraper**: tablas HTML (fecha, hora, competición, equipos, canales y dial) con JSON-LD como respaldo. Respeta `robots.txt`, descarta los canales "Bar" y "por confirmar", y falla de forma visible si no extrae nada (se conserva la última agenda publicada).
- **Depuración**: cada ejecución guarda el HTML descargado como artifact (`agenda-debug-N`, 3 días).
- **URL**: la app la recibe al compilarse en Actions (`BuildConfig.AGENDA_URL`, a partir de `GITHUB_REPOSITORY`). Se puede cambiar en Ajustes → Agenda deportiva. El repositorio debe ser **público** para que `raw.githubusercontent.com` sirva el JSON.
- **Diseño**: pantalla completa con estilo propio (azul noche, foco cian con halo, columnas de filtros, destacados y "Más partidos de hoy"). Código en `ui/screens/agenda/`. El halo se dibuja con contornos translúcidos, sin sombras ni desenfoques, para que vaya fluido en TVs modestas.
- **Logos** (escudos, competiciones y canales): se usan los de la web si los trae y, por encima de ellos, los de `tools/agenda/logos.json` (nombre → URL PNG/WebP), que controlas tú. La app los carga con un cargador propio (`RemoteImage`): los reduce al tamaño en que se pintan y usa caché en memoria (máx. 6 MB) y en disco. Sin logo se muestran las iniciales sobre un escudo de color.
- **"Abrir canal"**: cada canal de la agenda se compara con los canales de tus categorías (`ChannelMatcher`: sin acentos, con alias como M+ → Movistar y sin etiquetas HD/FHD/1080…). Es conservador: "DAZN" a secas no se enlaza, y "DAZN LaLiga" no enlaza con "DAZN LaLiga 2".

### Formato de `agenda.json` (v1)

```json
{
  "version": 1, "source": "futbolenlatv.es", "generatedAt": "2026-10-24T10:00:00Z",
  "timezone": "Europe/Madrid",
  "events": [{
    "id": "3747736f255c", "sport": "futbol", "competition": "La Liga EA Sports",
    "start": "2026-10-24T18:00:00+02:00", "home": "Real Madrid", "away": "Sevilla",
    "title": "Real Madrid - Sevilla", "homeLogo": "https://…", "awayLogo": "https://…",
    "channels": [{"name": "DAZN LaLiga", "dial": "M55 O113"}, {"name": "DAZN", "note": "Ver en directo"}]
  }]
}
```

Probar el scraper en local: `python tools/agenda/scrape_agenda.py --out agenda.json --debug-dir debug` · tests: `python -m unittest discover -s tools/agenda`.

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
app/src/test/                tests unitarios (DNS, decodificador, repositorio, catálogo)
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
