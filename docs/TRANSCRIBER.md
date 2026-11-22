# Transcripción on-device: Parakeet TDT 0.6B v2 (ONNX Runtime)

Implementación real del transcriptor de Hex para Android: PCM 16k mono → texto,
todo dentro del dispositivo. El audio **nunca sale del teléfono**: la única red que
se usa es para descargar los archivos del modelo (y el vocabulario ya viene dentro
del APK).

Archivos de este módulo (dueño exclusivo):

| Archivo | Rol |
|---|---|
| `app/src/main/java/cl/maarmapa/hexandroid/Transcriber.kt` | interfaz + pipeline completo |
| `app/src/main/java/cl/maarmapa/hexandroid/DescargadorModelo.kt` | descarga verificable (tamaño, SHA-256, reintentos) |
| `app/src/main/res/raw/parakeet_v2_vocab.txt` | vocabulario crudo (1025 tokens) |
| `docs/TRANSCRIBER.md` | este documento |

---

## 1. Modelo y URLs (verificados)

El export ONNX de la comunidad (istupakov), derivado del modelo oficial de NVIDIA.
Repositorio del export: <https://huggingface.co/istupakov/parakeet-tdt-0.6b-v2-onnx>
Modelo original: <https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2> (licencia CC-BY-4.0).

Todos los tamaños y SHA-256 fueron verificados contra la API de HuggingFace
(`https://huggingface.co/api/models/istupakov/parakeet-tdt-0.6b-v2-onnx/tree/main`),
donde los archivos LFS publican su SHA-256 como `lfs.oid`. Los hashes del vocabulario
se verificaron localmente con `shasum -a 256` tras descargarlo.

| Archivo | URL exacta | Tamaño (bytes) | SHA-256 |
|---|---|---|---|
| Encoder int8 | `https://huggingface.co/istupakov/parakeet-tdt-0.6b-v2-onnx/resolve/main/encoder-model.int8.onnx` | 652 184 014 (~622 MiB) | `3e0581fda6ab843888b51e56d7ee78b6d5bc3237ec113af1f732d1d5286aa155` |
| Decoder+joiner int8 | `https://huggingface.co/istupakov/parakeet-tdt-0.6b-v2-onnx/resolve/main/decoder_joint-model.int8.onnx` | 8 998 286 (~8,6 MiB) | `a449f49acd68979d418651dd2dcb737cc0f1bf0225e009e29ee326354edbf7d3` |
| Vocabulario | `https://huggingface.co/istupakov/parakeet-tdt-0.6b-v2-onnx/resolve/main/vocab.txt` | 9 384 | `ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d` |

Existe también la variante fp32 (`encoder-model.onnx` + `encoder-model.onnx.data`,
~2,4 GB total) y una variante fp32 sin datos externos (~42 MB de grafo + 2,4 GB de
pesos); no se usan porque el int8 es más apto para móviles.

El vocabulario ya se descargó y quedó embebido como recurso crudo en
`app/src/main/res/raw/parakeet_v2_vocab.txt`, así que no se descarga en runtime.

## 2. Idioma (corrección importante)

`parakeet-tdt-0.6b-v2` es un modelo **solo en inglés** (la model card de HuggingFace
declara `language: en`). El comentario anterior en `Transcriber.kt` que lo describía
como "multilenguaje, es incluido" era incorrecto. Para español existe el modelo
sucesor `parakeet-tdt-0.6b-v3`, que según la documentación de NVIDIA es
multilenguaje [sin verificar: no se auditó su export ONNX ni su vocabulario en
esta sesión].

## 3. Pipeline técnico

Parakeet v2 es un **transductor TDT** (Token-and-Duration Transducer), no un modelo
CTC: ver §4. La especificación de la tarea pedía "mel de 80 bins" y "decodificador
CTC greedy"; lo que sigue es lo que el modelo real exige, verificado en el código de
referencia de `onnx-asr` (GitHub: `istupakov/onnx-asr`, archivos
`preprocessors/nemo.py`, `preprocessors/fbanks.py`, `src/onnx_asr/models/nemo.py`,
`src/onnx_asr/asr.py`) y en `config.json` del export.

### 3.1 Normalización PCM (Kotlin puro: `NormalizadorPcm`)

- Entrada: PCM 16 bits mono a 16 kHz (`HexRecorder` ya graba a 16 kHz).
- Conversión a float `[-1, 1]`: `muestra / 32768`.
- Si `sampleRate != 16000` se lanza `IllegalArgumentException` (no se remuestrea).

### 3.2 Mel spectrogram (Kotlin puro: `MelEspectrograma` + `Fft512`)

Réplica exacta del preprocesador NeMo del export ONNX:

1. **Pre-énfasis** 0.97: `x[i] = x[i] - 0.97*x[i-1]` (primera muestra sin cambio).
2. **Relleno**: 256 ceros (n_fft/2) a cada lado.
3. **STFT**: ventana de Hann simétrica de 400 muestras (25 ms, `np.hanning(400)`)
   rellena con 56 ceros a cada lado hasta 512; hop de 160 muestras (10 ms);
   espectro de potencia `|X|^2` (sin normalizar), bins 0..256.
4. **Banco de filtros mel**: 128 bins (¡no 80! `config.json` del export declara
   `features_size: 128`), escala y normalización "slaney" (fórmula librosa:
   Hz→mel con `3*f/200` bajo 1 kHz y `15+27*log(f/1000)/log(6.4)` encima, con
   epsilon float32; normalización por área `2/(f_top-f_base)`), f_min=0, f_max=8000.
5. **Log**: `ln(mel + 2^-24)` (`log_zero_guard_value = 2**-24`).
6. **Longitud de features**: `frames_validos = muestras / 160` (división entera);
   el tensor de entrada al encoder es `[1, 128, frames_validos + 1]` y el frame
   extra va enmascarado a 0.
7. **Normalización por bin** (per-feature, como NeMo): media y varianza muestral
   (denominador `frames_validos - 1`) sobre los frames válidos, luego
   `(x - media) / (desv + 1e-5)`; frames fuera de la máscara quedan en 0.

La FFT (radix-2 iterativa de 512, bit-reversal + butterflies, float32) está
implementada en Kotlin puro, sin dependencias nuevas.

### 3.3 Sesiones ONNX Runtime (`ParakeetOnnxTranscriber`)

Dos sesiones (`ai.onnxruntime.OrtSession`, proveedor CPU), creadas de forma perezosa:

- **Encoder** (`encoder-model.int8.onnx`):
  - entradas: `audio_signal` float32 `[1,128,T]`, `length` int64 `[1]`;
  - salidas: `outputs` float32 `[1,T',512]`, `encoded_lengths` int64.
- **Decoder+joiner** (`decoder_joint-model.int8.onnx`):
  - entradas: `encoder_outputs` float32 `[1,512,1]`, `targets` int64 `[1,1]`,
    `target_length` int64 `[1]`, `input_states_1`/`input_states_2` float32 `[dim,1,dim]`;
  - salidas: `outputs` (logits concatenados tokens|duraciones), `output_states_1/2`.
  - Las dimensiones de estado se leen de la propia sesión al crearla
    (`getInputInfo(...).info as TensorInfo`), no van hardcodeadas.

(Nombres y formas verificados en `src/onnx_asr/models/nemo.py` de onnx-asr.)

### 3.4 Decodificador TDT greedy (Kotlin puro: `DecodificadorTdt`)

Loop greedy idéntico al de onnx-asr (`_AsrWithTransducerDecoding`):

```
t = 0; emitidos = 0
mientras t < frames_validos:
    logits = decoder_joint(frame t, token_previo, estados)   // [tokens | duraciones]
    token = argmax(logits[:tamano_vocab]); duracion = argmax(logits[tamano_vocab:])
    si token != vacio: guardar estados nuevos; emitir token; emitidos++
    si duracion > 0: t += duracion; emitidos = 0
    sino si token == vacio o emitidos == 10: t += 1; emitidos = 0
```

Detalles semánticos que se replicaron al pie de la letra:
- el estado LSTM solo se conserva cuando se emite un token (no con blank);
- `max_tokens_per_step = 10` (defecto de NeMo/onnx-asr);
- blank es el último token del vocabulario (`<blk>`, id 1024).

### 3.5 Vocabulario y texto (Kotlin puro: `VocabularioParakeet`, `DecodificadorTexto`)

- `parakeet_v2_vocab.txt`: una línea por token en formato `<token> <id>`;
  1025 tokens (SentencePiece, vocab de 1024 + `<blk>`).
- El token `▁` (U+2581, marcador de inicio de palabra) se reemplaza por espacio.
- El texto final replica el patrón `DECODE_SPACE_PATTERN` de onnx-asr
  (regex `\A\s|\s\B|(\s)\b`, reemplazo: `" "` si capturó el grupo, `""` si no),
  que elimina espacios iniciales, espacios antes de signos y colapsa separaciones.

## 4. Desviaciones respecto a la tarea original (y por qué)

| La tarea pedía | Lo que el modelo real exige | Evidencia |
|---|---|---|
| Mel de 80 bins | **128 bins** con n_fft=512 | `config.json` del export (`features_size: 128`), archivo `nemo128.onnx` |
| Ventana 25 ms / hop 10 ms | Igual (400/160 muestras) | `preprocessors/nemo.py` |
| Decodificador CTC greedy | **Greedy transductor TDT** (cabeza de tokens + cabeza de duración) | `src/onnx_asr/models/nemo.py` (clase `NemoConformerTdt`) |
| Modelo "multilenguaje, es incluido" | Solo inglés | model card de HF (`language: en`) |

La URL del antiguo `MODELO_URL` (`nvidia/parakeet-tdt-0.6b-v2/resolve/main/onnx/model.onnx`)
no existe: NVIDIA no publica ONNX en su repo oficial; se usa el export de la
comunidad, con hashes publicados y verificables. El antiguo comentario "~120 MB"
también era incorrecto: el int8 ocupa ~622 MiB.

## 5. Validación numérica realizada

Como esta máquina no tiene Android SDK ni `kotlinc`, el **algoritmo** se validó
con réplicas Python (numpy) línea a línea de las fórmulas del código Kotlin:

- Pipeline mel completo vs la referencia (`fbanks.py` + preprocesador NeMo sobre
  STFT numpy): diferencia máxima ~2×10⁻¹³ sobre 128×124 features (ruido de
  float32, esencialmente exacto).
- Banco de filtros mel vs `fbanks.py`: diferencia máxima ~4×10⁻¹⁶.
- `Fft512` vs `np.fft.fft`: error RMS ~2,4×10⁻⁶ (precisión float32, correcto).
- Loop TDT greedy vs el loop de `_AsrWithTransducerDecoding`: salidas idénticas
  (tokens y timestamps) en 5 escenarios con blank, saltos de duración y el tope
  de 10 tokens por paso.

El riesgo residual es de transcripción Kotlin (no compilado aquí), mitigado con
revisión manual: ver §7.

## 6. Descarga (`DescargadorModelo`, Kotlin puro)

- 3 intentos con retroceso exponencial (1 s, 2 s, 4 s).
- Escritura atómica: temporal `*.parte` junto al destino; se renombra solo tras
  validar **tamaño exacto** (652 184 014 / 8 998 286 bytes) y **SHA-256**.
- Sin claves ni tokens: HuggingFace no los requiere para estos archivos.
- El audio no participa de ningún modo en red.

## 7. [sin verificar] y pendientes

- **No compilado**: esta sesión no tiene Android SDK ni `kotlinc`. El código
  Kotlin se revisó a mano; puede haber errores de tipos o de la API Java de
  ONNX Runtime 1.18 que solo aflorarían al compilar.
- **No probado en dispositivo**: la descarga de 622 MiB, el rendimiento del
  encoder int8 en ARM, y la calidad real de la transcripción.
- **Nombres exactos de tensores**: tomados del código de referencia de onnx-asr;
  no se inspeccionó el binario ONNX aquí. Si ONNX Runtime queja un nombre,
  inspeccionar con `session.getInputNames()`.
- Número de clases de duración del TDT (probablemente 5) [sin verificar]:
  el código lo infiere en runtime, no lo asume.
- `parakeet-tdt-0.6b-v3` como reemplazo multilenguaje [sin verificar].
- Compatibilidad de `onnxruntime-android` con estos modelos int8 exportados por
  onnxscript [sin verificar; la librería 1.18.0 está en `build.gradle.kts`].

## 8. Qué necesita un humano con Android Studio

1. **Compilar**: abrir el proyecto y corregir cualquier error de compilación
   (probablemente en la API de `ai.onnxruntime`; nombres de tensor en
   `pasoDecoder`/`correrEncoder`).
2. **Integración de red**: `HexInputMethodService` llama a `Transcriber.create`
   y la primera `transcribe()` descarga ~622 MiB **de forma bloqueante**. Antes
   de probar en un teléfono real, mover la primera llamada a un hilo secundario
   (coroutines) con UI de progreso; `DescargadorModelo` ya expone un callback
   `(leidos, total)` y reintentos automáticos. `transcribe()` además es
   secuencial (un solo hilo por sesión); envolver en mutex si se llama de
   varios hilos (la creación de sesiones ya está sincronizada).
3. **Probar con el modelo**: descargar los dos archivos int8 (URLs en §1) o
   dejar que la app los baje en Wi-Fi; ubicación esperada en runtime:
   `filesDir/parakeet-v2-encoder-model.int8.onnx` y
   `filesDir/parakeet-v2-decoder-joint-model.int8.onnx`.
4. **Prueba de audio**: inyectar un WAV 16 kHz mono de prueba (por ejemplo uno
   de los `test-*.wav` del repo de HuggingFace) y comparar el texto con la
   salida de referencia de onnx-asr.
5. **Pruebas unitarias JVM**: las piezas puras (`NormalizadorPcm`,
   `MelEspectrograma`, `Fft512`, `DecodificadorTdt`, `VocabularioParakeet`,
   `DecodificadorTexto`, `DescargadorModelo`) no importan Android; pueden ir en
   `app/src/test/java/...` como tests unitarios normales (no instrumentados).
   No se agregaron archivos de test ni dependencias porque `app/build.gradle.kts`
   no era parte del alcance de esta tarea.
6. **Verificar memoria**: el buffer de mel `[1,128,T]` para 30 s de audio ocupa
   ~1,5 MB; los estados del decoder se reasignan por paso (optimización futura:
   reutilizar buffers).

## 9. Privacidad

- El audio se procesa 100 % on-device; ninguna función envía audio por red.
- La única red usada es la descarga inicial del modelo (HTTP GET a HuggingFace),
  que no contiene audio ni datos de usuario.
- Sin claves, tokens ni telemetría.
