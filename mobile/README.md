# GNSS 接收实验室 · 公开版

默认目标纬度和经度均为空，后台服务也不内置目标位置。手动填写有效 WGS-84 坐标，或通过授权的配套控制端同步当前场景后再开始记录。未设置目标时不使用隐藏默认坐标，不把未知目标显示为 0 或 NaN。

公开版使用独立应用 ID 与日志访问标识，不包含开发者目标、设备日志或接收轨迹。记录与导出仍可能包含使用者自己的真实位置，请自行保管。

## English

Target latitude and longitude are empty by default, including the background service. Enter valid WGS-84 coordinates or synchronize a scenario from the authorized companion controller before recording. An unset target has no hidden fallback and is not displayed as zero or NaN.

The public edition uses separate application/log identifiers and includes no developer target or captured logs. User-created recordings and exports can contain the user's actual location.

安装与构建 / Installation: [README](../Readme.md).
