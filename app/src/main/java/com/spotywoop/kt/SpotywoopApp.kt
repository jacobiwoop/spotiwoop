package com.spotywoop.kt

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import com.spotywoop.kt.data.AppLog

/** Démarre le journal avant tout le reste, et y note la vie de l'appli et du réseau. */
class SpotywoopApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        Log.i(TAG, "Démarrage du processus")

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, saved: Bundle?) { Log.i(TAG, "${a.localClassName} créée (restaurée=${saved != null})") }
            override fun onActivityStarted(a: Activity) { Log.i(TAG, "${a.localClassName} visible") }
            override fun onActivityResumed(a: Activity) { Log.i(TAG, "${a.localClassName} au premier plan") }
            override fun onActivityPaused(a: Activity) { Log.i(TAG, "${a.localClassName} en pause") }
            override fun onActivityStopped(a: Activity) { Log.i(TAG, "${a.localClassName} en arrière-plan") }
            override fun onActivitySaveInstanceState(a: Activity, out: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) { Log.i(TAG, "${a.localClassName} détruite") }
        })

        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var last = ""

            override fun onAvailable(network: Network) { Log.i(TAG, "Réseau disponible") }
            override fun onLost(network: Network) { Log.w(TAG, "Réseau perdu") }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val kind = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "données mobiles"
                    else -> "autre"
                }
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                // Le débit change sans arrêt : on ne note que le type de réseau et son accès à internet.
                val now = "$kind, internet validé=$validated"
                if (now != last) Log.i(TAG, "Réseau : $now, ↓${caps.linkDownstreamBandwidthKbps} kb/s")
                last = now
            }
        })
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) Log.w(TAG, "Mémoire faible (niveau $level)")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "Mémoire très faible")
    }

    private companion object {
        const val TAG = "App"
    }
}
