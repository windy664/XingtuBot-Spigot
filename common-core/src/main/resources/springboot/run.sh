#!/bin/bash
echo "========================================"
echo " 昕途机器人 · Spring Boot 独立运行"
echo "========================================"
echo

JAR="XingtuBot-SpringBoot.jar"
LIBS="libs"

# 检查 jar 是否存在
if [ ! -f "$JAR" ]; then
    echo "[错误] 找不到 $JAR"
    echo "请先执行: gradlew :common-core:bootStandalone"
    exit 1
fi

# 创建 libs 目录（扩展插件放这里）
mkdir -p "$LIBS"

# 启动（支持 -Dloader.path 加载扩展）
java -Dloader.path="${LIBS}/" -jar "$JAR" "$@"

echo
echo "昕途机器人已退出。"
