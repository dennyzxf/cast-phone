package com.nana.screencast

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity() {
    private data class Preset(val scale: Float, val bitrate: Int)

    private val presets = arrayOf(
        Preset(0.6f, 2_000_000),
        Preset(0.75f, 4_000_000),
        Preset(1.0f, 8_000_000)
    )

    private lateinit var urlEdit: EditText
    private lateinit var statusText: TextView
    private lateinit var qualitySpinner: Spinner
    private val prefs by lazy { getSharedPreferences("cast", Context.MODE_PRIVATE) }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        if (raw.isNullOrBlank()) {
            setStatus("扫码已取消")
            return@registerForActivityResult
        }
        val ws = normalize(raw)
        if (ws == null) {
            toast("无法识别这个二维码内容")
            return@registerForActivityResult
        }
        urlEdit.setText(ws)
        prefs.edit().putString("url", ws).apply()
        setStatus("已获取电脑地址，点「开始投屏」")
    }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchScan() else toast("需要相机权限才能扫码，也可以手动填地址")
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode != RESULT_OK || data == null) {
                setStatus("已取消录屏授权")
                return@registerForActivityResult
            }
            val dm = resources.displayMetrics
            val preset = presets[qualitySpinner.selectedItemPosition.coerceIn(0, presets.lastIndex)]
            val width = ((dm.widthPixels * preset.scale).toInt() / 16) * 16
            val height = ((dm.heightPixels * preset.scale).toInt() / 16) * 16

            val intent = Intent(this, ScreenCastService::class.java).apply {
                putExtra(ScreenCastService.EXTRA_WS_URL, urlEdit.text.toString().trim())
                putExtra(ScreenCastService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCastService.EXTRA_RESULT_DATA, data)
                putExtra(ScreenCastService.EXTRA_WIDTH, width)
                putExtra(ScreenCastService.EXTRA_HEIGHT, height)
                putExtra(ScreenCastService.EXTRA_BITRATE, preset.bitrate)
                putExtra(ScreenCastService.EXTRA_FPS, 30)
            }
            startForegroundService(intent)
            ScreenCastService.isRunning = true
            setStatus("投屏已启动，状态见通知栏")
            updateButtons()
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestProjection() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlEdit = findViewById(R.id.urlEdit)
        statusText = findViewById(R.id.statusText)
        qualitySpinner = findViewById(R.id.qualitySpinner)

        qualitySpinner.setSelection(prefs.getInt("quality", 1))
        urlEdit.setText(prefs.getString("url", "") ?: "")

        findViewById<Button>(R.id.scanBtn).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                launchScan()
            } else {
                cameraLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        findViewById<Button>(R.id.startBtn).setOnClickListener { beginCast() }
        findViewById<Button>(R.id.stopBtn).setOnClickListener {
            stopService(Intent(this, ScreenCastService::class.java))
            ScreenCastService.isRunning = false
            setStatus("投屏已停止")
            updateButtons()
        }

        setStatus(if (ScreenCastService.isRunning) "投屏进行中" else "尚未连接")
        updateButtons()
    }

    override fun onStart() {
        super.onStart()
        ScreenCastService.statusListener = { text -> runOnUiThread { setStatus(text) } }
        updateButtons()
    }

    override fun onStop() {
        ScreenCastService.statusListener = null
        super.onStop()
    }

    private fun launchScan() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("扫描电脑页面上的二维码")
            setBeepEnabled(false)
            setOrientationLocked(true)
        }
        scanLauncher.launch(options)
    }

    private fun beginCast() {
        val url = urlEdit.text.toString().trim()
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            toast("请先扫码，或填写 ws:// 开头的地址")
            return
        }
        prefs.edit()
            .putString("url", url)
            .putInt("quality", qualitySpinner.selectedItemPosition)
            .apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestProjection()
        }
    }

    private fun requestProjection() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    /** 二维码里是 http://IP:PORT/cast?room=XXX，转成发送端用的 ws 地址 */
    private fun normalize(raw: String): String? {
        val s = raw.trim()
        if (s.startsWith("ws://") || s.startsWith("wss://")) return s
        return try {
            val uri = Uri.parse(s)
            val host = uri.host ?: return null
            val room = uri.getQueryParameter("room") ?: return null
            val port = if (uri.port > 0) uri.port else 80
            "ws://$host:$port/ws?role=sender&room=$room"
        } catch (_: Exception) {
            null
        }
    }

    private fun updateButtons() {
        val running = ScreenCastService.isRunning
        findViewById<Button>(R.id.startBtn).isEnabled = !running
        findViewById<Button>(R.id.stopBtn).isEnabled = running
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
