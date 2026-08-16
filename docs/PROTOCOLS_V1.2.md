# v1.2 打印协议

v1.2 的协议层只接收统一的 1-bit、MSB-first 点阵，与蓝牙传输完全解耦。每种协议编码器都可以用 JVM 单元测试验证，不依赖打印机连接。

## 位图管线

```text
LabelDocument（毫米）
  → 按设备档案 DPI 渲染 ARGB 位图
  → 亮度阈值二值化（默认 180）
  → 每行 (widthDots + 7) / 8 字节，MSB-first
  → 设备档案选定的协议编码器
  → SPP / BLE 分包发送
```

打印前会比较 `画布毫米宽 × DPI / 25.4` 与设备档案的最大点宽。超宽任务不会被静默裁剪，而是直接阻止打印。

## ESC/POS

- 初始化：`ESC @`
- 居中：`ESC a 1`
- 图像：`GS v 0 m xL xH yL yH d...`
- 大图按 256 行分带，降低小缓冲打印机一次接收失败的概率。
- 恢复左对齐并按档案设置走纸行数；默认不自动切纸。

`GS v 0` 在 Epson 当前参考中已标为旧命令，但仍是大量通用蓝牙 POS 机共同实现的兼容子集。针对明确支持 Epson 新图形函数的设备，后续可增加单独档案，不能在通用档案里盲目替换。

## TSPL / TSPL2

任务结构：

```text
SIZE width mm,height mm
GAP 2 mm,0 mm
DIRECTION 1
REFERENCE 0,0
CLS
BITMAP 0,0,widthBytes,heightDots,0,<binary raster>
PRINT 1,1
```

`BITMAP` 的宽度单位是字节，高度单位是点，模式 0 为覆盖。此实现适合 TSC 及明确兼容 TSPL/TSPL2 的机型；纸张传感器、黑标和无间隙纸配置需要后续按具体设备档案扩展。

## CPCL

任务结构：

```text
! 0 dpi dpi heightDots 1
PW widthDots
CG widthBytes heightDots 0 0 <binary raster>
FORM
PRINT
```

使用 CPCL `CG` 二进制图形命令，面向明确支持 CPCL 的 Zebra 移动打印机。Zebra 同系列不同固件可能同时支持 CPCL/ZPL，必须选择与设备当前语言一致的档案。

## ZPL

任务使用 `^XA`/`^XZ` 包围，并以 `^GFA,total,total,bytesPerRow,ASCII-HEX` 发送点阵。ASCII 十六进制比原始二进制约大一倍，但对蓝牙串流、日志和首版兼容性更稳健。

## 公开依据

- [Epson ESC/POS 命令参考](https://download4.epson.biz/sec_pubs/pos/reference_en/escpos/commands.html)
- [TSC TSPL/TSPL2 Programming Manual](https://fs.tscprinters.com/system/files/31-0000001-00_tspl_tspl2_programming_3_0.pdf)
- [Zebra CPCL Programming Guide](https://www.zebra.com/content/dam/support-dam/en/documentation/unrestricted/guide/software/cpcl-link-os-pg-en.pdf)
- [Zebra ZPL ^GF 命令](https://docs.zebra.com/content/tcm/us/en/printers/software/zpl-pg/zpl-commands/%5Egf.html)

## 明确不实现

- 不复制 WePrint 私有协议的账号令牌、默认密钥或防仿授权逻辑。
- 不把未知二进制设备猜测成 ESC/POS。
- 不在没有真实机型证据时发送切刀、钱箱、固件升级或耗材写入命令。
