package com.dianming.phoneapp.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import kotlin.math.roundToInt

/** One editor for both the floating window and the in-app fallback. */
internal class ShareTaskPanel(
    context: Context,
    closeLabel: String,
    onClose: () -> Unit,
    onSettings: () -> Unit,
    onOpenOverlay: (() -> Unit)? = null
) : LinearLayout(context) {
    private val ink = Color.rgb(17, 24, 39)
    private val sub = Color.rgb(75, 85, 99)
    private val header = label("", 15f)
    private val status = label("", 13f)
    private val content = LinearLayout(context).apply { orientation = VERTICAL }
    private val source = ImageView(context).apply {
        adjustViewBounds = true
        maxHeight = dp(260)
        contentDescription = "分享的截图"
        visibility = GONE
    }
    private val draft = EditText(context).apply {
        setTextColor(ink)
        setHintTextColor(sub)
        textSize = 15f
        gravity = Gravity.TOP or Gravity.START
        minLines = 4
        filters = arrayOf(InputFilter.LengthFilter(ShareTasks.MAX_TEXT))
        hint = "每行一条消息"
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        maxLines = 8
    }
    private val replies = LinearLayout(context).apply { orientation = VERTICAL }
    private var bound: ShareTasks.Task? = null
    private var rendering = false
    private val previous = button("上一项") { ShareTasks.move(-1) }
    private val next = button("下一项") { ShareTasks.move(1) }
    private val delete = button("删除") { bound?.let(ShareTasks::delete) }
    private val analyze = button("确认并分析") {
        bound?.let { ShareTasks.analyze(context, it) }
        context.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(draft.windowToken, 0)
        draft.clearFocus()
    }
    private val showImage = button("查看截图") {
        source.visibility = if (source.visibility == VISIBLE) GONE else VISIBLE
    }
    private val observer = { render() }

    init {
        orientation = VERTICAL
        isFocusableInTouchMode = true
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(14).toFloat()
        }
        setPadding(dp(12), dp(8), dp(12), dp(10))

        val title = label("分享任务", 18f).apply { setTypeface(typeface, Typeface.BOLD) }
        val close = button(closeLabel, onClose)
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(close)
        })
        addView(header)
        addView(buttonRow(previous, next, delete))
        val body = LinearLayout(context).apply { orientation = VERTICAL }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(body)
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(status)
        body.addView(content)
        content.addView(showImage)
        content.addView(source, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(label("每行一条，标为「我：」或「对方：」。分析会使用最后 10 条消息。", 13f))
        content.addView(draft)
        content.addView(buttonRow(
            button("标为我") { markCurrentLine("我") },
            button("标为对方") { markCurrentLine("对方") }
        ))
        content.addView(analyze)
        content.addView(replies)
        content.addView(button("模型设置", onSettings))
        onOpenOverlay?.let { content.addView(button("用悬浮窗打开", it)) }
        draft.doAfterTextChanged { value ->
            if (!rendering) bound?.let { ShareTasks.edit(it, value.toString()) }
        }
        requestFocus()
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ShareTasks.addListener(observer)
        render()
    }

    override fun onDetachedFromWindow() {
        ShareTasks.removeListener(observer)
        super.onDetachedFromWindow()
    }

    private fun render() {
        val task = ShareTasks.selected
        if (task !== bound) {
            bound = task
            source.visibility = GONE
        }
        previous.isEnabled = ShareTasks.index > 0
        next.isEnabled = ShareTasks.index >= 0 && ShareTasks.index < ShareTasks.count - 1
        delete.isEnabled = task != null
        header.text = if (task == null) "" else {
            val kind = if (task.image) "截图" else "文本"
            "$kind · ${ShareTasks.index + 1} / ${ShareTasks.count}"
        }
        content.visibility = if (task == null) GONE else VISIBLE
        status.text = when {
            task == null -> "在其他应用的分享菜单中选择「分享到 Jev」。"
            task.loadingImage -> "正在本地识别截图…"
            else -> task.importError ?: ""
        }
        status.visibility = if (status.text.isEmpty()) GONE else VISIBLE
        if (task == null) {
            source.setImageDrawable(null)
            rendering = true
            draft.setText("")
            rendering = false
            replies.removeAllViews()
            return
        }
        showImage.visibility = if (task.thumbnail != null) VISIBLE else GONE
        source.setImageBitmap(task.thumbnail)
        draft.isEnabled = !task.loadingImage
        if (draft.text.toString() != task.draft) {
            rendering = true
            draft.setText(task.draft)
            rendering = false
        }
        analyze.isEnabled = !task.loadingImage && !task.running
        analyze.text = if (task.running) "分析中…" else "确认并分析"
        replies.removeAllViews()
        task.error?.let { replies.addView(label(it, 13f)) }
        task.judgment?.let { judgment ->
            val advice = judgment.shouldReplyNow?.takeIf { it.isFinite() }
            if (advice != null) {
                val message = if (advice >= 0.5) "判断建议：可以给出实质回复" else "判断建议：先缓一缓"
                replies.addView(label(message, 14f))
            }
        }
        task.judgmentError?.let { replies.addView(label(it, 13f)) }
        for (reply in task.replies) {
            replies.addView(label(reply, 15f).apply { setTextIsSelectable(true) })
            replies.addView(button("复制回复") {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("Jev 回复", reply))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            })
        }
    }

    private fun markCurrentLine(role: String) {
        if (!draft.isEnabled) return
        val text = draft.text.toString()
        val cursor = draft.selectionStart.coerceIn(0, text.length)
        val start = text.lastIndexOf('\n', cursor - 1) + 1
        val end = text.indexOf('\n', cursor).takeIf { it >= 0 } ?: text.length
        val marked = ShareDraft.mark(text.substring(start, end), role)
        draft.text.replace(start, end, marked)
        draft.setSelection((start + marked.length).coerceAtMost(draft.length()))
    }

    private fun buttonRow(vararg buttons: Button) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        buttons.forEach { addView(it, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun label(value: String, size: Float) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(if (size >= 15) ink else sub)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(title: String, click: () -> Unit) = Button(context).apply {
        text = title
        textSize = 13f
        isAllCaps = false
        setOnClickListener { click() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
