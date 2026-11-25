package com.kobblo.gallery.file.explorer.localsend

import android.content.Context
import android.os.Build
import com.kobblo.gallery.file.explorer.transfer.core.DeviceName

/**
 * What this phone tells other devices about itself, wherever it's asked: in announcements, in register calls,
 * and at the head of everything it offers to send.
 *
 * @param port where its server is listening. That's only known once the server has started, and is the
 *   usual port until then, which is also the right thing to say while nothing is listening at all.
 */
internal class OwnDevice(
    context: Context,
    @Volatile var port: Int = LocalSendServer.DEFAULT_HTTP_PORT,
    private val protocol: String = LocalSendWire.HTTPS
) {
    private val appContext = context.applicationContext

    val fingerprint: String get() = LocalSendSecurity.ownFingerprint(appContext)

    /** Read afresh each time, because the name can be changed in Settings while the service runs. */
    fun describe(): DeviceDto = DeviceDto(
        alias = DeviceName.current(appContext),
        version = LocalSendWire.VERSION,
        deviceModel = Build.MODEL,
        deviceType = LocalSendWire.MOBILE,
        fingerprint = fingerprint,
        port = port,
        protocol = protocol,
        download = false,
        announce = true
    )
}
