# TomTom Bridge Personal · v1.0 beta

**Una aplicación Android para uso estrictamente personal, sin hosting, PHP, backend, MCP ni servicios intermedios.** El único servicio remoto es la API oficial de Strava, si vinculas tu cuenta. El reloj se conecta directamente por Bluetooth Low Energy.

## Estado de esta entrega

- Código fuente completo preparado para **GitHub Actions** (Android Java, SDK 35, Java 17, Gradle 8.9).
- La conexión Bluetooth y el mecanismo de transferencia CRC siguen la versión 0.4 ya probada con el reloj TomTom MultiSport 1.8.52.
- La descarga múltiple, Strava OAuth local y subida directa **aún deben validarse en una prueba de APK Android y con la cuenta Strava real**. No se ha hecho ninguna subida real desde esta sesión.
- Se verificó el parser Java y el XML TCX con el TTBIN de 5.274 bytes proporcionado en el desarrollo. No se incluye tu TTBIN privado en el proyecto.

## Funciones

1. Encontrar y emparejar el reloj por Bluetooth LE, autenticar con PIN cuando sea necesario y leer los archivos `0x0091xxxx`.
2. **Sincronizar todas las actividades**: volver a consultar la lista, descargar secuencialmente todas las que no tengan una copia íntegra (hash SHA-256 conservado junto al TTBIN, aunque aún no sepamos interpretar su deporte), validar los bloques CRC16, guardar localmente y evitar borrados.
3. Historial con fecha local corregida, deporte, duración, distancia, datos de pulsaciones cuando sean interpretables, gráfico y exportación TCX/TTBIN.
4. Pantalla clara u oscura según los ajustes de Android. Diagnóstico técnico oculto por defecto.
5. Integración directa con **Strava para uso personal**, sin servidor ni intermediario: autorización OAuth en navegador con retorno a `127.0.0.1`, renovación de tokens en el teléfono, envío de TCX a `/api/v3/uploads`, seguimiento del procesamiento y prevención de duplicados.
6. El TTBIN original nunca se envía a Strava: solo TCX convertido. No se implementan DELETE, flasheo de firmware ni QuickGPS.

### Instalar el APK desde GitHub

1. Descomprime este ZIP. **En la raíz del repositorio** deben quedar `app/`, `.github/`, `build.gradle` y `settings.gradle` (no una carpeta exterior extra).
2. Sube los archivos a GitHub, haz *Commit* en la rama `main` y abre **Actions → Compilar APK TomTom Bridge**.
3. Descarga el artefacto `TomTomBridge-v1.0-Personal-APK`, descomprime e instala `app-debug.apk`.
4. Si Android pide desinstalar la versión anterior por firma distinta, exporta antes los TTBIN: desinstalar borra la carpeta privada de la app.
5. Pulsa **Buscar y conectar reloj**, autoriza si lo pide, y pulsa **Sincronizar todas las actividades nuevas**.

### Vincular Strava (una sola vez)

No necesitas Hostinger ni cuenta de servidor. **La API de Strava exige registrar una app personal**, con `Client ID` y `Client Secret`. En noviembre de 2026 su documentación de inicio indica que crear una aplicación API requiere una suscripción a Strava; comprueba tu cuenta antes de configurarlo.

1. Abre <https://www.strava.com/settings/api>, crea una aplicación personal y anota `Client ID` y `Client Secret`. No los pegues en GitHub ni los envíes por chats.
2. En **Authorization Callback Domain** introduce **`127.0.0.1`** (Strava también acepta `localhost` como retorno local, pero esta aplicación usa expresamente `127.0.0.1`).
3. En TomTom Bridge pulsa **Configurar y vincular Strava**, introduce los dos valores y autoriza `activity:write` en el navegador o app de Strava.
4. El móvil escuchará **temporalmente** en `http://127.0.0.1:18765/callback` exclusivamente para recibir el código OAuth. No es un servidor remoto y no necesita dominio ni abrir puertos del router. Vuelve a TomTom Bridge después de autorizar.
5. Al pulsar **Sincronizar**, las actividades nuevas e interpretables se descargarán y subirán automáticamente a tu cuenta. También existe **Reintentar subidas pendientes** para recuperar las que no se hubieran podido procesar.

**Seguridad de uso personal**: se almacenan el secreto y los tokens cifrados mediante AES-GCM y Android Keystore. El secreto **puede ser extraíble en un dispositivo comprometido**; esta solución no debe distribuirse como aplicación pública. OAuth usa `state` aleatorio y retorno local; no hay credenciales en el APK ni en repositorios. Cuando desvinculas se borran las credenciales de sesión del móvil; la revocación remota se hace en los ajustes de Strava.

### Estados de subida

- `Publicado`: Strava confirmó el ID de actividad.
- `Procesando`: Strava aceptó el archivo, pero aún no confirmó su publicación. Reintentar consulta el **mismo ID**, no vuelve a enviar el archivo.
- `Verificar en Strava`: la subida se inició pero la respuesta fue incierta (red móvil, timeout). **No se reenviará automáticamente** para evitar duplicados; comprueba si está publicada en Strava.
- `Pendiente`: falta conexión, autorización o los datos del TTBIN no superaron las comprobaciones.

### Precisión de los datos

- Se corrigió el desfase de una hora para el TTBIN de prueba: 08/11/2026 **20:44:15 Madrid**, exportado como **19:44:15Z**.
- Las métricas de cinta dependen de la estimación del reloj. No se inventan coordenadas GPS.
- El lector TTBIN y la exportación TCX para **natación, ciclismo y GPS necesitan archivos reales adicionales** para validar todas sus métricas. Si falta resumen o no se identifica el deporte, el archivo se guarda pero se bloquea la subida automática.
- Strava puede rechazar un entrenamiento por sus propias validaciones o por duplicado; el historial local no se pierde.

## Estructura

```text
app/src/main/java/com/tomtombridge/diagnostic/
  MainActivity.java      # BLE, historial, UI, descarga múltiple, exportadores
  TtbinParser.java       # decodificación conservadora TTBIN → TCX
  HeartChartView.java    # gráfico local de pulsaciones
  StravaSync.java        # Strava OAuth y uploads DIRECTOS desde Android
.github/workflows/android.yml
```

## Referencias técnicas

- <https://developers.strava.com/docs/authentication/>
- <https://developers.strava.com/docs/uploads/>
- <https://github.com/dlenski/ttblue>

No se requiere ninguna variable ni secreto en GitHub Actions. No hay directorio `backend/`.
