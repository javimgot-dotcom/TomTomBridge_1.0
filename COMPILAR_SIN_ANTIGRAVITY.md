# Compilación sin herramientas adicionales

1. Abre tu repositorio GitHub.
2. Sube el contenido del proyecto a la **raíz** del repositorio. Conserva `.github/workflows/android.yml`.
3. Haz `Commit changes` en `main`.
4. En **Actions** ejecuta **Compilar APK TomTom Bridge** o espera a que lo dispare el commit.
5. Descarga el artefacto **TomTomBridge-v1.0-Personal-APK** y extrae `app-debug.apk`.
6. Instálalo en Android. Si la firma de depuración cambia, respalda tus archivos originales antes de desinstalar la app previa.
7. La conexión a Strava se configura **solo desde la aplicación Android**, con las credenciales que entrega <https://www.strava.com/settings/api>. No hay backend ni variables de GitHub que configurar.

**Importante:** el código no se ha compilado en este contenedor porque no hay SDK Android/Gradle. Si falla GitHub Actions, conserva y envía el log de compilación. El parser Java sí se comprobó con la actividad real.
