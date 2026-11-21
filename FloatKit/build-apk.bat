@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
cd /d "%~dp0"
title FloatKit - Build APK

echo ==========================================================
echo    FloatKit  Android 悬浮助手 - Windows 一键打包
echo ==========================================================
echo.

rem ------------------------------------------------------------
rem [1/5] 查找 JDK
rem ------------------------------------------------------------
set "JDK_HOME="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JDK_HOME=%JAVA_HOME%"
if not defined JDK_HOME if exist "C:\Program Files\Android\Android Studio\jbr\bin\javac.exe" set "JDK_HOME=C:\Program Files\Android\Android Studio\jbr"
if not defined JDK_HOME if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\javac.exe" set "JDK_HOME=%LOCALAPPDATA%\Programs\Android Studio\jbr"
if not defined JDK_HOME if exist "%ProgramFiles%\Java\jdk-17\bin\javac.exe" set "JDK_HOME=%ProgramFiles%\Java\jdk-17"
if not defined JDK_HOME if exist "%ProgramFiles%\Eclipse Adoptium\jdk-17.0.11.9-hotspot\bin\javac.exe" set "JDK_HOME=%ProgramFiles%\Eclipse Adoptium\jdk-17.0.11.9-hotspot"

if defined JDK_HOME (
    set "JAVA_HOME=!JDK_HOME!"
    echo [1/5] JDK: !JDK_HOME!
) else (
    where javac >nul 2>nul
    if errorlevel 1 (
        echo [错误] 没有找到 JDK 17 或更新版本。
        echo        请安装 Android Studio（自带 JDK），或安装 JDK 17 并设置 JAVA_HOME 环境变量。
        echo.
        pause
        exit /b 1
    )
    echo [1/5] JDK: 使用 PATH 中的 JDK
)

rem ------------------------------------------------------------
rem [2/5] 查找 Android SDK 并写入 local.properties
rem ------------------------------------------------------------
set "SDK_DIR="
if defined ANDROID_HOME if exist "%ANDROID_HOME%\platforms" set "SDK_DIR=%ANDROID_HOME%"
if not defined SDK_DIR if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platforms" set "SDK_DIR=%ANDROID_SDK_ROOT%"
if not defined SDK_DIR if exist "%LOCALAPPDATA%\Android\Sdk\platforms" set "SDK_DIR=%LOCALAPPDATA%\Android\Sdk"

if not defined SDK_DIR (
    echo [错误] 没有找到 Android SDK。
    echo        默认路径应为 %LOCALAPPDATA%\Android\Sdk
    echo        请安装 Android Studio，并在 SDK Manager 中安装 SDK Platform 34。
    echo.
    pause
    exit /b 1
)
echo [2/5] Android SDK: !SDK_DIR!
set "SDK_FWD=!SDK_DIR:\=/!"
> "local.properties" echo sdk.dir=!SDK_FWD!

rem ------------------------------------------------------------
rem [3/5] 准备 Gradle（本地缓存，避免依赖 wrapper jar）
rem ------------------------------------------------------------
set "GRADLE_VER=8.7"
set "TOOLS_DIR=%~dp0.buildtools"
set "GRADLE_DIR=!TOOLS_DIR!\gradle-!GRADLE_VER!"

if exist "!GRADLE_DIR!\bin\gradle.bat" goto :gradle_ready

if not exist "!TOOLS_DIR!" mkdir "!TOOLS_DIR!"
set "GRADLE_ZIP=!TOOLS_DIR!\gradle-!GRADLE_VER!-bin.zip"
if not exist "!GRADLE_ZIP!" (
    echo [3/5] 正在下载 Gradle !GRADLE_VER! ...
    curl.exe -L --fail --retry 2 -o "!GRADLE_ZIP!" "https://services.gradle.org/distributions/gradle-!GRADLE_VER!-bin.zip"
    if errorlevel 1 (
        echo [错误] Gradle 下载失败，请检查网络后重试。
        pause
        exit /b 1
    )
)
echo [3/5] 正在解压 Gradle ...
tar -xf "!GRADLE_ZIP!" -C "!TOOLS_DIR!"
if errorlevel 1 (
    echo [错误] 解压 Gradle 失败。
    pause
    exit /b 1
)

:gradle_ready
echo [3/5] Gradle: !GRADLE_DIR!

rem ------------------------------------------------------------
rem [4/5] 编译 debug APK
rem ------------------------------------------------------------
echo [4/5] 开始编译（首次运行需要联网下载 Android Gradle Plugin，请耐心等待）...
call "!GRADLE_DIR!\bin\gradle.bat" --no-daemon :app:assembleDebug
if errorlevel 1 (
    echo.
    echo [错误] 编译失败，请查看上面的日志。
    pause
    exit /b 1
)

rem ------------------------------------------------------------
rem [5/5] 复制 APK，并补齐 gradlew（方便 Android Studio / 命令行使用）
rem ------------------------------------------------------------
if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo [5/5] 正在生成 Gradle Wrapper ...
    call "!GRADLE_DIR!\bin\gradle.bat" --no-daemon wrapper --gradle-version !GRADLE_VER! >nul 2>nul
)

set "APK_FILE=app\build\outputs\apk\debug\app-debug.apk"
if not exist "!APK_FILE!" (
    echo [错误] 没有找到 APK 输出：!APK_FILE!
    pause
    exit /b 1
)
if not exist "dist" mkdir "dist"
copy /y "!APK_FILE!" "dist\FloatKit-debug.apk" >nul

echo.
echo ==========================================================
echo    构建成功！
echo.
echo    APK 位置：%~dp0dist\FloatKit-debug.apk
echo    安装：把 APK 传到手机点击安装即可（需允许未知来源）
echo ==========================================================
echo.
pause
