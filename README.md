# Hex for Android (proyecto espejo de Hex, by Anomaly)

Dictado por voz **on-device** para Android: mantén el botón del teclado, habla, suelta — el texto queda en el campo con foco. El audio **nunca sale del teléfono**.

> Proyecto derivado y homenaje a [Hex](https://github.com/anomalyco/hex) de Anomaly (MIT). Hex es Mac/Windows/Linux y iOS (TestFlight); **no existe versión Android oficial**. Este repo es el andamio del port comunitario, con el mismo espíritu: local primero, reescritura opcional, nada de nube por defecto.

## Arquitectura (espejo de Hex desktop)

| Hex desktop | Hex Android (este repo) |
|---|---|
| hold-to-talk (Option/Toggle) | botón del IME: hold = grabar, release = transcribir + commit |
| captura CoreAudio | `HexRecorder` (AudioRecord PCM 16 kHz mono) |
| motor de transcripción local (Apple Speech / Parakeet) | `Transcriber`: Parakeet v2 vía ONNX Runtime (multilenguaje, es/en incluidos) · Whisper TFLite como alternativa |
| paste worker + Accessibility | `InputConnection.commitText()` (el IME escribe en el campo con foco) |
| Modes > Rewrite with OpenCode (off por defecto) | `HexRewriteClient` (BYOK opcional, off por defecto, fallback byte por byte) |
| History (7 días, sin audio) | pendiente (sprint 2): historial local de transcripciones, sin audio |
| Models en onboarding | descarga de modelo la primera vez (`ParakeetOnnxTranscriber`) |

## Estado (sprint 0 — scaffold)

- [x] Estructura Gradle + IME declarado en el manifest (`BIND_INPUT_METHOD`)
- [x] `HexInputMethodService` con hold-to-talk y commit por `InputConnection`
- [x] `HexRecorder` (AudioRecord PCM 16k, hilo de captura, stop que entrega el buffer)
- [x] `Transcriber` (interfaz + Parakeet ONNX + Whisper TFLite stub + descargador de modelos)
- [x] `HexRewriteClient` (apagado por defecto)
- [ ] Sprint 1: normalización PCM → mel features → sesión ONNX → decodificador CTC
- [ ] Sprint 1: permiso de micrófono con flujo propio (sin tirar al Settings del sistema)
- [ ] Sprint 2: teclado visual (compor), historial local, idiomas (es/en), guess de idioma
- [ ] Sprint 2: rewrite opcional real (BYOK) y fallback byte por byte
- [ ] Sprint 3: hotkey global (asistente de accesibilidad), modo meetings igual que Hex

## Cómo compilar

Android Studio Ladybug+ · JDK 17 · sincroniza y corre en un dispositivo o emulador (API 26+). El modelo Parakeet (~120 MB int8) se descarga al primer dictado.

## Licencia y créditos

MIT. El diseño, el espíritu y el nombre le deben todo a [anomalyco/hex](https://github.com/anomalyco/hex) de Anomaly (Christopher Langton y equipo). Este proyecto es un fork-espejo comunitario no oficial: sin afiliación con Anomaly, sin sus binarios ni sus modelos — solo el espíritu MIT.
