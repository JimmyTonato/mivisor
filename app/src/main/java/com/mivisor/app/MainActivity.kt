package com.mivisor.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mivisor.app.diagnostics.CrashReporter
import com.mivisor.app.ui.MiVisorApp

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter.install(applicationContext)
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && CrashReporter.takeUnseenCrash(this)) {
            Toast.makeText(
                this,
                "MiVisor se cerró inesperadamente la última vez. Puedes ver el error en Ajustes → Registro de errores.",
                Toast.LENGTH_LONG
            ).show()
        }
        enterImmersiveMode()
        if (savedInstanceState == null) handleDeepLink(intent)
        setContent { MiVisorApp(vm) }
    }

    /**
     * Red de seguridad para el mando: si la búsqueda de foco de Compose falla al pulsar una flecha
     * (fallo conocido de foco en filas/listas perezosas), no cerramos la app. Se guarda el error con su
     * traza en el Registro de errores para poder corregirlo, y se ignora esa pulsación.
     * Solo se capturan errores de ejecución durante el reparto de teclas; nada más.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = try {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            CrashReporter.breadcrumb("Tecla ${KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")}")
        }
        super.dispatchKeyEvent(event)
    } catch (e: IllegalStateException) {
        recoverKey(event, e)
    } catch (e: IllegalArgumentException) {
        recoverKey(event, e)
    } catch (e: IndexOutOfBoundsException) {
        recoverKey(event, e)
    } catch (e: NullPointerException) {
        recoverKey(event, e)
    }

    private fun recoverKey(event: KeyEvent, e: RuntimeException): Boolean {
        CrashReporter.nonFatal(applicationContext, "tecla ${KeyEvent.keyCodeToString(event.keyCode)}", e)
        return true
    }

    /** Android vuelve a mostrar las barras tras diálogos, teclado o al volver a la app. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    /**
     * Pantalla completa en móvil y tablet: sin barra de estado (hora, batería, notificaciones)
     * ni barra de navegación. Aparecen temporalmente al deslizar desde el borde.
     */
    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** launchMode=singleTask: un segundo deep link llega aquí en vez de abrir otra instancia. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        val scheme = uri.scheme?.lowercase() ?: return
        if (scheme != "mylinkspaste" && scheme != "mivisor") return
        val code = sequenceOf(uri.host, uri.pathSegments?.firstOrNull(), uri.schemeSpecificPart)
            .filterNotNull()
            .map { it.trim('/', ' ') }
            .firstOrNull { it.isNotBlank() }
            ?: return
        vm.openCode(code)
    }
}
