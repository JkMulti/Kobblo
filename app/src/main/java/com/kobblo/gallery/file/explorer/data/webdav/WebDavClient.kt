package com.kobblo.gallery.file.explorer.data.webdav

import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** One file or folder in a WebDAV listing. */
data class WebDavEntry(
    val name: String,
    /** The address the server gives for it, as the server wrote it. */
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val contentType: String?
)

/** Why a call to a WebDAV server failed, sorted into the cases the app tells apart. */
sealed class WebDavException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthFailed(cause: Throwable? = null) : WebDavException("Incorrect username or password", cause)
    class NotFound(path: String) : WebDavException("Not found: $path")
    class ServerUnreachable(cause: Throwable? = null) : WebDavException("Couldn't reach the server", cause)
    class Unknown(cause: Throwable? = null) : WebDavException(cause?.message ?: "Something went wrong", cause)
}

/**
 * The files of one [WebDavConnection]. Paths are relative to the connection's address, with or without a
 * leading slash. Every call runs off the main thread, and whatever goes wrong in one comes out as a
 * [WebDavException].
 */
class WebDavClient(
    private val connection: WebDavConnection,
    private val password: String
) {
    // Made by the first request, not here, so that failing to set up TLS is reported like any other
    // failed request.
    private val dav: OkHttpSardine by lazy(::openDav)

    /** Asks the server for the connection's own folder. True when it answers; throws when it doesn't. */
    suspend fun testConnection(): Boolean = request {
        dav.list(connection.normalizedUrl)
        true
    }

    /** What is directly inside the folder at [path]. */
    suspend fun list(path: String = ""): List<WebDavEntry> = request {
        // The answer starts with the folder that was asked about, then what is in it.
        dav.list(urlOf(path)).drop(1).map(::entryOf)
    }

    suspend fun download(remotePath: String, destination: File): File = request {
        dav.get(urlOf(remotePath)).use { body ->
            destination.outputStream().use { local -> body.copyTo(local) }
        }
        destination
    }

    suspend fun upload(localFile: File, remotePath: String, mimeType: String = "application/octet-stream") =
        request { dav.put(urlOf(remotePath), localFile, mimeType) }

    suspend fun delete(remotePath: String) = request { dav.delete(urlOf(remotePath)) }

    suspend fun createFolder(remotePath: String) = request { dav.createDirectory(urlOf(remotePath)) }

    suspend fun move(fromPath: String, toPath: String) = request { dav.move(urlOf(fromPath), urlOf(toPath)) }

    private fun urlOf(path: String): String = connection.normalizedUrl + path.trimStart('/')

    private suspend fun <T> request(send: () -> T): T = withContext(Dispatchers.IO) {
        try {
            send()
        } catch (failure: Exception) {
            throw sorted(failure)
        }
    }

    private fun sorted(failure: Exception): WebDavException {
        if (failure is SardineException) return sortedByStatus(failure)
        // Trouble with the connection or a stream, with no answer from the server to go by.
        if (failure is IOException) return WebDavException.ServerUnreachable(failure)
        return WebDavException.Unknown(failure)
    }

    /** For a request the server did answer, only to turn it down. */
    private fun sortedByStatus(refusal: SardineException): WebDavException = when (refusal.statusCode) {
        HttpUnauthorized, HttpForbidden -> WebDavException.AuthFailed(refusal)
        HttpNotFound -> WebDavException.NotFound(refusal.message ?: "")
        else -> WebDavException.Unknown(refusal)
    }

    private fun openDav(): OkHttpSardine {
        val http = OkHttpClient.Builder()
        if (connection.allowSelfSignedCert) http.acceptAnyCertificate()
        return OkHttpSardine(http.build()).apply { setCredentials(connection.username, password) }
    }

    /**
     * Turns off the two checks a server with a certificate it signed itself can't pass: that someone the
     * phone trusts vouches for the certificate, and that it names the host being spoken to. The traffic
     * is still encrypted, but nothing proves who is at the other end, which is why this is asked for per
     * connection.
     */
    private fun OkHttpClient.Builder.acceptAnyCertificate() {
        val anyone = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val tls = SSLContext.getInstance("TLS")
        tls.init(null, arrayOf(anyone), SecureRandom())
        sslSocketFactory(tls.socketFactory, anyone)
        hostnameVerifier { _, _ -> true }
    }

    private fun entryOf(resource: DavResource): WebDavEntry {
        val address = resource.href.toString()
        return WebDavEntry(
            // The library cuts the name out of the address and can come back with nothing; then it's cut here.
            name = resource.name ?: address.trimEnd('/').substringAfterLast('/'),
            path = address,
            isDirectory = resource.isDirectory,
            sizeBytes = resource.contentLength ?: 0L,
            lastModifiedMillis = resource.modified?.time ?: 0L,
            contentType = resource.contentType
        )
    }

    private companion object {
        const val HttpUnauthorized = 401
        const val HttpForbidden = 403
        const val HttpNotFound = 404
    }
}
