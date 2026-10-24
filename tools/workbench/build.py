#!/usr/bin/env python3
"""把 index.html + engine.js + tpl/* 合成一个自包含的单文件 HTML。

## 为什么要单文件

工作台要在**手机浏览器**里打开（甚至 `file://` 直接开）。多文件版本要靠相对
路径去取 `engine.js`，而手机上「下载下来的 html」旁边通常没有那个 js。
单文件版随便存哪、发给谁都能直接用。

## 做什么

1. `<script src="engine.js"></script>` → 内联成 `<script>…</script>`
2. `<!--@TPL:xxx-->` → `<script type="text/plain" id="tpl-xxx">…</script>`
   这些是打包插件时要用到的文本模板（lib/index.js、cordis.patch.yml、
   README、LICENSE），运行时用 `textContent` 读出来。

## 为什么模板用 `type="text/plain"`

浏览器不会执行它，但 `textContent` 能原样取到内容 —— 于是模板里的 `</script>`、
`${}`、反引号全都不用转义。代价是内容里**绝不能**出现 `</script`，
所以下面有一道显式检查，命中就报错而不是悄悄产出坏文件。

用法：
    python3 build.py                 # 生成 DSH桌宠工作台.html
    python3 build.py --out foo.html
"""

import argparse
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
OUT_DEFAULT = HERE / "DSH桌宠工作台.html"

# 占位名 → 模板文件
TEMPLATES = {
    "index.js": "tpl/index.js.tpl",
    "patch.yml": "tpl/patch.yml.tpl",
    "readme": "tpl/readme.md.tpl",
    "license": "tpl/license.tpl",
}


def inline_engine(html: str) -> str:
    engine = (HERE / "engine.js").read_text(encoding="utf-8")
    marker = '<script src="engine.js"></script>'
    if marker not in html:
        sys.exit("找不到 engine.js 的 script 标签，index.html 结构变了？")
    return html.replace(marker, "<script>\n" + engine + "\n</script>")


def inline_templates(html: str) -> str:
    for name, rel in TEMPLATES.items():
        path = HERE / rel
        if not path.is_file():
            sys.exit(f"模板文件缺失: {rel}")
        body = path.read_text(encoding="utf-8")
        # 保险：内容里出现 </script 会提前结束标签，产出坏文件
        if "</script" in body.lower():
            sys.exit(f"模板 {rel} 里含 </script，不能内联进 <script> 标签")

        marker = f"<!--@TPL:{name}-->"
        if marker not in html:
            sys.exit(f"index.html 里找不到占位 {marker}")

        block = (
            f'<script type="text/plain" id="tpl-{name}">\n'
            f"{body}\n"
            f"</script>"
        )
        html = html.replace(marker, block)

    # 还有没替换的占位就是漏了
    if "<!--@TPL:" in html:
        rest = html.split("<!--@TPL:")[1].split("-->")[0]
        sys.exit(f"index.html 里有未登记的模板占位: {rest}")
    return html


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(OUT_DEFAULT))
    args = ap.parse_args()

    html = (HERE / "index.html").read_text(encoding="utf-8")
    html = inline_engine(html)
    html = inline_templates(html)

    out = pathlib.Path(args.out)
    out.write_text(html, encoding="utf-8")
    print(f"已生成 {out.name}  ({out.stat().st_size:,} 字节)")


if __name__ == "__main__":
    main()
