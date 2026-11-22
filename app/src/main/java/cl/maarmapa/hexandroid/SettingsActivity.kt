package cl.maarmapa.hexandroid

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button

/** Pantalla para habilitar Hex como teclado y conceder el micrófono. */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = Button(this).apply {
            text = "1) Activa Hex como teclado   2) Concede el micrófono"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        }
        setContentView(b)
    }
}
