package com.example.nikond300controller

/**
 * 二级页面（白平衡 / 包围曝光 / 自动 ISO）的统一样式构件。
 *
 * 这些页面原来各自手搓 View：按钮用系统 MaterialButton、输入框白底、
 * 开关默认蓝、进度条默认蓝——在主界面这块深色盘面上很跳。
 * 这里把说服力收回到一套 token 上：同一张卡片底、同一种 48dp 行高、
 * 同一个琥珀强调色，跟参数页完全一致；具体业务（PTP 读写）仍留在 MainActivity 里。
 */
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch

// ---------------------------------------------------------------- 基础度量

private fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

private fun Context.color(@ColorRes id: Int): Int = ContextCompat.getColor(this, id)

private val Context.matchParent: Int get() = ViewGroup.LayoutParams.MATCH_PARENT
private val Context.wrapContent: Int get() = ViewGroup.LayoutParams.WRAP_CONTENT

// ---------------------------------------------------------------- 抽屉外壳

/**
 * 宿主 Activity 可以实现它，用来感知"现在有抽屉打开"。
 *
 * 用途：菜单/二级页打开期间，主机身那套周期性属性轮询应该停手 ——
 * 用户在里面选档位、写属性，后台还在 5 秒一轮地读 9 条属性，
 * 既抢同一条 USB 通道（菜单响应变慢），也让机身一直停在"被问话"的忙碌态。
 */
interface SheetHost {
    fun onSheetOpened()
    fun onSheetClosed()
}

class SheetPage(
    val dialog: BottomSheetDialog,
    /** 往里堆内容的那个竖向容器（已经在 ScrollView 里，超长可滚） */
    val content: LinearLayout
) {
    /**
     * 关闭回调做成**列表**而不是单个 listener：
     * BottomSheetDialog 只保留一个 OnDismissListener，谁后设谁生效 ——
     * 以前各处自己 setOnDismissListener，外壳（要通知宿主恢复轮询）就没地方挂了。
     */
    private val dismissHooks = mutableListOf<() -> Unit>()

    fun onDismiss(hook: () -> Unit) {
        dismissHooks.add(hook)
    }

    fun show() {
        val host = dialog.context as? SheetHost
        dialog.setOnDismissListener {
            host?.onSheetClosed()
            dismissHooks.forEach { runCatching { it() } }
        }
        dialog.show()
        host?.onSheetOpened()
    }
}

/**
 * 建一个跟主界面同款的底部抽屉：把手 + 居中标题 + 分隔线 + 可滚内容区。
 * 系统 BottomSheet 默认是白底圆角，这里显式铺上深色画布，免得圆角上沿浮出白边。
 */
fun Context.buildSheetPage(title: String, subtitle: String? = null): SheetPage {
    val shell = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_sheet)
    }

    shell.addView(View(this).apply {
        setBackgroundResource(R.drawable.bg_sheet_handle)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(10)
            bottomMargin = dp(6)
        }
    })

    shell.addView(TextView(this).apply {
        text = title
        setTextColor(color(R.color.text_primary))
        textSize = 17f
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
            bottomMargin = dp(6)
        }
    })

    if (!subtitle.isNullOrEmpty()) {
        shell.addView(TextView(this).apply {
            text = subtitle
            setTextColor(color(R.color.text_secondary))
            textSize = 12f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                bottomMargin = dp(10)
            }
        })
    }

    shell.addView(View(this).apply {
        setBackgroundColor(color(R.color.stroke))
        layoutParams = LinearLayout.LayoutParams(matchParent, dp(1)).apply {
            bottomMargin = if (subtitle.isNullOrEmpty()) dp(8) else 0
        }
    })

    val scroll = ScrollView(this).apply { isFillViewport = false }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(24))
    }
    scroll.addView(content, ViewGroup.LayoutParams(matchParent, wrapContent))
    shell.addView(scroll, LinearLayout.LayoutParams(matchParent, wrapContent))

    val dialog = BottomSheetDialog(this)
    dialog.setContentView(shell)
    dialog.setOnShowListener {
        // 不换掉 Material 自带的底，圆角上沿会浮出一条白边
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            ?.background = null
    }
    return SheetPage(dialog, content)
}

// ---------------------------------------------------------------- 分组卡片

/**
 * 参数页那种带小标题的分组卡片。返回的是"卡片内部的容器"，子 View 直接往里加。
 */
fun Context.sheetCard(parent: LinearLayout, title: String? = null): LinearLayout {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(8), dp(10), dp(8), dp(10))
        layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
            topMargin = if (parent.childCount == 0) 0 else dp(12)
        }
    }
    if (!title.isNullOrEmpty()) {
        card.addView(TextView(this).apply {
            text = title
            setTextColor(color(R.color.text_secondary))
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(dp(6), 0, 0, dp(4))
        })
    }
    parent.addView(card)
    return card
}

// ---------------------------------------------------------------- 行控件

data class SheetValueRow(val view: LinearLayout, val value: TextView)

/**
 * "标签 + 当前值 + 箭头"的取值行：点一下弹出 showValueMenu 那种下拉。
 * 返回 vale TextView，选完直接改它的 text。
 */
fun Context.sheetValueRow(
    label: String,
    initial: String = "读取中…",
    topMarginDp: Int = 2,
    onClick: (View) -> Unit
): SheetValueRow {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_setting_row)
        setPadding(dp(14), 0, dp(10), 0)
        layoutParams = LinearLayout.LayoutParams(matchParent, dp(48)).apply {
            topMargin = dp(topMarginDp)
        }
        setOnClickListener(onClick)
    }

    row.addView(TextView(this).apply {
        text = label
        setTextColor(color(R.color.text_primary))
        textSize = 14f
    })

    row.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
    })

    val value = TextView(this).apply {
        text = initial
        setTextColor(color(R.color.accent_amber))
        textSize = 13f
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.END
    }
    row.addView(value)

    row.addView(ImageView(this).apply {
        setImageResource(R.drawable.ic_chevron_right)
        layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
            marginStart = dp(6)
        }
    })
    return SheetValueRow(row, value)
}

/** 左边说明右边数值的一行（总步数之类的静态信息） */
fun Context.sheetInfoRow(label: String, initial: String): Pair<LinearLayout, TextView> {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent)
    }
    row.addView(TextView(this).apply {
        text = label
        setTextColor(color(R.color.text_primary))
        textSize = 14f
    })
    row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })
    val value = TextView(this).apply {
        text = initial
        setTextColor(color(R.color.text_primary))
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
    }
    row.addView(value)
    return row to value
}

data class SheetSwitchRow(val view: LinearLayout, val switch: MaterialSwitch)

/** 开关行：跟取值行一样 48dp 一行，右边的 MaterialSwitch 染成琥珀 */
fun Context.sheetSwitchRow(
    label: String,
    checked: Boolean = false,
    topMarginDp: Int = 2,
    onChange: ((Boolean) -> Unit)? = null
): SheetSwitchRow {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_setting_row)
        setPadding(dp(14), 0, dp(8), 0)
        layoutParams = LinearLayout.LayoutParams(matchParent, dp(48)).apply {
            topMargin = dp(topMarginDp)
        }
    }
    row.addView(TextView(this).apply {
        text = label
        setTextColor(color(R.color.text_primary))
        textSize = 14f
        layoutParams = LinearLayout.LayoutParams(0, wrapContent, 1f)
    })

    val amber = color(R.color.accent_amber)
    val on = intArrayOf(android.R.attr.state_checked)
    val off = intArrayOf(-android.R.attr.state_checked)
    val states = arrayOf(on, off)

    val sw = MaterialSwitch(this).apply {
        isChecked = checked
        trackTintList = ColorStateList(states, intArrayOf(amber, Color.parseColor("#333C4A")))
        thumbTintList = ColorStateList(states, intArrayOf(Color.parseColor("#FFF6E6"), Color.parseColor("#8A94A6")))
        trackDecorationTintList = ColorStateList(states, intArrayOf(Color.parseColor("#5A3F00"), Color.parseColor("#5A6472")))
    }
    row.addView(sw)
    // 点整行也能切换：开关本身的热区太小
    row.setOnClickListener {
        sw.isChecked = !sw.isChecked
        onChange?.invoke(sw.isChecked)
    }
    sw.setOnCheckedChangeListener { _, isChecked -> onChange?.invoke(isChecked) }
    return SheetSwitchRow(row, sw)
}

// ---------------------------------------------------------------- 输入

fun Context.sheetNumberInput(
    hint: String,
    initial: String,
    unit: String? = null
): EditText {
    return EditText(this).apply {
        this.hint = hint
        setText(initial)
        inputType = InputType.TYPE_CLASS_NUMBER
        setTextColor(color(R.color.text_primary))
        setHintTextColor(color(R.color.text_disabled))
        setBackgroundResource(R.drawable.bg_field)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        textSize = 15f
        setTypeface(null, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            textCursorDrawable = ContextCompat.getDrawable(context, R.drawable.cursor_amber)
        }
    }
}

// ---------------------------------------------------------------- 网格块

data class SheetTileGrid(
    val view: View,
    /** 把第 index 块高亮（其余落回常态）；传 -1 清空选择 */
    val select: (Int) -> Unit,
    /**
     * 多块同时高亮。
     * 用途：ISO 菜单里自动 ISO 与手动值**不冲突**（说明书：开着自动时用户选的值仍是基准，
     * 曝光不足才上调），所以手动档和「自动」块要一起点亮。
     */
    val selectMany: (List<Int>) -> Unit,
    /**
     * 把第 index 块滚到抽屉可视区的**中间**。
     * 用途：快门 50 多档要滚好几屏，打开时若停在表头，用户得从 1/8000 一路划到 30s
     * 才知道自己在哪。居中之后上下滑都是"更远的档"，方向感才对。
     */
    val centerOn: (Int) -> Unit
)

/**
 * 把自己滚到所在 ScrollView 的垂直中间。
 *
 * 拿屏幕坐标算位移（而不是 view.top —— 那是相对直接父容器的），
 * 所以无论中间隔了多少层容器都成立。
 */
private fun View.centerInScrollView() {
    var p: ViewParent = parent
    while (p != null && p !is ScrollView) p = p.parent
    val sv = p as? ScrollView ?: return
    post {
        val me = IntArray(2)
        val host = IntArray(2)
        getLocationOnScreen(me)
        sv.getLocationOnScreen(host)
        val delta = (me[1] - host[1]) - (sv.height - height) / 2
        sv.smoothScrollBy(0, delta)
    }
}

/**
 * 白平衡模式 / 预设编号那种块状选择器。
 * 自己维护互斥：点哪块就选哪块，回调把下标抛给业务层。
 */
fun Context.sheetTileGrid(
    columns: Int,
    labels: List<String>,
    initialSelected: Int = -1,
    /** 长按某块的处理（返回 true 表示已消费）。
     *  用途：ISO 菜单里「自动」那块长按 = 进自动 ISO 的细化设置。
     *  ⚠️ 必须排在 onPick 前面：各处调用都用尾随 lambda 传 onPick，
     *  尾随 lambda 只会绑到最后一个参数上。 */
    onLongPick: ((Int) -> Boolean)? = null,
    onPick: (Int) -> Unit
): SheetTileGrid {
    val grid = GridLayout(this).apply {
        columnCount = columns
        alignmentMode = GridLayout.ALIGN_BOUNDS
        layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent)
    }
    val primaryColor = color(R.color.text_primary)
    val amber = color(R.color.accent_amber)
    val tiles = ArrayList<TextView>(labels.size)

    labels.forEachIndexed { index, label ->
        val tile = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 13f
            isAllCaps = false
            setTextColor(if (index == initialSelected) amber else primaryColor)
            isSelected = index == initialSelected
            setBackgroundResource(R.drawable.bg_tile)
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(46)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            layoutParams = lp
            setOnClickListener { onPick(index) }
            if (onLongPick != null) {
                setOnLongClickListener { onLongPick.invoke(index) }
            }
        }
        tiles.add(tile)
        grid.addView(tile)
    }

    val selectMany: (List<Int>) -> Unit = { targets ->
        tiles.forEachIndexed { i, tile ->
            val on = i in targets
            tile.isSelected = on
            tile.setTextColor(if (on) amber else primaryColor)
        }
    }
    val select: (Int) -> Unit = { target -> selectMany(listOf(target)) }
    val centerOn: (Int) -> Unit = { index -> tiles.getOrNull(index)?.centerInScrollView() }
    return SheetTileGrid(grid, select, selectMany, centerOn)
}

// ---------------------------------------------------------------- 文本

fun Context.sheetHint(text: String): TextView = TextView(this).apply {
    this.text = text
    setTextColor(color(R.color.text_secondary))
    textSize = 12f
    setLineSpacing(dp(2).toFloat(), 1f)
    setPadding(dp(6), dp(8), dp(6), dp(2))
    layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent)
}

fun Context.sheetStatusText(initial: String): TextView = TextView(this).apply {
    text = initial
    setTextColor(color(R.color.text_primary))
    textSize = 14f
    gravity = Gravity.CENTER
    setPadding(0, dp(14), 0, dp(10))
    layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent)
}

// ---------------------------------------------------------------- 按钮 / 进度

fun Context.sheetPrimaryButton(text: String): AppCompatButton = AppCompatButton(this).apply {
    this.text = text
    setBackgroundResource(R.drawable.bg_btn_amber)
    setTextColor(Color.parseColor("#0B0E13"))
    textSize = 15f
    setTypeface(null, Typeface.BOLD)
    isAllCaps = false
    stateListAnimator = null
    layoutParams = LinearLayout.LayoutParams(matchParent, dp(52)).apply {
        topMargin = dp(12)
    }
}

fun Context.sheetDangerButton(text: String): AppCompatButton = AppCompatButton(this).apply {
    this.text = text
    setBackgroundResource(R.drawable.bg_btn_red)
    setTextColor(Color.WHITE)
    textSize = 15f
    setTypeface(null, Typeface.BOLD)
    isAllCaps = false
    stateListAnimator = null
    layoutParams = LinearLayout.LayoutParams(matchParent, dp(52)).apply {
        topMargin = dp(12)
    }
}

fun Context.sheetProgress(): ProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
    progressDrawable = ContextCompat.getDrawable(this@sheetProgress, R.drawable.progress_sheet)
    layoutParams = LinearLayout.LayoutParams(matchParent, dp(6)).apply {
        topMargin = dp(12)
    }
    visibility = View.GONE
}
