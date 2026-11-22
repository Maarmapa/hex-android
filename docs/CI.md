# CI — pipeline de compilación

El repo compila con GitHub Actions en cada PR y en cada push a `main`. No usa secretos:
el proyecto solo se compila; no se firma ni se publica nada.

## Pipeline (`.github/workflows/build.yml`)

1. **Checkout** (`actions/checkout@v4`).
2. **Java 17** (`actions/setup-java@v4`, distribución Temurin).
3. **Android SDK** (`android-actions/setup-android@v3`): instala el SDK y acepta licencias.
4. **Gradle 8.7** (`gradle/actions/setup-gradle@v4`): el repo no tiene wrapper, así que se
   instala Gradle directamente. Si más adelante se agrega el wrapper (`gradlew`), basta
   cambiar los comandos a `./gradlew`.
5. **`gradle compileDebugKotlin`**: verifica que todo el Kotlin compila (fase rápida).
6. **`gradle assembleDebug`**: genera el APK de debug (verificación completa).

Ver el estado en la pestaña **Actions** del repositorio.

## Probar en local sin Android Studio

Solo hace falta Java 17 y las *cmdline-tools* del SDK.

1. Instalar Java 17 (en macOS: `brew install --cask temurin@17`).
2. Descargar las cmdline-tools del sitio de Android Studio
   (sección "Command line tools only") y descomprimirlas en
   `~/Library/Android/sdk/cmdline-tools/latest`.
3. Instalar los componentes que faltan:

   ```bash
   sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
   ```

   `sdkmanager` vive en `~/Library/Android/sdk/cmdline-tools/latest/bin`; si no está en
   el `PATH`, llamarlo con la ruta completa. Acepta las licencias con
   `sdkmanager --licenses`.
4. Indicar dónde está el SDK (solo si no está en la ruta por defecto
   `~/Library/Android/sdk`), creando `local.properties` en la raíz del repo:

   ```properties
   sdk.dir=/ruta/a/android-sdk
   ```

   (`local.properties` está en `.gitignore`; no se sube.)
5. Compilar:

   ```bash
   gradle compileDebugKotlin   # o ./gradlew si existe el wrapper
   gradle assembleDebug
   ```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Notas de seguridad

- El workflow no pide ningún secreto: sin claves del repo ni del runner.
- La key del proveedor de reescritura vive solo en `SharedPreferences` del dispositivo;
  nunca en el repo, variables de entorno ni logs.
