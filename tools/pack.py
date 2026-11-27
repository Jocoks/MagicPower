#!/usr/bin/env python3
"""把 aapt2 产出的空 APK 加上 classes.dex、assets/ 与 META-INF/xposed/ 元数据。

usage: pack.py <base.apk> <out.apk> <classes.dex> <meta_dir> [assets_dir]
"""
import os
import shutil
import sys
import zipfile


def main():
    if len(sys.argv) not in (5, 6):
        print("usage: pack.py <base.apk> <out.apk> <classes.dex> <meta_dir> [assets_dir]")
        return 2
    base, out, dex, meta_dir = sys.argv[1:5]
    assets_dir = sys.argv[5] if len(sys.argv) == 6 else None

    shutil.copyfile(base, out)
    with zipfile.ZipFile(out, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(dex, "classes.dex")
        for root, _dirs, files in os.walk(meta_dir):
            for name in sorted(files):
                p = os.path.join(root, name)
                arc = os.path.relpath(p, meta_dir)
                z.write(p, arc)
        if assets_dir and os.path.isdir(assets_dir):
            for root, _dirs, files in os.walk(assets_dir):
                for name in sorted(files):
                    p = os.path.join(root, name)
                    arc = "assets/" + os.path.relpath(p, assets_dir)
                    z.write(p, arc)

    with zipfile.ZipFile(out) as z:
        names = z.namelist()
    print("packed:", out, "entries:", len(names))
    for n in names:
        print("   ", n)
    return 0


if __name__ == "__main__":
    sys.exit(main())
