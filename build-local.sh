#!/bin/sh
# 本机（proot / arm64）重新编译 Kokona 的一键脚本
# 用法: sh build-local.sh [额外 gradle 参数...]

set -e
cd "$(dirname "$0")"

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
export ANDROID_HOME=/opt/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew :app:assembleDebug \
  --no-daemon \
  --max-workers=2 \
  -Pandroid.aapt2FromMavenOverride=/opt/aapt2/aapt2 \
  -Dorg.gradle.jvmargs="-Xmx2000m -XX:MaxMetaspaceSize=768m" \
  -Dkotlin.daemon.jvmargs="-Xmx1400m" \
  -x :web:buildWebUi \
  "$@"

ls -lh app/build/outputs/apk/github/debug/*.apk
