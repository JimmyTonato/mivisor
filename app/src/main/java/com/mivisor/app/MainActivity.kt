package com.mivisor.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mivisor.app.ui.MiVisorApp

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()
        if (savedInstanceState == null) handleDeepLink(intent)
        setContent { MiVisorApp(vm) }
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
