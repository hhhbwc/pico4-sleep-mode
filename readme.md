# PICO 4 Sleep Mode

[中文](#中文) | [English](#english) | [Русский](#русский)

---

## 中文

PICO 4 的 LSPosed 模块：把一个 V-Sleep Mode 开关加入 Dock 右侧时间/电源入口打开的二级快捷设置面板。

### 起因

在北京时间 CST (UTC+8)：`2026-08-15 04:20`，协调世界时 UTC：`2026-08-14 20:20`，<br>
太平洋夏令时 PDT (UTC-7)：`2026-08-14 13:20`，美东夏令时 EDT (UTC-4)：`2026-08-14 16:20`，<br>
中欧夏令时 CEST (UTC+2)：`2026-08-14 22:20`，莫斯科时间 MSK (UTC+3)：`2026-08-14 23:20`，<br>
我准备在 VRChat 里 V 睡。充电宝的充电功率干不过头显的耗电，电量越用越少；怒而写出这个模块 😡。~~现在写完了，准备测试一下这个 V-Sleep Mode 到底有没有用。~~经过测试有效。


### 已实现

- 在 PICO 系统二级快捷设置面板加入 V-Sleep Mode 快捷格，复用系统原生外观、焦点和开启状态边框。
- 使用模块自带图标，不修改系统 APK。
- 一键开启低功耗模式：眼缓冲 `1024 x 1024`、固定注视渲染（FFR）、亮度 `1`、CPU governor `powersave`。
- 一键关闭并恢复开启前保存的眼缓冲、FFR、亮度和 CPU governor。
- 已在实机连续开关测试，开启与恢复均有 LSPosed 日志记录。

### 当前限制与计划

- 仅在已 root 且安装 LSPosed / Zygisk-Vector 的 PICO 4 上验证；系统更新可能改变 Hook 目标。
- 快捷格当前固定在快捷栏第一位。
- 官方“设置 -> 通用 -> 快捷方式”的添加、删除和拖拽排序暂不支持。该固件的编辑链路与自定义项目不兼容，曾导致 Settings 崩溃，现已禁用以优先保证稳定。
- 不会关闭 6DoF 追踪，也没有防误触逻辑。
- 后续计划：可配置睡眠预设、兼容性诊断、长时间 VRChat V 睡的电量/温度/稳定性实测，以及固件兼容性矩阵。

### 安装与验证

1. 安装 Release 提供的 `v-sleep-mode.apk`；自行构建时 APK 位于 `mod_vsleep/build/v-sleep-mode.apk`。
2. 在 LSPosed / Zygisk-Vector 启用模块，作用域只选择 `com.picovr.settings`。
3. 强制停止“设置”或重启头显。
4. 点击 Dock 右侧时间/电源区域，V-Sleep Mode 位于二级面板底部快捷栏第一格。

开启后可检查：

```sh
adb shell 'settings get global pico_vsleep_enabled'
adb shell su -c 'getprop persist.pvr.config.eyebuffer_width; getprop persist.pvr.config.eyebuffer_height; getprop persist.pvr.config.ffr'
adb shell su -c 'for p in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor; do printf "%s=" "$p"; cat "$p"; done'
```

预期依次为 `1`、`1024`、`1024`、`1`，各 CPU policy 为 `powersave`。关闭后应恢复原值。

---

## English

An LSPosed module for PICO 4 that adds a V-Sleep Mode toggle to the secondary Quick Settings panel opened from the Dock time/power area.

### Why it exists

At CST (UTC+8) `2026-08-15 04:20` and UTC `2026-08-14 20:20`,<br>
PDT (UTC-7) `2026-08-14 13:20` and EDT (UTC-4) `2026-08-14 16:20`,<br>
CEST (UTC+2) `2026-08-14 22:20` and MSK (UTC+3) `2026-08-14 23:20`,<br>
I was about to V-sleep in VRChat. My power bank could not keep up with the headset's power draw, so the battery kept draining while charging. I got angry and wrote this module 😡. ~~It is now finished, and I am about to test whether this V-Sleep Mode actually helps.~~ tested and confirmed.

### Implemented

- Native-looking V-Sleep Mode Quick Settings tile with module-provided icon; no system APK modification.
- One-tap sleep mode: `1024 x 1024` eye buffer, FFR enabled, brightness `1`, and `powersave` CPU governor.
- One-tap restoration of the eye buffer, FFR, brightness, and CPU governor saved before activation.
- Repeated on-device toggle testing completed, with LSPosed logs for both activation and restoration.

### Limitations and roadmap

- Tested only on rooted PICO 4 with LSPosed / Zygisk-Vector. Firmware updates can break the hooks.
- The tile is currently fixed in the first Quick Settings slot.
- The stock add/remove/reorder editor is disabled for now because this firmware's editor path crashes with custom items.
- No 6DoF disabling and no accidental-touch prevention.
- Planned: configurable presets, compatibility diagnostics, long VRChat V-sleep power/temperature/stability tests, and a firmware compatibility matrix.

### Install

1. Install `v-sleep-mode.apk` from Releases, or build `mod_vsleep/build/v-sleep-mode.apk` yourself.
2. Enable the module in LSPosed / Zygisk-Vector, scoped only to `com.picovr.settings`.
3. Force-stop Settings or reboot the headset.
4. Open the Dock time/power panel. V-Sleep Mode is the first tile in the lower Quick Settings row.

---

## Русский

Модуль LSPosed для PICO 4: добавляет переключатель V-Sleep Mode во вторичную панель быстрых настроек, которая открывается через область времени/питания в Dock.

### Зачем он нужен

В CST (UTC+8) `2026-08-15 04:20` и UTC `2026-08-14 20:20`,<br>
PDT (UTC-7) `2026-08-14 13:20` и EDT (UTC-4) `2026-08-14 16:20`,<br>
CEST (UTC+2) `2026-08-14 22:20` и MSK (UTC+3) `2026-08-14 23:20`,<br>
я собирался V-спать в VRChat. Пауэрбанк не справлялся с энергопотреблением шлема, поэтому заряд продолжал уменьшаться даже во время зарядки. Я разозлился и написал этот модуль 😡. ~~Теперь он готов, и я собираюсь проверить, действительно ли помогает этот V-Sleep Mode.~~ проверено, всё нормально.

### Реализовано

- Нативно выглядящая плитка V-Sleep Mode в быстрых настройках с иконкой модуля, без изменения системного APK.
- Режим низкого энергопотребления одним нажатием: буфер глаз `1024 x 1024`, FFR, яркость `1`, governor CPU `powersave`.
- Восстановление ранее сохранённых буфера глаз, FFR, яркости и governor CPU одним нажатием.
- Выполнены повторные тесты переключения на устройстве; активация и восстановление записываются в журналы LSPosed.

### Ограничения и планы

- Проверено только на PICO 4 с root и LSPosed / Zygisk-Vector; обновление прошивки может сломать Hook.
- Плитка сейчас закреплена на первом месте ряда быстрых настроек.
- Добавление, удаление и перетаскивание в штатном редакторе временно отключены: эта прошивка аварийно завершает Settings при пользовательских элементах.
- Нет отключения 6DoF и защиты от случайных нажатий.
- В планах: настраиваемые пресеты, диагностика совместимости, длительные тесты V-Sleep Mode в VRChat и таблица совместимости прошивок.

### Установка

1. Установите `v-sleep-mode.apk` из Releases или соберите `mod_vsleep/build/v-sleep-mode.apk` самостоятельно.
2. Включите модуль в LSPosed / Zygisk-Vector только для `com.picovr.settings`.
3. Принудительно остановите Settings или перезагрузите шлем.
4. Откройте панель времени/питания в Dock. V-Sleep Mode будет первой плиткой нижнего ряда быстрых настроек.

---

## Build

Source lives in `mod_vsleep/`. The current lightweight Windows build uses apktool + D8:

```bat
pico4\build_mod.bat ..\pico4-vsleep\mod_vsleep com\picoxr\vsleep vsleep
```

It requires JDK 8+, Android D8 / R8, apktool, and an APK signing certificate. `stub/` supplies compile-time Android/Xposed declarations only; LSPosed and Android provide their actual runtime implementations.

## Risk

This module needs root and writes system properties, brightness settings, and CPU governor values. It reduces visual quality and performance, and cannot guarantee that every VRChat scene will consume less power than the charger supplies. Back up your device, monitor temperature, and use it at your own risk.

## Credits

- PICO Quick Settings implementation for the reusable visual shell.
- LSPosed / Zygisk-Vector for the Hook runtime.
