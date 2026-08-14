# PICO 4 V-Sleep

PICO 4 的 LSPosed 模块：把一个 V-Sleep 开关加入 Dock 右侧时间/电源入口打开的快捷设置面板。

## 起因

北京时间凌晨 4 点 20 分，我准备在 VRChat 里 V 睡。充电宝的充电功率干不过头显的耗电，电量越用越少；怒而写出这个模块 😡。现在写完了，准备 V 睡测试一下它到底有没有用。

V-Sleep 指 VR 辅助睡眠场景，不是 Virtual Desktop 串流功能。

## 已实现

- 在 PICO 系统的二级快捷设置面板加入 V-Sleep 快捷格。
- 使用系统原生快捷格外观、焦点和开启状态边框。
- 使用模块自带的 V-Sleep 图标，不修改系统 APK。
- 一键开启低功耗模式：
  - 眼缓冲分辨率：`1024 x 1024`
  - 固定注视渲染（FFR）：开启
  - 屏幕亮度：`1`
  - CPU governor：`powersave`
- 一键关闭并恢复开启前保存的眼缓冲、FFR、亮度和 CPU governor。
- 实机完成连续开关测试；开启和恢复均有 LSPosed 日志记录。

## 当前限制

- 仅针对已 root、已安装 LSPosed / Zygisk-Vector 的 PICO 4 固件验证。
- Hook 宿主为 `com.picovr.settings`；系统更新后内部类或方法变化可能导致失效。
- 快捷格当前固定插入快捷栏首位。
- 官方“设置 -> 通用 -> 快捷方式”中的添加、删除、拖拽排序暂不支持。该系统版本的编辑链路会因自定义资源 / RecyclerView ViewType 不兼容导致 Settings 崩溃，已主动禁用以保持主面板稳定。
- 不会关闭 6DoF 追踪，也没有防误触逻辑。
- `powersave` 是否可用取决于设备内核提供的 governor；本模块在 PICO 4 上验证过。

## 安装

1. 安装发行版提供的 `vsleep.apk`；自行构建时，APK 位于 `mod_vsleep/build/vsleep.apk`。
2. 在 LSPosed / Zygisk-Vector 启用模块，作用域只选择：

   ```text
   com.picovr.settings
   ```

3. 强制停止“设置”或重启头显。
4. 点击 Dock 右侧的时间/电源区域，打开二级快捷设置面板；V-Sleep 位于底部快捷栏第一格。

## 使用与验证

点击快捷格切换模式。开启后可用以下命令检查：

```sh
adb shell 'settings get global pico_vsleep_enabled'
adb shell su -c 'getprop persist.pvr.config.eyebuffer_width; getprop persist.pvr.config.eyebuffer_height; getprop persist.pvr.config.ffr'
adb shell su -c 'for p in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor; do printf "%s=" "$p"; cat "$p"; done'
```

开启时预期为：`1`、`1024`、`1024`、`1`，各 CPU policy 为 `powersave`。关闭后应恢复开启前保存的值。

## 构建

源码位于 `mod_vsleep/`。当前使用 apktool + D8 的轻量构建流程，Windows 上可复用本项目工作区的构建脚本：

```bat
pico4\build_mod.bat ..\pico4-vsleep\mod_vsleep com\picoxr\vsleep vsleep
```

构建需要：JDK 8+、Android D8 / R8、apktool，以及可用的 APK 签名证书。输出为：

```text
mod_vsleep\build\vsleep.apk
```

`stub/` 仅用于编译期提供 Xposed 和 Android API 声明；运行时由 LSPosed 和系统框架提供实际实现。

## 计划 / 待实现

- 在不触发系统编辑页崩溃的前提下，重新实现官方快捷方式编辑页的添加、删除和拖拽排序。
- 做成可配置的睡眠预设，例如眼缓冲分辨率、亮度、FFR 与 CPU 策略。
- 增加状态诊断与兼容性检查，明确提示系统版本或 governor 不支持的情况。
- 长时间 VRChat V 睡实测：记录电量变化、温度、稳定性和恢复行为。
- 为不同 PICO 固件版本整理兼容性矩阵。

## 风险与免责

该模块会写入系统属性、亮度设置和 CPU governor，需要 root。它会降低显示和性能质量，不保证降低总功耗足以抵消所有 VRChat 场景的耗电。请自行备份，确认设备温度和充电设备安全；使用风险自担。

## 致谢

- PICO 系统快捷设置实现提供了可复用的界面外壳。
- LSPosed / Zygisk-Vector 提供 Hook 运行环境。
