# Runtime Coordination

本模块通过 `Settings.Global` 实现 `pico_power_coord_v2` 最后操作生效协议。

## v2 keys

- `pico_power_coord_v2_request`
- `pico_power_coord_v2_ack`
- `pico_power_coord_v2_effective_owner`
- `pico_power_coord_v2_phase`
- `pico_power_coord_v2_error`

请求格式为 `2|token|owner|payload`。token 使用 Java 8 `UUID.randomUUID()` 生成。`request` 是发布者的最新意图；`ack` 只能复制当前完整 request，因此新请求覆盖旧请求时不得确认旧请求。

## Ownership and handoff

V-Sleep enable 先发布 `owner=vsleep` 请求。只有请求仍为最新、且没有待恢复快照时，才捕获快照并应用低功耗状态；`pico_vsleep_enabled` 是最后提交点。成功后 `effective_owner=vsleep`、`phase=active`，且 `ack=request`。

当 V-Sleep active 或存在快照时发现最新 `owner=power` 请求，立即关闭有效 UI、取消睡眠计时、释放 wakelock，恢复私有和协调快照，并完整清理事务；只有清理完成后才 ack 当前最新 power request。轮询使用已有单线程 executor。新请求覆盖旧请求时只处理最新请求，不重复恢复旧快照。

Settings 进程重启后，启动轮询会根据持久化快照和最新 power request 继续恢复交接。UI 不仅检查 `pico_vsleep_enabled`，只有 `effective_owner=vsleep`、`phase=active` 且 committed 状态才显示开启；`restoring` / `error` 显示中文短提示并关闭开启边框。

## Stored state

事务记录 eye-buffer 宽高、FFR、enable_ffr、foveation.level、精确 FPS（含空值）、亮度和每个 CPU policy 的 governor。升级读取旧 v1 事务时，v1 未管理的两项属性使用当时仍未被模块修改的 live 值。`pico_power_coord_snapshot_valid=1` 表示恢复仍待完成。硬件状态不会用猜测的默认值替换。快捷面板编辑继续隔离 Room，仅保存模块自己的全局设置。

## Matrix region-switch handoff

Matrix region switching is a package transition and requires the same display/power quiescence as other system changes. The Matrix module sets the Global setting `pico_matrix_coord_state=transitioning` before its transition and removes it when finished. V-Sleep refuses to start a new transaction while this value is `transitioning`.

When V-Sleep is active, Matrix publishes `2|token|power|matrix-switch` to `pico_power_coord_v2_request`. The existing V-Sleep poll restores the saved display/CPU snapshot, releases the wakelock, clears `sleep_active` and `snapshot_valid`, acknowledges the exact request, and enters `idle`. Matrix waits for that acknowledgement before downloading or installing the APK. A timeout or `error` phase leaves the user-visible state unchanged and must be investigated rather than clearing snapshot settings manually.

Diagnostics:

```sh
settings get global pico_matrix_coord_state
settings get global pico_power_coord_v2_phase
settings get global pico_power_coord_sleep_active
settings get global pico_power_coord_snapshot_valid
settings get global pico_power_coord_v2_request
settings get global pico_power_coord_v2_ack
```

## Validation

协议纯 Java 判断逻辑位于 `CoordinationProtocol.java`，测试覆盖非法解析、token 精确匹配、later request wins 和 effective UI 判定；原有排序、佩戴传感器、180 秒睡眠延迟测试保持有效。目标设备为 PICO 4 A8110 firmware `5.13.7`，需验证每个 Power Mode 等级、active 状态下请求、刷新 Settings 以及进程重启恢复。
