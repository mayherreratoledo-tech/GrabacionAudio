# Grabador de Clases (Android)

Graba clases con la pantalla apagada, las organiza en carpetas y recuerda dónde quedó cada reproducción.

## Obtener el APK

### Opción A: GitHub Actions (no instalas nada)
1. Crea un repositorio en github.com (puede ser privado) y sube todo el contenido de esta carpeta.
2. Ve a la pestaña **Actions** → «Build APK» → espera unos 5 minutos.
3. Entra a la ejecución terminada y descarga **GrabadorClases-APK** (en *Artifacts*). Dentro está `app-debug.apk`.

### Opción B: Android Studio
1. Abre la carpeta como proyecto y deja que sincronice Gradle.
2. Menú **Build → Build APK(s)**. El archivo queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Instalar
Pasa el APK al teléfono y ábrelo; permite «instalar apps de origen desconocido» si lo pide.
En el primer uso acepta micrófono y notificaciones, y toca «Evitar que el sistema cierre la grabación».
