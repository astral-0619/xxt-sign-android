package com.xxt.sign.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xxt.sign.R
import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.LogStore
import com.xxt.sign.core.Settings
import com.xxt.sign.databinding.ActivityCourseBinding
import com.xxt.sign.study.CourseAuditStore
import com.xxt.sign.study.ChapterRow
import com.xxt.sign.study.CourseAudit
import com.xxt.sign.study.CourseRunQueue
import com.xxt.sign.study.RunGate
import com.xxt.sign.study.RunMode
import com.xxt.sign.study.displayState
import com.xxt.sign.study.stageProgress
import com.xxt.sign.study.StudyCourse
import com.xxt.sign.study.StudyCourseStore
import com.xxt.sign.study.displayState
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch

/**
 * 课程页：一个「刷课」按钮 + 一个「做题」按钮 + 逐章进度，别的都不放。
 *
 * 文案只说人话（正在刷视频、正在做测验、等你到教室签个到）。
 * 两个入口共用进程级互斥（视频固定 1 路）：一方跑着，另一方禁用并写清原因。
 */
class CourseActivity : AppCompatActivity() {

    private lateinit var b: ActivityCourseBinding
    private var course: StudyCourse? = null
    private val chapterAdapter = ChapterRowAdapter()

    /** 已经自动滚过的章：只在「正在刷的章变了」时滚，不跟用户抢滚动手势 */
    private var lastScrolledChapter = ""
    private var lastLoggedSignature = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCourseBinding.inflate(layoutInflater)
        setContentView(b.root)

        val key = intent.getStringExtra(EXTRA_COURSE_KEY).orEmpty()
        val account = AccountStore.get(Settings.studyAccountId)
        course = account?.let { StudyCourseStore.load(it.id) }
            ?.courses?.firstOrNull { it.key == key }

        b.toolbar.setNavigationOnClickListener { finish() }
        b.listChapters.layoutManager = LinearLayoutManager(this)
        b.listChapters.adapter = chapterAdapter
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })

        val c = course
        if (c == null) {
            b.toolbar.title = "没找到这门课"
            b.btnRunCourse.isEnabled = false
            b.btnRunQuiz.isEnabled = false
            return
        }
        b.toolbar.title = c.title
        if (AccountStore.get(Settings.studyAccountId) == null) {
            // 没有账号就别让按钮看起来能点
            b.tvStateLine.text = "先去「账号」页加一个账号"
            b.btnRunCourse.isEnabled = false
            b.btnRunQuiz.isEnabled = false
        } else {
            b.btnRunCourse.setOnClickListener { CourseRunQueue.toggle(c, RunMode.COURSE) }
            b.btnRunQuiz.setOnClickListener { CourseRunQueue.toggle(c, RunMode.QUIZ) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CourseRunQueue.states.collect { render() }
            }
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val c = course ?: return
        val run = CourseRunQueue.stateOf(c.key)
        val audit = CourseAuditStore.loadCurrent().firstOrNull { it.courseId == c.courseId }
        val runningMode = run?.takeIf { it.running || it.phase == CourseRunQueue.Phase.QUEUED }?.mode

        val done = run?.donePoints ?: audit?.doneTaskPoints ?: 0
        val total = run?.totalPoints ?: audit?.taskPoints ?: 0

        // 一行就够：在跑/暂停/跑完都直接用它的人话，不再按 phase 过滤（否则暂停后这行会消失）
        val runningText = run?.message.orEmpty().takeIf { it.isNotBlank() && it != "没在跑" }.orEmpty()
        val progressText = if (total > 0) "任务点 $done/$total" else ""
        // 另一方被互斥挡住的原因（点了也没用，得说清楚为什么）
        val blockedText = listOf(RunMode.COURSE, RunMode.QUIZ).mapNotNull { m ->
            if (runningMode == m) return@mapNotNull null
            val v = CourseRunQueue.verdictFor(c, m)
            v.reason.takeIf { !v.allowed && it.isNotBlank() }
        }.firstOrNull().orEmpty()
        val line = listOf(runningText, progressText, blockedText).filter { it.isNotBlank() }.joinToString(" · ")
        b.tvStateLine.visibility = if (line.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        b.tvStateLine.text = line

        // 两个入口各自一条进度：刷课（视频/文档）· 做题（workA 测验）
        if (audit == null) {
            b.tvModeProgress.visibility = android.view.View.GONE
        } else {
            b.tvModeProgress.visibility = android.view.View.VISIBLE
            b.tvModeProgress.text = listOf(
                audit.stageProgress(RunMode.COURSE).text(RunMode.COURSE.label),
                audit.stageProgress(RunMode.QUIZ).text(RunMode.QUIZ.label)
            ).joinToString(" · ")
        }

        // 进度条：0 就画 0%（之前底槽 + 尾端小圆点看起来像满格）
        val percent = if (total > 0) (done * 100L / total).toInt().coerceIn(0, 100) else 0
        b.tvProgressText.visibility = android.view.View.VISIBLE
        b.tvProgressText.text = if (total > 0) {
            "任务点 $done/$total · $percent%"
        } else {
            "还没拉过目录，点刷课或做题"
        }
        b.progress.visibility = if (total > 0) android.view.View.VISIBLE else android.view.View.GONE
        b.progress.setProgressCompat(percent, false)

        // 逐章列表：**有体检行就只用体检行**，缓存只用来补体检没跑过的章。
        // 行状态由 done/total 现算，不信任持久化里的旧状态字符串（旧版本存的是图标口径）。
        val current = run?.currentChapter.orEmpty()
        val runningNow = run?.running == true && current.isNotBlank()
        val cachedChapters = AccountStore.get(Settings.studyAccountId)
            ?.let { StudyCourseStore.load(it.id) }
            ?.chapters?.get(c.key).orEmpty()
        val auditRows = audit?.chapterRows.orEmpty()
        val storedVersion = audit?.rowsVersion ?: 0
        // 版本对，或者行里有 done/total 明细 → 都能按任务点现算；啥都没有才退缓存
        val rowsTrusted = storedVersion >= CourseAudit.ROWS_VERSION || auditRows.any { it.total > 0 }
        val fromAudit = rowsTrusted && auditRows.isNotEmpty()
        val source = when {
            !fromAudit -> "缓存"
            storedVersion >= CourseAudit.ROWS_VERSION -> "体检"
            else -> "体检（旧行按明细重算 v$storedVersion）"
        }
        val chapterRows = if (fromAudit) {
            auditRows.map { r ->
                ChapterRowAdapter.Row(
                    title = r.title,
                    done = r.done,
                    total = r.total,
                    // 台账里没明细的章按「未开始」显示，不再出现「没查到」
                    state = r.displayState(),
                    running = runningNow && r.title == current,
                    videoDone = r.videoDone,
                    videoTasks = r.videoTasks
                )
            }
        } else {
            cachedChapters.map { ch ->
                ChapterRowAdapter.Row(
                    title = ch.title,
                    done = 0,
                    total = ch.jobCount.coerceAtLeast(0),
                    // 目录缓存里每章本来就带任务点个数：算「未开始」，不是「没查到」
                    state = when {
                        ch.finished -> ChapterRow.State.DONE
                        ch.needUnlock -> ChapterRow.State.LOCKED
                        else -> ChapterRow.State.TODO
                    },
                    running = runningNow && ch.title == current
                )
            }
        }
        chapterAdapter.submit(chapterRows)

        val finishedChapters = chapterRows.count { it.state == ChapterRow.State.DONE }
        val partialChapters = chapterRows.count { it.state == ChapterRow.State.PARTIAL }
        b.tvChapterHeader.visibility = if (chapterRows.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        b.tvChapterHeader.text = "章节 · $finishedChapters/${chapterRows.size} 章已完成"

        // 真机一眼可见的验收日志：计数口径 + 行状态到底从哪来
        val signature = "${chapterRows.size}/${auditRows.size}/${cachedChapters.size}/$finishedChapters/$current/$source"
        if (signature != lastLoggedSignature) {
            lastLoggedSignature = signature
            LogStore.add(
                "[课程页] ${c.title} 已完成章 $finishedChapters/${chapterRows.size}" +
                    " · 部分完成 $partialChapters · 行状态来源 $source" +
                    " · 列表 ${chapterRows.size} 行（体检 ${auditRows.size} / 缓存 ${cachedChapters.size}）" +
                    "，正在刷：${current.ifBlank { "—" }}",
                chapterRows.isNotEmpty() && fromAudit
            )
            if (chapterRows.isEmpty()) {
                LogStore.add("[课程页] 章节列表是空的：体检结果里有这门课的章节行吗？课程 id=${c.courseId}", false)
            }
            if (fromAudit) {
                badRowsRounds.remove(c.key)
                versionAlarmed.remove(c.key)
            } else {
                // 目录底账也没有时最多容忍一轮：连续两轮还不可信才告警，且每次进入只告一次
                val rounds = (badRowsRounds[c.key] ?: 0) + 1
                badRowsRounds[c.key] = rounds
                if (rounds >= 2 && versionAlarmed.add(c.key)) {
                    LogStore.add(
                        "[课程页] 体检行版本异常：存的 v$storedVersion / 期望 v${CourseAudit.ROWS_VERSION}" +
                            "（连续 $rounds 轮没拿到可用行，先按目录底账/未开始显示）",
                        false
                    )
                } else {
                    LogStore.add("[课程页] 这轮还没有归集行，先按目录缓存显示（点刷课/做题会拉目录归集）", false)
                }
            }
        }

        // 当前正在刷的那章自动滚到可见位置（只跟换章时滚一次）
        if (runningNow && current != lastScrolledChapter) {
            val index = chapterRows.indexOfFirst { it.running }
            if (index >= 0) {
                lastScrolledChapter = current
                b.listChapters.post {
                    (b.listChapters.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(index, b.listChapters.height / 3)
                }
            }
        }

        val needs = run?.needsYou.orEmpty().ifBlank { if ((audit?.humanPending ?: 0) > 0) "有要你本人处理的事" else "" }
        b.tvNeedsYou.visibility = if (needs.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        b.tvNeedsYou.text = needs

        // 两个按钮：自己那轮在跑 → 暂停；另一方在跑 → 禁用（原因已写在状态行）
        listOf(RunMode.COURSE to b.btnRunCourse, RunMode.QUIZ to b.btnRunQuiz).forEach { (mode, btn) ->
            val ownRunning = runningMode == mode
            val allowed = ownRunning || CourseRunQueue.verdictFor(c, mode).allowed
            btn.text = if (ownRunning) "暂停" else mode.label
            btn.isEnabled = allowed
            btn.alpha = if (allowed) 1f else 0.5f
        }
        val running = run?.running == true || run?.phase == CourseRunQueue.Phase.QUEUED

        // 运行期间让屏幕别自己灭（锁屏测试用：不按电源键就不会黑）；暂停/跑完立即清掉
        if (running) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    companion object {
        const val EXTRA_COURSE_KEY = "course_key"

        /** 连续几轮拿不到可用体检行（进程内计数，重启归零） */
        private val badRowsRounds = mutableMapOf<String, Int>()

        /** 版本异常只告一次，不刷屏 */
        private val versionAlarmed = mutableSetOf<String>()
    }
}
