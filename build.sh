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

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex"

echo "[1/5] javac"
javac -source 8 -target 8 -nowarn -Xlint:-options \
    -classpath "$TOOLS/android.jar:$TOOLS/libxposed-api.jar" \
    -d "$BUILD/classes" \
    $(find "$ROOT/src" -name '*.java')

echo "[2/5] d8"
java -cp "$TOOLS/d8.jar" com.android.tools.r8.D8 \
    --lib "$TOOLS/android.jar" --min-api 29 \
    --output "$BUILD/dex" \
    $(find "$BUILD/classes" -name '*.class')

echo "[3/5] aapt2 compile"
aapt2 compile --dir "$ROOT/res" -o "$BUILD/res.zip"

echo "[4/5] aapt2 link + pack"
aapt2 link \
    -o "$BUILD/base.apk" \
    -I "$TOOLS/android.jar" \
    --manifest "$ROOT/AndroidManifest.xml" \
    "$BUILD/res.zip"
python3 "$TOOLS/pack.py" "$BUILD/base.apk" "$BUILD/unsigned.apk" \
    "$BUILD/dex/classes.dex" "$ROOT/meta"

echo "[5/5] zipalign"
zipalign -f -p 4 "$BUILD/unsigned.apk" "$OUT"

echo "OK -> $OUT"
