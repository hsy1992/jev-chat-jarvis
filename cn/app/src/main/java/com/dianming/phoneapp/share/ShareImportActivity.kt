package com.dianming.phoneapp.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dianming.phoneapp.SettingsActivity
import com.dianming.phoneapp.padForSystemBars

class ShareImportActivity : AppCompatActivity() {
    private var pendingOverlayTask: Long? = null
    private var resumed = false
    private var openingOverlay = false
    private val stateListener = { advance() }
    private val overlayTimeout = Runnable {
        if (openingOverlay) {
            openingOverlay = false
            stopService(Intent(this, ShareOverlayService::class.java))
            toast("悬浮窗未能打开，已保留在应用内。")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val panel = ShareTaskPanel(this, "返回", { finish() },
            onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
            onOpenOverlay = { openOverlay() })
        setContentView(FrameLayout(this).apply {
            padForSystemBars()
            addView(panel)
        })
        ShareTasks.addListener(stateListener)
        if (savedInstanceState == null) receive(intent)
        else pendingOverlayTask = savedInstanceState.getLong("pendingOverlayTask").takeIf { it > 0 }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receive(intent)
        advance()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        advance()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("pendingOverlayTask", pendingOverlayTask ?: 0)
    }

    override fun onDestroy() {
        ShareTasks.removeListener(stateListener)
        window.decorView.removeCallbacks(overlayTimeout)
        super.onDestroy()
    }

    private fun receive(source: Intent) {
        pendingOverlayTask = null
        if (source.action == Intent.ACTION_SEND_MULTIPLE) {
            toast("一次请选择一张截图。")
            return
        }
        if (source.action != Intent.ACTION_SEND) {
            if (ShareTasks.overlayVisible) stopService(Intent(this, ShareOverlayService::class.java))
            return
        }
        runCatching {
            when {
                source.type == "text/plain" -> {
                    val shared = source.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                        ?: source.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text?.toString()
                    if (shared.isNullOrBlank() || shared.length > ShareTasks.MAX_TEXT) {
                        toast("请分享非空且不超过 24,000 字符的文本。")
                        return
                    }
                    pendingOverlayTask = ShareTasks.add(image = false, draft = shared)?.id
                    if (pendingOverlayTask == null) taskLimit()
                }
                source.type?.startsWith("image/") == true -> {
                    val uri = imageUri(source)
                    if (uri == null) {
                        toast("请选择一张可读取的截图。")
                        return
                    }
                    val task = ShareTasks.add(image = true) ?: run { taskLimit(); return }
                    pendingOverlayTask = task.id
                    SharedImageImport.read(applicationContext, task.id, uri)
                }
                else -> toast("请选择文本或一张截图。")
            }
        }.onFailure { toast("无法读取分享内容。") }
    }

    private fun imageUri(source: Intent): Uri? {
        val uris = linkedSetOf<Uri>()
        @Suppress("DEPRECATION")
        val stream = source.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (stream != null) uris.add(stream)
        source.clipData?.let { clip ->
            if (clip.itemCount != 1) return null
            clip.getItemAt(0).uri?.let(uris::add)
        }
        return uris.singleOrNull()?.takeIf { it.scheme == "content" }
    }

    private fun advance() {
        if (!resumed || isFinishing) return
        val pending = pendingOverlayTask?.let(ShareTasks::find)
        if (openingOverlay) {
            if (ShareTasks.overlayVisible) {
                window.decorView.removeCallbacks(overlayTimeout)
                // A new image share may arrive while the previous overlay is
                // starting. Keep this Activity's URI grant until decoding ends.
                if (pending?.loadingImage == true) return
                pendingOverlayTask = null
                openingOverlay = false
                finish()
            } else if (ShareTasks.overlayError != null) {
                openingOverlay = false
                window.decorView.removeCallbacks(overlayTimeout)
                toast("悬浮窗未能打开，已保留在应用内。")
            }
            return
        }
        if (pending?.loadingImage == true) return
        pendingOverlayTask = null
        if (pending != null && Settings.canDrawOverlays(this)) openOverlay()
    }

    private fun openOverlay() {
        if (ShareTasks.selected == null || openingOverlay ||
            pendingOverlayTask?.let(ShareTasks::find)?.loadingImage == true) return
        if (!Settings.canDrawOverlays(this)) {
            toast("开启悬浮窗权限后可使用，当前可在应用内分析。")
            return
        }
        if (ShareTasks.overlayVisible) {
            finish()
            return
        }
        ShareTasks.overlayState(false)
        openingOverlay = true
        runCatching { startForegroundService(Intent(this, ShareOverlayService::class.java)) }
            .onSuccess { window.decorView.postDelayed(overlayTimeout, 3500) }
            .onFailure {
                openingOverlay = false
                toast("悬浮窗未能打开，已保留在应用内。")
            }
    }

    private fun taskLimit() = toast("已有 12 项分享，请先删除一项。")
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
