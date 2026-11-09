package com.xxt.sign.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录页归集 + 两个入口互斥。
 *
 * 真机问题（Pixel 5）：课程页逐章列表里几乎所有未完成章节都是「没查到」。
 * 根因是逐章行只有逐卡体检（`knowledge/cards`）才写，没访问到的章全是空行，
 * 界面按 `stateByTaskPoints()` 一算就掉进 UNCHECKED。
 *
 * 现在目录页（`studentcourse`）一到手就按 **chapter id** 把每章任务点个数/已完成数/状态
 * 归集落盘，列表立刻可用；目录也没覆盖到的章按「未开始」显示，不再有兜底文案。
 */
class ChapterDirectoryTest {

    private fun chapter(
        id: String,
        title: String,
        jobs: Int,
        finished: Boolean = false,
        locked: Boolean = false
    ) = StudyChapter(id = id, title = title, jobCount = jobs, finished = finished, needUnlock = locked)

    private fun video(jobId: String, passed: Boolean) =
        CardTask(key = jobId, type = "video", passed = passed)

    private fun quiz(jobId: String, passed: Boolean = false) =
        CardTask(key = jobId, type = "workid", passed = passed, module = "work", worktype = "workA")

    private fun emptyAudit() = CourseAudit(courseId = "c1", courseTitle = "大学体育与健康")

    /** 本账号真实规模：88 章、392 个任务点 */
    private fun realSizeChapters(): List<StudyChapter> {
        val jobCounts = List(88) { i -> if (i < 10) 8 else 4 } // 80 + 312 = 392
        return jobCounts.mapIndexed { i, jobs ->
            chapter("cur${1000 + i}", "第 ${i + 1} 章", jobs, finished = i < 6)
        }
    }

    // ------------------------------------------------ 按章归集（392 口径）

    @Test
    fun directoryAggregatesTaskPointsPerChapter() {
        val chapters = realSizeChapters()
        val audit = emptyAudit().withDirectoryChapters(chapters)

        assertEquals("一章一行，不多不少", chapters.size, audit.chapterRows.size)
        assertEquals("任务点总数就是目录每章个数之和（本账号 392）", 392, audit.taskPoints)
        assertEquals(392, audit.chapterRows.sumOf { it.total })
        assertEquals("已完成 6 章 × 8 个 = 48", 48, audit.doneTaskPoints)

        chapters.forEachIndexed { i, ch ->
            val row = audit.chapterRows[i]
            assertEquals(ch.title, row.title)
            assertEquals("归集要带上 chapter id，后面回写/合并都按它", ch.id, row.chapterId)
            assertEquals(ch.jobCount, row.total)
            assertEquals(if (ch.finished) ch.jobCount else 0, row.done)
            assertEquals(
                if (ch.finished) ChapterRow.State.DONE else ChapterRow.State.TODO,
                row.displayState()
            )
        }
    }

    @Test
    fun aggregationIsKeyedByChapterIdNotTitle() {
        // 老师真的会把两章起同样的名字；按标题归集就会串行
        val chapters = listOf(
            chapter("cur1", "第一章 绪论", 3),
            chapter("cur2", "第一章 绪论", 5, finished = true)
        )
        val audit = emptyAudit().withDirectoryChapters(chapters)

        assertEquals(2, audit.chapterRows.size)
        assertEquals("cur1", audit.chapterRows[0].chapterId)
        assertEquals(3, audit.chapterRows[0].total)
        assertEquals(0, audit.chapterRows[0].done)
        assertEquals("cur2", audit.chapterRows[1].chapterId)
        assertEquals(5, audit.chapterRows[1].total)
        assertEquals(5, audit.chapterRows[1].done)
    }

    @Test
    fun lockedChapterStaysLocked() {
        val audit = emptyAudit().withDirectoryChapters(
            listOf(chapter("cur9", "第 9 章", 4, locked = true))
        )
        assertEquals(ChapterRow.State.LOCKED, audit.chapterRows.single().displayState())
    }

    @Test
    fun rerunIsIdempotent() {
        val chapters = realSizeChapters()
        val once = emptyAudit().withDirectoryChapters(chapters)
        val twice = once.withDirectoryChapters(chapters)
        val thrice = twice.withDirectoryChapters(chapters)

        assertEquals("重跑不重复加行", once.chapterRows.size, twice.chapterRows.size)
        assertEquals(once, twice)
        assertEquals(twice, thrice)
        assertEquals(392, thrice.taskPoints)
    }

    // ------------------------------------------------ 「没查到」回归

    @Test
    fun unscannedChaptersNeverShowUnchecked() {
        // 体检只逐卡扫到第 1 章；第 2 章扫描失败、第 3 章压根没跑到 —— 真机上就是这三类空行
        val scanned = CourseAuditCalculator.audit(
            "c1", "大学体育与健康",
            listOf(
                ChapterAuditInput("第 1 章", summarizeChapter(listOf(video("v1", false))), "cur1"),
                ChapterAuditInput("第 2 章", ChapterCheck(1, 0, 1, 0, scanFailed = true), "cur2"),
                ChapterAuditInput("第 3 章", null, "cur3")
            )
        )
        // 修之前：两张空行 → UNCHECKED → 界面「没查到」
        assertEquals(2, scanned.chapterRows.count { it.state == ChapterRow.State.UNCHECKED })

        val merged = scanned.withDirectoryChapters(
            listOf(
                chapter("cur1", "第 1 章", 1),
                chapter("cur2", "第 2 章", 4),
                chapter("cur3", "第 3 章", 3)
            )
        )
        assertTrue(
            "目录归集后不允许再出现 UNCHECKED 行",
            merged.chapterRows.none { it.displayState() == ChapterRow.State.UNCHECKED }
        )
        assertEquals(ChapterRow.State.TODO, merged.chapterRows[0].displayState())
        assertEquals(4, merged.chapterRows[1].total)
        assertEquals(ChapterRow.State.TODO, merged.chapterRows[1].displayState())
        assertEquals(3, merged.chapterRows[2].total)
        assertEquals(8, merged.taskPoints)
        assertEquals(0, merged.doneTaskPoints)
    }

    @Test
    fun rowWithoutAnyDetailDisplaysTodo() {
        // 旧缓存/目录没覆盖到的行：0/0 也不许渲染成「没查到」
        val row = ChapterRow("5.1.1 起跑", total = 0, done = 0, state = ChapterRow.State.UNCHECKED)
        assertEquals(ChapterRow.State.TODO, row.displayState())
        // 旧状态口径（图标写下的 TODO）但任务点已经全完成 → 已完成
        assertEquals(
            ChapterRow.State.DONE,
            ChapterRow("x", total = 4, done = 4, state = ChapterRow.State.TODO).displayState()
        )
        // 未开放原样保留
        assertEquals(
            ChapterRow.State.LOCKED,
            ChapterRow("x", 0, 0, ChapterRow.State.LOCKED).displayState()
        )
    }

    @Test
    fun scannedDetailWinsOverDirectoryCount() {
        // 逐卡扫描过的章：真实 done/total 要保留，不能被目录的个数盖掉
        val scanned = CourseAuditCalculator.audit(
            "c1", "体育",
            listOf(
                ChapterAuditInput("第 1 章", summarizeChapter(listOf(video("v1", true), video("v2", false))), "cur1")
            )
        )
        val merged = scanned.withDirectoryChapters(listOf(chapter("cur1", "第 1 章", 6)))
        val row = merged.chapterRows.single()
        assertEquals("逐卡数出来的任务点数优先", 2, row.total)
        assertEquals(1, row.done)
        assertEquals(2, row.videoTasks)
        assertEquals(1, row.videoDone)
        assertEquals(ChapterRow.State.PARTIAL, row.displayState())
    }

    @Test
    fun iconFinishedChapterForcesDone() {
        // 文档/测验的 isPassed 不回填，逐卡数会偏小：图标说全完成就以图标为准
        val scanned = CourseAuditCalculator.audit(
            "c1", "体育",
            listOf(ChapterAuditInput("第 1 章", summarizeChapter(listOf(video("v1", true))), "cur1"))
        )
        val merged = scanned.withDirectoryChapters(listOf(chapter("cur1", "第 1 章", 3, finished = true)))
        val row = merged.chapterRows.single()
        assertEquals(ChapterRow.State.DONE, row.displayState())
        assertEquals(row.total, row.done)
    }

    @Test
    fun chaptersMissingFromDirectoryAreKept() {
        // 目录接口没覆盖到的章（缓存旧、转班）不丢，排在后面
        val scanned = CourseAuditCalculator.audit(
            "c1", "体育",
            listOf(
                ChapterAuditInput("第 1 章", summarizeChapter(listOf(video("v1", false))), "cur1"),
                ChapterAuditInput("第 8 章 新增", summarizeChapter(listOf(video("v8", false))), "cur8")
            )
        )
        val merged = scanned.withDirectoryChapters(listOf(chapter("cur1", "第 1 章", 1)))
        assertEquals(2, merged.chapterRows.size)
        assertEquals("cur8", merged.chapterRows[1].chapterId)
        assertNotEquals(ChapterRow.State.UNCHECKED, merged.chapterRows[1].displayState())
    }

    // ------------------------------------------------ 两个入口的进度

    @Test
    fun stageProgressSplitsCourseAndQuiz() {
        val audit = CourseAuditCalculator.audit(
            "c1", "体育",
            listOf(
                ChapterAuditInput(
                    "第 1 章",
                    summarizeChapter(
                        listOf(
                            video("v1", true), video("v2", false), video("v3", false),
                            quiz("work-1", passed = true), quiz("work-2")
                        )
                    ),
                    "cur1"
                )
            )
        )
        val course = audit.stageProgress(RunMode.COURSE)
        assertEquals(3, course.total)
        assertEquals(1, course.done)
        assertEquals(2, course.pending)
        assertEquals("刷课 1/3", course.text("刷课"))

        val quiz = audit.stageProgress(RunMode.QUIZ)
        assertEquals(2, quiz.total)
        assertEquals(1, quiz.done)
        assertEquals("做题 1/2", quiz.text("做题"))
    }

    @Test
    fun stageProgressFallsBackToPendingWhenDenominatorUnknown() {
        // 只做了目录归集（没逐卡扫描）→ 不知道分母，只说还差多少，不算假进度
        val audit = emptyAudit()
            .withDirectoryChapters(listOf(chapter("cur1", "第 1 章", 4)))
            .copy(videoPending = 3, quizPending = 2)

        assertFalse(audit.stageProgress(RunMode.COURSE).known)
        assertEquals("刷课 还差 3", audit.stageProgress(RunMode.COURSE).text("刷课"))
        assertEquals("做题 还差 2", audit.stageProgress(RunMode.QUIZ).text("做题"))
    }

    // ------------------------------------------------ 两入口互斥

    @Test
    fun bothEntriesAllowedWhenNothingRuns() {
        assertTrue(RunGate.verdict(RunMode.COURSE, "c1:1", emptyList()).allowed)
        assertTrue(RunGate.verdict(RunMode.QUIZ, "c1:1", emptyList()).allowed)
    }

    @Test
    fun quizIsBlockedWhileCourseRunIsActive() {
        val active = listOf(RunGate.Active("c1:1", RunMode.COURSE, "大学体育"))
        val quiz = RunGate.verdict(RunMode.QUIZ, "c1:1", active)
        assertFalse("刷课跑着的时候做题必须禁用", quiz.allowed)
        assertTrue("要说清原因：正在刷课", quiz.reason.contains("刷课"))
        assertTrue("要说清为什么不能并行", quiz.reason.contains("视频固定 1 路"))

        val same = RunGate.verdict(RunMode.COURSE, "c1:1", active)
        assertFalse("自己这轮在跑时按钮是「暂停」，不是再点一次开始", same.allowed)
    }

    @Test
    fun courseRunIsBlockedWhileQuizIsActive() {
        val active = listOf(RunGate.Active("c1:1", RunMode.QUIZ, "大学体育"))
        val course = RunGate.verdict(RunMode.COURSE, "c1:1", active)
        assertFalse("做题跑着的时候刷课必须禁用", course.allowed)
        assertTrue(course.reason.contains("做题"))
    }

    @Test
    fun otherCourseRunningAlsoBlocks() {
        val active = listOf(RunGate.Active("c2:2", RunMode.COURSE, "高数"))
        val verdict = RunGate.verdict(RunMode.QUIZ, "c1:1", active)
        assertFalse(verdict.allowed)
        assertTrue("视频固定 1 路：别的课在跑也不能并着开", verdict.reason.contains("高数"))
    }

    @Test
    fun videoWriteBackDoesNotShrinkChapterTotal() {
        // 一章 4 个任务点（2 视频 + 2 文档）：视频刷完不能把整章算成 2 个点的已完成
        val audit = emptyAudit().withDirectoryChapters(listOf(chapter("cur1", "第 1 章", 4)))
        val step = audit.withChapterVideoProgress("第 1 章", done = 2, total = 2)!!
        val row = step.chapterRows.single()
        assertEquals("整章分母只增不减", 4, row.total)
        assertEquals(2, row.videoTasks)
        assertEquals(ChapterRow.State.PARTIAL, row.displayState())
    }

    // ------------------------------------------------ 落盘往返

    @Test
    fun directoryRowsSurviveStoreRoundTrip() {
        val audit = emptyAudit()
            .withDirectoryChapters(realSizeChapters())
            .copy(quizPending = 2)
        val back = CourseAuditJson.decode(CourseAuditJson.encode(listOf(audit))).single()

        assertEquals(392, back.taskPoints)
        assertEquals(audit.chapterRows.size, back.chapterRows.size)
        assertEquals("cur1000", back.chapterRows[0].chapterId)
        assertEquals(8, back.chapterRows[0].total)
        assertEquals(CourseAudit.ROWS_VERSION, back.rowsVersion)
        // 归集后重新归集一遍仍然是同一份（真机上每次点刷课/做题都会重跑一次）
        assertEquals(back, back.withDirectoryChapters(realSizeChapters()).copy(quizPending = 2))
    }
}
