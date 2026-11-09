package com.xxt.sign.study

/**
 * 课程页的两个入口：**刷课**（视频 + 文档）和**做题**（workA 章节测验）。
 *
 * 为什么拆开：用户实机反馈「刷课」和「做题」是两件事 ——
 * 刷课要占着视频播放（固定 1 路、慢），做题是纯 HTTP 请求（快）；
 * 混在一个「开始」里，用户既不知道现在在干嘛，也没法只做其中一件。
 */
enum class RunMode(val label: String) {
    /** 视频 + 文档任务点 */
    COURSE("刷课"),

    /** workA 章节测验 */
    QUIZ("做题");

    val other: RunMode get() = if (this == COURSE) QUIZ else COURSE
}

/**
 * 两个入口的互斥判断（进程级：同一时刻只跑一条流水线，视频固定 1 路）。
 *
 * 纯逻辑：按钮禁用、原因文案都由它出，单测直接喂状态即可，不碰 Android。
 */
object RunGate {

    /** 正在跑的一条流水线（够判断互斥就行） */
    data class Active(val courseKey: String, val mode: RunMode, val title: String = "")

    data class Verdict(val allowed: Boolean, val reason: String = "") {
        companion object {
            val OK = Verdict(true)
        }
    }

    /**
     * 能不能点这个入口。
     *
     * - 这门课正在跑同一个入口 → 不允许（按钮此时是「暂停」，不靠这条文案）
     * - 这门课正在跑另一个入口 → 不允许，说明原因（做题 ↔ 刷课）
     * - 别的课在跑 → 也不允许：视频固定 1 路，排队不如直接告诉用户
     */
    fun verdict(mode: RunMode, courseKey: String, actives: Collection<Active>): Verdict {
        val mineSame = actives.firstOrNull { it.courseKey == courseKey && it.mode == mode }
        if (mineSame != null) return Verdict(false, "正在${mode.label}，要停就点暂停")

        val mineOther = actives.firstOrNull { it.courseKey == courseKey }
        if (mineOther != null) {
            return Verdict(false, "正在${mineOther.mode.label}，${mode.label}要等它跑完（视频固定 1 路）")
        }

        val anywhere = actives.firstOrNull()
        if (anywhere != null) {
            val who = anywhere.title.takeIf { it.isNotBlank() }?.let { "《$it》" }.orEmpty()
            return Verdict(false, "$who 正在${anywhere.mode.label}，等它跑完再${mode.label}（视频固定 1 路）")
        }
        return Verdict.OK
    }
}
