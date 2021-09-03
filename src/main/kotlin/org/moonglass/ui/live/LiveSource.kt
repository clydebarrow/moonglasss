package org.moonglass.ui.live

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.features.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.WebSocketSession
import io.ktor.utils.io.core.readBytes
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.khronos.webgl.Uint8Array
import org.moonglass.ui.Duration90k
import org.moonglass.ui.Time90k
import org.moonglass.ui.widgets.Toast
import org.w3c.dom.mediasource.AppendMode
import org.w3c.dom.mediasource.MediaSource
import org.w3c.dom.mediasource.OPEN
import org.w3c.dom.mediasource.ReadyState
import org.w3c.dom.mediasource.SEGMENTS
import org.w3c.dom.mediasource.SourceBuffer
import org.w3c.dom.url.URL
import kotlin.collections.set


/**
 * A class that receives data from a websocket and streams it into a mediasource.
 * @param wsUrl The websocket url
 *
 */
class LiveSource(private val wsUrl: String, val title: String) {


    /**
     * the aspect ratio of the received video. Updated once the stream starts
     */
    var aspectRatio: Double = 16.0 / 9.0
        private set

    /**
     * The mediasource that buffers the stream
     */
    private val mediaSource = MediaSource().apply {
        // attach event listeners
        onsourceended = {
            console.log("MediaSource ended: $it")
            close()
        }
        onsourceclose = {
            console.log("MediaSource closed: $it")
            close()
        }
        onsourceopen = { start() }
    }

    /**
     * A source url suitable for assignment to a video element's src property
     */
    val srcUrl = URL.createObjectURL(mediaSource)

    private lateinit var srcBuffer: SourceBuffer

    /**
     * The coroutine scope used to run tasks.
     */
    private val scope = MainScope()


    /**
     * A buffer queue to be sent to the media source
     */

    private val bufferQueue = ArrayDeque<DataHeaders>()

    /**
     * The client we will use for the websocket and other network fetches
     */

    private val client = HttpClient(Js) {
        install(WebSockets) { }
    }

    /**
     * Fetch the initialization segment for a stream
     */

    private suspend fun getInitializationSegment(id: Int): ByteArray {
        val response: HttpResponse = client.get {
            url {
                // for some reason the defaults set by URLBuilder don't include the port
                window.location.port.toIntOrNull()?.let { port = it }
                path("api", "init", "$id.mp4")
            }
        }
        // if there is an X-Aspect header, parse it in the form 16:9, convert this to 16/9
        response.headers["X-Aspect"]?.let { headerText ->
            headerText.split(':').map { it.toIntOrNull() }.let {
                it[0]?.toDouble()?.div(it[1]?.toDouble() ?: 1.0)
            }
        }?.let { aspectRatio = it }
        val data = response.content.readRemaining(100000, 0).readBytes()
        return data
    }

    private fun transfer() {
        if (!srcBuffer.updating)
            bufferQueue.removeFirstOrNull()?.let { data ->
                val ranges = srcBuffer.buffered
                val length = ranges.length
                srcBuffer.timestampOffset = if (length == 0) 0.0 else ranges.end(length - 1)
                srcBuffer.appendBuffer(data.data)
            }
    }

    private suspend fun WebSocketSession.setup() {
        val message = incoming.receive() as Frame.Binary
        val buffer = ByteBuffer(message.data)
        val data = buffer.parseBuffer()
        //console.log(data.toString())
        if (!MediaSource.isTypeSupported(data.contentType)) {
            Toast.toast("Media type ${data.contentType} not supported")
            throw IllegalArgumentException("Media type not supported")
        }
        srcBuffer = mediaSource.addSourceBuffer(data.contentType)
        srcBuffer.mode = AppendMode.SEGMENTS
        srcBuffer.onerror = {
            console.log("Mediasource error: $it")
        }
        srcBuffer.onabort = {
            console.log("Mediasource abort: $it")
        }
        srcBuffer.onupdateend = {
            transfer()
        }
        bufferQueue.add(data)
        srcBuffer.appendBuffer(getInitializationSegment(data.sampleId))     // starts the transfer callbacks
    }

    /**
     * open the websocket and start streaming data
     */
    private fun start() {
        scope.launch {
            client.webSocket(wsUrl) {
                try {
                    setup()
                    while (mediaSource.readyState == ReadyState.OPEN) {
                        val message = incoming.receive() as Frame.Binary
                        val data = ByteBuffer(message.data).parseBuffer()
                        if (bufferQueue.size < MAX_BUFFER) {
                            bufferQueue.add(data)
                            transfer()
                        } else
                            console.log("Discarded buffer $data")
                    }
                } catch (ex: Exception) {
                    console.log("$ex")
                }
                close()
            }
        }
    }


    /**
     * End the streaming.
     */
    fun close() {
        try {
            client.close()
            scope.cancel()
            console.log("Closing LiveSource, mediaState = ${mediaSource.readyState}")
            if (mediaSource.readyState == ReadyState.OPEN)
                mediaSource.endOfStream()
            bufferQueue.clear()
        } catch(ex: Exception) {
            console.log("Exception in close: $ex")
        }
    }

    private class ByteBuffer(val data: ByteArray) {
        var offset = 0
        val size = data.size
        val balance get() = Uint8Array(data.drop(offset).toTypedArray())

        fun indexOf(c: Char): Int {
            var i = offset
            while (i != size && data[i].toInt() != c.code)
                i++
            if (i == size)
                return -1
            return i - offset
        }

        fun getLine(): String {
            val index = indexOf('\n')
            if (index < 1 || data[offset + index - 1].toInt() != '\r'.code)
                throw IllegalArgumentException("No well-formed line found")
            val line = data.drop(offset).take(index - 1)
            offset += index + 1
            return line.map { it.toInt().toChar() }.toCharArray().concatToString()
        }

        fun getHeaders(): Map<String, String> {
            val map = mutableMapOf<String, String>()
            while (true) {
                val line = getLine()
                if (line.isEmpty()) {
                    return map
                }
                val parts = line.split(':').map { it.trim() }
                if (parts.size == 2)
                    map[parts[0]] = parts[1]
            }
        }
    }

    private data class DataHeaders(
        val contentType: String,
        val openId: Int,
        val recordingId: Int,
        val recordingStart: Time90k,
        val prevDuration: Duration90k,
        val rangeStart: Duration90k,
        val rangeEnd: Duration90k,
        val sampleId: Int,
        val data: Uint8Array
    ) {
        override fun toString(): String {
            return "DataHeaders(contentType='$contentType', openId=$openId, recordingId=$recordingId, recordingStart=$recordingStart, prevDuration=$prevDuration, rangeStart=$rangeStart, rangeEnd=$rangeEnd, sampleId=$sampleId)"
        }
    }

    private fun ByteBuffer.parseBuffer(): DataHeaders {
        val headers = getHeaders()
        val ids = headers["X-Recording-Id"]?.split('.')?.map { it.toIntOrNull() }
        val range = headers["X-Media-Time-Range"]?.split('-')?.map { it.toLongOrNull() }
        return DataHeaders(
            headers["Content-Type"] ?: "",
            ids?.getOrNull(0) ?: -1,
            ids?.getOrNull(1) ?: -1,
            headers["X-Recording-Start"]?.toLongOrNull() ?: 0,
            headers["X-Prev-Media-Duration"]?.toLongOrNull() ?: 0,
            range?.getOrNull(0) ?: -1,
            range?.getOrNull(1) ?: -1,
            headers["X-Video-Sample-Entry-Id"]?.toIntOrNull() ?: -1,
            balance
        )
    }

    companion object {
        const val MAX_BUFFER = 5
    }
}
