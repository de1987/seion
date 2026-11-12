package org.seion.art

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** The network as the online cover lookup sees it. */
data class NetworkState(
    val connected: Boolean,
    /** NET_CAPABILITY_NOT_METERED (Wi-Fi, Ethernet, or an unmetered tether): what "Wi-Fi only" requires. */
    val unmetered: Boolean
)

/** Why a lookup may or may not go online (pure, unit-tested). */
enum class OnlineDecision { ALLOWED, DISABLED, OFFLINE, METERED }

object OnlineGate {
    fun decide(onlineEnabled: Boolean, wifiOnly: Boolean, network: NetworkState): OnlineDecision = when {
        !onlineEnabled -> OnlineDecision.DISABLED
        !network.connected -> OnlineDecision.OFFLINE
        wifiOnly && !network.unmetered -> OnlineDecision.METERED
        else -> OnlineDecision.ALLOWED
    }
}

/**
 * Each time the gate opens again after it was closed (offline -> online, mobile data -> Wi-Fi with "Wi-Fi only"):
 * the moment to ask again the lookups skipped meanwhile. The first value only sets the starting point.
 */
fun Flow<Boolean>.openings(): Flow<Unit> = distinctUntilChanged().drop(1).filter { it }.map { }

fun interface NetworkStateProvider {
    fun current(): NetworkState
}

class SystemNetworkStateProvider(private val context: Context) : NetworkStateProvider {
    override fun current(): NetworkState {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkState(false, false)
        val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities) ?: return NetworkState(false, false)
        val connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return NetworkState(connected, unmetered)
    }
}
