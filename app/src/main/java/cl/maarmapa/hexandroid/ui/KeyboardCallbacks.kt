package cl.maarmapa.hexandroid.ui

/**
 * Contrato entre la vista del teclado ([KeyboardView]) y el servicio IME.
 *
 * La vista nunca toca InputConnection: expone intenciones y es el servicio
 * (HexInputMethodService) quien las traduce al campo con foco:
 *
 *   - [onCommit]        -> InputConnection.commitText(text, 1)   (el "paste" de Hex)
 *   - [onDelete]        -> InputConnection.deleteSurroundingText(1, 0)
 *   - [onEditorAction]  -> InputConnection.performEditorAction(...)
 *
 * Los métodos se invocan en el hilo principal de la UI (son eventos de toque);
 * el servicio debe despachar a sus propios hilos si lo necesita, pero nunca
 * bloquear aquí.
 */
interface KeyboardCallbacks {
    /** El usuario mantuvo presionado el botón de dictado: empezar a capturar audio. */
    fun onStartCapture()

    /** El usuario soltó el botón: terminar la captura y transcribir. */
    fun onFinishCapture()

    /** El usuario canceló deslizando hacia abajo (o el sistema canceló el gesto): descartar el audio. */
    fun onCancelCapture()

    /** Texto listo para insertarse en el campo con foco (teclado QWERTY o chip de resultado). */
    fun onCommit(text: String)

    /** Borrado hacia atrás pedido desde el panel QWERTY. */
    fun onDelete()

    /** Enter del panel QWERTY: acción del editor (IME_ACTION_*) según el campo. */
    fun onEditorAction()
}
