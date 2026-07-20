@echo off
chcp 65001 >nul 2>&1
title XingtuBot Spring Boot

echo ========================================
echo  昕途机器人 · Spring Boot 独立运行
echo ========================================
echo.

set JAR=XingtuBot-SpringBoot.jar
set LIBS=libs

REM 检查 jar 是否存在
if not exist "%JAR%" (
    echo [错误] 找不到 %JAR%
    echo 请先执行: gradlew :common-core:bootStandalone
    pause
    exit /b 1
)

REM 构建 classpath：主 jar + libs/ 下的扩展 jar
set CP=%JAR%
if exist "%LIBS%" (
    for %%f in (%LIBS%\*.jar) do (
        set CP=!CP!;%%f
    )
)

REM 启动（支持 -Dloader.path 加载扩展）
java -Dloader.path=%LIBS%/ -jar %JAR% %*

echo.
echo 昕途机器人已退出。
pause
