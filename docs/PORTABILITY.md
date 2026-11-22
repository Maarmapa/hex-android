# Audit de portabilidad · hex (desktop) → Android

Fuente: clon de `anomalyco/hex` @ main (nov 2026). El repo público es el rewrite Rust+Rune (hex2); el legado Swift vive en el historial.

## Qué es portable y qué no

| Componente de Hex desktop | Portable a Android | Nota |
|---|---|---|
| Motor de dictado local (Parakeet/Apple Speech) | ✅ parcial | Parakeet v2 corre en Android vía ONNX Runtime (multilenguaje, es incluido). Apple Speech: no |
| Captura de audio (CoreAudio) | ❌→✅ reescrita | `AudioRecord` PCM 16k — ya esqueleto en `HexRecorder` |
| Paste en el campo con foco (Accessibility/paste worker) | ❌→✅ reescrita | En Android el IME **es** el campo: `commitText()` — sin Accessibility |
| Modes / correcciones / transforms (TS config `~/.config/hex`) | ✅ concepto | pipeline igual: crudo → correcciones → transformaciones ordenadas |
| Rewrite with OpenCode | ✅ concepto | acá: BYOK directo (el servicio local de OpenCode no corre en Android MVP) |
| Meetings (beta) | ❌ después | requiere ScreenCaptureKit-equivalente; slice futuro |
| HUD Metal / Rune window | ❌ | el UI es el teclado mismo (Compose) |
| History 7 días sin audio | ✅ sprint 2 | Room local, sin audio, cap de entradas |
| Sparkle updates | ✅ concepto | Play Store o updates directos firmados |

## Por qué un IME y no una app flotante

En Android, el único mecanismo para insertar texto en **cualquier** campo con foco es ser el teclado (IME). Es el equivalente exacto del paste de Hex, con el permiso `BIND_INPUT_METHOD`.

## Por qué Parakeet primero

- On-device real (igual que el espíritu de Hex: el audio no sale).
- Multilenguaje con español incluido (Map dicta en es/en).
- ONNX Runtime tiene runtime Android oficial — el modelo int8 corre en arm64.

## Sprints (de README.md)

0. Scaffold (hecho) → 1. transcripción real → 2. teclado+historial+rewrite → 3. hotkey/meetings.
