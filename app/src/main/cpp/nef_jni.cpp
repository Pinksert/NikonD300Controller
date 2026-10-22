// NEF → RGB 的 JNI 桥。
//
// 出口只有一个：把一个 NEF 文件解成 **PPM（P6，8bit RGB）** 写到指定路径，
// 剩下的（PPM → Bitmap → JPEG）交给 Kotlin 那边用系统 API 做 ——
// JPEG 编码用 Bitmap.compress 就够了，没必要再拖一个 libjpeg 进来编。
//
// 出错一律返回错误码，**绝不 exit()**（LibRaw 的库接口本来就是返回码，
// 这也是选它而不是直接编 dcraw.c 的原因：dcraw 的 main 里到处 exit(1)，
// 在 App 进程里跑等于直接把 App 干掉）。
#include <jni.h>
#include <cstdio>
#include <cstring>
#include <cstdlib>

#include "libraw/libraw.h"

namespace {

/** 写一份 PPM：头 + 逐行 RGB。P6 格式最简单，Kotlin 端几行就能读 */
int write_ppm(const char *path, const unsigned char *data, int width, int height) {
    FILE *f = fopen(path, "wb");
    if (!f) return -2001;
    if (fprintf(f, "P6\n%d %d\n255\n", width, height) < 0) {
        fclose(f);
        return -2002;
    }
    size_t want = (size_t)width * (size_t)height * 3u;
    size_t wrote = fwrite(data, 1, want, f);
    fclose(f);
    return wrote == want ? 0 : -2003;
}

} // namespace

extern "C" {

/**
 * @return 0 成功；负数 = LibRaw 错误码（见 libraw_const.h 的 LIBRAW_XXX）；
 *         -2xxx = 写文件失败；-3000 = 解码结果不是 3 通道 8bit
 */
JNIEXPORT jint JNICALL
Java_com_example_nikond300controller_NefDecoder_convertToPpm(
        JNIEnv *env, jclass, jstring jIn, jstring jOut) {
    if (jIn == nullptr || jOut == nullptr) return LIBRAW_FILE_UNSUPPORTED;

    const char *inPath = env->GetStringUTFChars(jIn, nullptr);
    const char *outPath = env->GetStringUTFChars(jOut, nullptr);
    if (!inPath || !outPath) {
        if (inPath) env->ReleaseStringUTFChars(jIn, inPath);
        if (outPath) env->ReleaseStringUTFChars(jOut, outPath);
        return LIBRAW_FILE_UNSUPPORTED;
    }

    // ⚠️ LibRaw 对象**必须堆分配**：它的 libraw_data_t 有一兆多（曲线表、色彩数据、
    // 缩略图列表都在里面），放在栈上会直接把线程栈撑爆 —— 而我们是从 Kotlin 的
    // 后台线程调进来的，那个线程的栈只有 1 MB 上下。
    LibRaw *processor = new (std::nothrow) LibRaw();
    if (!processor) {
        env->ReleaseStringUTFChars(jIn, inPath);
        env->ReleaseStringUTFChars(jOut, outPath);
        return LIBRAW_UNSUFFICIENT_MEMORY;
    }

    // —— 出图口径：尽量接近机身直出 JPEG ——
    // 机身白平衡（不是 dcraw 那种自动白平衡）
    processor->imgdata.params.use_camera_wb = 1;
    // sRGB 输出：相册/浏览器都按 sRGB 显示，出 AdobeRGB 会显得发灰
    processor->imgdata.params.output_color = 1;
    processor->imgdata.params.output_bps = 8;
    // AHD 插值：画质最好的那一档（12MP 在手机上几秒，值得等）
    processor->imgdata.params.user_qual = 3;
    // 不做半尺寸、不做四色、保留默认的自动曝光与 gamma
    processor->imgdata.params.half_size = 0;
    processor->imgdata.params.four_color_rgb = 0;
    processor->imgdata.params.no_auto_bright = 0;
    processor->imgdata.params.highlight = 0;

    int err = processor->open_file(inPath);
    if (err == LIBRAW_SUCCESS) err = processor->unpack();
    if (err == LIBRAW_SUCCESS) err = processor->dcraw_process();

    int rc = err;
    if (err == LIBRAW_SUCCESS) {
        int memErr = 0;
        libraw_processed_image_t *img = processor->dcraw_make_mem_image(&memErr);
        if (!img || memErr != LIBRAW_SUCCESS) {
            rc = memErr != LIBRAW_SUCCESS ? memErr : LIBRAW_UNSUFFICIENT_MEMORY;
        } else if (img->colors != 3 || img->bits != 8) {
            rc = -3000;
        } else {
            rc = write_ppm(outPath, img->data, img->width, img->height);
        }
        if (img) LibRaw::dcraw_clear_mem(img);
    }

    delete processor;   // recycle() 在析构里跑，会顺手把解出来的 RAW 缓冲一起放了
    env->ReleaseStringUTFChars(jIn, inPath);
    env->ReleaseStringUTFChars(jOut, outPath);
    return rc;
}

/** 给界面显示用的错误码说明（出错时至少告诉用户是哪一步不行） */
JNIEXPORT jstring JNICALL
Java_com_example_nikond300controller_NefDecoder_errorText(JNIEnv *env, jclass, jint code) {
    const char *msg = (code == 0) ? "成功" : libraw_strerror(code);
    return env->NewStringUTF(msg);
}

} // extern "C"
