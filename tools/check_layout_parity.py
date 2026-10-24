#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
横屏布局一致性校验。

背景：res/layout-land/activity_main.xml 是竖屏那份的**整份复制**，不是增量覆盖 ——
改竖屏忘了改横屏（或者反过来）不会报错，甚至不会编译失败，只在用户把手机横过来
的那一刻炸：findViewById 拿到 null，或者某个按钮在横屏下压根不存在。
这个脚本就是用来在提交前挡住这类"改一个漏一个"。

检查内容：
  1. 横屏里缺了竖屏有的 id（最常见的坑，会直接 NPE）
  2. 横屏里多出竖屏没有的 id（通常是重命名时没同步，旧 id 再也取不到）
  3. 两边共有的 id，控件类型是否一致（比如竖屏是 Button、横屏写成 TextView，
     findViewById<T>() 会 ClassCastException）

用法：
    python tools/check_layout_parity.py            # 检查全部成对的横屏布局
    python tools/check_layout_parity.py activity_main   # 只查某一个

退出码：0 = 没问题；1 = 有问题（适合挂到 CI / pre-commit）。
"""

import os
import sys
import xml.etree.ElementTree as ET

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
LAYOUT = os.path.join(RES, "layout")
LAYOUT_LAND = os.path.join(RES, "layout-land")


def parse_ids(path):
    """返回 {id 名: 标签名}。解析失败抛异常。"""
    tree = ET.parse(path)
    out = {}
    for el in tree.iter():
        rid = el.get("{http://schemas.android.com/apk/res/android}id")
        if not rid or not rid.startswith("@+id/"):
            continue
        out[rid[len("@+id/"):]] = el.tag
    return out


def check(name):
    port = os.path.join(LAYOUT, name + ".xml")
    land = os.path.join(LAYOUT_LAND, name + ".xml")
    if not os.path.exists(port):
        print("  跳过 %s：竖屏布局不存在" % name)
        return []

    problems = []
    if not os.path.exists(land):
        # 没有横屏专属版本 → 系统直接用竖屏那份，天然一致，不算问题
        return problems

    try:
        p = parse_ids(port)
        l = parse_ids(land)
    except ET.ParseError as e:
        problems.append("  解析失败：%s" % e)
        return problems

    for k in sorted(set(p) - set(l)):
        problems.append("  横屏缺 id：%s（竖屏是 <%s>）" % (k, p[k]))
    for k in sorted(set(l) - set(p)):
        problems.append("  横屏多出 id：%s（<%s>）—— 竖屏没有，多半是重命名没同步" % (k, l[k]))
    for k in sorted(set(p) & set(l)):
        if p[k] != l[k]:
            problems.append("  类型不一致：%s 竖屏 <%s> / 横屏 <%s>" % (k, p[k], l[k]))
    return problems


def main():
    if not os.path.isdir(LAYOUT):
        print("找不到 res/layout：%s" % LAYOUT)
        return 1

    if len(sys.argv) > 1:
        names = [sys.argv[1]]
    else:
        names = sorted(f[:-4] for f in os.listdir(LAYOUT) if f.endswith(".xml"))

    total = 0
    for n in names:
        problems = check(n)
        if problems:
            total += len(problems)
            print("[不一致] %s" % n)
            print("\n".join(problems))
        else:
            print("[  ok  ] %s" % n)

    if total:
        print("\n共 %d 处不一致 —— 横屏布局是整份复制，改一处就要改两处。" % total)
        return 1
    print("\n全部一致。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
