package com.nana.screencast

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

class CastSocket(
    private val url: String,
    private val onOpen: () -> Unit,
    private val onClosed: (String) -> Unit
) {
    companion object {
        private const val TAG = "CastSocket"
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var ws: WebSocket? = null

    fun connect() {
        val request = Request.Builder().url(url).build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ws = webSocket
                Log.i(TAG, "已连接 $url")
                onOpen()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "连接失败: ${t.message}")
                ws = null
                onClosed(t.message ?: "连接失败")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "连接关闭 $code $reason")
                ws = null
                onClosed(reason)
            }
        })
    }

    fun queueSize(): Long = ws?.queueSize() ?: 0L

    fun sendText(text: String): Boolean = ws?.send(text) ?: false

    fun sendFrame(frame: ByteArray): Boolean {
        val socket = ws ?: return false
        return socket.send(ByteString.of(frame, 0, frame.size))
    }

    fun close() {
        try { ws?.close(1000, "停止投屏") } catch (_: Exception) {}
        ws = null
    }
}
