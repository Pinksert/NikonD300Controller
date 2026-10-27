package com.example.nikond300controller

/**
 * 二级页面（白平衡 / 包围曝光 / 自动 ISO）的统一样式构件。
 *
 * 这些页面原来各自手搓 View：按钮用系统 MaterialButton、输入框白底、
 * 开关默认蓝、进度条默认蓝——在主界面这块深色盘面上很跳。
 * 这里把说服力收回到一套 token 上：同一张卡片底、同一种 48dp 行高、
 * 同一套强调色，跟参数页完全一致；具体业务（PTP 读写）仍留在 MainActivity 里。
 */
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
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
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
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
        // ★ 抽屉里除了内容区以外的东西一律不许嵌套滚动。
        // BottomSheetBehavior 认孩子是按"谁 nestedScrollingEnabled = true"挑第一个的，
        // 默认每个 View 都为 true —— 挑中把手/标题这种不会滚的 View，它就会认为
        // "里面没人要滚"，于是往下一划直接被当成"拖抽屉收起"。
        // 把手/标题/分割线关掉之后，唯一剩下的候选就是下面那个 NestedScrollView。
        isNestedScrollingEnabled = false
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
        isNestedScrollingEnabled = false
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
            isNestedScrollingEnabled = false
            layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                bottomMargin = dp(10)
            }
        })
    }

    shell.addView(View(this).apply {
        setBackgroundColor(color(R.color.stroke))
        isNestedScrollingEnabled = false
        layoutParams = LinearLayout.LayoutParams(matchParent, dp(1)).apply {
            bottomMargin = if (subtitle.isNullOrEmpty()) dp(8) else 0
        }
    })

    // ★ NestedScrollView 而不是 ScrollView：只有它会在滑动时把已滚动的距离
    // 通过嵌套滚动协议报给 BottomSheetBehavior，Behavior 才知道"用户在滚内容、
    // 不是在拖抽屉"。普通 ScrollView 也能嵌套，但不会在到顶/到底时把未消费的距离
    // 交出来，手势就只剩"拖抽屉"一种解释。
    val scroll = NestedScrollView(this).apply { isFillViewport = false }
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
        val bottomSheet = dialog.findViewById<FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        )
        bottomSheet?.background = null

        // ★ 内容超高时把高度收住，否则"划不到最上面"。
        // 这个 NestedScrollView 是 wrap_content 的：内容比屏幕还高时它自己也跟着长到那么高，
        // 抽屉整体被顶到屏幕上方之外，而它因为"高度 == 内容高度"认为没东西可滚。
        // 于是最上面那几屏永远摸不到 —— 快门 50 多档（14 行）必踩，长列表二级页也一样。
        // 限高之后才有真实的可滚区域，上下都能滑到。
        val maxHeight = (Resources.getSystem().displayMetrics.heightPixels * 0.86f).toInt()
        scroll.post {
            if (scroll.isAttachedToWindow && scroll.height > maxHeight) {
                scroll.layoutParams =
                    (scroll.layoutParams as LinearLayout.LayoutParams).apply { height = maxHeight }
            }
        }

        // ★ 兜底：内容没滚回最顶上之前，抽屉不许被拖走。
        // 上面的嵌套滚动已经能挡住绝大多数情况，但只要有一次手势是竖直起步略快、
        // 或者中间隔着 GridLayout（档位网格本身不会发嵌套滚动），
        // Behavior 还是会当作"下拉关闭"。这里再补一道：当前不是顶部位置就锁掉拖拽，
        // 滑到顶了才把收起手势还给用户。
        bottomSheet?.let {
            val behavior = BottomSheetBehavior.from(it)
            scroll.setOnScrollChangeListener(
                NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
                    behavior.isDraggable = scrollY == 0
                }
            )
            behavior.isDraggable = scroll.scrollY == 0
        }
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

/** 开关行：跟取值行一样 48dp 一行，右边的 MaterialSwitch 染成强调色 */
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
 * 把自己滚到所在滚动区（[ScrollView] 或 [NestedScrollView]）的垂直中间。
 *
 * 拿屏幕坐标算位移（而不是 view.top —— 那是相对直接父容器的），
 * 所以无论中间隔了多少层容器都成立。
 */
private fun View.centerInScrollView() {
    // 抽屉的内容区现在是 NestedScrollView，但它**不是** ScrollView 的子类
    // （它继承 FrameLayout）—— 只认 ScrollView 的话这里会一路找到 null，
    // 快门那种几十档的菜单就再也居中不了。两种都要认。
    // 声明成可空：View.getParent() 到最外层就返回 null，
    // 写成非空类型时编译器会认为下面的判空恒为真（还可能踩空指针）。
    var p: ViewParent? = parent
    while (p != null && p !is ScrollView && p !is NestedScrollView) p = p.parent
    val sv = p as? View ?: return
    post {
        if (!isAttachedToWindow) return@post
        val me = IntArray(2)
        val host = IntArray(2)
        getLocationOnScreen(me)
        sv.getLocationOnScreen(host)
        // 目标 = 自己当前位置相对滚动区视口的偏移 + 已滚过的量 - 半个视口
        val target = sv.scrollY + (me[1] - host[1]) - (sv.height - height) / 2
        // 夹在可滚范围内：当前档靠表头/表尾时不会滚出可视区之外
        val limit = (((sv as? ViewGroup)?.getChildAt(0)?.height ?: 0) - sv.height).coerceAtLeast(0)
        val y = target.coerceIn(0, limit)
        when (sv) {
            is NestedScrollView -> sv.smoothScrollTo(0, y)
            is ScrollView -> sv.smoothScrollTo(0, y)
        }
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
            textSize = 12.5f
            // 固定两行高度：像「RAW + JPEG 精细」这种长档位会折成两行，
            // 若让高度随内容走，同一页里就会出现高低不一的按钮。maxLines=2 + 统一高度后，
            // 单行档位垂直居中、双行档位正好填满，全页按钮一样大。
            maxLines = 2
            isAllCaps = false
            setTextColor(if (index == initialSelected) amber else primaryColor)
            isSelected = index == initialSelected
            setBackgroundResource(R.drawable.bg_tile)
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(52)
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

    // 最后一行不满时补上占位格：GridLayout 的列宽是按"本行有几个单元格"分的，
    // 8 个按钮塞 3 列时最后一行只有 2 个，它们就会各占半行 —— 比上面两行的按钮宽出一截。
    // 补空 View（不可见、不接收触摸）把最后一行填满，所有按钮才是同样大小。
    val remainder = labels.size % columns
    if (labels.isNotEmpty() && remainder != 0) {
        repeat(columns - remainder) {
            grid.addView(View(this).apply {
                visibility = View.INVISIBLE
                isClickable = false
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(52)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
            })
        }
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
