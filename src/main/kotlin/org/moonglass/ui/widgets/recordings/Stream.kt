package org.moonglass.ui.widgets.recordings

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import kotlinx.browser.window
import org.moonglass.ui.Duration90k
import org.moonglass.ui.api.Api
import org.moonglass.ui.api.RecList
import org.moonglass.ui.formatDate
import org.moonglass.ui.formatTime
import org.moonglass.ui.url
import kotlin.js.Date

data class Stream(val name: String, val metaData: Api.ApiStream, var recList: RecList, val camera: Api.Camera) {
    val key = "${camera.uuid}/${name}"
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Stream) return false

        if (key != other.key) return false

        return true
    }

    override fun hashCode(): Int {
        return key.hashCode()
    }

    fun filename(recording: RecList.Recording): String {
        return camera.shortName + "-" + name + "-" +
            recording.startTime90k.formatDate + "-" +
            recording.startTime90k.formatTime + recording.endTime90k.formatTime + ".mp4"
    }

    fun url(recording: RecList.Recording, caption: Boolean): String {
        val params = mutableMapOf<String, String?>(
            "s" to "${recording.startId}-${recording.endId}@${recording.openId}"
        )
        if (caption)
            params["ts"] = null
        return "/api/cameras/${camera.uuid}/${name}/view.mp4".url(params)
    }

    val wsUrl = window.location.let {
        URLBuilder().apply {
            path("api/cameras/$key/live.m4s")
            protocol = if (window.location.protocol == "https") URLProtocol.WSS else URLProtocol.WS
            window.location.port.toIntOrNull()?.let { port = it }
        }.build().also {
            console.log("wsurl = $it")
        }
    }

    suspend fun fetchRecordings(startDateTime: Date, endDateTime: Date, maxDuration: Duration90k): RecList {
        console.log("Fetching recording for $key, startDate=$startDateTime, end=$endDateTime")
        return RecList.fetchRecording(
            this@Stream,
            startDateTime,
            endDateTime,
            maxDuration
        ).also {
            console.log("Fetched ${it.recordings.size} recordings for ${camera.shortName}/${name}")
        }
    }

    override fun toString(): String {
        return "${camera.shortName} ($name)"
    }
}
