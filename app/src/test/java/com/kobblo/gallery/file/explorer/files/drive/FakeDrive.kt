package com.kobblo.gallery.file.explorer.files.drive

import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.google.api.services.drive.Drive
import java.net.URLDecoder

/** Google Drive's side of the conversation: it answers what [answer] says and remembers what it was asked. */
internal class FakeDrive(private val answer: (Request) -> Answer) {
    class Request(val method: String, val url: String, val body: String) {
        val path: String get() = url.substringAfter("googleapis.com").substringBefore('?')

        fun argument(name: String): String? = url.substringAfter('?', "").split('&')
            .firstOrNull { it.startsWith("$name=") }
            ?.let { URLDecoder.decode(it.substringAfter('='), "UTF-8") }

        val wantsBytes: Boolean get() = argument("alt") == "media"
    }

    class Answer(val body: String = "{}", val status: Int = 200, val location: String? = null)

    val requests = ArrayList<Request>()

    /** What was asked, as "GET /drive/v3/files/1". */
    val asked: List<String> get() = requests.map { "${it.method} ${it.path}" }

    val drive: Drive = Drive.Builder(Transport(), GsonFactory.getDefaultInstance(), null).setApplicationName("Test").build()

    private inner class Transport : MockHttpTransport() {
        override fun buildRequest(method: String, url: String): LowLevelHttpRequest = object : MockLowLevelHttpRequest(url) {
            override fun execute(): LowLevelHttpResponse {
                val request = Request(method, url, contentAsString)
                requests += request
                val said = answer(request)
                return MockLowLevelHttpResponse().apply {
                    statusCode = said.status
                    contentType = "application/json"
                    setContent(said.body)
                    said.location?.let { addHeader("Location", it) }
                }
            }
        }
    }

    companion object {
        val refused = Answer("""{"error": {"code": 403, "message": "Not allowed"}}""", status = 403)

        /** Where the bytes of an upload go, once Drive has been told that one is coming. */
        const val UploadTo = "https://www.googleapis.com/upload/here"

        fun isUploadStart(request: Request) = request.argument("uploadType") == "resumable"
    }
}
