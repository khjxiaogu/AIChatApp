@echo off
chcp 65001 >nul  REM 设置控制台为UTF-8编码，支持中文显示
setlocal enabledelayedexpansion

REM 检查参数个数
if "%~3"=="" (
    echo 用法: %~nx0 文件夹1 文件夹2 文件夹3
    echo 示例: %~nx0 C:\list1 D:\source E:\target
    exit /b 1
)

set "folder1=%~1"
set "folder2=%~2"
set "folder3=%~3"

REM 检查文件夹1是否存在
if not exist "%folder1%\" (
    echo 错误: 文件夹1 "%folder1%" 不存在。
    exit /b 1
)

REM 检查文件夹2是否存在
if not exist "%folder2%\" (
    echo 错误: 文件夹2 "%folder2%" 不存在。
    exit /b 1
)

REM 创建文件夹3（如果不存在）
if not exist "%folder3%\" (
    mkdir "%folder3%"
    if errorlevel 1 (
        echo 错误: 无法创建文件夹3 "%folder3%"
        exit /b 1
    )
)

echo 开始处理...

REM 遍历文件夹1中的文件（忽略子文件夹）
for /f "delims=" %%f in ('dir /b /a-d "%folder1%" 2^>nul') do (
    if exist "%folder2%\%%f" (
        echo 复制 "%folder2%\%%f" 到 "%folder3%\"
        copy /y "%folder2%\%%f" "%folder3%\" >nul
        if errorlevel 1 (
            echo 警告: 复制 %%f 失败。
        )
    ) else (
        echo 找不到文件: %%f 在文件夹2中
    )
)

echo 处理完成。
endlocal
pause