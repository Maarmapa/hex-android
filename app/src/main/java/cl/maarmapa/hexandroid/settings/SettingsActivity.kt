package cl.maarmapa.hexandroid.settings

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import cl.maarmapa.hexandroid.history.HistoryStore

/**
 * Pantalla de ajustes real de Hex para Android (espejo de los Settings de Hex):
 *  1) Habilitar Hex como teclado del sistema (ajustes de método de entrada).
 *  2) Permiso de micrófono, con explicación previa antes de pedirlo.
 *  3) Reescritura opcional, APAGADA por defecto, con su clave guardada en
 *     SharedPreferences (la clave nunca se loguea ni se imprime).
 *  4) Borrar todo el historial local de dictado (solo texto; el audio nunca se guarda).
 */
class SettingsActivity : Activity() {

    private companion object {
        const val CODIGO_PERMISO_MICROFONO = 1001
    }

    private lateinit var estadoMicrofono: TextView
    private lateinit var estadoClave: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(construirVista())
        refrescarEstado()
    }

    override fun onResume() {
        super.onResume()
        refrescarEstado()
    }

    // ------------------------------------------------------------------ interfaz

    private fun construirVista(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        fun titulo(texto: String) = TextView(this).apply {
            this.text = texto
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, Typeface.BOLD)
        }

        fun texto(texto: String) = TextView(this).apply {
            this.text = texto
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(6), 0, dp(6))
        }

        fun boton(texto: String, accion: () -> Unit) = Button(this).apply {
            this.text = texto
            setOnClickListener { accion() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        raiz.apply {
            addView(titulo("Hex — ajustes"))
            addView(texto("Dictado local: la transcripción ocurre en el dispositivo y el audio nunca sale de él."))

            // 1) Habilitar Hex como teclado
            addView(titulo("Teclado"))
            addView(boton("Activar Hex como teclado") {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            })

            // 2) Permiso de micrófono con explicación previa
            addView(titulo("Micrófono"))
            estadoMicrofono = texto("")
            addView(estadoMicrofono)
            addView(boton("Conceder micrófono") { pedirMicrofono() })

            // 3) Reescritura opcional (apagada por defecto) con su clave BYOK
            addView(titulo("Reescritura (opcional)"))
            addView(texto("Apagada por defecto. Si la activas, solo se envía el texto, nunca el audio. La clave se guarda en este dispositivo y no se muestra nunca."))
            val interruptor = Switch(this@SettingsActivity).apply {
                text = "Activar reescritura"
                isChecked = RewriteSettings.reescrituraActivada(this@SettingsActivity)
                setOnCheckedChangeListener { _, activado ->
                    RewriteSettings.setReescrituraActivada(this@SettingsActivity, activado)
                }
            }
            addView(interruptor)
            val campoClave = EditText(this@SettingsActivity).apply {
                hint = "Clave del proveedor (opcional)"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                // Nunca se sugiere ni se registra en el historial de sugerencias del teclado.
                isSaveEnabled = false
            }
            addView(campoClave)
            estadoClave = texto("")
            addView(estadoClave)
            addView(boton("Guardar clave") {
                val clave = campoClave.text.toString()
                if (clave.isBlank()) {
                    RewriteSettings.borrarClaveApi(this@SettingsActivity)
                } else {
                    RewriteSettings.setClaveApi(this@SettingsActivity, clave)
                }
                // La clave no se muestra ni se loguea: solo un resumen de longitud.
                estadoClave.text = RewriteSettings.resumenClave(this@SettingsActivity)
                campoClave.setText("")
                Toast.makeText(
                    this@SettingsActivity, "Preferencias guardadas en el dispositivo", Toast.LENGTH_SHORT
                ).show()
            })

            // 4) Borrar historial
            addView(titulo("Historial"))
            addView(texto("El historial guarda solo texto (no audio) y se purga a los 7 días, con un tope de 10.000 entradas."))
            addView(boton("Borrar historial") { confirmarBorrarHistorial() })
        }
        return raiz
    }

    private fun dp(valor: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, valor.toFloat(), resources.displayMetrics
    ).toInt()

    // ------------------------------------------------------------------ micrófono

    private fun tieneMicrofono(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun pedirMicrofono() {
        if (tieneMicrofono()) {
            refrescarEstado()
            return
        }
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
            AlertDialog.Builder(this)
                .setTitle("Se necesita el micrófono")
                .setMessage("Hex transcribe tu voz en el propio dispositivo para dictar en cualquier campo de texto. Sin el permiso de micrófono, el dictado no funciona.")
                .setPositiveButton("Continuar") { _, _ -> solicitarPermiso() }
                .setNegativeButton("Ahora no", null)
                .show()
        } else {
            solicitarPermiso()
        }
    }

    private fun solicitarPermiso() {
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.RECORD_AUDIO), CODIGO_PERMISO_MICROFONO
        )
    }

    override fun onRequestPermissionsResult(
        codigo: Int, permisos: Array<out String>, resultados: IntArray
    ) {
        super.onRequestPermissionsResult(codigo, permisos, resultados)
        if (codigo == CODIGO_PERMISO_MICROFONO) refrescarEstado()
    }

    // ------------------------------------------------------------------ historial

    private fun confirmarBorrarHistorial() {
        AlertDialog.Builder(this)
            .setTitle("Borrar historial")
            .setMessage("Se eliminarán todas las entradas de dictado guardadas en este dispositivo. Esta acción no se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ ->
                Thread {
                    val borradas = HistoryStore.borrarTodoBloqueante(this)
                    runOnUiThread {
                        Toast.makeText(
                            this, "Historial borrado ($borradas entradas)", Toast.LENGTH_SHORT
                        ).show()
                        refrescarEstado()
                    }
                }.start()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------ estado

    private fun refrescarEstado() {
        estadoMicrofono.text = if (tieneMicrofono()) {
            "Micrófono concedido. Listo para dictar."
        } else {
            "Sin permiso de micrófono: el dictado necesita el permiso."
        }
        estadoClave.text = RewriteSettings.resumenClave(this)
    }
}
