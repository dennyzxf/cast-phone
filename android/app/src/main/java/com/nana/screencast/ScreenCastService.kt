package com.nana.screencast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject

class ScreenCastService : Service() {
    companion object {
        private const val TAG = "ScreenCastService"
        private const val CHANNEL_ID = "cast_channel"
        private const val NOTIF_ID = 1001

        const val EXTRA_WS_URL = "ws_url"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_FPS = "fps"

        @Volatile
        var isRunning = false

        @Volatile
        var statusListener: ((String) -> Unit)? = null
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: H264StreamEncoder? = null
    private var socket: CastSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var encoderStarted = false

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val url = intent.getStringExtra(EXTRA_WS_URL)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (url.isNullOrBlank() || data == null) {
            report("参数缺失，无法投屏")
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14 起必须先进入前台，才能获取 MediaProjection
        foreground("正在连接电脑…")
        startCasting(url, resultCode, data, intent)
        return START_NOT_STICKY
    }

    private fun startCasting(url: String, resultCode: Int, data: Intent, cfg: Intent) {
        val width = cfg.getIntExtra(EXTRA_WIDTH, 720)
        val height = cfg.getIntExtra(EXTRA_HEIGHT, 1280)
        val bitrate = cfg.getIntExtra(EXTRA_BITRATE, 4_000_000)
        val fps = cfg.getIntExtra(EXTRA_FPS, 30)

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            projection = mgr.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            report("录屏授权无效：${e.message}")
            stopSelf()
            return
        }
        if (projection == null) {
            report("录屏授权无效，请重新授权")
            stopSelf()
            return
        }

        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                report("系统结束了投屏")
                stopSelf()
            }
        }, mainHandler)

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CastPhone::Cast").also {
                it.acquire(60 * 60 * 1000L)
            }
        } catch (_: Exception) {
        }

        isRunning = true
        report("正在连接 $url")
        socket = CastSocket(
            url,
            onOpen = {
                report("已连接电脑，开始推流")
                mainHandler.post { startEncoder(width, height, bitrate, fps) }
            },
            onClosed = { reason -> report("连接断开：$reason") }
        )
        socket?.connect()
    }

    private fun startEncoder(width: Int, height: Int, bitrate: Int, fps: Int) {
        if (encoderStarted || projection == null) return
        encoderStarted = true
        try {
            val enc = H264StreamEncoder(
                width, height, bitrate, fps,
                onConfig = { cfg ->
                    val json = JSONObject()
                        .put("type", "config")
                        .put("codec", cfg.codec)
                        .put("width", cfg.width)
                        .put("height", cfg.height)
                        .put("fps", cfg.fps)
                        .put("description", cfg.description)
                    socket?.sendText(json.toString())
                },
                onFrame = { isKey, frame ->
                    val queued = socket?.queueSize() ?: 0L
                    // 网络跟不上时优先丢非关键帧保实时性，下一个关键帧（约 1 秒）会恢复画面
                    if (queued <= 24 || isKey) socket?.sendFrame(frame)
                }
            )
            enc.start()
            encoder = enc
            val dm = resources.displayMetrics
            virtualDisplay = projection?.createVirtualDisplay(
                "CastPhone",
                width, height, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                enc.surface, null, mainHandler
            )
            report("投屏中 ${width}x${height} @${fps}fps")
        } catch (e: Exception) {
            Log.e(TAG, "启动编码器失败", e)
            report("启动编码器失败：${e.message}")
            stopSelf()
        }
    }

    private fun report(text: String) {
        Log.i(TAG, text)
        try { statusListener?.invoke(text) } catch (_: Exception) {}
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    private fun foreground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "稳投屏服务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "投屏进行中的状态通知"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("稳投屏")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onDestroy() {
        isRunning = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { encoder?.stop() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        socket?.close()
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        virtualDisplay = null
        encoder = null
        projection = null
        socket = null
        wakeLock = null
        encoderStarted = false
        try { statusListener?.invoke("投屏已停止") } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }
}
