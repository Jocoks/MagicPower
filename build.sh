#!/bin/sh
# 通知中心日历（LSPosed 模块）—— 容器内离线构建（无 Gradle）。
# 产物未签名，交给用户自己签。
set -e

ROOT=$(cd "$(dirname "$0")" && pwd)
TOOLS="$ROOT/tools"
BUILD="$ROOT/build"

VC=$(grep -o 'android:versionCode="[0-9]*"' "$ROOT/AndroidManifest.xml" | grep -o '[0-9]*')
VC=$((VC + 1))
sed -i "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VC\"/" "$ROOT/AndroidManifest.xml"
VN=$(grep -o 'android:versionName="[^"]*"' "$ROOT/AndroidManifest.xml" | cut -d'"' -f2)
OUT="$BUILD/MagicPower-${VN}-unsigned.apk"
echo "versionCode -> $VC"

[ -x /usr/local/go/bin/go ] && PATH="/usr/local/go/bin:$PATH"
export PATH

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/assets"

echo "[0/6] go build (检查页面服务)"
if command -v go >/dev/null 2>&1; then
    ( cd "$ROOT/web" && go build -o "$BUILD/assets/magicpower-check" . )
    echo "    -> $(du -h "$BUILD/assets/magicpower-check" | cut -f1)"
else
    echo "    !! 未找到 go，APK 将不含检查页面"
    rmdir "$BUILD/assets" 2>/dev/null || true
fi

echo "[1/6] javac"
javac -source 8 -target 8 -nowarn -Xlint:-options \
    -classpath "$TOOLS/android.jar:$TOOLS/libxposed-api.jar" \
    -d "$BUILD/classes" \
    $(find "$ROOT/src" -name '*.java')

echo "[2/6] d8"
java -cp "$TOOLS/d8.jar" com.android.tools.r8.D8 \
    --lib "$TOOLS/android.jar" --min-api 29 \
    --output "$BUILD/dex" \
    $(find "$BUILD/classes" -name '*.class')

echo "[3/6] aapt2 compile"
aapt2 compile --dir "$ROOT/res" -o "$BUILD/res.zip"

echo "[4/6] aapt2 link + pack"
aapt2 link \
    -o "$BUILD/base.apk" \
    -I "$TOOLS/android.jar" \
    --manifest "$ROOT/AndroidManifest.xml" \
    "$BUILD/res.zip"
python3 "$TOOLS/pack.py" "$BUILD/base.apk" "$BUILD/unsigned.apk" \
    "$BUILD/dex/classes.dex" "$ROOT/meta" "$BUILD/assets"

echo "[5/6] zipalign"
zipalign -f -p 4 "$BUILD/unsigned.apk" "$OUT"

echo "OK -> $OUT"
