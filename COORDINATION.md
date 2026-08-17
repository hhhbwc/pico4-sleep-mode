# Runtime Coordination

This module participates in protocol `pico_power_coord_v1` through `Settings.Global`.

## Ownership

- V-Sleep owns physical display and CPU state while `pico_power_coord_owner=vsleep` and `pico_power_coord_sleep_active=1`.
- Power Mode always publishes `pico_power_coord_requested_power_mode`; it must not write eye-buffer properties while V-Sleep owns the transaction.
- On V-Sleep exit, a valid pending Power Mode request is applied through PICO's native mode switch. Without one, the captured baseline is restored.

## Stored State

The transaction records eye-buffer width and height, FFR, FPS, brightness, and every discovered CPU policy governor. `pico_power_coord_snapshot_valid=1` means restoration remains pending. Hardware state is never replaced with guessed defaults.

## Validation

This protocol targets PICO 4 A8110 firmware `5.13.7`. Verify V-Sleep enable/disable at every Power Mode level, a deferred Power Mode selection while V-Sleep is active, and recovery after restarting `com.picovr.settings`.
