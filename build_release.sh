#!/bin/bash

# ===================== 配置项 自己改这里 =====================
# 1. APK归档存放的根目录
ARCHIVE_ROOT="/Users/dragon/Workground/local/RCamera/apk"
# 2. App模块名 一般就是app
APP_MODULE="app"
# ============================================================

# 进入项目根目录
SCRIPT_DIR=$(cd "$(dirname "$0")"; pwd)
cd "$SCRIPT_DIR" || exit 1

# 1. 读取版本名称 versionName
VERSION_NAME=$(grep -E 'versionName' ./$APP_MODULE/build.gradle.kts | sed -n 's/.*versionName[ ]*=[ ]*"\([^"]*\)".*/\1/p' | head -n1)
if [ -z "$VERSION_NAME" ]; then
    echo "❌ 读取版本号失败，请检查 build.gradle 里是否有 versionName"
    exit 1
fi
echo "✅ 当前App版本号：$VERSION_NAME"

# 2. 清理并编译Release
echo "🔨 开始编译Release版本..."
./gradlew clean assembleRelease

# 3. 源APK路径
SOURCE_APK="./$APP_MODULE/build/outputs/apk/release/$APP_MODULE-release.apk"

if [ ! -f "$SOURCE_APK" ]; then
    echo "❌ 编译失败，未找到Release APK"
    exit 1
fi

# 4. 创建版本归档目录
TARGET_DIR="$ARCHIVE_ROOT/$VERSION_NAME"
mkdir -p "$TARGET_DIR"

# 5. 拷贝APK并重命名 带版本号
TARGET_APK="$TARGET_DIR/app-release-$VERSION_NAME.apk"
cp "$SOURCE_APK" "$TARGET_APK"

echo "🎉 编译归档完成！"
echo "📦 归档路径：$TARGET_APK"