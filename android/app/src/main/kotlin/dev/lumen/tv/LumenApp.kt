package dev.lumen.tv

import android.app.Application

class LumenApp : Application() {
    /** Créé à la première utilisation, hors du thread principal (chargement du .so via JNA). */
    val coreGateway: CoreGateway by lazy { RustCoreGateway(this) }
}
