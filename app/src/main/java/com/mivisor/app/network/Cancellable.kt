package com.mivisor.app.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/**
 * Las lecturas de sockets son bloqueantes y no reaccionan a la cancelación de corrutinas.
 * Este helper cierra el recurso en cuanto se cancela el ámbito, desbloqueando la lectura.
 * Imprescindible para que la "carrera" entre servidores DNS no espere al perdedor.
 */
internal inline fun <T> CoroutineScope.closeOnCancel(noinline close: () -> Unit, block: () -> T): T {
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            runCatching(close)
        }
    }
    try {
        return block()
    } finally {
        watcher.cancel()
        runCatching(close)
    }
}
