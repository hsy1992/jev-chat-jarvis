package com.dianming.phoneapp

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.dianming.phoneapp.capture.ChatAppAdapter
import com.dianming.phoneapp.capture.ConversationSession
import com.dianming.phoneapp.capture.KeepAliveService
import com.dianming.phoneapp.capture.FeishuAdapter
import com.dianming.phoneapp.capture.GuardedInputWriter
import com.dianming.phoneapp.capture.NewMessageGate
import com.dianming.phoneapp.capture.QQAdapter
import com.dianming.phoneapp.capture.WhatsAppAdapter
import com.dianming.phoneapp.capture.WhatsAppRules
import com.dianming.phoneapp.capture.XAdapter
import com.dianming.phoneapp.capture.collectFeishuBubbleRects
import com.dianming.phoneapp.capture.WeChatAdapter
import com.dianming.phoneapp.capture.WeChatRules
import com.dianming.phoneapp.capture.findTitleInActionBar
import com.dianming.phoneapp.capture.ocr.MlKitOcr
import com.dianming.phoneapp.capture.ocr.OcrLine
import com.dianming.phoneapp.capture.ocr.ScreenCapture
import com.dianming.phoneapp.core.BubbleRect
import com.dianming.phoneapp.core.ChatSnapshot
import com.dianming.phoneapp.core.Msg
import com.dianming.phoneapp.core.Prefs
import com.dianming.phoneapp.core.kb.ContextBuilder
import com.dianming.phoneapp.core.kb.KbStore
import com.dianming.phoneapp.jev.JevClient
import com.dianming.phoneapp.overlay.OverlayController
import com.dianming.phoneapp.share.ShareTasks
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The live capture service. It reads whichever
 * adapted chat app is in the foreground, detects a new incoming message from the
 * other person, runs Jev analysis off the main thread, and drives the floating
 * overlay.
 *
 * Per-app node rules live in [ChatAppAdapter] implementations; everything here
 * is app-agnostic.
 *
 * It never sends a message. The only write action is ACTION_SET_TEXT (or a
 * clipboard PASTE fallback) to fill the chat input box when the user taps
 * "填入"; the user still presses send.
 */
open class MyAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)

    /** Judge/reply HTTP runs here under a hard watchdog (see [withHardTimeout]).
     *  Cached so an abandoned request — interrupts do not break a wedged socket
     *  read — never blocks the next round's threads. */
    private val analysisPool = Executors.newCachedThreadPool { r ->
        Thread(r, "jev-http").apply { isDaemon = true }
    }

    /**
     * Run [body] on [analysisPool] but never wait longer than [ms].
     *
     * HttpURLConnection bounds connect/read per attempt yet NOT DNS resolution
     * (and some OEM stacks stall connects beyond both), so one wedged request
     * used to hold its round's callback forever: the panel stayed on 生成中 and
     * the round's [analyzing] flag never dropped, silently discarding every
     * later analysis. Past the ceiling we abandon the attempt and surface a
     * plain, retryable error instead.
     */
    private fun <T> withHardTimeout(ms: Long, what: String, body: () -> T): T {
        val f = analysisPool.submit(Callable { body() })
        return try {
            f.get(ms, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            f.cancel(true)
            throw java.io.IOException("$what 超时（${ms / 1000}秒），请检查网络后重试")
        }
    }

    /** Adapted chat apps, keyed by package name. Apps in [BLOCKED_PKGS] are
     *  never read at all (see [maybeCapture] / [onAccessibilityEvent]). */
    private val adapters = listOf(QQAdapter(), XAdapter(), FeishuAdapter(), WhatsAppAdapter(), WeChatAdapter()).associateBy { it.pkg }

    /** Submit to the worker, ignoring rejection after the service is torn down
     *  (a stale overlay callback must never crash the process). */
    private fun submit(task: () -> Unit) {
        try { worker.execute(task) } catch (_: RejectedExecutionException) { }
    }
    private lateinit var prefs: Prefs
    private var overlay: OverlayController? = null
    private var editorOverlayVisible = false
    private val onEditorOverlayChanged: () -> Unit = {
        val visible = ShareTasks.overlayVisible
        if (visible != editorOverlayVisible) {
            editorOverlayVisible = visible
            if (visible) {
                main.removeCallbacks(confirmLeave)
                main.removeCallbacks(confirmAwayHide)
                leaveConversation()
                overlay?.hide()
            } else if (!destroyed && prefs.enabled) {
                main.post { if (!destroyed && prefs.enabled) maybeCapture() }
            }
        }
    }

    private var lastSignature: String = ""
    private var activePkg: String? = null
    private var analyzing = false
    /** A "换一组" redraft is in flight (candidates only; the judgment stays). */
    private var regenerating = false
    private val session = ConversationSession()
    /** Tells new incoming messages apart from scrolling through history. */
    private val gate = NewMessageGate()
    /** The snapshot and knowledge context of the last analysis, reused by "换一组". */
    private var lastAnalyzed: ChatSnapshot? = null
    private var lastCtx: com.dianming.phoneapp.core.kb.ChatContext? = null
    private val analysisTasks = ArrayList<Future<*>>()
    private var analysisRevision = 0L
    private data class TreeReview(val pkg: String, val windowId: Int, val snapshot: ChatSnapshot)
    private var treeReview: TreeReview? = null
    private var destroyed = false
    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "enabled" || key == "whitelist") {
            main.post {
                leaveConversation()
                overlay?.hide()
            }
        }
    }

    /** Invalidate callbacks before cancelling workers; interruption alone is not a guard. */
    private fun cancelAnalysis() {
        analysisRevision++
        session.invalidate()
        main.removeCallbacks(debounce)
        pendingSnapshot = null
        analyzing = false
        regenerating = false
        analysisTasks.forEach { it.cancel(true) }
        analysisTasks.clear()
        overlay?.resetForNewConversation()
    }

    /** Drop the round in flight (its result would be stale) but keep what the card shows. */
    private fun cancelInFlight() {
        analysisRevision++
        session.invalidate()
        main.removeCallbacks(debounce)
        analyzing = false
        regenerating = false
        analysisTasks.forEach { it.cancel(true) }
        analysisTasks.clear()
    }

    private fun observeTarget(target: ConversationSession.Target?) {
        if (session.observe(target)) {
            treeReview = null
            cancelAnalysis()
            currentSnapshot = null
            activePkg = null
            lastSignature = ""
            lastOcrSignature = ""
            gate.reset()
            lastAnalyzed = null
            lastCtx = null
        }
    }

    private fun leaveConversation() {
        treeReview = null
        treeReadFeedbackUntil = 0L
        observeTarget(null)
        cancelAnalysis()
        currentSnapshot = null
    }

    /** Read the live target, never the previous chat's cached/stabilized title. */
    private fun targetFor(root: AccessibilityNodeInfo, extracted: ChatSnapshot? = null): ConversationSession.Target? {
        val pkg = root.packageName?.toString() ?: return null
        if (isBlocked(pkg) || pkg == packageName || pkg == "com.android.systemui" ||
            pkg.contains("launcher", true) || pkg == "com.miui.home") return null
        val adapter = adapters[pkg]
        var messagesSignature: String? = null
        val title = if (adapter != null) {
            val snapshot = extracted ?: adapter.extract(root, resources) ?: return null
            messagesSignature = snapshot.takeIf { it.messages.isNotEmpty() }?.signature()
            // A loading/unknown title cannot prove which conversation is open —
            // unless the SAME app+window already has a known one: animated
            // headers swap in skeleton/transient titles for a beat (#68), and
            // unbinding the session there killed in-flight analyses. Keep the
            // previously verified title instead.
            val fresh = snapshot.title?.takeUnless { isTransientTitle(it) }
            if (fresh != null) fresh
            else {
                if (pkg == WeChatRules.PACKAGE) null
                else {
                    val known = session.target
                    if (known != null && known.pkg == pkg && known.windowId == root.windowId &&
                        !known.title.isNullOrBlank()) known.title else return null
                }
            }
        } else {
            findTitleInActionBar(root, Int.MAX_VALUE, resources.displayMetrics.widthPixels,
                resources, 0.15, 0.85)
        }
        return ConversationSession.Target(pkg, root.windowId, title, messagesSignature)
    }

    private fun isCurrent(token: ConversationSession.Token): Boolean {
        if (destroyed || !prefs.enabled || !session.accepts(token)) return false
        val live = rootInActiveWindow?.let { targetFor(it) }
        // WeChat may have a readable title/input but no readable messages. Its
        // adapter now preserves that identity. If even that identity disappears,
        // do not accept a delayed manual screenshot or analysis for the old page.
        if (token.target.pkg == WeChatRules.PACKAGE && live == null) {
            leaveConversation()
            return false
        }
        // A null re-extract is a transient accessibility glitch (list animation,
        // node cache miss), NOT proof the chat was left — hiding there killed
        // freshly rendered candidates (seen on QQ). Identity checks apply
        // only when we actually got a reading.
        if (live != null && (!live.sameConversation(token.target) ||
            !prefs.isAllowed(currentSnapshot?.title ?: live.title))) {
            leaveConversation()
            overlay?.hide()
            return false
        }
        return true
    }

    /** Only called on the main thread, including the context-completion callback. */
    private fun submitAnalysis(task: () -> Unit) {
        try { analysisTasks.add(worker.submit(task)) } catch (_: RejectedExecutionException) { }
    }

    private val debounce = Runnable { runAnalysis() }
    private var pendingSnapshot: ChatSnapshot? = null
    private var treeReadFeedbackUntil = 0L

    /**
     * #18: WINDOW_STATE_CHANGED blips — the notification shade, a permission
     * dialog, a split-screen handle — are transient, but the drop branch below
     * used to tear the bubble down the instant one arrived, and an open panel
     * was lost mid-read. Hide only if the screen is STILL a drop-zone after a
     * short grace period; any chat-app event in between cancels the hide.
     */
    private val confirmAwayHide = Runnable {
        val fg = rootInActiveWindow?.packageName?.toString()
        val stillAway = fg == null || fg == packageName ||
            fg.contains("launcher", ignoreCase = true) ||
            fg == "com.miui.home" || fg == "com.android.systemui"
        if (stillAway) overlay?.hide()
    }
    @Volatile private var currentSnapshot: ChatSnapshot? = null
    private var foregroundPkg: String? = null

    // ---- OCR path (B stage). Everything here runs on the main thread: the
    // screenshot callback and the ML Kit callback are both posted back to it.
    private val screenCapture by lazy {
        ScreenCapture(this,
            hideOverlay = { overlay?.setHiddenForShot(true) },
            restoreOverlay = { overlay?.setHiddenForShot(false) })
    }
    private val ocr = MlKitOcr()
    private var ocrBusy = false

    /** What the screen looked like the last time we fired an automatic shot.
     *  See [ocrSignature]: this is the brake on the OCR path. */
    private var lastOcrSignature: String = ""

    /** Whether the "not supported" notice has been shown for the current visit
     *  to a blocked app. Reset whenever another foreground is seen, so it
     *  re-appears next visit but does not re-pop on every event. */
    private var blockedNoticeShown = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        getSharedPreferences(Prefs.PREFS_MAIN, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(preferencesListener)
        overlay = OverlayController(this)
        overlay?.onManualAnalyze = {
            currentSnapshot?.let { pendingSnapshot = it; runAnalysis() }
        }
        overlay?.onRegenerate = {
            if (treeReview == null) regenerateReplies() else runAnalysis()
        }
        // Bubble menu: file the open conversation as a knowledge-base contact.
        // Contacts are never created automatically — this is the one-tap way in.
        overlay?.onSaveContact = {
            val title = currentSnapshot?.title
            val pkg = activePkg ?: foregroundPkg ?: ""
            when {
                title.isNullOrBlank() -> overlay?.toast("当前会话没有标题，存不了")
                isTransientTitle(title) -> overlay?.toast("当前会话标题还没加载出来，稍后再试")
                else -> submit {
                    val msg = try {
                        KbStore.get(this).saveOrMergeContact(title, pkg)
                    } catch (e: Exception) { "保存失败：${e.javaClass.simpleName}" }
                    main.post { overlay?.toast(msg) }
                }
            }
        }
        // Bubble menu: one manual screenshot + OCR, for any app at all.
        overlay?.onOcrCapture = { ocrCaptureManual() }
        overlay?.onReadTree = { readTreeManual() }
        ShareTasks.addListener(onEditorOverlayChanged)
        onEditorOverlayChanged()
        // Keep the process at foreground importance so MIUI does not freeze us.
        runCatching { KeepAliveService.start(this) }
        // Load the bundled OCR model now, off the main thread: the first
        // recognize() otherwise pays for it inside the screenshot callback.
        submit { MlKitOcr.warmUp() }
        // HyperOS may kill and restart us. On (re)connect, proactively re-show the
        // bubble for whatever chat is already open, so it comes back on its own
        // instead of waiting for the user to scroll.
        main.postDelayed({ if (prefs.enabled) runCatching { maybeCapture() } }, 900)
        Log.i(TAG, "capture service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || editorOverlayVisible) return
        if (!prefs.enabled) { leaveConversation(); overlay?.hide(); return }

        val type = event.eventType
        // #18-4: our own overlay redraws ("generating…" → judgment → candidates)
        // emit WINDOW_CONTENT_CHANGED / VIEW_SCROLLED carrying our package name.
        // Reacting to them woke the service in a loop: re-read → re-analyze →
        // redraw → …, which pinned the panel on "generating" forever. Window-state
        // events keep flowing: the fg == packageName branch above still hides the
        // bubble inside our own settings screens.
        if (event.packageName?.toString() == packageName &&
            (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
             type == AccessibilityEvent.TYPE_VIEW_SCROLLED)) return
        // Decide "did we leave the chat app" from the REAL active window, not the
        // event's package. The event package can be an IME (e.g. com.tencent.wetype)
        // or the status bar while the chat app is still foreground — keying off it
        // made the bubble flicker (hide → re-show → hide…). rootInActiveWindow stays
        // on the chat app while the keyboard is up, so this is stable.
        //
        // An app with no adapter is NOT a reason to take the bubble away: the only
        // way into DingTalk / Telegram / anything else is the bubble menu's
        // "截屏识别一次", and a bubble that is gone cannot be tapped. So we park
        // the idle bubble there instead — still no automatic capture, no analysis.
        // The bubble does come off for places where it would only be in the way:
        // our own settings screens, the launcher, and the system UI.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val fg = rootInActiveWindow?.packageName?.toString()
            // Blocked apps: never read/screenshot/OCR/fill there, only show the
            // one-time "not supported" notice and stop. Checked before the
            // generic no-adapter branch.
            if (isBlocked(fg)) { foregroundPkg = fg; showBlocked(auto = true); return }
            if (fg != null && fg !in adapters) {
                val target = rootInActiveWindow?.let { targetFor(it) }
                if (session.target != target) leaveConversation()
                foregroundPkg = fg
                blockedNoticeShown = false // left a blocked app → allow the notice again next visit
                val drop = fg == packageName ||
                    fg.contains("launcher", ignoreCase = true) ||
                    fg == "com.miui.home" ||
                    fg == "com.android.systemui"
                if (drop) {
                    // Grace period instead of an instant tear-down (see [confirmAwayHide]).
                    main.removeCallbacks(confirmAwayHide)
                    main.postDelayed(confirmAwayHide, FOREGROUND_CONFIRM_MS)
                } else {
                    main.removeCallbacks(confirmAwayHide)
                    overlay?.showIdle(null)
                }
                return
            }
        }

        when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> maybeCapture()
        }
    }

    private fun maybeCapture() {
        if (editorOverlayVisible) return
        // The chat app is back (or never left): a pending hide confirmation is moot.
        main.removeCallbacks(confirmAwayHide)
        val root = rootInActiveWindow ?: run {
            if (SystemClock.uptimeMillis() >= treeReadFeedbackUntil) {
                leaveConversation()
                overlay?.hide()
            }
            return
        }
        val pkg = root.packageName?.toString()
        treeReview?.let { review ->
            if (matchesTreeReview(review, root)) return
            leaveConversation()
        }
        // Blocked apps — no tree read, no screenshot, no OCR, no fill. A
        // content-changed / scrolled event there only re-shows the one-time
        // notice (deduped); it must never reach an adapter or the OCR path.
        if (isBlocked(pkg)) { showBlocked(auto = true); return }
        blockedNoticeShown = false // any other foreground → allow the notice again next visit
        // Apps with no adapter are never handled automatically (v1.3 revision):
        // the only way in for them is the bubble menu's "截屏识别一次".
        val adapter = adapters[pkg] ?: run {
            if (session.target != null && session.target != targetFor(root)) leaveConversation()
            return
        }
        // Outside a chat window (the conversation list, a profile, settings…) the
        // adapter returns null. That is NOT a reason to show nothing: an adapted
        // app must behave at least as well as an unadapted one, which parks an idle
        // bubble so the menu stays reachable. Without this, opening QQ / Feishu on
        // their list screen produced no bubble at all.
        val rawSnapshot = adapter.extract(root, resources)
        if (rawSnapshot == null) { scheduleLeave(); return }
        val target = targetFor(root, rawSnapshot)
        if (target == null) { scheduleLeave(); return }
        main.removeCallbacks(confirmLeave)
        if (treeReadFeedbackUntil != 0L) {
            treeReadFeedbackUntil = 0L
            overlay?.clearTreePreview()
        }
        observeTarget(target)
        overlay?.setApp(pkg)
        // Use only this window's title; never inherit another conversation's title.
        val snapshot = rawSnapshot
        if (!prefs.isAllowed(snapshot.title)) { leaveConversation(); overlay?.hide(); return }
        // In a chat window but the tree holds no text (Feishu draws its bodies)
        // → screenshot + OCR, subject
        // to ScreenCapture's own >=1s throttle and failure backoff.
        if (snapshot.messages.isEmpty()) {
            if (snapshot.partialMessages.isNotEmpty()) {
                currentSnapshot = null
                pendingSnapshot = null
                main.removeCallbacks(debounce)
            }
            // In a chat window, but the tree carries no text (Feishu draws its
            // message bodies). Park the bubble BEFORE attempting OCR, so the user
            // still has something to tap when OCR is off, deduped, or comes back
            // empty — previously all three cases left the screen with no bubble.
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            if (prefs.ocrFallback && pkg !in NO_SCREENSHOT_PKGS && pkg != WeChatRules.PACKAGE) {
                // Gate BEFORE the shot, not after the OCR. Feishu's tree is empty
                // on every content-changed event, and a successful shot resets the
                // failure backoff — so without this the caret blinking or an
                // "online" badge flipping keeps a screenshot going out every
                // second forever. The picture can only differ if the bubbles moved
                // or the conversation changed, and that is exactly what the
                // signature measures.
                val sig = ocrSignature(pkg ?: "", snapshot.title, snapshot.bubbleRects)
                if (sig == lastOcrSignature && overlay?.isShowing() == true) return
                if (ocrBusy) return
                lastOcrSignature = sig
                ocrCapture(snapshot.title, snapshot.bubbleRects, pkg ?: "", manual = false)
            }
            return
        }

        // Switching to another adapted app resets the dedupe signature, so two apps
        // whose last few messages happen to match cannot swallow each other.
        if (pkg != activePkg) { activePkg = pkg; lastSignature = "" }

        currentSnapshot = snapshot
        val sig = snapshot.signature()
        val showing = overlay?.isShowing() == true
        // Same content and the card is already up → nothing to do.
        if (sig == lastSignature && showing) return
        lastSignature = sig
        // Scrolling through history changes what is on screen but brings nothing
        // new: keep the card exactly as it is (no clearing, no re-analysis). The
        // same goes for the user's own new message. Only a first look at a chat or
        // a new incoming message goes on to analysis.
        when (gate.observe(snapshot.messages)) {
            NewMessageGate.Verdict.NOTHING_NEW, NewMessageGate.Verdict.NEW_FROM_ME -> {
                overlay?.showIdle(snapshot.title); return
            }
            NewMessageGate.Verdict.FIRST_LOOK, NewMessageGate.Verdict.NEW_FROM_OTHER -> Unit
        }
        Log.d(TAG, "snapshot[$pkg] title=${snapshot.title} n=${snapshot.messages.size} " +
            snapshot.messages.takeLast(6).joinToString(" | ") { "${it.side}:${it.text.length}" }) // sides + lengths only, never content

        // Trigger only when the newest message is from the other person, and only
        // if auto-analyze is on. Otherwise show the idle bubble (tap to analyze).
        if (snapshot.latestFrom != "other" || !prefs.autoAnalyze) {
            overlay?.showIdle(snapshot.title); return
        }

        // A newer message makes any round in flight stale; the card keeps showing
        // the previous result until the new one lands.
        cancelInFlight()
        pendingSnapshot = snapshot
        main.removeCallbacks(debounce)
        main.postDelayed(debounce, 800) // debounce bursts of content-changed events
    }

    /**
     * A chat app's tree can briefly read as "not a chat" while its list re-lays
     * out (QQ while scrolling). Leave the conversation only if it still reads
     * that way after a short grace period; any good read in between cancels it.
     */
    private val confirmLeave = Runnable {
        val root = rootInActiveWindow
        val adapter = root?.packageName?.toString()?.let { adapters[it] }
        val gone = root == null || adapter == null ||
            adapter.extract(root, resources) == null || targetFor(root) == null
        if (gone) { leaveConversation(); overlay?.showIdle(null) }
    }

    private fun scheduleLeave() {
        val feedbackRemaining = treeReadFeedbackUntil - SystemClock.uptimeMillis()
        if (feedbackRemaining > 0) {
            main.removeCallbacks(confirmLeave)
            main.postDelayed(confirmLeave, feedbackRemaining)
            return
        }
        if (session.target == null) { leaveConversation(); overlay?.showIdle(null); return }
        main.removeCallbacks(confirmLeave)
        main.postDelayed(confirmLeave, LEAVE_CONFIRM_MS)
    }

    /** "换一组" / 重试: redraft the candidates only; the judgment on the card stays. */
    private fun regenerateReplies() {
        if (analyzing || regenerating || destroyed || !prefs.enabled) return
        val snapshot = lastAnalyzed ?: currentSnapshot ?: return
        val token = session.token() ?: return
        if (!isCurrent(token)) return
        if (!prefs.hasKey()) { overlay?.showError("未设置判断接口密钥，去设置里填"); return }
        regenerating = true
        val avoid = overlay?.currentReplyTexts().orEmpty()
        overlay?.beginRegenerate()
        val client = JevClient(prefs)
        val rel = prefs.relationship
        val ctx = lastCtx
        val count = prefs.candidateCount
        submitAnalysis {
            var replyError: String? = null
            val ranked = try {
                withHardTimeout(REPLY_TIMEOUT_MS, "回复接口") {
                    client.draftAndRank(snapshot, rel, ctx, count, avoid)
                }
            } catch (e: Exception) {
                replyError = replyErrorText(e)
                Log.w(TAG, "redraft failed: $replyError")
                emptyList()
            }
            main.post {
                regenerating = false
                if (isCurrent(token)) overlay?.showReplies(ranked, replyError) { text -> fillInput(token, text) }
            }
        }
    }

    private fun replyErrorText(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return if (m.contains("超时")) "生成超时" else m
    }

    private fun isBlocked(pkg: String?): Boolean = pkg != null && pkg in BLOCKED_PKGS

    /**
     * Foreground is a blocked app: no node-tree read, no screenshot, no OCR, no
     * fill. We only surface a one-time "not supported" notice. [auto] events
     * (accessibility callbacks) show it once per visit — [blockedNoticeShown]
     * dedupes them; a manual bubble tap ([auto] = false) always shows it. Never
     * gated by the user's whitelist (it is an info notice, not a read).
     */
    private fun showBlocked(auto: Boolean) {
        leaveConversation()
        if (auto && blockedNoticeShown) return
        blockedNoticeShown = true
        // No popup panel there — take the overlay off entirely and show the
        // reason once as a small transient toast.
        overlay?.hide()
        overlay?.toast(BLOCKED_MSG)
    }

    /** A placeholder title an app shows only for a moment (e.g. X's "连接中…"
     *  right after opening a DM thread) — never a real conversation title.
     *  Blank/null counts too, so a caller can always fall back the same way. */
    private fun isTransientTitle(t: String?): Boolean {
        val trimmed = t?.trim()?.removeSuffix("…")?.removeSuffix("...")?.trim()
        if (trimmed.isNullOrEmpty()) return true
        val lower = trimmed.lowercase()
        return TRANSIENT_TITLE_WORDS.any { lower.contains(it.lowercase()) }
    }

    private fun submitSnapshot(snapshot: ChatSnapshot) {
        cancelInFlight()
        pendingSnapshot = snapshot
        runAnalysis()
    }

    private fun runAnalysis() {
        val snapshot = pendingSnapshot ?: return
        if (analyzing || destroyed || !prefs.enabled) {
            // A lost round used to strand analyzing=true here forever, with
            // nothing in logcat saying why updates stopped — never again.
            if (analyzing) Log.w(TAG, "round skipped: previous round still in flight")
            return
        }
        val review = treeReview
        if (review != null && !isTreeReviewCurrent(review)) return
        if (review == null && session.token()?.let(::isCurrent) != true) return
        if (!prefs.hasKey()) { overlay?.showError("未设置判断接口密钥，去设置里填"); return }
        val token = if (review == null) session.begin() ?: return else null
        val revision = ++analysisRevision
        fun isRoundCurrent(): Boolean = revision == analysisRevision &&
            if (review != null) isTreeReviewCurrent(review) else token?.let(::isCurrent) == true
        if (review != null) overlay?.finishTreeRoleEditing()
        analyzing = true
        overlay?.beginRound()
        overlay?.setNote(snapshot.note)
        val count = prefs.candidateCount
        val client = JevClient(prefs)
        val rel = prefs.relationship
        submitAnalysis {
            val ctx = try {
                token?.let { ContextBuilder.build(this, snapshot, it.target.pkg, prefs) }
            } catch (e: Exception) {
                Log.w(TAG, "context build failed: ${e.javaClass.simpleName}"); null
            }
            main.post {
                if (!isRoundCurrent()) return@post
                if (token != null) {
                    lastAnalyzed = snapshot
                    lastCtx = ctx
                }
                overlay?.setContextInfo(ctx?.notes?.size ?: 0, ctx?.history?.size ?: 0)
                var remaining = 2
                fun completed() {
                    remaining--
                    if (remaining == 0 && revision == analysisRevision) {
                        analyzing = false
                        analysisTasks.clear()
                    }
                }
                submitAnalysis {
                    val judgment = try {
                        withHardTimeout(JUDGE_TIMEOUT_MS, "判断接口") { client.judge(snapshot, rel, ctx) }
                    } catch (e: Exception) {
                        Log.w(TAG, "judge round failed: ${e.message}")
                        null
                    }
                    main.post {
                        if (isRoundCurrent()) {
                            when {
                                judgment == null -> overlay?.showError("判断接口超时，请重试")
                                judgment.error != null -> overlay?.showError(judgment.error)
                                else -> overlay?.showJudgment(judgment)
                            }
                        }
                        // Outside the guard: a stale session must never strand
                        // analyzing=true — that bricked every later round.
                        completed()
                    }
                }
                submitAnalysis {
                    var replyError: String? = null
                    val t0 = SystemClock.elapsedRealtime()
                    val ranked = try {
                        withHardTimeout(REPLY_TIMEOUT_MS, "回复接口") {
                            client.draftAndRank(snapshot, rel, ctx, count)
                        }
                    } catch (e: Exception) {
                        replyError = replyErrorText(e)
                        Log.w(TAG, "reply failed after ${SystemClock.elapsedRealtime() - t0}ms: $replyError")
                        emptyList()
                    }
                    if (replyError == null) {
                        Log.i(TAG, "reply ok after ${SystemClock.elapsedRealtime() - t0}ms ranked=${ranked.size}")
                    }
                    main.post {
                        if (isRoundCurrent()) {
                            val fill = if (review != null) {
                                { text: String -> fillInputForReview(review, text) }
                            } else {
                                token?.let { t -> { text: String -> fillInput(t, text) } }
                            }
                            overlay?.showReplies(ranked, replyError, fill)
                        }
                        completed() // outside the guard: see the judge round
                    }
                }
            }
        }
    }

    private fun readTreeManual() {
        if (destroyed || !prefs.enabled || editorOverlayVisible) return
        val panel = overlay ?: return
        if (treeReview != null) leaveConversation()
        val debug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        fun showUnboundPreview(text: String, onReview: (() -> Unit)? = null, actionLabel: String = "分析读取结果") {
            leaveConversation()
            val duration = if (onReview == null) TREE_READ_FEEDBACK_MS else TREE_REVIEW_PREVIEW_MS
            treeReadFeedbackUntil = SystemClock.uptimeMillis() + duration
            main.removeCallbacks(confirmAwayHide)
            panel.showTreePreview(text, onReview, actionLabel)
            scheduleLeave()
        }
        fun unavailable(reason: String) {
            if (debug) Log.d(TREE_LOG_TAG, reason)
            showUnboundPreview(reason)
        }
        if (debug) Log.d(TREE_LOG_TAG, "开始读取当前窗口")
        treeReadFeedbackUntil = 0L
        panel.clearTreePreview()
        val root = rootInActiveWindow ?: run { unavailable("没有取得当前窗口的内容树"); return }
        val pkg = root.packageName?.toString()
        if (isBlocked(pkg)) { showBlocked(auto = false); return }
        val adapter = adapters[pkg] ?: run { unavailable("当前应用没有聊天树适配器"); return }
        val snapshot = adapter.extract(root, resources)
        val target = snapshot?.let { targetFor(root, it) }
        val titleHidden = pkg == WeChatRules.PACKAGE && snapshot != null && snapshot.title == null
        if (snapshot == null || (target == null && !titleHidden)) {
            unavailable(if (pkg == WeChatRules.PACKAGE) {
                "未能从微信当前页面确认单聊。请打开单聊页面后再点「读树」。"
            } else "已取得窗口节点，但无法确认聊天会话。请打开聊天页面后重试。")
            return
        }
        if (!prefs.isAllowed(target?.title) || !prefs.isAllowed(snapshot.title)) {
            unavailable("当前会话不在白名单中。")
            return
        }
        val readMessages = snapshot.partialMessages.ifEmpty { snapshot.messages }
        if (debug) {
            Log.d(TREE_LOG_TAG, "包名=$pkg 会话=${target?.title ?: "未公开"} 消息数=${readMessages.size}")
            readMessages.forEachIndexed { index, msg ->
                val sender = when (msg.side) {
                    "me" -> "我"
                    "other" -> "对方"
                    else -> "未确认"
                }
                // Keep long messages below Logcat's per-entry byte limit.
                var start = 0
                while (start < msg.text.length) {
                    var end = minOf(start + 800, msg.text.length)
                    if (end < msg.text.length && msg.text[end - 1].isHighSurrogate()) end--
                    Log.d(TREE_LOG_TAG, "${index + 1}. $sender：${msg.text.substring(start, end)}")
                    start = end
                }
            }
        }
        val needsReview = target == null || snapshot.partialMessages.isNotEmpty()
        val recentMessages = readMessages.takeLast(10)
        val needsRoles = recentMessages.any { it.side != "me" && it.side != "other" }
        val review: (() -> Unit)? = if (needsReview && pkg != null && recentMessages.isNotEmpty()) {
            val source = TreeReview(pkg, root.windowId, snapshot)
            val open: () -> Unit = { openTreeReview(source, recentMessages) }
            open
        } else null
        val preview = buildString {
            append(target?.title ?: "微信 · 会话标题未公开").append("\n")
            if (review != null && needsRoles) append("请先确认未分边消息的发送方。\n")
            if (readMessages.isEmpty()) {
                append("已确认会话，正文不可读或发送方无法确认。")
            } else {
                val visible = recentMessages
                append("读到 ").append(readMessages.size).append(" 条，显示最近 ").append(visible.size).append(" 条")
                for (message in visible) {
                    append("\n").append(when (message.side) {
                        "me" -> "我："
                        "other" -> "对方："
                        else -> "未确认："
                    })
                    append(message.text.take(240))
                    if (message.text.length > 240) append("…")
                }
                if (visible.any { it.text.length > 240 }) append("\n长消息预览已截短。")
            }
        }
        if (needsReview || target == null) {
            showUnboundPreview(preview, review, if (needsRoles) "校正并分析" else "分析读取结果")
            return
        }
        main.removeCallbacks(confirmAwayHide)
        main.removeCallbacks(confirmLeave)
        observeTarget(target)
        panel.setApp(pkg)
        currentSnapshot = snapshot.takeIf { it.messages.isNotEmpty() }
        val token = session.token() ?: return
        val analyze: (() -> Unit)? = if (snapshot.messages.isEmpty()) null else fun() {
            if (analyzing || regenerating) {
                panel.toast("正在分析，请稍后再试")
                return
            }
            if (!isCurrent(token)) {
                panel.toast("读取结果已过期，请重新读树")
                return
            }
            submitSnapshot(snapshot)
            if (analyzing) panel.clearTreePreview()
        }
        panel.showTreePreview(preview, analyze)
    }

    private fun matchesTreeReview(review: TreeReview, root: AccessibilityNodeInfo): Boolean =
        root.packageName?.toString() == review.pkg && root.windowId == review.windowId &&
            prefs.isAllowed(review.snapshot.title) && adapters[review.pkg]?.extract(root, resources) == review.snapshot

    private fun isTreeReviewCurrent(review: TreeReview): Boolean {
        if (destroyed || !prefs.enabled || treeReview !== review) return false
        if (rootInActiveWindow?.let { matchesTreeReview(review, it) } == true) return true
        leaveConversation()
        overlay?.showIdle(null)
        return false
    }

    private fun openTreeReview(review: TreeReview, messages: List<Msg>) {
        val panel = overlay ?: return
        if (rootInActiveWindow?.let { matchesTreeReview(review, it) } != true) {
            panel.toast("会话内容已变化，请重新读树。")
            return
        }
        leaveConversation()
        main.removeCallbacks(confirmLeave)
        main.removeCallbacks(confirmAwayHide)
        treeReview = review
        val analyze: (List<Msg>) -> Unit = { confirmed ->
            if (isTreeReviewCurrent(review)) {
                submitSnapshot(ChatSnapshot(review.snapshot.title, confirmed, note = "本次读树分析，可复制回复"))
            }
        }
        panel.showTreeRoles(messages, onAnalyze = analyze, onClose = {
            leaveConversation()
            panel.showIdle(null)
        })
        if (messages.all { it.side == "me" || it.side == "other" }) analyze(messages)
    }

    // ------------------------------------------------------------------ OCR

    /**
     * Bubble menu → "截屏识别一次". Works on ANY app, adapted or not: one whole
     * screen shot, every line OCR'd, lines grouped into pseudo-bubbles by line
     * spacing. Nobody can tell who said what this way, so everything is filed as
     * the other person and the panel says so.
     */
    private fun ocrCaptureManual() {
        if (treeReview != null) leaveConversation()
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: foregroundPkg ?: activePkg ?: ""
        // Blocked apps: a manual "截屏识别一次" must NOT take a screenshot there —
        // just show the notice (a manual tap always shows it).
        if (isBlocked(pkg)) { showBlocked(auto = false); return }
        if (pkg in NO_SCREENSHOT_PKGS) { overlay?.showError("这个应用里不使用截屏识别"); return }
        val target = root?.let { targetFor(it) } ?: run {
            overlay?.toast("无法确认当前会话，请等待标题加载后重试")
            return
        }
        if (!prefs.isAllowed(target.title)) { leaveConversation(); overlay?.hide(); return }
        main.removeCallbacks(confirmLeave)
        observeTarget(target)
        ocrCapture(target.title, emptyList(), pkg, manual = true)
    }

    /**
     * What the screen would look like to a camera, as far as the tree can tell.
     *
     * Feishu: the conversation title plus every bubble rectangle and its side —
     * the bubbles move whenever the list scrolls or a message arrives, and stay
     * put when only chrome (caret, presence dot, timestamp) redraws. Apps that
     * give us no rectangles fall back to package + title, which at least stops a
     * burst of events on one screen from becoming a burst of screenshots.
     */
    private fun ocrSignature(pkg: String, title: String?, rects: List<BubbleRect>): String {
        if (rects.isEmpty()) return pkg + "|" + (title ?: "")
        return (title ?: "") + "|" + rects.joinToString(";") { br ->
            val r = br.rect
            "${r.left},${r.top},${r.right},${r.bottom},${br.side}"
        }
    }

    /**
     * Screenshot, then either OCR each known bubble rect (Feishu: the tree knows
     * where the bubbles are and who sent them, just not what they say) or OCR
     * the whole screen (everything else).
     */
    private fun ocrCapture(treeTitle: String?, rects: List<BubbleRect>, pkg: String, manual: Boolean) {
        if (ocrBusy || destroyed || !prefs.enabled) return
        val token = session.token() ?: return
        if (!isCurrent(token)) return
        ocrBusy = true
        screenCapture.capture(shouldCapture = { isCurrent(token) }) { res ->
            if (!isCurrent(token)) {
                if (res is ScreenCapture.Result.Ok) res.bitmap.recycle()
                ocrBusy = false
                return@capture
            }
            when (res) {
                is ScreenCapture.Result.Failed -> {
                    ocrBusy = false
                    Log.i(TAG, "ocr: screenshot failed code=${res.code}")
                    // Nothing was read, so the signature must not claim this
                    // screen is done — the next event may retry, still held
                    // back by ScreenCapture's own throttle and failure backoff.
                    if (!manual) lastOcrSignature = ""
                    // Throttle/interval codes are transient timing, not
                    // something the user can act on — nagging would be constant.
                    val transient = res.code == ScreenCapture.CODE_THROTTLED || res.code == 3
                    if (manual || !transient) overlay?.showError(res.humanMessage)
                }
                is ScreenCapture.Result.Ok -> {
                    ocr.scaleX = res.scaleX; ocr.scaleY = res.scaleY
                    ocr.originX = res.originX; ocr.originY = res.originY
                    if (rects.isNotEmpty() && !manual) {
                        // Re-measure inside the callback. The rects handed in were
                        // read before the 120ms overlay-hide wait and the shot
                        // itself; one scroll tick in between and we would crop the
                        // rows next to the ones in the picture. Fall back to the
                        // old rects only if the tree gives us nothing now.
                        val fresh = rootInActiveWindow?.let { collectFeishuBubbleRects(it, resources) }
                        ocrByRects(res.bitmap, if (fresh.isNullOrEmpty()) rects else fresh, treeTitle, pkg, token)
                    } else ocrWholeScreen(res.bitmap, treeTitle, pkg, manual, token)
                }
            }
        }
    }

    /** One OCR pass per bubble rectangle; each rect becomes exactly one message. */
    private fun ocrByRects(bmp: Bitmap, rects: List<BubbleRect>, title: String?, pkg: String, token: ConversationSession.Token) {
        val sx = ocr.scaleX; val sy = ocr.scaleY
        // Screen -> bitmap: drop the window origin first. A window shot does not
        // start at (0,0) in split screen or when it excludes the status bar.
        val ox = ocr.originX; val oy = ocr.originY
        val out = arrayOfNulls<Msg>(rects.size)
        var remaining = rects.size
        rects.forEachIndexed { i, br ->
            val region = Rect(
                ((br.rect.left - ox) * sx).toInt(), ((br.rect.top - oy) * sy).toInt(),
                ((br.rect.right - ox) * sx).toInt(), ((br.rect.bottom - oy) * sy).toInt())
            ocr.recognize(bmp, region) { lines ->
                val text = cleanBubbleText(lines.joinToString(" ") { it.text })
                if (text.isNotEmpty()) out[i] = Msg(br.side, text)
                remaining--
                if (remaining == 0) {
                    runCatching { bmp.recycle() }
                    finishOcrSnapshot(ChatSnapshot(title, out.filterNotNull()), pkg, manual = false, token = token)
                }
            }
        }
    }

    /** Whole screen minus the top bar and the input area, grouped by line gaps. */
    private fun ocrWholeScreen(bmp: Bitmap, treeTitle: String?, pkg: String, manual: Boolean, token: ConversationSession.Token) {
        val region = Rect(0, (bmp.height * TOP_CROP).toInt(), bmp.width, (bmp.height * BOTTOM_CROP).toInt())
        ocr.recognize(bmp, region) { lines ->
            runCatching { bmp.recycle() }
            val msgs = groupOcrLines(lines)
            val title = treeTitle?.takeIf { it.isNotBlank() }
                ?: lines.firstOrNull()?.text?.trim()?.take(24)
            finishOcrSnapshot(ChatSnapshot(title, msgs, note = OCR_NOTE), pkg, manual, token)
        }
    }

    /**
     * OCR lines → "bubbles": a gap larger than 1.2x the previous line's height
     * starts a new one. Side is unknowable from a flat screen read, so every
     * group is filed as the other person (and [OCR_NOTE] says so on the panel).
     */
    private fun groupOcrLines(lines: List<OcrLine>): List<Msg> {
        val usable = lines
            .filter { it.text.isNotBlank() && !PURE_TIME.matches(it.text.trim()) }
            .sortedBy { it.bounds.top }
        val out = ArrayList<Msg>()
        val buf = StringBuilder()
        var prev: OcrLine? = null
        for (l in usable) {
            val p = prev
            if (p != null) {
                val gap = l.bounds.top - p.bounds.bottom
                val lineHeight = maxOf(p.bounds.height(), 1)
                if (gap > lineHeight * 1.2f) {
                    if (buf.isNotEmpty()) { out.add(Msg("other", buf.toString())); buf.setLength(0) }
                }
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(l.text.trim())
            prev = l
        }
        if (buf.isNotEmpty()) out.add(Msg("other", buf.toString()))
        return out
    }

    /** Strip the read receipt and the timestamp Feishu glues onto a bubble. */
    private fun cleanBubbleText(raw: String): String {
        var t = raw.trim()
        var changed = true
        while (changed && t.isNotEmpty()) {
            changed = false
            for (tail in arrayOf("已读", "未读")) {
                if (t.endsWith(tail)) { t = t.removeSuffix(tail).trim(); changed = true }
            }
            TAIL_TIME.find(t)?.let { t = t.substring(0, it.range.first).trim(); changed = true }
        }
        return t
    }

    /** Shared tail of both OCR paths: dedupe, then analyze or park the bubble. */
    private fun finishOcrSnapshot(snapshot: ChatSnapshot, pkg: String, manual: Boolean, token: ConversationSession.Token) {
        ocrBusy = false
        if (!isCurrent(token)) return
        // Counts only — OCR'd chat text never goes to logcat.
        Log.i(TAG, "ocr[$pkg] msgs=${snapshot.messages.size} manual=$manual")
        if (snapshot.messages.isEmpty()) {
            if (manual) overlay?.showError("这一屏没认出文字")
            return
        }
        if (!prefs.isAllowed(snapshot.title)) { leaveConversation(); overlay?.hide(); return }

        if (pkg.isNotEmpty() && pkg != activePkg) { activePkg = pkg; lastSignature = "" }
        currentSnapshot = snapshot
        overlay?.setApp(pkg.ifEmpty { null })
        val sig = snapshot.signature()
        // Manual taps always re-run; the automatic path dedupes like the tree path.
        if (!manual && sig == lastSignature) {
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            return
        }
        lastSignature = sig
        // Same rule as the tree path: only a first look or a new incoming message
        // starts an automatic round; scrolling keeps the card as it is.
        val verdict = gate.observe(snapshot.messages)
        val fresh = verdict == NewMessageGate.Verdict.FIRST_LOOK || verdict == NewMessageGate.Verdict.NEW_FROM_OTHER
        val auto = fresh && prefs.ocrAutoAnalyze && prefs.autoAnalyze && snapshot.latestFrom == "other"
        if (manual || auto) {
            submitSnapshot(snapshot)
        } else {
            overlay?.setNote(snapshot.note)
            overlay?.showIdle(snapshot.title)
        }
    }

    private fun findInputForPkg(pkg: String, root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val input = when (pkg) {
            "com.tencent.mobileqq" -> root.findAccessibilityNodeInfosByViewId("com.tencent.mobileqq:id/input").firstOrNull()
            "com.ss.android.lark" -> root.findAccessibilityNodeInfosByViewId("com.ss.android.lark:id/kb_rich_text_content").firstOrNull()
            WeChatRules.PACKAGE -> root.findAccessibilityNodeInfosByViewId(WeChatRules.INPUT_ID).firstOrNull() ?: findEditable(root)
            "com.twitter.android" -> findEditable(root)
            else -> null // Unknown apps support explicit clipboard copy, not unverified writes.
        }
        // Re-read the node after SET_TEXT: the accessibility cache may still
        // contain the previous draft even though the write already succeeded.
        input ?: return null
        if (!input.refresh()) return null
        return input.takeIf { it.isVisibleToUser && it.isEnabled && !it.isPassword }
    }

    /** Resolve only the originating chat's input, never an arbitrary foreground editor. */
    private fun inputFor(token: ConversationSession.Token): AccessibilityNodeInfo? {
        if (!isCurrent(token)) return null
        val root = rootInActiveWindow ?: return null
        if (targetFor(root) != token.target) return null
        return findInputForPkg(token.target.pkg, root)
    }

    private fun isTreeReviewAlive(review: TreeReview): Boolean {
        if (destroyed || !prefs.enabled || treeReview !== review) return false
        val root = rootInActiveWindow ?: return false
        return root.packageName?.toString() == review.pkg && root.windowId == review.windowId &&
            prefs.isAllowed(review.snapshot.title)
    }

    private fun inputForReview(review: TreeReview): AccessibilityNodeInfo? {
        if (!isTreeReviewAlive(review)) return null
        val root = rootInActiveWindow ?: return null
        return findInputForPkg(review.pkg, root)
    }

    /** Fill without blocking the main thread; all retries re-resolve the original target. */
    private fun fillInput(token: ConversationSession.Token, text: String) {
        executeFill(
            isLive = { isCurrent(token) },
            resolveInput = { inputFor(token) },
            text = text
        )
    }

    private fun fillInputForReview(review: TreeReview, text: String) {
        executeFill(
            isLive = { isTreeReviewAlive(review) },
            resolveInput = { inputForReview(review) },
            text = text
        )
    }

    private fun executeFill(
        isLive: () -> Boolean,
        resolveInput: () -> AccessibilityNodeInfo?,
        text: String
    ) {
        fun finish(ok: Boolean) {
            if (!isLive()) return
            if (ok) overlay?.toast("已填入，确认后自己发送")
            else { copyToClipboard(text); overlay?.toast("已复制，长按输入框粘贴") }
        }
        if (!isLive()) { overlay?.toast("会话已变化，请重新分析后填入"); return }
        if (resolveInput() == null) { finish(false); return }
        GuardedInputWriter(
            resolve = {
                resolveInput()?.let { node ->
                    object : GuardedInputWriter.Input {
                        override val text: String? get() = node.text?.toString()
                        override fun setText(text: String) = setTextRaw(node, text)
                        override fun focus() { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                        override fun paste() { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }
                    }
                }
            },
            later = { delay, action -> main.postDelayed({ action() }, delay) },
            copy = { copyToClipboard(it) },
            complete = { finish(it) }
        ).fill(text)
    }

    private fun setTextRaw(edit: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        var found: AccessibilityNodeInfo? = null
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            if (node.isEditable && node.isVisibleToUser && node.isEnabled && !node.isPassword) {
                if (found != null) return null // Ambiguous editor: clipboard only.
                found = node
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        return if (stack.isEmpty()) found else null
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
    }

    override fun onInterrupt() {
        leaveConversation()
        overlay?.hide()
    }

    override fun onDestroy() {
        destroyed = true
        ShareTasks.removeListener(onEditorOverlayChanged)
        getSharedPreferences(Prefs.PREFS_MAIN, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(preferencesListener)
        leaveConversation()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
        // Tear the overlay down and cut its callback so a stale button tap can
        // never call back into this dead instance.
        overlay?.onManualAnalyze = null
        overlay?.onSaveContact = null
        overlay?.onOcrCapture = null
        overlay?.onReadTree = null
        overlay?.hide()
        overlay = null
        worker.shutdownNow()
    }

    companion object {
        private const val TAG = "JEVASSIST"
        private const val TREE_LOG_TAG = "JevManualTree"
        private const val TREE_READ_FEEDBACK_MS = 5000L
        private const val TREE_REVIEW_PREVIEW_MS = 20_000L

        /** Hard ceiling on one round's judge/reply HTTP. HttpURLConnection bounds
         *  connect/read per attempt but NOT DNS resolution, so a wedged lookup
         *  could hold a round's callback forever (panel stuck on 生成中, every
         *  later round silently dropped). Deliberately shorter than HttpJson's
         *  own worst retry tail (~165s). 30s each: past that the user would rather
         *  see "生成超时" with a retry than keep waiting. */
        private const val JUDGE_TIMEOUT_MS = 30_000L
        private const val REPLY_TIMEOUT_MS = 30_000L

        /** Grace period before a "not a chat" read really leaves the conversation. */
        private const val LEAVE_CONFIRM_MS = 1200L

        /** Apps that are never read: they protect their content against screen
         *  reading or screenshots, so any capture attempt there is off-limits.
         *  No adapter, no capture, only a one-time "not supported" notice. */
        /** Supported apps that are read through the node tree only and never
         *  screenshotted: no OCR fallback, no manual "截屏识别一次". */
        private val NO_SCREENSHOT_PKGS = setOf(WhatsAppRules.PACKAGE)

        private val BLOCKED_PKGS = setOf("cn.soulapp.android")

        /** Shown once per visit to a blocked app. Plain words, full-width
         *  punctuation; steers the user to a supported app. */
        private const val BLOCKED_MSG =
            "这个应用暂不支持，请在别的软件上使用"

        /** How long a transient non-chat foreground must persist before the
         *  bubble is taken away (#18). */
        private const val FOREGROUND_CONFIRM_MS = 1500L

        /** Whole-screen OCR keeps the middle: no action bar, no input area. */
        private const val TOP_CROP = 0.12f
        private const val BOTTOM_CROP = 0.84f

        /** Said on the panel whenever a snapshot came from flat-screen OCR. */
        private const val OCR_NOTE = "OCR 未分边，把全部消息当作对方所说"

        private val PURE_TIME = Regex("""\d{1,2}[:：]\d{2}""")
        private val TAIL_TIME = Regex("""\d{1,2}[:：]\d{2}$""")

        /** Transient placeholder titles apps show while a chat page is still
         *  connecting/loading — see [isTransientTitle]. Matched as a substring,
         *  case-insensitive, after trimming a trailing ellipsis. */
        private val TRANSIENT_TITLE_WORDS = listOf(
            "连接中", "正在连接", "未连接", "Connecting",
            "加载中", "Loading", "同步中", "Syncing"
        )
    }
}
