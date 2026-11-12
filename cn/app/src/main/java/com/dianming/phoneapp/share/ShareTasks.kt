package com.dianming.phoneapp.share

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.dianming.phoneapp.core.Analysis
import com.dianming.phoneapp.core.ChatSnapshot
import com.dianming.phoneapp.core.Msg
import com.dianming.phoneapp.core.Prefs
import com.dianming.phoneapp.jev.JudgeClient
import com.dianming.phoneapp.jev.ReplyClient
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Shared by the overlay and the Activity. All task mutations happen on the main thread. */
internal object ShareTasks {
    const val MAX_TEXT = 24_000
    private const val MAX_TASKS = 12

    class Task(val id: Long, val image: Boolean, var draft: String) {
        var loadingImage = image
        var thumbnail: Bitmap? = null
        var importError: String? = null
        var error: String? = null
        var replies = emptyList<String>()
        var judgment: Analysis? = null
        var judgmentError: String? = null
        var running = false
        var revision = 0L
        var request: Future<*>? = null
    }

    private val tasks = mutableListOf<Task>()
    private val listeners = mutableSetOf<() -> Unit>()
    private val worker = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private var nextId = 1L
    var selected: Task? = null
        private set
    var overlayVisible = false
        private set
    var overlayError: String? = null
        private set

    val count get() = tasks.size
    val index get() = tasks.indexOf(selected)

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    private fun changed() { listeners.toList().forEach { it() } }

    fun add(image: Boolean, draft: String = ""): Task? {
        if (tasks.size >= MAX_TASKS) return null
        val task = Task(nextId++, image, draft)
        tasks.add(task)
        selected = task
        changed()
        return task
    }

    fun find(id: Long): Task? = tasks.firstOrNull { it.id == id }

    fun move(step: Int) {
        tasks.getOrNull(index + step)?.let {
            selected = it
            changed()
        }
    }

    fun delete(task: Task) {
        val at = tasks.indexOf(task)
        if (at < 0) return
        cancel(task)
        tasks.removeAt(at)
        if (selected === task) selected = tasks.getOrNull(at) ?: tasks.lastOrNull()
        changed()
    }

    fun imageReady(id: Long, text: String, thumbnail: Bitmap?, error: String?) {
        val task = find(id) ?: return
        task.draft = text
        task.thumbnail = thumbnail
        task.loadingImage = false
        task.importError = error
        changed()
    }

    fun overlayState(visible: Boolean, error: String? = null) {
        if (overlayVisible == visible && overlayError == error) return
        overlayVisible = visible
        overlayError = error
        changed()
    }

    fun edit(task: Task, text: String) {
        if (task.draft == text) return
        task.draft = text
        if (task.running || task.replies.isNotEmpty() || task.judgment != null ||
            task.error != null || task.judgmentError != null) {
            cancel(task)
            changed()
        }
    }

    private fun cancel(task: Task) {
        task.revision++
        task.request?.cancel(true)
        task.request = null
        task.running = false
        task.error = null
        task.replies = emptyList()
        task.judgment = null
        task.judgmentError = null
    }

    fun analyze(context: Context, task: Task) {
        if (task.loadingImage || task.running) return
        val messages = ShareDraft.messages(task.draft)?.takeLast(10)
        if (messages == null || messages.none { it.side == "other" }) {
            task.error = "请标注每条消息。最后 10 条中至少要有一条对方的消息。"
            changed()
            return
        }
        val prefs = Prefs(context.applicationContext)
        if (prefs.effectiveReplyKey().isBlank()) {
            task.error = "请先在设置中配置回复接口密钥。"
            changed()
            return
        }
        cancel(task)
        val revision = task.revision
        val snapshot = ChatSnapshot(null, messages)
        val relationship = prefs.relationship
        val candidateCount = prefs.candidateCount
        val judgeEnabled = prefs.hasKey()
        task.running = true
        task.request = worker.submit {
            val replies = runCatching {
                ReplyClient(prefs).draft(snapshot, relationship, count = candidateCount)
            }.getOrDefault(emptyList())
            main.post {
                if (find(task.id) === task && task.revision == revision) {
                    task.replies = replies
                    if (replies.isEmpty()) task.error = "未生成回复，请检查回复接口后重试。"
                    changed()
                }
            }
            val judgment = if (judgeEnabled && !Thread.currentThread().isInterrupted) {
                runCatching { JudgeClient(prefs).judge(snapshot, relationship) }.getOrNull()
            } else null
            main.post {
                if (find(task.id) === task && task.revision == revision) {
                    task.running = false
                    task.request = null
                    task.judgment = judgment?.takeIf { it.error == null }
                    if (judgeEnabled && task.judgment == null) {
                        task.judgmentError = "判断暂不可用，请检查判断接口。"
                    }
                    changed()
                }
            }
        }
        changed()
    }
}

/** Sender roles come only from explicit labels in the editable draft. */
internal object ShareDraft {
    private val label = Regex("^(我|对方)[：:]\\s*")

    fun messages(text: String): List<Msg>? {
        val messages = mutableListOf<Msg>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val match = label.find(line) ?: return null
            val body = line.substring(match.range.last + 1).trim()
            if (body.isEmpty()) return null
            messages.add(Msg(if (match.groupValues[1] == "我") "me" else "other", body))
        }
        return messages
    }

    fun mark(line: String, speaker: String): String {
        val trimmed = line.trimStart()
        val existing = label.find(trimmed)
        val body = if (existing == null) trimmed else trimmed.substring(existing.range.last + 1)
        return "${speaker}：$body"
    }
}
