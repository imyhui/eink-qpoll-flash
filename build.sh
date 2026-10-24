#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
sdk_path="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
java_path="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home}"
tools_path="$sdk_path/build-tools/34.0.0"
android_jar="$sdk_path/platforms/android-34/android.jar"
export PATH="$java_path/bin:$PATH"
if [[ ! -f "$android_jar" || ! -x "$tools_path/aapt2" ]]; then
  echo "需要 Android SDK platform android-34 + build-tools 34.0.0；设置 ANDROID_SDK_ROOT。"
  exit 1
fi
mkdir -p build/classes build/dex
find build/classes build/dex -type f -delete
asset_stage="build/apk-assets"
rm -rf "$asset_stage"
mkdir -p "$asset_stage"
cp -R assets/. "$asset_stage/"
injector_archive="assets/frida-inject-17.24.0-android-arm64.xz"
injector_sha="261875c11ec37fa1d3c26c05f71c0390ef1176539b0545d0b253cdbf24ece0e5"
actual_injector_sha="$(shasum -a 256 "$injector_archive" | awk '{print $1}')"
if [[ "$actual_injector_sha" != "$injector_sha" ]]; then
  echo "Frida injector archive checksum mismatch."
  exit 1
fi
xz -dc "$injector_archive" > "$asset_stage/frida-inject"
rm -f "$asset_stage/frida-inject-17.24.0-android-arm64.xz"
"$tools_path/aapt2" compile --dir res -o build/resources.zip
"$tools_path/aapt2" link -I "$android_jar" --manifest AndroidManifest.xml -A "$asset_stage" -o build/resources.apk build/resources.zip
find src -name '*.java' > build/sources.txt
javac --release 8 -encoding UTF-8 -cp "$android_jar" -d build/classes @build/sources.txt
jar cf build/classes.jar -C build/classes .
"$tools_path/d8" --min-api 26 --lib "$android_jar" --output build/dex build/classes.jar
cp build/resources.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes*.dex)
"$tools_path/zipalign" -f 4 build/unsigned.apk build/aligned.apk
if [[ ! -f build/debug.keystore ]]; then
  keytool -genkeypair -keystore build/debug.keystore -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=Eink QPoll Flash Debug" >/dev/null 2>&1
fi
"$tools_path/apksigner" sign --ks build/debug.keystore --ks-pass pass:android --out build/eink-qpoll-flash-debug.apk build/aligned.apk
"$tools_path/apksigner" verify --verbose build/eink-qpoll-flash-debug.apk
echo "APK: $PWD/build/eink-qpoll-flash-debug.apk"
