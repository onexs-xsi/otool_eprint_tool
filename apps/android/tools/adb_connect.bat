@echo off
rem 无线 ADB 连接 SM-X800（Galaxy Tab S8+，Android 14）。
rem 注意：Android 无线调试端口每次开启会随机变化。
rem 连接失败时，在平板上进入 开发者选项 -^> 无线调试 查看当前 IP:端口，
rem 修改下方 ADB_TARGET 后重试；首次配对需先用 "adb pair IP:配对端口"。
rem Git Bash 下执行 adb 命令时注意设 MSYS2_ARG_CONV_EXCL="*"，避免远端路径被改写。
setlocal
set ADB=%~dp0..\..\..\.tools\android-sdk\platform-tools\adb.exe
set ADB_TARGET=DEVICE_IP:ADB_PORT
"%ADB%" connect %ADB_TARGET%
"%ADB%" devices
endlocal
