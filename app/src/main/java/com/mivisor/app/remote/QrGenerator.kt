package com.mivisor.app.remote

import io.nayuki.qrcodegen.QrCode

/** QR generado en el propio dispositivo (Nayuki QR Code generator, MIT). Nada sale a Internet. */
class QrMatrix(val size: Int, private val modules: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]
}

object QrGenerator {
    /** Corrección media (M): legible desde el sofá y tolerante a reflejos de la pantalla. */
    fun encode(text: String): QrMatrix {
        val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        val n = qr.size
        val modules = BooleanArray(n * n) { i -> qr.getModule(i % n, i / n) }
        return QrMatrix(n, modules)
    }
}
