package com.kobblo.gallery.file.explorer.transfer.hotspot

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.location.LocationManagerCompat
import com.kobblo.gallery.file.explorer.transfer.core.PairingWifi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface HotspotState {
    data object Off : HotspotState
    data object Starting : HotspotState
    data class Running(val wifi: PairingWifi) : HotspotState
    data class Failed(val reason: Reason) : HotspotState

    enum class Reason {
        /** A regular hotspot or tethering is already on, or the system refuses the combination. */
        Incompatible,
        NoChannel,
        Disallowed,
        LocationOff,
        NeedsPermission,
        /** It started, but the system wouldn't say the network's name and password. */
        NoCredentials,
        Unknown
    }
}

/**
 * The system's local-only hotspot: a Wi-Fi network with no internet that another phone can join, for
 * when two phones have no network in common. It's held here, not by a screen, so it outlives leaving
 * the screen while a transfer runs.
 */
object LocalHotspot {
    private const val TAG = "LocalHotspot"
    private val _state = MutableStateFlow<HotspotState>(HotspotState.Off)
    val state: StateFlow<HotspotState> = _state.asStateFlow()

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    /** The permissions this needs at this API level, in a form a permission launcher takes. */
    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // Below Android 13 the system also refuses unless location itself is switched on.
    fun needsLocationOn(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return false
        val location = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return !LocationManagerCompat.isLocationEnabled(location)
    }

    @SuppressLint("MissingPermission") // The screen asks first, and a refusal is caught below.
    fun start(context: Context) {
        if (_state.value is HotspotState.Running || _state.value is HotspotState.Starting) return
        if (needsLocationOn(context)) {
            _state.value = HotspotState.Failed(HotspotState.Reason.LocationOff)
            return
        }
        _state.value = HotspotState.Starting
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    val credentials = credentialsOf(res)
                    _state.value = if (credentials != null) {
                        HotspotState.Running(credentials)
                    } else {
                        res.close()
                        reservation = null
                        HotspotState.Failed(HotspotState.Reason.NoCredentials)
                    }
                }

                override fun onStopped() {
                    reservation = null
                    _state.value = HotspotState.Off
                }

                override fun onFailed(reason: Int) {
                    Log.w(TAG, "Hotspot failed with reason $reason")
                    reservation = null
                    _state.value = HotspotState.Failed(
                        when (reason) {
                            ERROR_NO_CHANNEL -> HotspotState.Reason.NoChannel
                            ERROR_INCOMPATIBLE_MODE -> HotspotState.Reason.Incompatible
                            ERROR_TETHERING_DISALLOWED -> HotspotState.Reason.Disallowed
                            else -> HotspotState.Reason.Unknown
                        }
                    )
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            Log.w(TAG, "Hotspot refused: ${e.message}")
            _state.value = HotspotState.Failed(HotspotState.Reason.NeedsPermission)
        } catch (e: IllegalStateException) {
            // Thrown when another request from this app is still open.
            Log.w(TAG, "Hotspot busy: ${e.message}")
            _state.value = HotspotState.Failed(HotspotState.Reason.Incompatible)
        }
    }

    fun stop() {
        reservation?.close()
        reservation = null
        _state.value = HotspotState.Off
    }

    fun clearFailure() {
        if (_state.value is HotspotState.Failed) _state.value = HotspotState.Off
    }

    @Suppress("DEPRECATION")
    private fun credentialsOf(res: WifiManager.LocalOnlyHotspotReservation): PairingWifi? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = res.softApConfiguration
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                config.wifiSsid?.toString()?.removeSurrounding("\"")
            } else {
                config.ssid
            } ?: return null
            // Transition mode takes WPA2 as well, so only WPA3 on its own needs the different request.
            val wpa3Only = config.securityType == android.net.wifi.SoftApConfiguration.SECURITY_TYPE_WPA3_SAE
            return PairingWifi(ssid, config.passphrase, wpa3 = wpa3Only)
        }
        val config = res.wifiConfiguration ?: return null
        val ssid = config.SSID?.removeSurrounding("\"") ?: return null
        return PairingWifi(ssid, config.preSharedKey?.removeSurrounding("\""))
    }
}

sealed interface WifiJoinState {
    data object Idle : WifiJoinState
    data class Joining(val ssid: String) : WifiJoinState
    data class Joined(val ssid: String) : WifiJoinState
    /** It didn't work, or can't on this Android version, so joining by hand is the way forward. */
    data class Manual(val wifi: PairingWifi, val failed: Boolean) : WifiJoinState
}

/**
 * Joins the Wi-Fi a pairing code names, without touching the phone's regular Wi-Fi settings. The
 * system asks the person to confirm, then gives this app alone a route onto that network.
 */
object WifiJoiner {
    private const val TAG = "WifiJoiner"
    private const val JOIN_TIMEOUT_MS = 30_000
    private val _state = MutableStateFlow<WifiJoinState>(WifiJoinState.Idle)
    val state: StateFlow<WifiJoinState> = _state.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** Calls [onJoined] once traffic can reach the other phone. */
    fun join(context: Context, wifi: PairingWifi, onJoined: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _state.value = WifiJoinState.Manual(wifi, failed = false)
            return
        }
        leave(context)
        _state.value = WifiJoinState.Joining(wifi.ssid)
        val app = context.applicationContext
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(wifi.ssid)
            .apply {
                wifi.password?.let { if (wifi.wpa3) setWpa3Passphrase(it) else setWpa2Passphrase(it) }
            }
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Without this, sockets keep going out over mobile data, where the other phone isn't.
                connectivity.bindProcessToNetwork(network)
                _state.value = WifiJoinState.Joined(wifi.ssid)
                onJoined()
            }

            override fun onUnavailable() {
                Log.w(TAG, "Couldn't join")
                callback = null
                _state.value = WifiJoinState.Manual(wifi, failed = true)
            }

            override fun onLost(network: Network) {
                connectivity.bindProcessToNetwork(null)
                _state.value = WifiJoinState.Idle
            }
        }
        callback = networkCallback
        try {
            connectivity.requestNetwork(request, networkCallback, JOIN_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Join refused: ${e.message}")
            callback = null
            _state.value = WifiJoinState.Manual(wifi, failed = true)
        }
    }

    fun manualFallback(wifi: PairingWifi) {
        _state.value = WifiJoinState.Manual(wifi, failed = true)
    }

    fun leave(context: Context) {
        val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        connectivity.bindProcessToNetwork(null)
        _state.value = WifiJoinState.Idle
    }
}
