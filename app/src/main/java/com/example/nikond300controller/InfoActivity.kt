package com.example.nikond300controller

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 两个"只读"页面：使用说明 / 关于。
 *
 * 合到一个 Activity 里的原因：两页都只是**静态文本 + 一个返回键**，
 * 拆成两个只换来两份几乎一样的 manifest 条目和两坨样板代码。
 * 用 [EXTRA_PAGE] 区分内容，入口各写各的 Intent。
 *
 * 界面在代码里建，直接复用 SheetUi 里那套构件（卡片 / 取值行 / 提示文字），
 * 这样不用为了两页静态说明再同步改两份 XML。
 */
class InfoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = intent.getStringExtra(EXTRA_PAGE) ?: PAGE_HELP
        val isAbout = page == PAGE_ABOUT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg_base))
        }

        root.addView(buildTopBar(if (isAbout) "关于" else "使用说明"))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(30))
        }
        if (isAbout) buildAbout(body) else buildHelp(body)

        root.addView(
            ScrollView(this).apply { addView(body) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        setContentView(root)
    }

    // ---------------------------------------------------------------- 顶栏

    private fun buildTopBar(title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            )
            addView(ImageView(this@InfoActivity).apply {
                setImageResource(R.drawable.ic_arrow_back)
                val s = dp(40)
                layoutParams = LinearLayout.LayoutParams(s, s).apply {
                    marginStart = dp(4)
                }
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener { finish() }
            })
            addView(TextView(this@InfoActivity).apply {
                text = title
                setTextColor(color(R.color.text_primary))
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(6), 0, 0, 0)
            })
        }

    // ---------------------------------------------------------------- 关于

    private fun buildAbout(root: LinearLayout) {
        // 版本信息从 PackageManager 读，不去碰 buildFeatures.buildConfig ——
        // 为了两行静态文本开一整项构建特性不划算，顺带也少一处"改了忘了同步"的地方。
        val versionName = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "未知"
        } catch (_: Exception) {
            "未知"
        }

        val info = sheetCard(root, "本程序")
        sheetInfoRow("版本", versionName).let { info.addView(it.first) }
        sheetInfoRow("包名", packageName).let { info.addView(it.first) }
        sheetInfoRow("适配机身", "Nikon D300 / D300s").let { info.addView(it.first) }
        sheetInfoRow("连接方式", "USB OTG · 机身 USB 模式选 MTP/PTP")
            .let { info.addView(it.first) }

        root.addView(sectionTitle("来历"))
        root.addView(paragraph(
            "本 App 以 RemoteYourCam 的 PTP 常量表为起点重写，操作码与属性码都对照 " +
                "libgphoto2 的 ptp.h、真机 DeviceInfo 以及 D300 固件反编译结果逐条核过；" +
                "只有固件调度表里确实存在的私有命令才会用，猜出来的码一律不发 —— " +
                "机身不认的操作码会让 USB 端点挂住，整条指令通道就废了。"
        ))

        root.addView(sectionTitle("边界 · 做不到什么"))
        root.addView(paragraph(
            "· 删除照片：多数 D300 机身的 USB 操作清单里不给 0x100B DeleteObject，" +
                "点了会直接告诉你做不到，请用机身按键删。\n" +
                "· 快门次数：没有 PTP 通道，只能从照片 EXIF 的尼康 MakerNote 里读。\n" +
                "· 取景内拍摄：照片留在机身内存，App 取回后存进手机相册，卡上不会有这一张。\n" +
                "· 录制：机身不会录像，录制是把取景帧在手机上压成 MP4。"
        ))

        root.addView(sectionTitle("日志页是给人看的"))
        root.addView(paragraph(
            "顶栏右侧的日志按钮进去。连不上、参数写了没反应、照片没落卡，" +
                "最后几行基本都能说清卡在哪一步；反馈问题时把它整段贴出来最省事。"
        ))
    }

    // --------------------------------------------------------------- 使用说明

    private fun buildHelp(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = "第一次用，按这个顺序走一遍"
            setTextColor(color(R.color.text_primary))
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(2), dp(8), 0, dp(4))
        })
        root.addView(paragraph(
            "全程只需要一根 OTG 线，不用电脑、不用机身开 Wi-Fi。"
        ))

        root.addView(sectionTitle("1 · 先把相机连上"))
        root.addView(paragraph(
            "机身：设定菜单 → USB → 选 MTP/PTP。选成「大容量存储」就只是个 U 盘，" +
                "机身不接受任何指令。\n" +
                "再用 OTG 线把手机和相机接好、相机开机，回 App 点「连接相机」。\n" +
                "第一次会弹 USB 授权，勾上「默认」再确认，省得每次弹。\n" +
                "左上角显示机型名（如 D300）就是连上了；一直「未连接」就去日志页看最后几行。"
        ))

        root.addView(sectionTitle("2 · 拍摄页的几个按钮"))
        root.addView(paragraph(
            "取景 · 抬起反光板，把实时画面传到手机上\n" +
                "AF · 让机身对一次焦，合焦后状态行变绿\n" +
                "拍摄 · 释放快门，照片写进卡里\n" +
                "录制 · 把取景帧在手机上压成 MP4（机身本身不录像）\n" +
                "峰值 · 手动对焦时把合焦边缘描亮，看哪儿实了\n" +
                "曝光预览 · 走一次真实快门但不落卡，取回来看曝光和直方图\n" +
                "反光板预升 · 先抬反光板、再放快门，长曝和微距时避免机震"
        ))

        root.addView(sectionTitle("3 · ISO / 光圈 / 快门"))
        root.addView(paragraph(
            "点这三个大数字任意一下，弹出对应的档位表，点哪档就是哪档，立刻写进机身。\n" +
                "档位表来自机身描述符，不是写死的 —— 换镜头、改机身上的 b1/b2/b3 步长，" +
                "表里内容会跟着变。\n" +
                "手动档（M）能同时改光圈和快门；光圈优先（A）下快门交给机身。"
        ))

        root.addView(sectionTitle("4 · 参数页分了五组"))
        root.addView(paragraph(
            "曝光与画质 · 白平衡 / 测光 / 闪光补偿 / 图像质量 / 记录媒介\n" +
                "实时取景 · 对焦点数量（51 或 11）、构图网格\n" +
                "包围曝光 · 光圈包围、快门包围\n" +
                "拍摄流程 · 自拍延时、间隔拍摄、拍完自动下载\n" +
                "机身 · 存储卡、个人设定菜单（a–f 共 50 项）、拍摄菜单、NCP 滤镜导入"
        ))

        root.addView(sectionTitle("5 · 相册"))
        root.addView(paragraph(
            "底部导航第三格进去。点一张看大图，左右滑切换；长按任意一张进多选，\n" +
                "底栏「原图导出」= 相机里是什么字节就存什么字节；\n" +
                "「JPG 导出」= NEF 用 LibRaw 真解 RAW 再压 JPG（长按这颗改压缩品质，\n" +
                "按钮第二行写着当前档位）。JPEG 原件两种方式都是原样拷贝。\n" +
                "转出来的图会把原图 EXIF 原样搬过去 —— 所以在电脑上看仍然带光圈快门 ISO。"
        ))

        root.addView(sectionTitle("6 · 看不懂的提示"))
        root.addView(paragraph(
            "未连接 / 找不到相机 → OTG 线松了或机身休眠，重插一次再点连接\n" +
                "机身忙（0x2002）→ 上一张还在写卡，等几秒\n" +
                "参数不认（0x201C）→ 曝光补偿这类必须填机身枚举里的原值，App 已按机身表填\n" +
                "对焦失败（0x2019）→ 太暗或没反差，先 AF 或改用手动对焦\n" +
                "完全没反应 → 有命令把 USB 管道挂住了，拔线重插最省事"
        ))

        root.addView(sectionTitle("7 · 机身上的前提"))
        root.addView(paragraph(
            "· 释放模式拨盘别停在遥控/自拍以外的奇怪档；用反光板预升时按 App 提示走\n" +
                "· 镜头上的 A/M 开关打到 A 时，机身自动对焦才有效\n" +
                "· 电量太低时机身会拒掉取景指令，先充满再试"
        ))

        root.addView(TextView(this).apply {
            text = "看完整版说明"
            setTextColor(color(R.color.accent_amber))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(2), dp(20), 0, 0)
            setOnClickListener {
                startActivity(Intent(this@InfoActivity, InfoActivity::class.java)
                    .putExtra(EXTRA_PAGE, PAGE_ABOUT))
            }
        })
    }

    // ---------------------------------------------------------------- 构件

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    /** 组标题：琥珀色小字，跟参数页卡片里的抬头一个味道 */
    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(R.color.accent_amber))
        textSize = 13f
        setTypeface(null, Typeface.BOLD)
        letterSpacing = 0.06f
        setPadding(dp(2), dp(18), 0, dp(6))
    }

    /** 正文段落：13sp 主文字，行距放松一点，长文好读 */
    private fun paragraph(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(R.color.text_primary))
        textSize = 13f
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(2), 0, dp(2), 0)
    }

    companion object {
        const val EXTRA_PAGE = "page"
        const val PAGE_ABOUT = "about"
        const val PAGE_HELP = "help"

        fun open(context: Context, page: String) {
            context.startActivity(
                Intent(context, InfoActivity::class.java).putExtra(EXTRA_PAGE, page)
            )
        }
    }
}
