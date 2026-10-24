package com.mivisor.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.mivisor.app.ui.MiVisorApp

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleDeepLink(intent)
        setContent { MiVisorApp(vm) }
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
