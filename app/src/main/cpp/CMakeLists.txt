cmake_minimum_required(VERSION 3.22.1)
project("nefdecoder")

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# LibRaw：真正的 RAW 解码器
#
# 为什么非要引一个 C++ 库进来：D300 的 NEF 是 34713（尼康无损压缩）的 Bayer 数据，
# 要出图得走「霍夫曼解码 → 预测还原 → 线性化曲线 → 白平衡 → 彩色插值 → 色彩矩阵 →
# gamma/sRGB」一整条链。以前图省事直接把机身内嵌的那张预览 JPEG 抠出来当成品，
# 那张预览只有 239 KB（4288×2848 压到极限），画质和体积都不像一张正常照片 ——
# 用户要的"NEF 转档"必须真的解 RAW，LibRaw（dcraw 的库化版本）就是干这个的。
#
# 不编的部分：
# - src/integration/*：要 Adobe DNG SDK / RawSpeed，我们不需要
# - USE_JPEG / USE_ZLIB / USE_LCMS2 / USE_JASPER 全部不开：NEF 解码用不到，
#   开了反而要拖 libjpeg/zlib 进来编
# ---------------------------------------------------------------------------
file(GLOB_RECURSE LIBRAW_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/libraw/src/*.cpp)

add_library(libraw STATIC ${LIBRAW_SOURCES})
target_include_directories(libraw PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/libraw)
target_compile_definitions(libraw PRIVATE LIBRAW_USE_AUTOPTR)
# 大文件在 Android 上要走 64 位偏移，否则 >2GB 的 seek 会错
target_compile_options(libraw PRIVATE -fvisibility=hidden -w -O2)

# ---------------------------------------------------------------------------
# JNI 桥：给 Kotlin 一个「NEF → RGB」的出口
# ---------------------------------------------------------------------------
add_library(nefdecoder SHARED nef_jni.cpp)
target_include_directories(nefdecoder PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/libraw)
target_link_libraries(nefdecoder libraw m log)
