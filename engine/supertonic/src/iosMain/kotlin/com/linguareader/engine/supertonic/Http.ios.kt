package com.linguareader.engine.supertonic

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import okio.IOException
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * `NSURLSession` en streaming: los bloques llegan por el delegado y se entregan a [HttpResponse.read] a través
 * de un canal (el consumidor los escribe en disco a medida que llegan).
 * No sigue redirecciones (el descargador las gestiona). Pendiente: sesión en segundo plano para que una
 * descarga de cientos de MB sobreviva a la suspensión de la app.
 */
actual fun platformHttpTransport(connectTimeoutMs: Int, readTimeoutMs: Int): HttpTransport = NsUrlSessionTransport(readTimeoutMs)

@OptIn(ExperimentalForeignApi::class)
private class NsUrlSessionTransport(private val readTimeoutMs: Int) : HttpTransport {

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        val nsUrl = NSURL(string = url)
        val request = NSMutableURLRequest(uRL = nsUrl).apply {
            setHTTPMethod("GET")
            setTimeoutInterval(readTimeoutMs / 1000.0)
            headers.forEach { (k, v) -> setValue(v, forHTTPHeaderField = k) }
        }
        val delegate = SessionDelegate()
        val session = NSURLSession.sessionWithConfiguration(
            NSURLSessionConfiguration.ephemeralSessionConfiguration(),
            delegate = delegate,
            delegateQueue = null,
        )
        val task = session.dataTaskWithRequest(request)
        task.resume()
        try {
            delegate.headersReady.await()
        } catch (e: Throwable) {
            session.finishTasksAndInvalidate()
            throw e
        }
        return StreamingResponse(delegate, session, task)
    }
}

/** Recibe cabeceras y bloques del cuerpo; el cuerpo se encola para [StreamingResponse.read]. */
@OptIn(ExperimentalForeignApi::class)
private class SessionDelegate : NSObject(), NSURLSessionDataDelegateProtocol {
    // Sin límite: un bloqueo del hilo del delegado (contrapresión) puede retener la salida del proceso.
    val chunks = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    val headersReady = CompletableDeferred<Unit>()
    var code: Int = 0
    var headers: Map<String, String> = emptyMap()

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (NSURLSessionResponseDisposition) -> Unit,
    ) {
        (didReceiveResponse as? NSHTTPURLResponse)?.let { http ->
            code = http.statusCode.toInt()
            headers = http.allHeaderFields.entries.associate { it.key.toString().lowercase() to it.value.toString() }
        }
        headersReady.complete(Unit)
        completionHandler(NSURLSessionResponseAllow)
    }

    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
        val bytes = didReceiveData.toByteArray()
        if (bytes.isNotEmpty()) chunks.trySend(bytes)
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        if (didCompleteWithError != null) {
            val error = IOException(didCompleteWithError.localizedDescription)
            chunks.close(error)
            headersReady.completeExceptionally(error)
        } else {
            chunks.close()
            headersReady.complete(Unit)
        }
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit,
    ) {
        // Las redirecciones las sigue el descargador (conserva Range y descarta cabeceras al cambiar de host).
        completionHandler(null)
    }
}

private class StreamingResponse(
    private val delegate: SessionDelegate,
    private val session: NSURLSession,
    private val task: NSURLSessionDataTask,
) : HttpResponse {
    private var pending: ByteArray? = null
    private var pendingOffset = 0

    override val code: Int get() = delegate.code

    override fun header(name: String): String? = delegate.headers[name.lowercase()]

    override suspend fun read(buffer: ByteArray): Int {
        var current = pending
        if (current == null || pendingOffset >= current.size) {
            val next = delegate.chunks.receiveCatching()
            if (next.isClosed) {
                next.exceptionOrNull()?.let { throw it }
                return -1
            }
            current = next.getOrThrow()
            pending = current
            pendingOffset = 0
        }
        val n = minOf(buffer.size, current.size - pendingOffset)
        current.copyInto(buffer, 0, pendingOffset, pendingOffset + n)
        pendingOffset += n
        return n
    }

    override fun close() {
        task.cancel()
        session.finishTasksAndInvalidate()
        delegate.chunks.close()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    if (n == 0) return ByteArray(0)
    val out = ByteArray(n)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
