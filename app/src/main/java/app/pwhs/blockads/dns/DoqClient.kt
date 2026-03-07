package app.pwhs.blockads.dns

import android.content.Context
import com.google.android.gms.net.CronetProviderInstaller
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * DNS-over-QUIC (DoQ) client implementation using Cronet's HTTP/3 (QUIC) transport.
 *
 * Uses Google's Cronet engine (via Play Services) which provides native QUIC/HTTP3 support.
 * When the user specifies a `quic://` DNS server, this client:
 * 1. Converts the URL to an HTTPS DoH endpoint
 * 2. Forces HTTP/3 (QUIC) as the transport protocol
 * 3. Sends standard DNS-over-HTTPS requests over QUIC
 *
 * This gives the performance benefits of QUIC (0-RTT, multiplexing, reduced latency)
 * while maintaining compatibility with standard DoH server infrastructure.
 *
 * Example: quic://dns.adguard-dns.com/dns-query → uses HTTP/3 to https://dns.adguard-dns.com/dns-query
 */
class DoqClient(context: Context) {

    companion object {
        private const val DNS_MESSAGE_CONTENT_TYPE = "application/dns-message"
        private const val QUERY_TIMEOUT_MS = 5000L
    }

    private val executor: Executor = Executors.newSingleThreadExecutor()

    private val cronetEngine: CronetEngine? = try {
        // Try to install Cronet from Play Services first (lightweight, ~100KB)
        CronetProviderInstaller.installProvider(context)
        CronetEngine.Builder(context)
            .enableQuic(true)
            .enableHttp2(true)
            .enableBrotli(true)
            .setStoragePath(context.cacheDir.absolutePath)
            .build()
            .also { Timber.d("Cronet engine initialized with QUIC support") }
    } catch (e: Exception) {
        Timber.e(e, "Failed to initialize Cronet engine — DoQ will not be available")
        null
    }

    /**
     * Check if the QUIC/HTTP3 engine is available
     */
    fun isAvailable(): Boolean = cronetEngine != null

    /**
     * Perform a DNS query over QUIC (HTTP/3)
     * @param dohUrl The DoH server URL (e.g., https://dns.google/dns-query)
     * @param dnsPayload The raw DNS query packet
     * @return The DNS response packet or null if failed
     */
    suspend fun query(dohUrl: String, dnsPayload: ByteArray): ByteArray? {
        val engine = cronetEngine ?: run {
            Timber.w("Cronet engine not available, cannot perform DoQ query")
            return null
        }

        return try {
            withTimeout(QUERY_TIMEOUT_MS) {
                performDoqQuery(engine, dohUrl, dnsPayload)
            }
        } catch (e: Exception) {
            Timber.e(e, "DoQ query failed for $dohUrl")
            null
        }
    }

    private suspend fun performDoqQuery(
        engine: CronetEngine,
        dohUrl: String,
        dnsPayload: ByteArray
    ): ByteArray? = suspendCancellableCoroutine { continuation ->
        val responseBody = ByteArrayOutputStream()

        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(
                request: UrlRequest,
                info: UrlResponseInfo,
                newLocationUrl: String
            ) {
                request.followRedirect()
            }

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                val httpCode = info.httpStatusCode
                val protocol = info.negotiatedProtocol
                Timber.d("DoQ response started: HTTP $httpCode, protocol: $protocol")

                if (httpCode != 200) {
                    Timber.w("DoQ query returned non-200 status: $httpCode")
                    if (continuation.isActive) continuation.resume(null)
                    return
                }

                // Start reading the response body
                request.read(ByteBuffer.allocateDirect(32 * 1024))
            }

            override fun onReadCompleted(
                request: UrlRequest,
                info: UrlResponseInfo,
                byteBuffer: ByteBuffer
            ) {
                // Copy data from buffer to response stream
                byteBuffer.flip()
                val bytes = ByteArray(byteBuffer.remaining())
                byteBuffer.get(bytes)
                responseBody.write(bytes)

                // Continue reading
                byteBuffer.clear()
                request.read(byteBuffer)
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                val result = responseBody.toByteArray()
                val protocol = info.negotiatedProtocol
                Timber.d("DoQ query succeeded: ${result.size} bytes via $protocol")
                if (continuation.isActive) continuation.resume(result)
            }

            override fun onFailed(
                request: UrlRequest,
                info: UrlResponseInfo?,
                error: CronetException
            ) {
                Timber.e(error, "DoQ query failed")
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                Timber.d("DoQ query cancelled")
                if (continuation.isActive) continuation.resume(null)
            }
        }

        val requestBuilder = engine.newUrlRequestBuilder(dohUrl, callback, executor)
            .setHttpMethod("POST")
            .addHeader("Content-Type", DNS_MESSAGE_CONTENT_TYPE)
            .addHeader("Accept", DNS_MESSAGE_CONTENT_TYPE)
            .setUploadDataProvider(
                org.chromium.net.UploadDataProviders.create(dnsPayload),
                executor
            )

        val request = requestBuilder.build()

        continuation.invokeOnCancellation {
            request.cancel()
        }

        request.start()
    }

    /**
     * Clean up resources
     */
    fun shutdown() {
        try {
            cronetEngine?.shutdown()
        } catch (e: Exception) {
            Timber.w(e, "Error shutting down Cronet engine")
        }
    }
}
