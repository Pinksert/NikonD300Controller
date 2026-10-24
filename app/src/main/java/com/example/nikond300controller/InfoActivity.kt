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
 * 三个"只读"页面：使用说明 / 关于 / 开源许可证。
 *
 * 合到一个 Activity 里的原因：三页都只是**静态文本 + 一个返回键**，
 * 拆成三个只换来三份几乎一样的 manifest 条目和三坨样板代码。
 * 用 [EXTRA_PAGE] 区分内容，入口各写各的 Intent。
 *
 * 界面在代码里建，直接复用 SheetUi 里那套构件（卡片 / 取值行 / 提示文字），
 * 这样不用为了几页静态说明再同步改好几份 XML。
 */
class InfoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = intent.getStringExtra(EXTRA_PAGE) ?: PAGE_HELP

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg_base))
        }

        root.addView(buildTopBar(titleOf(page)))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(30))
        }
        when (page) {
            PAGE_ABOUT -> buildAbout(body)
            PAGE_LICENSE -> buildLicense(body)
            else -> buildHelp(body)
        }

        root.addView(
            ScrollView(this).apply { addView(body) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        setContentView(root)
    }

    private fun titleOf(page: String): String = when (page) {
        PAGE_ABOUT -> "关于"
        PAGE_LICENSE -> "开源许可证"
        else -> "使用说明"
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
        sheetInfoRow("系统要求", "Android 7.0（API 24）以上，需支持 USB OTG")
            .let { info.addView(it.first) }

        // ---- 开源许可证 ----
        root.addView(sectionTitle("开源许可证"))
        root.addView(paragraph(
            "本项目以 「MIT License」 开源，可自由使用、修改、分发，包括商业用途；" +
                "唯一要求是保留版权声明与许可声明。软件按「原样」提供，作者不承担任何担保与责任。"
        ))
        root.addView(linkRow("查看完整许可证文本与第三方声明") { open(this, PAGE_LICENSE) })

        root.addView(sectionTitle("第三方组件"))
        root.addView(paragraph(
            "· LibRaw（LGPL-2.1 / CDDL 双许可）—— NEF 真解 RAW 的解码核心，\n" +
                "  源码随包提供在 app/src/main/cpp/libraw，经 JNI 编成 libnefdecoder.so。\n" +
                "· RemoteYourCam-USB —— 早期 PTP 常量与 D300 实机对焦点数据的参考来源。\n" +
                "· libgphoto2（ptp.h） —— 尼康私有操作码 / 属性码命名与值域的对照依据。\n" +
                "· AndroidX / Material Components —— Apache-2.0。\n" +
                "第三方组件各自遵循其原始许可，完整声明见许可证页。"
        ))

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
                "· 反光板预升：没有专用 PTP 指令，只能把释放模式拨盘转到 Mup，" +
                "由 App 连按两次快门（第一次抬板、第二次曝光）。\n" +
                "· 录制：机身不会录像，录制是把取景帧在手机上压成 MP4。\n" +
                "· 手持模式的对焦点只能落在那 51 个固定点位上 —— 这是相位检测 AF 的硬件限制，" +
                "要能在画面任意位置选点，请切到三脚架模式。"
        ))

        root.addView(sectionTitle("日志页是给人看的"))
        root.addView(paragraph(
            "顶栏右侧的日志按钮进去。连不上、参数写了没反应、照片没落卡，" +
                "最后几行基本都能说清卡在哪一步；反馈问题时把它整段贴出来最省事。"
        ))

        root.addView(linkRow("第一次用？看使用说明") { open(this, PAGE_HELP) })
    }

    // ------------------------------------------------------------ 开源许可证

    private fun buildLicense(root: LinearLayout) {
        root.addView(sectionTitle("MIT License"))
        val card = sheetCard(root, null)
        card.addView(TextView(this).apply {
            text = MIT_LICENSE_TEXT.trimIndent()
            setTextColor(color(R.color.text_primary))
            textSize = 12f
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setTypeface(android.graphics.Typeface.MONOSPACE)
        })

        root.addView(sectionTitle("第三方组件许可"))
        root.addView(paragraph(
            "· LibRaw —— Copyright (C) 2008-2024 LibRaw LLC。" +
                "按 LGPL-2.1 或 CDDL-1.0（任选其一）授权，用于 NEF/RAW 解码。\n" +
                "  完整源码：https://www.libraw.org/\n\n" +
                "· RemoteYourCam-USB —— PTP 常量与 D300 实机对焦点数据的参考来源。\n\n" +
                "· libgphoto2 —— Copyright (C) 2001-2024 Marcus Meissner 等贡献者，LGPL-2.1。" +
                "其 ptp.h 中的尼康私有操作码 / 属性码命名被本项目的协议注释引用。\n\n" +
                "· AndroidX / Material Components for Android —— Apache License 2.0。"
        ))
        root.addView(paragraph(
            "上述组件各自遵循其原始许可证；本 App 除这些第三方组件外的代码按上面的 MIT License 授权。"
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
            "取景 · 抬起反光板，把实时画面传到手机上（长按可切手持 / 三脚架）\n" +
                "对焦 · 让机身对一次焦，合焦后状态行变绿\n" +
                "拍摄 · 释放快门，照片写进卡里\n" +
                "录制 · 把取景帧在手机上压成 MP4（机身本身不录像）\n" +
                "峰值 · 手动对焦时把合焦边缘描亮，看哪儿实了\n" +
                "触摸对焦 · 点画面哪儿就对哪儿（只在取景时出现）\n" +
                "全屏 · 画面铺满整屏，按钮贴到画面上下沿外面，不挡构图\n" +
                "反光板预升 · 先抬反光板、再放快门，长曝和微距时避免机震"
        ))

        root.addView(sectionTitle("3 · 对焦：点哪儿对哪儿"))
        root.addView(paragraph(
            "先点「取景」，等画面出来。取景有两个模式，对焦方式完全不同：\n\n" +
                "「手持模式」（默认）：用的是取景器那套 51 点相位检测 AF。" +
                "把「触摸对焦」点亮，然后点画面 —— App 会把点击吸附到最近那个对焦点，" +
                "写进机身再自动对一次焦。对焦点只能落在这 51 个固定点位上，" +
                "这是相位检测的硬件限制，官方软件也只能一个点一个点地选。\n\n" +
                "「三脚架模式」：长按「取景」按钮切过去。这时是对比度检测 AF，" +
                "AF 框能停在画面「任意位置」 —— 点画面哪儿框就去哪儿，" +
                "机身自己会跑一次对焦（会响提示音），不用再按「对焦」。\n\n" +
                "放大看细节时（画面上的倍率按钮）先回到全画面再选点：" +
                "放大状态下挪对焦点，机身会顺势把放大的那一块也挪走，画面会跟着跑。"
        ))

        root.addView(sectionTitle("4 · ISO / 光圈 / 快门"))
        root.addView(paragraph(
            "点这三个大数字任意一下，弹出对应的档位表，点哪档就是哪档，立刻写进机身。\n" +
                "档位表来自机身描述符，不是写死的 —— 换镜头、改机身上的 b1/b2/b3 步长，" +
                "表里内容会跟着变。\n" +
                "手动档（M）能同时改光圈和快门；光圈优先（A）下快门交给机身。"
        ))

        root.addView(sectionTitle("5 · 参数页分了五组"))
        root.addView(paragraph(
            "曝光与画质 · 白平衡 / 测光 / 闪光补偿 / 图像质量 / 记录媒介\n" +
                "实时取景 · 对焦点数量（51 或 11）、构图网格\n" +
                "包围曝光 · 光圈包围、快门包围\n" +
                "拍摄流程 · 自拍延时、间隔拍摄、拍完自动下载\n" +
                "机身 · 存储卡、相机设定菜单（a–f 共 50 项）、拍摄菜单、机身时钟、\n" +
                "诊断包、备份与恢复、关于与使用说明"
        ))

        root.addView(sectionTitle("6 · 相册"))
        root.addView(paragraph(
            "底部导航第三格进去。点一张看大图，左右滑切换；长按任意一张进多选，\n" +
                "底栏四个按钮：\n" +
                "「原图导出」= 相机里是什么字节就存什么字节；\n" +
                "「JPG 导出」= NEF 用 LibRaw 真解 RAW 再压 JPG（点下去先选压缩品质，\n" +
                "全是 JPEG 时不会问）；「删除 N 张」= 逐张发机身删除指令；「退出」= 回到相册。\n" +
                "转出来的 JPG 会把原图 EXIF 搬过去 —— 在电脑上看仍然带光圈快门 ISO 和拍摄时间。\n\n" +
                "导出、删除、合成视频都有进度条，旁边那颗「取消」随时能按：\n" +
                "已经存到手机的那几张照旧留着，单张失败会跳过继续，不会整批作废。"
        ))
        root.addView(paragraph(
            "顶栏「⋯」里还有两件事：\n" +
                "「按日期分组」= 机身只按文件夹（100NCD300）存照片，那是号段不是日期，\n" +
                "同一个文件夹里可能横跨几个月；切成按日期就能按天看，网格带日期抬头。\n" +
                "「合成延时视频」= 勾选的照片按拍摄时间从旧到新合成一段 MP4（D300 不会录像，\n" +
                "这是另一种补法，而且用的是完整照片，画质比取景录制好得多）。"
        ))

        root.addView(sectionTitle("7 · 一次拍一批：间隔 · 包围 · 焦点包围"))
        root.addView(paragraph(
            "参数页「拍摄流程」里有三件「一次点下去拍一串」的事：\n\n" +
                "「间隔拍摄」= 每隔 N 秒拍一张，共 M 张 —— 延时、星轨用这个。\n\n" +
                "「曝光包围」= 同一构图连拍几张不同曝光（快门 / 光圈），回来挑或合成 HDR。\n\n" +
                "「焦点包围」= 对焦堆叠：在 51 个对焦点里沿着一列，\n" +
                "从「近」逐站走到「远」，每站选点、合焦、拍一张，回来做景深合成。\n" +
                "微距和风景很实用 —— 光圈再小也盖不住的景深，靠合成能补上。\n" +
                "机身本身没有这个功能（没有「拍一张把焦点往前挪一点」的指令），是 App 编排出来的。\n" +
                "⚠️ 走的是机身的**固定对焦点**，不是画面任意位置，也不进实时取景 ——\n" +
                "取景里一拍就得先退取景，那样每张都要来回一次，太慢还容易卡住 USB 通道。\n" +
                "机身对焦模式要放在 AF-S / AF-C；手动对焦下机身不会重新合焦，堆叠就没有意义了。"
        ))

        root.addView(sectionTitle("8 · 取景辅助：峰值与斑马纹"))
        root.addView(paragraph(
            "取景时点「峰值」，合焦的边缘会整片泛红 —— 屏幕小、画面软，肉眼分不出\n" +
                "「差一点」和「正合焦」，看着亮斑转对焦环最直观，手动头和微距尤其有用。\n\n" +
                "「长按」峰值按钮进设置：能切到「过曝斑马纹」、调灵敏度、换颜色。\n" +
                "斑马纹不看边缘只看「亮度」：快爆掉的高光会画上斜条纹。\n" +
                "三脚架拍风景判断天空是不是已经死白很好用 —— 默认 245 会提前两档提醒，\n" +
                "调到 255 就只标纯白。这两样只能开一个，同时开会互相盖住。"
        ))

        root.addView(sectionTitle("9 · 机身时钟 · 诊断包 · 备份"))
        root.addView(paragraph(
            "参数页「机身」组末尾有三项，都是为了别让数据白白丢掉：\n\n" +
                "「机身时钟」= 一键把手机时间写进机身。机身里那颗小电池掉电后时间会归零，\n" +
                "EXIF 时间戳就全是 2000 年，相册排序和间隔拍摄的时点全跟着错，\n" +
                "而照片一旦拍完就没法补救 —— 所以连上时偏差超过一分钟会主动提醒。\n\n" +
                "「导出诊断包」= 把机身信息、机身自述支持的操作码、关键属性快照\n" +
                "和最近几百行日志打成一个 txt 存到「下载/D300」。\n" +
                "出问题时把它发出来，比口头描述半天有用得多。\n\n" +
                "「备份与恢复」= 对焦框标定、手工试出来的属性码、拍摄流程设置导出成 json。\n" +
                "换装正式签名的包必须先卸载旧的（签名不同覆盖安装会失败），\n" +
                "而卸载就会清空这些 —— 先导出一份，装完再导回来，标定不用重做。"
        ))

        root.addView(sectionTitle("10 · 看不懂的提示"))
        root.addView(paragraph(
            "未连接 / 找不到相机 → OTG 线松了或机身休眠，重插一次再点连接\n" +
                "机身忙（0x2002）→ 上一张还在写卡，等几秒\n" +
                "参数不认（0x201C）→ 曝光补偿这类必须填机身枚举里的原值，App 已按机身表填\n" +
                "对焦失败（0x2019）→ 太暗或没反差，先对焦或改用手动对焦\n" +
                "点了画面没反应 → 触摸对焦没打开，或放大状态下；先回全画面再点\n" +
                "完全没反应 → 有命令把 USB 管道挂住了，拔线重插最省事"
        ))

        root.addView(sectionTitle("11 · 机身上的前提"))
        root.addView(paragraph(
            "· 释放模式拨盘别停在遥控/自拍以外的奇怪档；用反光板预升时按 App 提示走\n" +
                "· 镜头上的 A/M 开关打到 A 时，机身自动对焦才有效\n" +
                "· 电量太低时机身会拒掉取景指令，先充满再试"
        ))

        root.addView(linkRow("版本、许可证与第三方声明") { open(this, PAGE_ABOUT) })
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

    /** 页内跳转：琥珀色可点文字，串起 使用说明 ⇄ 关于 → 许可证 */
    private fun linkRow(text: String, onClick: Context.() -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(color(R.color.accent_amber))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(2), dp(20), 0, 0)
            setOnClickListener { this@InfoActivity.onClick() }
        }

    companion object {
        const val EXTRA_PAGE = "page"
        const val PAGE_ABOUT = "about"
        const val PAGE_HELP = "help"
        const val PAGE_LICENSE = "license"

        fun open(context: Context, page: String) {
            context.startActivity(
                Intent(context, InfoActivity::class.java).putExtra(EXTRA_PAGE, page)
            )
        }

        /**
         * MIT 正文。放在代码里而不是 assets 里，是为了"关于页一定能打开" ——
         * 少一个文件读失败的路径，也少一处需要同步维护的资源。
         */
        private const val MIT_LICENSE_TEXT = """
Copyright (c) 2025 Nikon D300 Controller contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
"""
    }
}
