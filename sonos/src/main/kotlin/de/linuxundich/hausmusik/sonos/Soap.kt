// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later
// Sonos handling based on noson (libnoson) by Jean-Luc Barrière, GPL-3.0-or-later; see NOTICE.md.

package de.linuxundich.hausmusik.sonos

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A SOAP fault from a player, with the UPnP error code if it sent one. */
class SoapFault(val action: String, val upnpErrorCode: Int?, message: String) :
    IOException("$action failed: $message${upnpErrorCode?.let { " (UPnP $it)" } ?: ""}")

/**
 * Sends SOAP actions to one player. Arguments keep their order, because Sonos
 * rejects some actions whose arguments arrive in a different order.
 */
class SoapClient(private val http: OkHttpClient) {

    suspend fun call(
        baseUrl: HttpUrl,
        service: SonosService,
        action: String,
        args: List<Pair<String, Any>> = emptyList(),
    ): Map<String, String> {
        val body = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """)
            append("""s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""")
            append("""<u:$action xmlns:u="${service.urn}">""")
            for ((k, v) in args) append("<$k>").append(v.toString().xmlEscape()).append("</$k>")
            append("</u:$action></s:Body></s:Envelope>")
        }
        val request = Request.Builder()
            .url(baseUrl.resolve(service.controlPath)!!)
            .header("SOAPACTION", "\"${service.urn}#$action\"")
            .post(body.toRequestBody(XML))
            .build()
        // Reading the body blocks, so the whole exchange stays off the caller's
        // thread (the UI calls this from the main dispatcher).
        return withContext(Dispatchers.IO) {
            val text = http.newCall(request).await().use { response ->
                val t = response.body.string()
                if (!response.isSuccessful) throw fault(action, t, response.code)
                t
            }
            parseResponse(action, text)
        }
    }

    companion object {
        private val XML = "text/xml; charset=\"utf-8\"".toMediaType()

        internal fun parseResponse(action: String, text: String): Map<String, String> {
            val root = Xml.parse(text)
            val response = root.find("${action}Response") ?: throw SoapFault(action, null, "no response element")
            return response.children().associate { it.localName0 to it.textContent }
        }

        private fun fault(action: String, text: String, httpCode: Int): SoapFault {
            val code = runCatching { Xml.parse(text).find("errorCode")?.textContent?.trim()?.toInt() }.getOrNull()
            return SoapFault(action, code, "HTTP $httpCode")
        }
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
    })
}
