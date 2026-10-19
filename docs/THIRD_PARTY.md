# 技术来源与第三方说明 / Technical origins and third-party notices

本文件区分底层信号实现、本项目集成和外部数据服务；不将上游功能列表当作本应用验证结果。
This document separates upstream signal implementations, local integration and external services. An upstream feature list is not an application acceptance result.

| 来源 / Origin | 在本项目中的作用 / Role | 版本与许可说明 / Revision and notices |
| --- | --- | --- |
| [Great Scott Gadgets HackRF](https://github.com/greatscottgadgets/hackrf) | 仓库基础、libhackrf、host tools、固件和硬件 / Repository base, device library, host tools, firmware and hardware | 基础提交 / base `468c5d9`; preserve component notices and the original [Readme](HACKRF-UPSTREAM.md). Firmware, host software and hardware may have different terms. |
| [Takuji Ebinuma / osqzss gps-sdr-sim](https://github.com/osqzss/gps-sdr-sim) | GPS L1 C/A 生成核心 / GPS L1 C/A core | 固定 / pinned `28ca29a6719475195e3aabd5930c4ed02d67190f`; MIT, see the [upstream license](../research/gps-sdr-sim/LICENSE). |
| [Jun Mo / globsky SignalSim](https://github.com/globsky/SignalSim) | 北斗 B1I 核心和保留的多 GNSS 研究代码 / BeiDou B1I core and retained multi-GNSS research | 固定 / pinned `baba9deb68ed193efb9fd9a362b431ae8c491fa4`; source headers retain Jun Mo copyright and “All rights reserved.” No repository-level LICENSE file was found at this revision. Do not assume MIT or blanket relicensing; confirm upstream permission/terms for intended redistribution and commercial use. |
| [GNSS-SDR](https://gnss-sdr.org/) | 独立软件解码、定位和 NMEA 输出 / Independent decoding, positioning and NMEA | 外部安装，不与 app 一起打包 / separately installed; see [upstream source and license](https://github.com/gnss-sdr/gnss-sdr). |
| [eviltransform](https://github.com/googollee/eviltransform) | GCJ-02 / WGS-84 转换；Android 含适配实现 / Coordinate conversion and adapted Android implementation | Python dependency `0.1.1`; preserve [BSD-2-Clause notice](../mobile-controller/licenses/eviltransform-LICENSE.txt). Conversion is approximate. |
| [NumPy](https://numpy.org/) | 轨迹、坐标和数值计算 / Trajectory and numerical work | Python dependency pinned in `research/requirements.txt`; upstream BSD license. |
| [OpenStreetMap](https://www.openstreetmap.org/) | 可选联网地图 / Optional online map | 保留署名；数据与瓦片服务条款分别适用 / Keep attribution; data and tile-service terms apply separately. [Copyright](https://www.openstreetmap.org/copyright), [tile policy](https://operations.osmfoundation.org/policies/tiles/). |
| [高德 / AMap](https://lbs.amap.com/) | Web JS 与 Android 地图、Android 搜索和城市定位 / Web and Android maps, Android search and city lookup | Android dependency `11.3.100_loc11.3.000_sea9.8.1`; proprietary SDK/service terms. Users supply their own credentials and consent. |
| Apple MapKit / CoreLocation | 桌面文字搜索与城市识别 / Desktop text search and city lookup | macOS system frameworks and service terms; separate from the selected OSM/AMap base map. |

## 本项目增加了什么 / Local integration

- **产品界面 / Applications:** SwiftUI desktop UI, Android controller/receiver tools, public presets, local settings, map selection and diagnostics.
- **轨迹与策略 / Motion and policies:** continuous motion and drift models, shared environment policy, previews and platform-alignment checks.
- **GPS 集成 / GPS:** `research/build_generator.py` builds a patched copy of the pinned gps-sdr-sim source, including motion/timing and environment integration.
- **北斗集成 / BeiDou:** `research/build_bds_generator.py` checks the pinned SignalSim revision and builds a patched copy. Changes include RINEX BDS model/header handling, continuous 10 Hz ECEF input, optional model defaults and platform configuration. The script also contains changes used only by retained research paths.
- **管线 / Pipeline:** navigation download/coverage checks, bounded native streams, GPS/B1I mixing, USB transport, cancellation and stop diagnostics, independent-validation helpers.
- **可追踪构建 / Provenance:** generated `provenance.json` records the source revision, patched-source hash, binary hash and patch descriptions. This is traceability, not a digital signature or a receiver certification.

SignalSim's original README describes broader constellation, frequency and commercial-feature support. Those claims belong to upstream; Tianshu's public application scope remains GPS L1 C/A and BeiDou B1I plus a combined experimental/integration path. In particular, retained B1C research explicitly does not claim a complete, independently validated B1C waveform.

## 导航数据 / Navigation data

The URL templates in [`shared/navigation-sources.json`](../shared/navigation-sources.json) use NOAA CORS, a BeiDou-related public broadcast-data service, BKG/IGS archives and Geoscience Australia mirrors. These providers supply navigation data, not this application's signal-generation implementation. Availability, recency and coverage must be checked for every run. Do not describe archived test fixtures as current data.

## 许可证范围 / License scope

Keep the upstream submodules and their notices intact. The root HackRF license does not replace dependency licenses or service terms, and this public fork does not grant new rights over third-party code. The absence of a SignalSim LICENSE file is recorded explicitly rather than filled in with an invented license. No personal API keys or signing materials are distributed.
