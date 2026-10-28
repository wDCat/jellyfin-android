package org.jellyfin.mobile.player.videoproxy

import org.json.JSONArray
import org.json.JSONObject

/** Statistics exposed to the web playback info panel, without authenticated source URLs. */
fun VideoProxyDebugInfo.toPlaybackInfoJson(): JSONObject = JSONObject().apply {
    put("videoId", videoId)
    put("isProxying", isProxying)
    put("playbackState", playbackState)
    put("videoDecoderName", videoDecoderName)
    put("audioDecoderName", audioDecoderName)
    put("videoFormat", videoFormat)
    put("audioFormat", audioFormat)
    put("resolution", resolution)
    put("frameRate", frameRate)
    put("videoBitrate", videoBitrate)
    put("audioBitrate", audioBitrate)
    put("droppedFrames", droppedFrames)
    put("renderedFrames", renderedFrames)
    put("bufferPosition", bufferPosition)
    put("currentPosition", currentPosition)
    put("duration", duration)
    put("playbackSpeed", playbackSpeed)
    put("volume", volume)
    put("isHardwareDecoding", isHardwareDecoding)
    put("surfaceSize", surfaceSize)
    put("networkBandwidth", networkBandwidth)
    put("textTrackEnabled", textTrackEnabled)
    val tracks = subtitleTracks.map { track ->
        JSONObject().apply {
            put("index", track.index)
            put("language", track.language)
            put("label", track.label)
            put("codec", track.codec)
            put("isSelected", track.isSelected)
        }
    }
    put("subtitleTracks", JSONArray(tracks))
}
