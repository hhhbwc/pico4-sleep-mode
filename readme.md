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
- 戴着头显时保持亮屏；摘下后等待 3 分钟再休眠，倒计时内重新戴上会取消休眠。
- 支持在“设置 -> 通用 -> 快捷方式”中添加、移除和拖拽排序；模块条目不会写入 PICO 的 Room 数据库。
- 使用 `pico_power_coord_v2` 最后操作生效协议：请求格式 `2|token|owner|payload`，V-Sleep 与 Power Mode 通过 request/ack/effective_owner/phase/error 完成交接；版本 1.2 (code 3)。

### 当前限制与计划

- 仅在已 root 且安装 LSPosed / Zygisk-Vector 的 PICO 4、系统版本 5.13.7 上验证；系统更新可能改变 Hook 目标。
- 不会关闭 6DoF 追踪，也没有防误触逻辑。
- 后续计划：可配置睡眠预设、兼容性诊断、长时间 VRChat V 睡的电量/温度/稳定性实测，以及固件兼容性矩阵。

### 安装与验证

1. 安装 Release 提供的 `v-sleep-mode.apk`；自行构建时 APK 位于 `mod_vsleep/build/v-sleep-mode.apk`。
2. 在 LSPosed / Zygisk-Vector 启用模块，作用域只选择 `com.picovr.settings`。
3. 强制停止“设置”或重启头显。
4. 在“设置 -> 通用 -> 快捷方式”中添加或排序 V-Sleep Mode，然后从 Dock 右侧时间/电源区域打开二级快捷设置面板。

开启前先记录当前值，开启和关闭后均可检查：

```sh
adb shell 'settings get global pico_vsleep_enabled; settings get global pico_vsleep_snapshot_valid; settings get system screen_brightness'
adb shell su -c 'getprop persist.pvr.config.eyebuffer_width; getprop persist.pvr.config.eyebuffer_height; getprop persist.pvr.config.ffr; getprop persist.pvr.config.target_fps'
adb shell su -c 'for p in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor; do printf "%s=" "$p"; cat "$p"; done'
```

开启后预期依次为模式标记 `1`、快照标记 `1`、亮度 `1`、眼缓冲 `1024`、`1024`、FFR `1`，各 CPU policy 为 `powersave`。关闭后模式和快照标记均应为 `0`，其余值必须逐项恢复为开启前记录的值。

模块会在任何低功耗写入前保存并验证完整快照。任一写入或 CPU policy 回验失败时，不会提交开启状态，并会尝试回滚。旧版本遗留的单一 governor 备份会在首次关闭时迁移到当前全部 CPU policy；若没有完整备份，模块会拒绝猜测默认值并在 LSPosed 日志中报告原因。

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
- The display stays awake while the headset is worn. Removing it starts a three-minute sleep timer; wearing it again cancels the timer.
- The tile can be added, removed, and reordered under Settings -> General -> Shortcuts. Module metadata is never written to PICO's Room database.
- Uses the `pico_power_coord_v2` last-operation-wins protocol (`2|token|owner|payload`) with request/ack/effective-owner/phase/error handoff; version 1.2 (code 3).

### Limitations and roadmap

- Tested only on rooted PICO 4 firmware 5.13.7 with LSPosed / Zygisk-Vector. Firmware updates can break the hooks.
- No 6DoF disabling and no accidental-touch prevention.
- Planned: configurable presets, compatibility diagnostics, long VRChat V-sleep power/temperature/stability tests, and a firmware compatibility matrix.

### Install

1. Install `v-sleep-mode.apk` from Releases, or build `mod_vsleep/build/v-sleep-mode.apk` yourself.
2. Enable the module in LSPosed / Zygisk-Vector, scoped only to `com.picovr.settings`.
3. Force-stop Settings or reboot the headset.
4. Add or reorder V-Sleep Mode under Settings -> General -> Shortcuts, then open the secondary Quick Settings panel from the Dock time/power area.

Before enabling, record the current values. Check after enabling and disabling:

```sh
adb shell 'settings get global pico_vsleep_enabled; settings get global pico_vsleep_snapshot_valid; settings get system screen_brightness'
adb shell su -c 'getprop persist.pvr.config.eyebuffer_width; getprop persist.pvr.config.eyebuffer_height; getprop persist.pvr.config.ffr; getprop persist.pvr.config.target_fps'
adb shell su -c 'for p in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor; do printf "%s=" "$p"; cat "$p"; done'
```

After enabling, the mode and snapshot flags should both be `1`, brightness should be `1`, eye-buffer dimensions should be `1024`, FFR should be `1`, and every CPU policy should be `powersave`. After disabling, both flags should be `0` and every other value must match its recorded pre-enable value.

The module saves and verifies a complete snapshot before changing power settings. A failed write or governor verification does not commit the enabled state and triggers a rollback attempt. A legacy single-governor backup is migrated across the currently available policies the first time it is disabled; without a complete backup, the module refuses to invent restoration defaults and logs the reason to LSPosed.

---

## Русский

Модуль LSPosed для PICO 4: добавляет переключатель V-Sleep Mode во вторичную панель быстрых настроек, которая открывается через область времени/питания в Dock.

### Зачем он нужен

В CST (UTC+8) `2026-08-15 04:20` и UTC `2026-08-14 20:20`,<br>
PDT (UTC-7) `2026-08-14 13:20` и EDT (UTC-4) `2026-08-14 16:20`,<br>
CEST (UTC+2) `2026-08-14 22:20` и MSK (UTC+3) `2026-08-14 23:20`,<br>
я собирался V-спать в VRChat. Пауэрбанк не справлялся с энергопотреблением шлема, поэтому заряд продолжал уменьшаться даже во время зарядки. Я разозлился и написал этот модуль 😡. ~~Теперь я собираюсь проверить, действительно ли помогает этот V-Sleep Mode.~~ проверено, всё нормально.

### Реализовано

- Нативно выглядящая плитка V-Sleep Mode в быстрых настройках с иконкой модуля, без изменения системного APK.
- Режим низкого энергопотребления одним нажатием: буфер глаз `1024 x 1024`, FFR, яркость `1`, governor CPU `powersave`.
- Восстановление ранее сохранённых буфера глаз, FFR, яркости и governor CPU одним нажатием.
- Выполнены повторные тесты переключения на устройстве; активация и восстановление записываются в журналы LSPosed.
- Пока шлем надет, экран не гаснет. После снятия запускается таймер сна на три минуты; повторное надевание отменяет таймер.
- Плитку можно добавлять, удалять и перемещать в «Настройки -> Общие -> Ярлыки»; данные модуля не записываются в базу Room PICO.

### Ограничения и планы

- Проверено только на PICO 4 с прошивкой 5.13.7, root и LSPosed / Zygisk-Vector; обновление прошивки может сломать Hook.
- Нет отключения 6DoF и защиты от случайных нажатий.
- В планах: настраиваемые пресеты, диагностика совместимости, длительные тесты V-Sleep Mode в VRChat и таблица совместимости прошивок.

### Установка

1. Установите `v-sleep-mode.apk` из Releases или соберите `mod_vsleep/build/v-sleep-mode.apk` самостоятельно.
2. Включите модуль в LSPosed / Zygisk-Vector только для `com.picovr.settings`.
3. Принудительно остановите Settings или перезагрузите шлем.
4. Добавьте или переместите V-Sleep Mode в «Настройки -> Общие -> Ярлыки», затем откройте вторичную панель быстрых настроек через область времени/питания Dock.

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
