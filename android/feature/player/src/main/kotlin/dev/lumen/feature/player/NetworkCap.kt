package dev.lumen.feature.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Connexion active en Wi-Fi (pas en Ethernet) : le plafond « en Wi-Fi » s'applique. */
internal fun Context.onWifi(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = runCatching { manager.getNetworkCapabilities(manager.activeNetwork) }.getOrNull() ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}

/** Plus petite des hauteurs non nulles ; 0 si aucune ne limite. */
internal fun qualityCap(vararg heights: Int): Int = heights.filter { it > 0 }.minOrNull() ?: 0
