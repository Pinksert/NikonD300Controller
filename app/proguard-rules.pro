# ---------------------------------------------------------------------------
# R8 保留规则
#
# nef_jni.cpp 用的是 JNI **静态注册**（extern "C" Java_com_example_..._NefDecoder_xxx），
# 符号名是编译期写死的字符串，R8 一旦重命名类或方法，运行时就找不到实现 →
# UnsatisfiedLinkError，而且是"只有点 NEF 转档时才崩"这种最难受的失败方式。
# ---------------------------------------------------------------------------
-keep class com.example.nikond300controller.NefDecoder { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# PTP 命令/属性码常量类被反射式打印（日志里按名字枚举），留着方便排查
-keep class com.example.nikond300controller.PtpConstants { *; }
