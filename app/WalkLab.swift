import SwiftUI
import Observation
import Foundation
import AppKit
import WebKit
import MapKit
import CoreLocation

struct SignalEnvironments: Decodable {
    struct Profile: Decodable { let id: String; let label: String }
    let profiles: [Profile]
    let routes: [String: String]
    let startupSpeedRangesCmPerSecond: [String: [Int]]
    let startupDriftRangesHundredths: [String: [Int]]
    static let shared: SignalEnvironments = {
        guard let url = Bundle.main.url(forResource: "signal-environments", withExtension: "json"),
              let data = try? Data(contentsOf: url), let policy = try? JSONDecoder().decode(SignalEnvironments.self, from: data) else { fatalError("信号环境配置缺失，请重新安装应用") }
        return policy
    }()
}

struct Settings: Codable, Equatable, Sendable {
    var longitude = -77.03637
    var latitude = 38.89761
    var coordinateSystem = "WGS-84"
    var height = 18.0
    var duration = 60.0
    var speed = 0.5
    var radius = 12.0
    var route = "circle"
    var heading = 0.0
    var heightVariation = 0.08
    var horizontalDrift = 2.5
    var verticalDrift = 0.5
    var seed = 42.0
    var speedVariation = 0.2
    var speedSmoothing = 2.0
    var randomHeading = true
    var headingMinimum = 0.0
    var headingMaximum = 360.0
    var headingHoldMinimum = 3.0
    var headingHoldMaximum = 8.0
    var turnSmoothing = 2.0
    var heightSmoothing = 2.0
    var driftSmoothing = 10.0
}

struct SavedLocation: Codable, Identifiable, Equatable, Sendable {
    var id = UUID().uuidString
    var alias: String
    var latitude: Double
    var longitude: Double
    var coordinateSystem: String
    var referenceElevation: Double? = nil
    var builtinKey: String? = nil
    var minimum: Double? = nil
    var maximum: Double? = nil

    static let presets = [
        SavedLocation(alias: "白宫", latitude: 38.89761, longitude: -77.03637, coordinateSystem: "WGS-84", referenceElevation: 18),
        SavedLocation(alias: "唐宁街10号", latitude: 51.503368, longitude: -0.127721, coordinateSystem: "WGS-84", referenceElevation: 10),
        SavedLocation(alias: "国会山", latitude: 38.8898, longitude: -77.009, coordinateSystem: "WGS-84", referenceElevation: 27)
    ].map { value in var preset = value; preset.builtinKey = preset.alias; return preset }
}

struct PreviewInfo: Decodable, Sendable {
    let wgs84Latitude: Double
    let wgs84Longitude: Double
    let coordinateConversion: String
    let sampledPath: [[Double]]?
    let idealPath: [[Double]]
    let emittedPath: [[Double]]
    let idealHeightMin: Double
    let idealHeightMax: Double
    let emittedHeightMin: Double
    let emittedHeightMax: Double
    let geoidUndulation: Double?
    let ellipsoidHeight: Double?
    let maxHorizontalDrift: Double
    let estimatedFileMB: Int
    let streamMBps: Double?
    let streamSampleRate: Int?
    let rfEnabled: Bool
}

struct ResultInfo: Decodable, Sendable {
    let folder: String
    let passed: Bool
    let validFixes: Int
    let medianError: Double?
    let maxError: Double?
    let medianErrorToHumanPath: Double?
    let decodedPath: [[Double]]
    let rfEnabled: Bool
    let startUTC: String?
    let sceneSatellites: [String]?
    let mode: String?
    let firstFixSeconds: Double?
    let maxGapSeconds: Double?
    let verificationKind: String?
    var isHardwareStream: Bool { verificationKind == "hardware-stream" }
    var validFixesText: String { isHardwareStream ? "—" : String(validFixes) }
    var hardwareStreamStatus: String { passed ? "流式发送已停止，接收记录单独验收" : "流式发送未通过硬件检查，请查看日志与接收记录" }
}

struct Event: Decodable, Sendable {
    let type: String
    let message: String?
    let fraction: Double?
    let preview: PreviewInfo?
    let result: ResultInfo?
    let receiverFile: String?
}

struct ReceiverSnapshot: Decodable, Sendable {
    let connected: Bool
    let device: String
    let sourceFile: String
    let state: String
    let fresh: Bool
    let ageSeconds: Double?
    let path: [[Double]]
    let latitude: Double?
    let longitude: Double?
    let height: Double?
    let accuracy: Double?
    let hdop: Double?
    let mock: Bool
    let satellites: String
    let satelliteAgeSeconds: Double?
    let network: String
}

@MainActor @Observable final class PlaceLookup: NSObject, @preconcurrency CLLocationManagerDelegate {
    var query = ""
    var city = ""
    var message = "经纬度按 经度,纬度 输入；文字查找使用 Apple 地图。"
    var results: [MKMapItem] = []
    var searching = false
    @ObservationIgnored private let manager = CLLocationManager()
    @ObservationIgnored private let geocoder = CLGeocoder()
    @ObservationIgnored private var requestedCity = false
    override init() { super.init(); manager.delegate = self; manager.desiredAccuracy = kCLLocationAccuracyKilometer }
    func locateCity() {
        requestedCity = true
        if manager.authorizationStatus == .notDetermined { manager.requestWhenInUseAuthorization() }
        else if manager.authorizationStatus == .authorizedAlways { manager.requestLocation() }
        else { message = "未获定位权限；仍可输入完整城市与地址查找。" }
    }
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if requestedCity && manager.authorizationStatus == .authorizedAlways { manager.requestLocation() }
    }
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last, abs(location.timestamp.timeIntervalSinceNow) < 300 else { return }
        geocoder.reverseGeocodeLocation(location) { [weak self] marks, error in
            Task { @MainActor in
                guard let self else { return }
                self.city = marks?.first?.locality ?? marks?.first?.administrativeArea ?? ""
                self.message = self.city.isEmpty ? "当前城市不可用，请输入完整地址。" : "默认搜索城市：\(self.city)"
            }
        }
    }
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) { message = "当前城市不可用；可输入城市与完整地址。" }
    static func coordinates(_ text: String) throws -> [Double]? {
        let query = text.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "，", with: ",")
        guard !query.isEmpty, query.allSatisfy({ "0123456789+-. ,;\t\n".contains($0) }) else { return nil }
        let parts = query.split(whereSeparator: { ",; \t\n".contains($0) })
        guard parts.count == 2, let lon = Double(parts[0]), let lat = Double(parts[1]), lon.isFinite, lat.isFinite, abs(lon) <= 180, abs(lat) <= 85 else {
            throw NSError(domain: "PlaceLookup", code: 1, userInfo: [NSLocalizedDescriptionKey: "请输入有效的 经度,纬度。"])
        }
        return [lat, lon]
    }
    func search(pick: @escaping (String, Double, Double) -> Void) {
        guard !searching else { return }
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { message = "请输入地点或经纬度。"; return }
        do { if let point = try Self.coordinates(text) { pick("经纬度选点", point[0], point[1]); results = []; return } }
        catch { message = error.localizedDescription; return }
        searching = true; message = "正在查找地点…"
        let request = MKLocalSearch.Request(); request.naturalLanguageQuery = city.isEmpty ? text : city + " " + text
        MKLocalSearch(request: request).start { [weak self] response, error in
            Task { @MainActor in
                guard let self else { return }
                self.searching = false; self.results = Array((response?.mapItems ?? []).prefix(10))
                self.message = error?.localizedDescription ?? (self.results.isEmpty ? "没有找到地点，请输入完整地址。" : "请选择查找结果，再命名保存。")
            }
        }
    }
}

@MainActor @Observable final class LabModel {
    static let shared = LabModel()
    var settings: Settings
    var signalMode = "gps"
    var selectedSignals: Set<String> = ["gps"]
    @ObservationIgnored var mixedTransmissionAccepted = true
    var simulationStartAction: String { signalMode == "gps" ? "start" : signalMode + "-start" }
    var heightKnown = true
    var canStartSimulation: Bool { !busy && heightKnown && preview != nil && !selectedSignals.isEmpty && (signalMode != "mixed" || mixedTransmissionAccepted) }
    var placeLookup = PlaceLookup()
    func selectSignal(_ system: String) {
        guard !busy else { return }
        if selectedSignals.contains(system) { selectedSignals.remove(system) } else { selectedSignals.insert(system) }
        defaults.set(Array(selectedSignals).sorted(), forKey: "selectedSignals")
        if !selectedSignals.isEmpty {
            signalMode = selectedSignals.count == 2 ? "mixed" : selectedSignals.first!
            if signalMode != "gps" { settings.duration = max(90, settings.duration) }
            updatePreview()
        } else { previewTask?.cancel(); preview = nil; status = "请选择卫星系统" }
    }
    func choosePlace(_ name: String, _ lat: Double, _ lon: Double) {
        guard !busy, lat.isFinite, lon.isFinite, abs(lat) <= 85, abs(lon) <= 180 else { return }
        selectedLocationID = ""; defaults.set("", forKey: "selectedLocationID"); locationName = name; settings.latitude = lat; settings.longitude = lon; settings.coordinateSystem = "WGS-84"
        heightKnown = false; settings.height = Settings().height; settings.heightVariation = Settings().heightVariation
        updatePreview()
    }
    var signalLabel: String { signalMode == "mixed" ? "GPS＋北斗" : signalMode == "bds" ? "北斗" : "GPS" }
    var locations: [SavedLocation] = []
    var selectedLocationID = ""
    var locationName = ""
    @ObservationIgnored private let defaults: UserDefaults
    var preview: PreviewInfo?
    var result: ResultInfo?
    var busy = false
    var taskStartedUptime = 0.0
    var progress = 0.0
    var status = "准备参数后开始模拟"
    var failed = false
    var phaseTitle: String { busy ? (cancelled ? "正在停止" : transmitting ? "模拟运行中" : "正在处理") : failed ? "任务未完成" : !heightKnown ? "高度待确认" : preview == nil ? "检查参数" : "准备就绪" }
    var logs = ""
    var deviceInfo = ""
    var showDevice = false
    var txGain = 47
    var txAmplifier = true
    var transmitting = false
    var receiver: ReceiverSnapshot?
    var receiverRunning = false
    var receiverFile = ""
    var receiverMessage = "等待本机 GNSS-SDR 解码输出"
    @ObservationIgnored private var receiverProcess: Process?
    @ObservationIgnored private var receiverOutput = Data()
    @ObservationIgnored private var receiverID = UUID()
    let signalProfiles = Dictionary(uniqueKeysWithValues: SignalEnvironments.shared.profiles.map { ($0.id, $0.label) })
    var signalProfile: String { SignalEnvironments.shared.routes[settings.route] ?? "open" }
    var domesticOnly = false { didSet { defaults.set(domesticOnly, forKey: "domesticOnly") } }
    @ObservationIgnored private var process: Process?
    @ObservationIgnored private var output = Data()
    @ObservationIgnored private var previewTask: Task<Void, Never>?
    @ObservationIgnored private var cancelled = false
    @ObservationIgnored private var runID = UUID()
    @ObservationIgnored let project = Bundle.main.object(forInfoDictionaryKey: "ProjectRoot") as? String ?? FileManager.default.currentDirectoryPath

    init(defaults: UserDefaults = UserDefaults(suiteName: "local.hackrf.walklab.public")!) {
        self.defaults = defaults
        settings = Settings()
        if defaults.object(forKey: "heightKnown") != nil { heightKnown = defaults.bool(forKey: "heightKnown") }
        domesticOnly = defaults.bool(forKey: "domesticOnly")
        if let signals = defaults.stringArray(forKey: "selectedSignals") {
            selectedSignals = Set(signals.filter { $0 == "gps" || $0 == "bds" })
            signalMode = selectedSignals.count == 2 ? "mixed" : selectedSignals.first ?? "gps"
        }
        if defaults.object(forKey: "txGain") != nil { txGain = min(47, max(0, defaults.integer(forKey: "txGain"))) }
        if defaults.object(forKey: "txAmplifier") != nil { txAmplifier = defaults.bool(forKey: "txAmplifier") }
        settings = Settings()
        if let data = defaults.data(forKey: "settings"),
           let saved = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let base = try? JSONEncoder().encode(Settings()),
           var merged = try? JSONSerialization.jsonObject(with: base) as? [String: Any] {
            merged.merge(saved) { _, existing in existing }
            if let encoded = try? JSONSerialization.data(withJSONObject: merged),
               let restored = try? JSONDecoder().decode(Settings.self, from: encoded) { settings = restored }
        }
        if !defaults.bool(forKey: "randomMotion20261017") {
            if settings.speed == 1.2 { settings.speed = 0.5 }
            defaults.set(true, forKey: "randomMotion20261017")
        }
        settings.duration = min(settings.duration, 600)
        if !["park", "stationary", "circle", "line"].contains(settings.route) {
            if let raw = defaults.data(forKey: "settings") { defaults.set(raw, forKey: "settingsRecovery") }
            settings.route = "park"; logs += "旧配置中的运动方式无法识别，已恢复园区路线；原始设置已保留。\n"
        }
        let previousSeed = defaults.object(forKey: "lastSessionSeed") as? Double ?? settings.seed
        repeat { settings.seed = Double(Int.random(in: 0...2147483647)) } while settings.seed == previousSeed
        defaults.set(settings.seed, forKey: "lastSessionSeed")
        randomizeSpeed()
        randomizeDrift()

        if !defaults.bool(forKey: "target20261016v2") {
            settings.longitude = Settings().longitude
            settings.latitude = Settings().latitude
            settings.coordinateSystem = "WGS-84"
            defaults.set(true, forKey: "target20261016v2")
        }
        if let data = defaults.data(forKey: "locations"), let saved = try? JSONDecoder().decode([SavedLocation].self, from: data) {
            locations = saved
        } else {
            if let raw = defaults.data(forKey: "locations") { defaults.set(raw, forKey: "locationsRecovery") }
            locations = SavedLocation.presets
            selectedLocationID = locations[0].id
            persistLocations()
        }
        selectedLocationID = defaults.string(forKey: "selectedLocationID") ?? locations.first?.id ?? ""
        if !selectedLocationID.isEmpty && !locations.contains(where: { $0.id == selectedLocationID }) { selectedLocationID = locations.first?.id ?? "" }
        if let selected = locations.first(where: { $0.id == selectedLocationID }),
           selected.latitude != settings.latitude || selected.longitude != settings.longitude || selected.coordinateSystem != settings.coordinateSystem {
            selectedLocationID = ""
            if defaults.object(forKey: "heightKnown") == nil { heightKnown = false }
        }
        if selectedLocationID.isEmpty && defaults.object(forKey: "heightKnown") == nil { heightKnown = false }
        locationName = locations.first(where: { $0.id == selectedLocationID })?.alias ?? ""
        if !defaults.bool(forKey: "presetLocations20261016") {
            for preset in SavedLocation.presets where !locations.contains(where: {
                $0.alias == preset.alias && $0.latitude == preset.latitude &&
                $0.longitude == preset.longitude && $0.coordinateSystem == preset.coordinateSystem
            }) {
                locations.append(preset)
            }
            persistLocations()
            defaults.set(true, forKey: "presetLocations20261016")
        }
        if !defaults.bool(forKey: "presetLocations20261017") {
            for preset in SavedLocation.presets.suffix(2) where !locations.contains(where: {
                $0.builtinKey == preset.builtinKey || ($0.alias == preset.alias &&
                $0.latitude == preset.latitude && $0.longitude == preset.longitude && $0.coordinateSystem == preset.coordinateSystem)
            }) {
                locations.append(preset)
            }
            persistLocations()
            defaults.set(true, forKey: "presetLocations20261017")
        }
        for index in locations.indices where locations[index].referenceElevation == nil {
            locations[index].referenceElevation = SavedLocation.presets.first(where: {
                $0.latitude == locations[index].latitude && $0.longitude == locations[index].longitude &&
                $0.coordinateSystem == locations[index].coordinateSystem
            })?.referenceElevation
        }
        for index in locations.indices where locations[index].builtinKey == nil {
            locations[index].builtinKey = SavedLocation.presets.first(where: {
                $0.alias == locations[index].alias || ($0.latitude == locations[index].latitude && $0.longitude == locations[index].longitude)
            })?.builtinKey
        }
        if let index = locations.firstIndex(where: { $0.id == selectedLocationID }), locations[index].referenceElevation == nil,
           locations[index].minimum == nil, settings.height != Settings().height,
           locations[index].latitude == settings.latitude, locations[index].longitude == settings.longitude,
           locations[index].coordinateSystem == settings.coordinateSystem {
            locations[index].minimum = settings.height - settings.heightVariation; locations[index].maximum = settings.height + settings.heightVariation
        }
        if let location = locations.first(where: { $0.id == selectedLocationID }),
           location.latitude == settings.latitude, location.longitude == settings.longitude,
           location.coordinateSystem == settings.coordinateSystem {
            applyLocationHeight(location)
        }
        persistLocations()
    }

    private func applyLocationHeight(_ location: SavedLocation) {
        if let low = location.minimum, let high = location.maximum, low.isFinite, high.isFinite, low >= -500, high <= 10000, low <= high {
            settings.height = (low + high) / 2; settings.heightVariation = (high - low) / 2; heightKnown = true
        } else if let elevation = location.referenceElevation {
            settings.height = elevation; settings.heightVariation = Settings().heightVariation; heightKnown = true
        } else { heightKnown = false; settings.height = Settings().height; settings.heightVariation = Settings().heightVariation }
    }
    private func retainLocationHeight() {
        guard heightKnown, settings.height.isFinite, settings.heightVariation.isFinite, settings.heightVariation >= 0,
              settings.height - settings.heightVariation >= -500, settings.height + settings.heightVariation <= 10000,
              let index = locations.firstIndex(where: { $0.id == selectedLocationID }),
              locations[index].latitude == settings.latitude, locations[index].longitude == settings.longitude,
              locations[index].coordinateSystem == settings.coordinateSystem else { return }
        locations[index].minimum = settings.height - settings.heightVariation
        locations[index].maximum = settings.height + settings.heightVariation
        persistLocations()
    }

    var selectedBuiltin: SavedLocation? {
        guard let key = locations.first(where: { $0.id == selectedLocationID })?.builtinKey else { return nil }
        return SavedLocation.presets.first(where: { $0.builtinKey == key })
    }

    func restoreDefaultLocation() {
        guard let preset = selectedBuiltin,
              let index = locations.firstIndex(where: { $0.id == selectedLocationID }) else { return }
        settings.latitude = preset.latitude; settings.longitude = preset.longitude
        settings.coordinateSystem = preset.coordinateSystem
        locations[index].latitude = preset.latitude; locations[index].longitude = preset.longitude
        locations[index].coordinateSystem = preset.coordinateSystem
        locations[index].referenceElevation = preset.referenceElevation
        locations[index].minimum = nil; locations[index].maximum = nil
        applyLocationHeight(preset)
        if let data = try? JSONEncoder().encode(settings) { defaults.set(data, forKey: "settings") }
        persistLocations(); status = "已恢复当前地点的内置默认地址"
    }

    private func persistLocations() {
        if let data = try? JSONEncoder().encode(locations) { defaults.set(data, forKey: "locations") }
        defaults.set(selectedLocationID, forKey: "selectedLocationID")
    }

    func selectLocation() {
        guard let location = locations.first(where: { $0.id == selectedLocationID }) else { return }
        settings.latitude = location.latitude; settings.longitude = location.longitude
        settings.coordinateSystem = location.coordinateSystem; locationName = location.alias
        applyLocationHeight(location)
        if let data = try? JSONEncoder().encode(settings) { defaults.set(data, forKey: "settings") }
        defaults.set(selectedLocationID, forKey: "selectedLocationID")
    }

    func saveLocation(add: Bool) {
        let name = locationName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, name.count <= 80, settings.latitude.isFinite, settings.longitude.isFinite,
              (-85...85).contains(settings.latitude), (-180...180).contains(settings.longitude) else {
            status = "请填写 1–80 字别名和有效经纬度"; return
        }
        var location = SavedLocation(alias: name, latitude: settings.latitude, longitude: settings.longitude, coordinateSystem: settings.coordinateSystem)
        location.referenceElevation = SavedLocation.presets.first(where: {
            $0.latitude == location.latitude && $0.longitude == location.longitude &&
            $0.coordinateSystem == location.coordinateSystem
        })?.referenceElevation
        if heightKnown { location.minimum = settings.height - settings.heightVariation; location.maximum = settings.height + settings.heightVariation }
        if !add, let index = locations.firstIndex(where: { $0.id == selectedLocationID }) {
            location.id = selectedLocationID; location.builtinKey = locations[index].builtinKey; locations[index] = location
        } else { locations.append(location); selectedLocationID = location.id }
        locationName = name; persistLocations(); persistControls(); status = "位置已保存：" + name
    }

    func deleteLocation() {
        locations.removeAll { $0.id == selectedLocationID }
        selectedLocationID = locations.first?.id ?? ""; locationName = ""
        selectLocation(); persistLocations()
    }

    nonisolated static func previewProcess(project: String, input: Data, mode: String = "gps") throws -> PreviewInfo {
        let process = Process(), stdin = Pipe(), stdout = Pipe(), stderr = Pipe()
        process.executableURL = URL(fileURLWithPath: project + "/research/.venv/bin/python")
        process.arguments = [project + "/research/app_runner.py", mode == "gps" ? "preview" : mode + "-preview"]
        process.standardInput = stdin
        process.standardOutput = stdout
        process.standardError = stderr
        try process.run()
        DispatchQueue.global().asyncAfter(deadline: .now() + 10) { if process.isRunning { process.terminate() } }
        stdin.fileHandleForWriting.write(input)
        try stdin.fileHandleForWriting.close()
        let data = stdout.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        let event = try JSONDecoder().decode(Event.self, from: data)
        guard let preview = event.preview, process.terminationStatus == 0 else {
            throw NSError(domain: "WalkLab", code: 1, userInfo: [NSLocalizedDescriptionKey: event.message ?? "无法计算轨迹预览"])
        }
        return preview
    }

    func connectReceiver() {
        guard !receiverRunning, !receiverFile.isEmpty else { return }
        receiverID = UUID(); let id = receiverID
        let child = Process(), pipe = Pipe()
        receiverOutput = Data(); receiverMessage = "正在读取本机 GNSS-SDR 解码输出"
        child.executableURL = URL(fileURLWithPath: project + "/research/.venv/bin/python")
        child.arguments = [project + "/research/mac_receiver_monitor.py", "--file", receiverFile]
        child.standardOutput = pipe; child.standardError = pipe
        pipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            if data.isEmpty { handle.readabilityHandler = nil; return }
            Task { @MainActor in
                guard let self, self.receiverID == id else { return }
                self.receiverOutput.append(data)
                while let newline = self.receiverOutput.firstIndex(of: 10) {
                    let line = Data(self.receiverOutput[..<newline]); self.receiverOutput.removeSubrange(...newline)
                    if let snapshot = try? JSONDecoder().decode(ReceiverSnapshot.self, from: line) {
                        self.receiver = snapshot; self.receiverMessage = snapshot.state
                    }
                }
                if self.receiverOutput.count > 1_000_000 { self.receiverOutput.removeAll(); self.receiverMessage = "接收数据格式异常，请重新连接" }
            }
        }
        child.terminationHandler = { [weak self] _ in
            Task { @MainActor in
                guard let self, self.receiverID == id else { return }
                self.receiverRunning = false; self.receiverProcess = nil; self.receiverMessage = "本机解码显示已结束，可重新读取"
            }
        }
        do { try child.run(); receiverProcess = child; receiverRunning = true }
        catch { pipe.fileHandleForReading.readabilityHandler = nil; receiverMessage = error.localizedDescription }
    }

    func stopReceiver() {
        receiverID = UUID(); receiverProcess?.terminate(); receiverProcess = nil
        receiverRunning = false; receiverMessage = "已停止接收显示"
    }

    func useReceiverFile(_ path: String) {
        stopReceiver(); receiver = nil; receiverFile = path; connectReceiver()
    }
    func chooseReceiverFile() {
        let panel = NSOpenPanel(); panel.canChooseDirectories = false; panel.allowsMultipleSelection = false
        panel.message = "选择 Mac GNSS-SDR 输出的 NMEA 文件（通常为 fix.nmea）"
        if panel.runModal() == .OK, let url = panel.url { useReceiverFile(url.path) }
    }

    static func geographicPath(_ points: [[Double]], latitude: Double, longitude: Double, height: Double) -> [[Double]] {
        let angle = latitude * .pi / 180, q = 1 - 0.00669437999014 * pow(sin(angle), 2)
        let north = 6378137 * (1 - 0.00669437999014) / pow(q, 1.5) + height
        let east = (6378137 / sqrt(q) + height) * cos(angle)
        guard abs(east) > 1 else { return [] }
        return points.compactMap { p in
            guard p.count >= 2, p[0].isFinite, p[1].isFinite else { return nil }
            let lon = longitude + p[0] / east * 180 / .pi
            return [latitude + p[1] / north * 180 / .pi, (lon + 540).truncatingRemainder(dividingBy: 360) - 180]
        }
    }

    func updatePreview() {
        guard !busy else { return }
        previewTask?.cancel()
        preview = nil; result = nil; failed = false
        guard let input = try? JSONEncoder().encode(settings) else { return }
        defaults.set(input, forKey: "settings"); defaults.set(heightKnown, forKey: "heightKnown")
        retainLocationHeight()
        let project = project
        let mode = signalMode
        previewTask = Task {
            do {
                try await Task.sleep(for: .milliseconds(250))
                let computed = try await Task.detached { try Self.previewProcess(project: project, input: input, mode: mode) }.value
                try Task.checkCancellation()
                preview = computed
                status = !heightKnown ? "请填写并确认当前地点的模拟高度" : mode == "mixed" ? (mixedTransmissionAccepted ? "参数有效 · 可实时流式模拟" : "参数有效 · 可实时流式模拟，待箱内验收") : "参数有效 · 可实时流式模拟"
            } catch is CancellationError { }
            catch { preview = nil; failed = true; status = error.localizedDescription }
        }
    }

    func randomizeSpeed() {
        let speedRange = SignalEnvironments.shared.startupSpeedRangesCmPerSecond[settings.route]!
        let previousLow = settings.speed - settings.speedVariation, previousHigh = settings.speed + settings.speedVariation
        var low: Double, high: Double
        repeat {
            low = Double(Int.random(in: speedRange[0]...speedRange[1])) / 100
            high = Double(Int.random(in: speedRange[2]...speedRange[3])) / 100
        } while high > 0 && abs(low - previousLow) < 0.0001 && abs(high - previousHigh) < 0.0001
        settings.speed = (low + high) / 2; settings.speedVariation = (high - low) / 2
    }

    func randomizeDrift() {
        let ranges = SignalEnvironments.shared.startupDriftRangesHundredths
        let horizontal = ranges["horizontalDrift"]!, vertical = ranges["verticalDrift"]!, smoothing = ranges["driftSmoothing"]!
        settings.horizontalDrift = Double(Int.random(in: horizontal[0]...horizontal[1])) / 100
        settings.verticalDrift = Double(Int.random(in: vertical[0]...vertical[1])) / 100
        settings.driftSmoothing = Double(Int.random(in: smoothing[0]...smoothing[1])) / 100
    }

    func renewSimulationSeed() {
        randomizeDrift()
        let previous = settings.seed
        repeat { settings.seed = Double(Int.random(in: 0...2147483647)) } while settings.seed == previous
    }

    func start(_ action: String = "run") {
        guard !busy else { return }
        guard action == "device" || !selectedSignals.isEmpty else { status = "请选择卫星系统"; return }
        if signalMode == "mixed", action != "device" {
            guard action == "mixed-start" else { status = "混合模式仅支持实时流式模拟，不生成整段 I/Q 文件"; return }
            guard mixedTransmissionAccepted else { status = "GPS＋北斗混合发送待箱内验收，尚未开放"; return }
        }
        guard signalMode == "gps" || action == signalMode || action == signalMode + "-start" || action == "device" else { return }
        guard action == "device" || heightKnown else { failed = true; status = "请先填写并确认当前地点的模拟高度"; return }
        if action == "start" || action.hasSuffix("-start") { renewSimulationSeed() }
        let data: Data
        if action == "transmit" {
            guard let result, result.passed,
                  let payload = try? JSONSerialization.data(withJSONObject: ["folder": result.folder, "gain": txGain, "amplifier": txAmplifier]) else { return }
            data = payload
        } else if action == "start" || action.hasSuffix("-start") {
            guard let encoded = try? JSONEncoder().encode(settings),
                  let parameters = try? JSONSerialization.jsonObject(with: encoded),
                  let payload = try? JSONSerialization.data(withJSONObject: ["settings": parameters, "gain": txGain, "amplifier": txAmplifier]) else { return }
            data = payload
        } else {
            guard let payload = try? JSONEncoder().encode(settings) else { return }
            data = payload
        }
        previewTask?.cancel()
        cancelled = false; failed = false
        retainLocationHeight()
        defaults.set(txGain, forKey: "txGain"); defaults.set(txAmplifier, forKey: "txAmplifier")
        if let saved = try? JSONEncoder().encode(settings) { defaults.set(saved, forKey: "settings") }
        runID = UUID(); let currentRunID = runID
        taskStartedUptime = ProcessInfo.processInfo.systemUptime
        busy = true
        progress = 0
        if action == "run" || action == "start" || action == signalMode || action.hasSuffix("-start") { result = nil }
        transmitting = action == "transmit" || action == "start" || action.hasSuffix("-start")
        output = Data()
        logs = ""
        status = action == "device" ? "正在只读检查设备" : (transmitting ? "正在准备最新导航数据与流式缓冲" : "正在准备步行场景")
        let child = Process(), stdout = Pipe(), stdin = Pipe()
        child.executableURL = URL(fileURLWithPath: project + "/research/.venv/bin/python")
        child.arguments = [project + "/research/app_runner.py", action]
        child.environment = ProcessInfo.processInfo.environment.merging([
            "WALKLAB_DOMESTIC_ONLY": domesticOnly ? "true" : "false"
        ]) { _, current in current }
        child.standardInput = stdin
        child.standardOutput = stdout
        child.standardError = stdout
        stdout.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            if data.isEmpty { handle.readabilityHandler = nil; return }
            Task { @MainActor in guard let self, self.runID == currentRunID else { return }; self.ingest(data) }
        }
        child.terminationHandler = { [weak self] process in
            let code = process.terminationStatus
            Task { @MainActor in
                guard let self, self.runID == currentRunID else { return }
                self.busy = false
                self.process = nil
                if self.cancelled || code != 0 || self.result?.passed == false {
                    let outcome = self.cancelled ? "已停止，可重新开始；设备停止情况请查看日志" : self.status
                    self.logs += "任务结束：" + self.status + "\n"; self.resetFlow(message: outcome, failure: !self.cancelled)
                }
                self.transmitting = false
            }
        }
        do {
            try child.run()
            process = child
            stdin.fileHandleForWriting.write(data)
            try stdin.fileHandleForWriting.close()
        } catch {
            busy = false; transmitting = false; process = nil
            logs += error.localizedDescription + "\n";resetFlow(message: "无法启动：" + error.localizedDescription, failure: true)
            stdout.fileHandleForReading.readabilityHandler = nil
        }
    }

    private func ingest(_ data: Data) {
        output.append(data)
        while let newline = output.firstIndex(of: 10) {
            let line = Data(output[..<newline])
            output.removeSubrange(...newline)
            guard let event = try? JSONDecoder().decode(Event.self, from: line) else {
                logs += String(decoding: line, as: UTF8.self) + "\n"
                continue
            }
            if let file = event.receiverFile { useReceiverFile(file) }
            if let preview = event.preview { self.preview = preview }
            if let message = event.message {
                status = message
                logs += message + "\n"
            }
            if let fraction = event.fraction { progress = fraction }
            if let result = event.result {
                self.result = result
                progress = 1
                if result.isHardwareStream {
                    status = result.hardwareStreamStatus
                } else if signalMode != "gps", !transmitting, result.mode == "current-broadcast" {
                    status = result.passed ? "\(signalLabel)最新数据轨迹验证通过；未启动射频" : "\(signalLabel)最新数据轨迹验证未通过，请查看报告"
                } else if result.passed, let timestamp = result.startUTC,
                   let date = ISO8601DateFormatter().date(from: timestamp) {
                    status = "验证通过 · 计划发送 " + date.formatted(date: .omitted, time: .standard) + (transmitting ? "；将自动启动，可随时关闭" : "；点击一键启动将重新获取最新数据")
                } else {
                    status = result.passed ? "历史离线场景，需重新生成当前星历" : "文件已生成，但定位验收未通过，请查看结果"
                }
            }
            if event.type == "device" { deviceInfo = event.message ?? "无设备信息"; showDevice = true }
        }
    }

    func resetFlow(message: String = "准备就绪 · 可重新开始", failure: Bool = false) {
        runID = UUID();progress = 0;result = nil;output = Data();cancelled = false;transmitting = false
        status = message; failed = failure
    }
    func persistControls() {
        defaults.set(heightKnown, forKey: "heightKnown")
        defaults.set(txGain, forKey: "txGain"); defaults.set(txAmplifier, forKey: "txAmplifier")
        retainLocationHeight()
        if let saved = try? JSONEncoder().encode(settings) { defaults.set(saved, forKey: "settings") }
    }
    func stop() {
        guard busy else { return }
        cancelled = true;status = "正在停止并清理本次任务…"
        process?.terminate()
    }
}

struct RoutePlot: View {
    @State private var zoom = 1.0
    @State private var pan = CGSize.zero
    @GestureState private var drag = CGSize.zero
    let preview: PreviewInfo?
    let result: ResultInfo?
    var body: some View {
        Canvas { context, size in
            let ideal = preview?.idealPath ?? []
            let emitted = preview?.sampledPath ?? preview?.emittedPath ?? []
            let decoded = result?.decodedPath ?? []
            let all = ideal + emitted + decoded
            let xs = all.compactMap { $0.first }, ys = all.compactMap { $0.count > 1 ? $0[1] : nil }
            let minX = min(xs.min() ?? -10, -2), maxX = max(xs.max() ?? 10, 2)
            let minY = min(ys.min() ?? -10, -2), maxY = max(ys.max() ?? 10, 2)
            let scale = min((size.width - 60) / max(maxX - minX, 10), (size.height - 55) / max(maxY - minY, 10)) * zoom
            let offsetX = pan.width + drag.width, offsetY = pan.height + drag.height
            func point(_ row: [Double]) -> CGPoint {
                CGPoint(x: size.width / 2 + offsetX + (row[0] - (maxX + minX) / 2) * scale,
                        y: size.height / 2 + offsetY - (row[1] - (maxY + minY) / 2) * scale)
            }
            var grid = Path()
            for i in 1..<8 {
                let x = size.width * Double(i) / 8, y = size.height * Double(i) / 8
                grid.move(to: CGPoint(x: x, y: 0)); grid.addLine(to: CGPoint(x: x, y: size.height))
                grid.move(to: CGPoint(x: 0, y: y)); grid.addLine(to: CGPoint(x: size.width, y: y))
            }
            context.stroke(grid, with: .color(.white.opacity(0.06)), lineWidth: 1)
            for (rows, color, width) in [(ideal, Color.white.opacity(0.65), 2.0), (emitted, Color.orange, 2.0), (decoded, Color.blue, 2.5)] {
                guard let first = rows.first else { continue }
                var path = Path(); path.move(to: point(first))
                for row in rows.dropFirst() { path.addLine(to: point(row)) }
                context.stroke(path, with: .color(color), lineWidth: width)
                let p = point(first)
                context.fill(Path(ellipseIn: CGRect(x: p.x - 3, y: p.y - 3, width: 6, height: 6)), with: .color(color))
            }
            let origin = point([0, 0])
            context.fill(Path(ellipseIn: CGRect(x: origin.x - 4, y: origin.y - 4, width: 8, height: 8)), with: .color(.mint))
            context.draw(Text("局部轨迹 · 东 → / 北 ↑").font(.caption).foregroundColor(.white.opacity(0.65)), at: CGPoint(x: 100, y: 18))
        }
        .background(Color(red: 0.035, green: 0.075, blue: 0.12))
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .accessibilityLabel("步行轨迹预览：灰白色为设定运动，橙色为叠加偏移和漂移后的轨迹，蓝色为独立接收机解算")
        .contentShape(Rectangle())
        .gesture(DragGesture(minimumDistance: 2)
            .updating($drag) { value, state, _ in state = value.translation }
            .onEnded { value in pan.width += value.translation.width; pan.height += value.translation.height })
        .overlay(alignment: .bottomTrailing) {
            HStack(spacing: 8) {
                Button("缩小") { zoom = max(0.25, zoom / 2) }.disabled(zoom <= 0.25)
                Button("全图") { zoom = 1; pan = .zero }
                Button("放大") { zoom = min(16, zoom * 2) }.disabled(zoom >= 16)
                Text("\(Int(zoom * 100))%").font(.caption).monospacedDigit()
            }
            .padding(8).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8)).padding(8)
        }
    }
}

@MainActor final class MapTileHandler: NSObject, WKURLSchemeHandler {
    private var requests: [ObjectIdentifier: URLSessionDataTask] = [:]
    private var schemeTasks: [ObjectIdentifier: WKURLSchemeTask] = [:]
    private let directory: URL
    init(directory: URL? = nil) { self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("WalkLabPublic/map-history");super.init() }
    func webView(_ webView: WKWebView, start task: WKURLSchemeTask) { load(task) }
    private func load(_ task: WKURLSchemeTask) {
        guard let url = task.request.url, url.host == "osm",
              url.path.range(of: #"^/[0-9]{1,2}/[0-9]{1,6}/[0-9]{1,6}\.png$"#, options: .regularExpression) != nil else { task.didFailWithError(URLError(.badURL)); return }
        let parts = url.path.dropFirst().replacingOccurrences(of: ".png", with: "").split(separator: "/").compactMap { Int($0) }
        guard parts.count == 3, (3...18).contains(parts[0]), parts[1] < 1 << parts[0], parts[2] < 1 << parts[0] else { task.didFailWithError(URLError(.badURL)); return }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent(parts.map(String.init).joined(separator: "-") + ".png")
        let expiry = file.appendingPathExtension("expires")
        let cached = try? Data(contentsOf: file)
        if let cached, let text = try? String(contentsOf: expiry, encoding: .utf8), let until = Double(text), until > Date().timeIntervalSince1970 { respond(task, cached); return }
        var request = URLRequest(url: URL(string: "https://tile.openstreetmap.org" + url.path)!, timeoutInterval: 8)
        request.setValue("WalkLab/0.6.12 macOS", forHTTPHeaderField: "User-Agent")
        let id = ObjectIdentifier(task)
        let download = URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            Task { @MainActor in
                guard let self, self.requests.removeValue(forKey: id) != nil, let task = self.schemeTasks.removeValue(forKey: id) else { return }
                if let data, data.count <= 1_000_000, data.starts(with: [137,80,78,71]), let http = response as? HTTPURLResponse, http.statusCode == 200 {
                    var ttl = 604800.0
                    if let control = http.value(forHTTPHeaderField: "Cache-Control"), let match = control.range(of: #"max-age=([0-9]+)"#, options: .regularExpression), let age = Double(control[match].dropFirst(8)) { ttl = max(ttl, age) }
                    if let expires = http.value(forHTTPHeaderField: "Expires") { let format = DateFormatter();format.locale = Locale(identifier: "en_US_POSIX");format.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz";if let date = format.date(from: expires) { ttl = max(ttl, date.timeIntervalSinceNow) } }
                    try? data.write(to: file, options: .atomic)
                    try? String(Date().timeIntervalSince1970 + min(ttl,31536000)).write(to: expiry, atomically: true, encoding: .utf8)
                    self.trim();self.respond(task, data)
                } else if let cached { self.respond(task, cached) }
                else { task.didFailWithError(error ?? URLError(.badServerResponse)) }
            }
        }
        schemeTasks[id] = task;requests[id] = download;download.resume()
    }
    func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) { let id = ObjectIdentifier(task);schemeTasks.removeValue(forKey: id);requests.removeValue(forKey: id)?.cancel() }
    private func respond(_ task: WKURLSchemeTask, _ data: Data) { guard let url = task.request.url else { return };task.didReceive(URLResponse(url: url, mimeType: "image/png", expectedContentLength: data.count, textEncodingName: nil));task.didReceive(data);task.didFinish() }
    static func selfTest() throws {
        @MainActor final class Probe: NSObject, @preconcurrency WKURLSchemeTask {
            let request = URLRequest(url: URL(string: "walklab-tile://osm/3/0/0.png")!)
            var bytes = Data();var finished = false
            func didReceive(_ response: URLResponse) { }
            func didReceive(_ data: Data) { bytes.append(data) }
            func didFinish() { finished = true }
            func didFailWithError(_ error: Error) { preconditionFailure(error.localizedDescription) }
        }
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let bytes = Data([137,80,78,71,13,10,26,10]);let file = root.appendingPathComponent("3-0-0.png")
        try bytes.write(to: file);try String(Date().timeIntervalSince1970 + 3600).write(to: file.appendingPathExtension("expires"), atomically: true, encoding: .utf8)
        let task = Probe();let handler = MapTileHandler(directory: root);handler.load(task)
        withExtendedLifetime(handler) { precondition(task.finished && task.bytes == bytes) }
    }
    private func trim() {
        let files = ((try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey])) ?? []).filter { $0.pathExtension == "png" }.sorted { ((try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) < ((try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) }
        var total = files.reduce(0) { $0 + ((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
        for file in files where total > 32*1024*1024 { let size = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0;if (try? FileManager.default.removeItem(at: file)) != nil { total -= size;try? FileManager.default.removeItem(at: file.appendingPathExtension("expires")) } }
    }
}

enum AMapConfiguration {
    static let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("WalkLabPublic/amap.json")
}

@MainActor final class AMapServer {
    static let shared = AMapServer()
    private var process: Process?
    private(set) var url: URL?
    private init() {
        guard FileManager.default.fileExists(atPath: AMapConfiguration.url.path),
              let helper = Bundle.main.url(forResource: "map_server", withExtension: "py"),
              let page = Bundle.main.url(forResource: "amap", withExtension: "html") else { return }
        let child = Process(), output = Pipe()
        let root = Bundle.main.object(forInfoDictionaryKey: "ProjectRoot") as? String ?? ""
        child.executableURL = URL(fileURLWithPath: root + "/research/.venv/bin/python")
        child.arguments = [helper.path, AMapConfiguration.url.path, page.path]
        child.standardOutput = output; child.standardError = FileHandle.nullDevice
        do {
            try child.run()
            let line = output.fileHandleForReading.availableData
            if let value = String(data: line, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines),
               let address = URL(string: value), address.scheme == "http", address.host == "127.0.0.1" {
                url = address; process = child
            } else { child.terminate() }
        } catch { url = nil }
    }
    func stop() { process?.terminate(); process = nil; url = nil }
}

struct LocationMapView: NSViewRepresentable {
    var onPick: ((Double, Double) -> Void)? = nil
    let latitude: Double
    let longitude: Double
    var simulation = ""
    var satellites = ""
    var idealPath: [[Double]] = []
    var emittedPath: [[Double]] = []
    var receivedPath: [[Double]] = []
    var receivedFresh = false
    @MainActor final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        var onPick: ((Double, Double) -> Void)?
        weak var view: WKWebView?
        var offlineHTML = ""
        func mapDiagnostic(_ message: String) {
            let clean = String(message.replacingOccurrences(of: "[a-fA-F0-9]{32}", with: "[redacted]", options: .regularExpression).prefix(300))
            let file = AMapConfiguration.url.deletingLastPathComponent().appendingPathComponent("map-diagnostics.log")
            let previous = (try? String(contentsOf: file, encoding: .utf8)) ?? ""
            try? String((previous + clean + "\n").suffix(4000)).write(to: file, atomically: true, encoding: .utf8)
            try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
        }
        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            if message.name == "mapDiagnostic" { mapDiagnostic(message.body as? String ?? "unknown"); return }
            if message.name == "mapFallback" || message.name == "mapReload" {
                mapDiagnostic(message.body as? String ?? message.name)
                ready = false
                let reason = (message.body as? String ?? "").contains("已切换") ? "" : "高德暂不可用 · "
                let fallback = offlineHTML.replacingOccurrences(of: "离线坐标网格 · 无道路楼宇", with: reason + "离线坐标网格 · 无道路楼宇")
                if message.name == "mapReload", let url = AMapServer.shared.url { view?.load(URLRequest(url: url)) }
                else { view?.loadHTMLString(fallback, baseURL: nil) }
                return
            }
            guard let point = message.body as? [Double], point.count == 2, point.allSatisfy({ $0.isFinite }), abs(point[0]) <= 85, abs(point[1]) <= 180 else { return }
            onPick?(point[0], point[1])
        }
        var latitude = Double.nan, longitude = Double.nan
        var ready = false
        var simulation = "", satellites = ""
        var idealPath: [[Double]] = [], emittedPath: [[Double]] = [], receivedPath: [[Double]] = []
        var receivedFresh = false
        func renderStatus(_ view: WKWebView) {
            guard ready, let payload = try? JSONSerialization.data(withJSONObject: [simulation,satellites]), let text = String(data: payload, encoding: .utf8) else { return };view.evaluateJavaScript("window.setSimulation(..." + text + ")", completionHandler: nil)
            let tracks: [Any] = [idealPath, emittedPath, receivedPath, receivedFresh]
            if let data = try? JSONSerialization.data(withJSONObject: tracks), let json = String(data: data, encoding: .utf8) {
                view.evaluateJavaScript("window.setTrajectories(..." + json + ")", completionHandler: nil)
            }
        }
        func update(_ view: WKWebView, _ lat: Double, _ lon: Double, force: Bool = false) {
            guard lat.isFinite, lon.isFinite, abs(lat) <= 85, abs(lon) <= 180 else { return }
            let changed = latitude != lat || longitude != lon
            latitude = lat; longitude = lon
            if ready && (changed || force) {
                view.evaluateJavaScript(String(format: "window.setLocation(%.9f,%.9f)", locale: Locale(identifier: "en_US_POSIX"), lat, lon), completionHandler: nil)
            }
        }
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            ready = true; update(webView, latitude, longitude, force: true);renderStatus(webView)
        }
        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void) {
            if action.navigationType == .linkActivated {
                if let url = action.request.url, url.scheme == "https", ["www.openstreetmap.org", "www.amap.com", "lbs.amap.com"].contains(url.host ?? "") { NSWorkspace.shared.open(url) }
                decisionHandler(.cancel)
            } else { decisionHandler(.allow) }
        }
    }
    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeNSView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration();configuration.applicationNameForUserAgent = "Tianshu/0.9.4";configuration.setURLSchemeHandler(MapTileHandler(), forURLScheme: "walklab-tile")
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = context.coordinator
        context.coordinator.onPick = onPick; context.coordinator.view = view
        for name in ["pickPlace", "mapFallback", "mapReload", "mapDiagnostic"] { configuration.userContentController.add(context.coordinator, name: name) }
        let amapURL = AMapServer.shared.url
        let picking = """
        pin.style.pointerEvents = 'auto'; pin.style.cursor = 'grab';
        let movingTarget = false, originalTarget = null;
        function pointAt(e) {
          const rect = map.getBoundingClientRect(), c = project(...center);
          return unproject(c[0] + e.clientX - rect.left - rect.width / 2, c[1] + e.clientY - rect.top - rect.height / 2);
        }
        pin.onpointerdown = e => { if (!center) return; e.stopPropagation(); movingTarget = true; originalTarget = [...target]; pin.setPointerCapture(e.pointerId); };
        pin.onpointermove = e => { if (movingTarget) { target = pointAt(e); render(); } };
        pin.onpointerup = e => { if (movingTarget) { movingTarget = false; window.webkit.messageHandlers.pickPlace.postMessage(pointAt(e)); } };
        pin.onpointercancel = () => { movingTarget = false; if (originalTarget) target = originalTarget; render(); };
        map.addEventListener('dblclick', e => {
          if (!center || e.target.closest('button')) return;
          window.webkit.messageHandlers.pickPlace.postMessage(pointAt(e));
        });
        """
        let offlinePicking = "if (!window.__AMAP_PAGE) {" + picking + (amapURL == nil ? "" : "document.getElementById('mode').textContent='高德';document.getElementById('mode').title='返回高德地图';document.getElementById('mode').onclick=()=>window.webkit.messageHandlers.mapReload.postMessage('reload');") + "}"
        configuration.userContentController.addUserScript(WKUserScript(source: offlinePicking, injectionTime: .atDocumentEnd, forMainFrameOnly: true))
        context.coordinator.simulation = simulation;context.coordinator.satellites = satellites
        context.coordinator.idealPath = idealPath;context.coordinator.emittedPath = emittedPath;context.coordinator.receivedPath = receivedPath;context.coordinator.receivedFresh = receivedFresh
        context.coordinator.update(view, latitude, longitude)
        if let url = Bundle.main.url(forResource: "map", withExtension: "html"), let html = try? String(contentsOf: url, encoding: .utf8) {
            context.coordinator.offlineHTML = html
            if let amapURL { view.load(URLRequest(url: amapURL)) } else { view.loadHTMLString(html, baseURL: nil) }
        }
        return view
    }
    func updateNSView(_ view: WKWebView, context: Context) { context.coordinator.update(view, latitude, longitude);context.coordinator.simulation = simulation;context.coordinator.satellites = satellites;context.coordinator.idealPath = idealPath;context.coordinator.emittedPath = emittedPath;context.coordinator.receivedPath = receivedPath;context.coordinator.receivedFresh = receivedFresh;context.coordinator.renderStatus(view) }
}

struct ReceiverPanel: View {
    @Bindable var model: LabModel
    var body: some View {
        GroupBox {
            VStack(alignment: .leading, spacing: 7) {
                HStack {
                    Label("Mac GNSS-SDR 本机解码", systemImage: "antenna.radiowaves.left.and.right").font(.headline)
                    Spacer()
                    Button(model.receiverRunning ? "停止显示" : "读取本机解码") {
                        if model.receiverRunning { model.stopReceiver() } else if model.receiverFile.isEmpty { model.chooseReceiverFile() } else { model.connectReceiver() }
                    }
                }
                HStack {
                    Button("选择本机 NMEA 文件") { model.chooseReceiverFile() }
                    Text(model.receiverMessage).foregroundStyle(model.receiverRunning && model.receiver?.fresh == true ? .green : .secondary)
                }
                if let r = model.receiver {
                    Text("\(r.device) · \(r.sourceFile.isEmpty ? "尚无记录" : r.sourceFile)").font(.caption).foregroundStyle(.secondary)
                    if let lat = r.latitude, let lon = r.longitude {
                        Text(String(format: "WGS-84 纬度 %.7f° · 经度 %.7f°", lat, lon)).font(.callout.monospacedDigit()).textSelection(.enabled)
                        HStack {
                            if let h = r.height { Text(String(format: "椭球高 %.2f m", h)) }
                            if let hdop = r.hdop { Text(String(format: "HDOP %.1f（非米）", hdop)) }
                            if let age = r.ageSeconds { Text(String(format: "解码输出 %.1f 秒前", age)) }
                            if r.mock { Text("Mock 标记：不绘入实测轨迹").foregroundStyle(.red) }
                        }.font(.caption.monospacedDigit())
                    }
                    Text(r.satellites).font(.caption.monospaced()).textSelection(.enabled)
                    if let age = r.satelliteAgeSeconds, age > 5 { Text(String(format: "卫星状态 %.0f 秒前，已陈旧", age)).font(.caption).foregroundStyle(.orange) }
                }
                Text("仅读取 Mac 本机 GNSS-SDR 的 NMEA 解码结果，不读取手机。仅生成验证时自动关联本轮文件；也可选择本机文件。离线解码不代表实际射频接收，流式发送不会自动产生解码结果。").font(.caption).foregroundStyle(.secondary)
            }.frame(maxWidth: .infinity, alignment: .leading).padding(4)
        }
    }
}

struct RunStatusPanel: View {
    let model: LabModel
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label(model.phaseTitle, systemImage: model.failed ? "exclamationmark.circle.fill" : "waveform.path")
                    .font(.headline).foregroundStyle(model.failed ? .red : .teal)
                Spacer()
                Text(model.signalLabel).font(.caption.weight(.semibold))
                if model.busy {
                    TimelineView(.periodic(from: .now, by: 1)) { _ in
                        Text("已用 \(Int(ProcessInfo.processInfo.systemUptime - model.taskStartedUptime)) 秒").font(.caption.monospacedDigit())
                    }
                }
            }
            Text(model.status.hasPrefix("No navigation source has fresh complete coverage:") ? "当前导航数据未满足完整时段校验，请稍后重试；各下载源详情见运行日志。" : model.status).font(.callout).foregroundStyle(model.failed ? .red : .primary).lineLimit(3).textSelection(.enabled)
            if model.failed { Text("展开下方“运行日志”查看完整原因。").font(.caption).foregroundStyle(.secondary) }
            if model.busy || model.progress > 0 { ProgressView(value: model.progress) }
        }.padding(14).frame(maxWidth: .infinity, alignment: .leading)
            .background(.quaternary.opacity(0.45), in: RoundedRectangle(cornerRadius: 12))
    }
}

struct ContentView: View {
    @State private var model = LabModel.shared
    private func number(_ label: String, _ value: Binding<Double>, digits: Int = 2) -> some View {
        HStack { Text(label); Spacer(); TextField(label, value: value, format: .number.precision(.fractionLength(digits))).labelsHidden().multilineTextAlignment(.trailing).frame(width: 115) }
    }
    private var heightMinimum: Binding<Double> {
        Binding(get: { model.settings.height - model.settings.heightVariation }, set: { lower in
            let upper = model.settings.height + model.settings.heightVariation
            guard lower.isFinite, lower <= upper else { model.status = "高度下限不能大于上限"; return }
            model.settings.height = (lower + upper)/2; model.settings.heightVariation = (upper - lower)/2
        })
    }
    private var speedMinimum: Binding<Double> {
        Binding(get: { max(0, model.settings.speed - model.settings.speedVariation) }, set: { lower in
            let upper = min(2.5, model.settings.speed + model.settings.speedVariation)
            guard lower.isFinite, lower >= 0, lower <= upper else { model.status = "速度下限必须介于 0 与上限之间"; return }
            model.settings.speed = (lower + upper)/2; model.settings.speedVariation = (upper - lower)/2
        })
    }
    private var speedMaximum: Binding<Double> {
        Binding(get: { min(2.5, model.settings.speed + model.settings.speedVariation) }, set: { upper in
            let lower = max(0, model.settings.speed - model.settings.speedVariation)
            guard upper.isFinite, upper >= lower, upper <= 2.5 else { model.status = "速度上限必须介于下限与 2.5 之间"; return }
            model.settings.speed = (lower + upper)/2; model.settings.speedVariation = (upper - lower)/2
        })
    }
    private var heightMaximum: Binding<Double> {
        Binding(get: { model.settings.height + model.settings.heightVariation }, set: { upper in
            let lower = model.settings.height - model.settings.heightVariation
            guard upper.isFinite, upper >= lower else { model.status = "高度上限不能小于下限"; return }
            model.settings.height = (lower + upper)/2; model.settings.heightVariation = (upper - lower)/2
        })
    }
    private func metric(_ title: String, _ value: String, _ detail: String) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.title3.monospacedDigit().weight(.semibold))
            Text(detail).font(.caption2).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading).padding(10).background(.quaternary.opacity(0.45), in: RoundedRectangle(cornerRadius: 12))
    }
    @ViewBuilder private var routeLegend: some View {
        Label("设定运动", systemImage: "minus").foregroundStyle(.secondary)
        Label("叠加偏移与漂移", systemImage: "minus").foregroundStyle(.orange)
        Label("接收机解算", systemImage: "minus").foregroundStyle(.blue)
    }

    var body: some View {
        GeometryReader { geometry in
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: "location.north.circle.fill").font(.system(size: 36)).foregroundStyle(.teal)
                VStack(alignment: .leading, spacing: 3) {
                    Text("天书 · GNSS 模拟").font(.title2.bold())
                    Text("GPS ／ 北斗 · 实时流式模拟").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Label(model.phaseTitle, systemImage: model.failed ? "exclamationmark.circle" : "waveform.path").font(.caption).padding(8).background(.teal.opacity(0.1), in: Capsule())
                Button("开始\(model.selectedSignals.isEmpty ? "" : model.selectedSignals.count == 2 ? "GPS＋北斗" : model.signalLabel)模拟") { NSApp.keyWindow?.makeFirstResponder(nil); model.start(model.simulationStartAction) }
                    .frame(minWidth: 120).buttonStyle(.borderedProminent).disabled(!model.canStartSimulation)
                Button("停止") { model.stop() }.frame(minWidth: 72).disabled(!model.busy)
                Button("检查设备") { model.start("device") }.disabled(model.busy)
            }
            HSplitView {
                Form {
                    Section("卫星系统") {
                        HStack {
                            ForEach(["gps", "bds"], id: \.self) { system in
                                Button { model.selectSignal(system) } label: {
                                    Label(system == "gps" ? "GPS L1 C/A" : "北斗 B1I", systemImage: model.selectedSignals.contains(system) ? "checkmark.square.fill" : "square")
                                        .frame(maxWidth: .infinity)
                                }.disabled(model.busy)
                            }
                        }
                        if model.selectedSignals.count == 2 { Text(model.mixedTransmissionAccepted ? "GPS 为主、北斗为辅；实时流式模拟，不保存整段 I/Q。" : "GPS 为主、北斗为辅；实时流式模拟待箱内验收。").font(.caption).foregroundStyle(.orange) }
                        Text(model.signalMode == "mixed" ? "GPS L1 C/A＋北斗 B1I；共享坐标、时刻及轨迹，GPS 为主。" : model.signalMode == "gps" ? "当前星历 · 实时流式模拟；可另选仅生成验证" : "最新北斗广播星历与模型 · 5 Msps实时流式；共享路线、高度、偏移与漂移。90–600秒。").font(.caption).foregroundStyle(.secondary)
                    }
                    Section("地点查找") {
                        TextField("地点名称 / 地址，或 经度,纬度", text: $model.placeLookup.query)
                        HStack {
                            Button("查找地点") { model.placeLookup.search(pick: model.choosePlace) }.disabled(model.busy || model.placeLookup.searching)
                            Button("定位当前城市") { model.placeLookup.locateCity() }
                        }
                        Text(model.placeLookup.message).font(.caption).foregroundStyle(.secondary)
                        ForEach(Array(model.placeLookup.results.enumerated()), id: \.offset) { _, item in
                            Button(item.name ?? "未命名地点") { model.choosePlace(item.name ?? "查找地点", item.placemark.coordinate.latitude, item.placemark.coordinate.longitude) }
                                .help(item.placemark.title ?? "")
                        }
                    }
                    Section("保存的位置") {
                        Picker("选择地点", selection: $model.selectedLocationID) {
                            Text("未选择").tag("")
                            ForEach(model.locations) { Text($0.alias).tag($0.id) }
                        }.onChange(of: model.selectedLocationID) { _, _ in model.selectLocation() }
                        TextField("位置别名", text: $model.locationName)
                        HStack {
                            Button("新增") { model.saveLocation(add: true) }
                            Button("保存修改") { model.saveLocation(add: false) }.disabled(model.selectedLocationID.isEmpty)
                            Button("删除", role: .destructive) { model.deleteLocation() }.disabled(model.selectedLocationID.isEmpty)
                        }
                    }
                    Section("本次信号环境") {
                        Text(model.signalMode != "gps" ? "运动轨迹 · 固定水平偏移 · 缓慢漂移" : (model.signalProfiles[model.signalProfile] ?? "未知环境"))
                        Text(model.signalMode != "gps" ? "信号环境随运动方式自动加载；相对衰落与噪声尚未标定为真实无线信道。" : "信号环境随运动方式自动加载。主要强星保持接收，弱星按场景变化，避免整体过弱。").font(.caption).foregroundStyle(.secondary)
                    }
                    Section("导航数据") {
                        DisclosureGroup("数据来源与时效") {
                        Text(model.signalMode == "mixed" ? "GPS＋北斗当前广播星历 · 国内源优先" : model.signalMode != "gps" ? "北斗国内源优先 · BKG／GA 回退" : "NOAA 优先 · BKG／GA 回退")
                        Text("有效缓存最长15分钟后刷新；文件发布≤30分钟，并校验整个实验时段。准备余量按流式就绪耗时调整；仅生成验证时另做离线解码。").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Section("位置") {
                        Picker("坐标系", selection: $model.settings.coordinateSystem) { Text("高德 GCJ-02").tag("GCJ-02"); Text("WGS-84").tag("WGS-84") }
                        number("经度（东经为正）", $model.settings.longitude, digits: 6)
                        number("纬度（北纬为正）", $model.settings.latitude, digits: 6)
                        Button("恢复默认地址") { model.restoreDefaultLocation() }.disabled(model.selectedBuiltin == nil)
                        if let elevation = model.locations.first(where: {
                            $0.id == model.selectedLocationID && $0.latitude == model.settings.latitude &&
                            $0.longitude == model.settings.longitude && $0.coordinateSystem == model.settings.coordinateSystem
                        })?.referenceElevation {
                            Text("参考海拔：\(elevation, specifier: "%.0f") 米（Copernicus / Open-Meteo，约 90 米网格）").font(.caption)
                            Text("初始海拔上下限以参考高度为中心；手动海拔按地点保存。生成前按 EGM2008 换算为椭球高。").font(.caption).foregroundStyle(.secondary)
                        }
                        if !model.heightKnown {
                            Text("此地点没有高度资料。请填写下方模拟高度，再确认使用；不会读取上一个地点的高度。").font(.caption).foregroundStyle(.orange)
                            Button("确认使用高度设置") { model.heightKnown = true; model.updatePreview() }
                        }
                        number("海拔下限 / 米", heightMinimum)
                        number("海拔上限 / 米", heightMaximum)
                        number("高度平滑时间 / 秒", $model.settings.heightSmoothing)
                        Text("输入为 EGM2008 海拔；生成前按 h = H + N 换算为 WGS-84 椭球高。GPS 与北斗共用转换后的三维轨迹；垂直漂移另行叠加。").font(.caption).foregroundStyle(.secondary)
                        if let n = model.preview?.geoidUndulation, let h = model.preview?.ellipsoidHeight {
                            Text(String(format: "EGM2008 高程差 N：%+.2f 米 · 基准椭球高 h：%.2f 米", n, h)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                        }
                    }
                    Section("运动方式") {
                        Picker("路线", selection: $model.settings.route) { Text("园区步行（平滑随机路线）").tag("park"); Text("办公室（久坐与短距离走动）").tag("stationary"); Text("绕中心行走").tag("circle"); Text("直线行走").tag("line") }.onChange(of: model.settings.route) { _, _ in model.randomizeSpeed() }
                        if model.settings.route != "stationary" {
                        number("默认步速 / 米每秒", $model.settings.speed).disabled(model.settings.route == "stationary")
                        number("速度随机下限 / 米每秒", speedMinimum).disabled(model.settings.route == "stationary")
                        number("速度随机上限 / 米每秒", speedMaximum).disabled(model.settings.route == "stationary")
                        number("速度平滑时间 / 秒", $model.settings.speedSmoothing)
                        Text("每秒抽取一次目标速度，平滑过渡；每次打开按运动方式随机选择速度上下限；本次可手动调整。办公室模式以久坐为主，偶尔短距离走动后回到座位。").font(.caption).foregroundStyle(.secondary)
                        number("运动半径 / 米（10–50）", $model.settings.radius).disabled(model.settings.route == "stationary" || model.settings.route == "line")
                        Toggle("随机初始朝向", isOn: $model.settings.randomHeading)
                        number("初始朝向 / 度（北 0 / 东 90）", $model.settings.heading).disabled(model.settings.randomHeading)
                        number("朝向随机下限 / 度", $model.settings.headingMinimum)
                        number("朝向随机上限 / 度", $model.settings.headingMaximum)
                        number("方向保持时长下限 / 秒", $model.settings.headingHoldMinimum).disabled(model.settings.route != "park")
                        number("方向保持时长上限 / 秒", $model.settings.headingHoldMaximum).disabled(model.settings.route != "park")
                        number("转向平滑时间 / 秒", $model.settings.turnSmoothing).disabled(model.settings.route != "park")
                        Text("园区模式按随机保持时长更新方向，接近边界时优先转回园区；直线模式左右偏离最多5米，方向持续变化；绕圆半径与速度持续变化。").font(.caption).foregroundStyle(.secondary)
                        } else {
                            Text("以初始位置为中心，20.00米内自然游移、缓慢回归；显示保留两位小数，保留30.00–80.00米起点偏移；多数时间坐着，偶尔走动1–4米后回到座位。")
                        }
                    }
                    Section("模拟定位漂移") {
                        DisclosureGroup("偏移、漂移与随机种子") {
                        Text("移动模式每次启动随机偏移30–80米（东南至西南）；水平漂移2.00–3.00米、垂直0.30–0.80米、平滑8.00–12.00秒。办公室在偏移后起点20.00米内自然游移。").font(.caption).foregroundStyle(.secondary)
                        if model.settings.route != "stationary" {
                        number("水平漂移峰值 / 米", $model.settings.horizontalDrift)
                        number("垂直漂移峰值 / 米", $model.settings.verticalDrift)
                        number("漂移平滑时间 / 秒", $model.settings.driftSmoothing)
                        }
                        number("本次随机种子", $model.settings.seed, digits: 0)
                        Button("更换随机种子") { model.settings.seed = Double(Int.random(in: 0...2147483647)) }
                        Text("启动模拟时自动刷新随机种子；仅生成验证可复现当前种子的轨迹。平滑时间越大，变化越慢。").font(.caption).foregroundStyle(.secondary)
                        Text("绕圆橙线按1–5秒稀疏采样；采样越疏或速度越快，相邻点跨越越大。给轨迹叠加平滑位置偏差；不代表已校准的多路径或接收机噪声。").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Section("生成") {
                        number("发射时长 / 秒（60–600）", $model.settings.duration, digits: 0)
                        Text("生成与发射使用所选时长。在线获取当前星历；准备余量按本机耗时动态调整，过期会重新生成。").font(.caption).foregroundStyle(.secondary)
                    }
                }.formStyle(.grouped).frame(minWidth: 330, idealWidth: 350, maxWidth: 370).disabled(model.busy)
                ScrollView { VStack(alignment: .leading, spacing: 12) {
                    RunStatusPanel(model: model)
                    TabView {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("GNSS 接收与模拟轨迹地图").font(.headline)
                            if let preview = model.preview {
                                LocationMapView(onPick: { lat, lon in model.choosePlace("地图选点", lat, lon) }, latitude: preview.wgs84Latitude, longitude: preview.wgs84Longitude, idealPath: LabModel.geographicPath(preview.idealPath, latitude: preview.wgs84Latitude, longitude: preview.wgs84Longitude, height: model.settings.height), emittedPath: LabModel.geographicPath(model.settings.route == "circle" ? (preview.sampledPath ?? preview.emittedPath) : preview.emittedPath, latitude: preview.wgs84Latitude, longitude: preview.wgs84Longitude, height: model.settings.height), receivedPath: model.receiver?.path ?? [], receivedFresh: model.receiverRunning && model.receiver?.fresh == true)
                                    .frame(height: min(680, max(420, geometry.size.height * 0.58))).clipShape(RoundedRectangle(cornerRadius: 12))
                                Text(String(format: "模拟目标 WGS-84  经度 %.6f° · 纬度 %.6f°", preview.wgs84Longitude, preview.wgs84Latitude)).font(.caption.monospacedDigit()).textSelection(.enabled)
                                Text("双击地图或拖动目标标记选点，填写位置别名后点“新增”保存。").font(.caption).foregroundStyle(.secondary)
                                Text("灰色：基础轨迹 · 橙色：模拟发送 · 蓝色：Mac 解码；陈旧接收点变灰。已配置高德时优先加载；支持离线网格回退。").font(.caption).foregroundStyle(.secondary)
                                if let first = preview.emittedPath.first, first.count == 2 {
                                    Text(String(format: "本次起点偏移 %.2f 米 · 启动模拟时重新随机", hypot(first[0],first[1]))).font(.caption.monospacedDigit())
                                }
                                Text(preview.coordinateConversion).font(.caption).foregroundStyle(.secondary)
                            } else { Text("请填写有效参数").foregroundStyle(.secondary) }
                        }.frame(maxWidth: .infinity, alignment: .topLeading)
                            .tabItem { Label("地图与位置", systemImage: "map") }
                        VStack(alignment: .leading, spacing: 8) {
                            Text("模拟路径").font(.headline)
                            RoutePlot(preview: model.preview, result: model.result).frame(height: min(680, max(420, geometry.size.height * 0.58)))
                            ViewThatFits(in: .horizontal) {
                                HStack(spacing: 12) { routeLegend }
                                VStack(alignment: .leading, spacing: 4) { routeLegend }
                            }.font(.caption)
                        }.frame(maxWidth: .infinity, alignment: .topLeading)
                            .tabItem { Label("运动轨迹", systemImage: "point.topleft.down.to.point.bottomright.curvepath") }
                        ScrollView { ReceiverPanel(model: model).padding(8) }
                            .tabItem { Label("接收与卫星数据", systemImage: "antenna.radiowaves.left.and.right") }
                    }.frame(height: min(680, max(420, geometry.size.height * 0.58)) + 145)
                    HStack(spacing: 10) {
                        metric("模型椭球高范围", model.preview.map { String(format: "%.2f–%.2f m", $0.emittedHeightMin, $0.emittedHeightMax) } ?? "—", "模型值，非接收精度")
                        metric("解码误差中位数", model.result?.isHardwareStream == true ? "—" : model.result?.medianError.map { String(format: "%.2f m", $0) } ?? "—", model.result?.isHardwareStream == true ? "硬件检查不代表接收定位通过" : "相对叠加漂移后的轨迹")
                        metric("有效定位点", model.result?.validFixesText ?? "—", model.result?.isHardwareStream == true ? "本轮接收记录单独验收" : "由 GNSS-SDR 独立解算")
                    }
                    if model.signalMode != "gps", model.result?.isHardwareStream != true, let first = model.result?.firstFixSeconds {
                        Text(String(format: "%@首次定位 %.1f 秒 · 最长无解算 %.1f 秒（含启动）", model.signalLabel, first, model.result?.maxGapSeconds ?? first)).font(.caption).foregroundStyle(.secondary)
                    }
                    if !model.logs.isEmpty {
                        DisclosureGroup("运行日志") { ScrollView { Text(model.logs).font(.caption.monospaced()).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading).textSelection(.enabled) }.frame(height: 100) }
                    }
                    HStack {
                        if model.signalMode != "mixed" {
                            Button("仅生成验证") { NSApp.keyWindow?.makeFirstResponder(nil); model.start(model.signalMode == "gps" ? "run" : model.signalMode) }.disabled(model.busy || !model.heightKnown || model.preview == nil || model.selectedSignals.count != 1)
                        }
                        if let result = model.result { Button("在 Finder 查看结果") { NSWorkspace.shared.open(URL(fileURLWithPath: result.folder)) } }
                        Spacer()
                        Text(model.preview.map { preview in
                            if model.signalMode == "mixed" || preview.streamMBps != nil {
                                guard let rate = preview.streamMBps, let sampleRate = preview.streamSampleRate else { return "实时流式输出 · 不保存整段 I/Q" }
                                return String(format: "实时 %.1f MB/s · %.1f Msps · 不保存整段 I/Q", rate, Double(sampleRate) / 1_000_000) + (model.signalMode == "mixed" ? "" : "；仅生成验证约 \(preview.estimatedFileMB) MB")
                            }
                            return "约 \(preview.estimatedFileMB) MB / 次"
                        } ?? "").font(.caption).foregroundStyle(.secondary)
                    }
                    HStack {
                        Stepper("TX 增益 \(model.txGain) dB", value: $model.txGain, in: 0...47)
                        Toggle("RF 放大器", isOn: $model.txAmplifier)
                    }.disabled(model.busy)
                    Text(model.signalMode == "mixed" ? "混合模拟：最新数据 → GPS 主信号与北斗辅信号 → 实时流式发送。不生成整段样本，可随时停止。" : "自动模拟：最新数据 → 预缓冲 → 边生成边发送，不保存整段 I/Q。仅生成验证才保存样本并独立解码；流式发送的接收结果另行验收。").font(.caption).foregroundStyle(.secondary)
                } }.padding(.leading, 16).frame(minWidth: 550)
            }
            Divider()
            HStack {
                Label(model.transmitting ? "正在执行发送任务" : "受控射频实验", systemImage: "antenna.radiowaves.left.and.right").foregroundStyle(.orange)
                Text("现场工程师负责屏蔽、功率链路和接收端 · 停止按钮可中止任务").foregroundStyle(.secondary)
                Spacer()
            }.font(.caption)
        }.padding(16).frame(maxWidth: 1480).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }.frame(minWidth: 1100, minHeight: 840)
        .task { model.updatePreview(); model.placeLookup.locateCity() }
        .onChange(of: model.settings) { old, new in
            model.updatePreview()
        }
        .sheet(isPresented: $model.showDevice) {
            VStack(alignment: .leading, spacing: 16) {
                Text("设备只读检查").font(.title2.bold())
                ScrollView { Text(model.deviceInfo).font(.system(.body, design: .monospaced)).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading) }.frame(width: 560, height: 300)
                Button("完成") { model.showDevice = false }.keyboardShortcut(.defaultAction)
            }.padding(24)
        }
    }
}

@MainActor final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationWillTerminate(_ notification: Notification) { LabModel.shared.persistControls(); LabModel.shared.stop(); LabModel.shared.stopReceiver(); AMapServer.shared.stop() }
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard LabModel.shared.busy else { return .terminateNow }
        LabModel.shared.stop()
        Task { @MainActor in
            for _ in 0..<150 {
                if !LabModel.shared.busy { sender.reply(toApplicationShouldTerminate: true); return }
                try? await Task.sleep(for: .milliseconds(100))
            }
            LabModel.shared.status = "任务尚未结束清理，请检查日志后再退出"
            sender.reply(toApplicationShouldTerminate: false)
        }
        return .terminateLater
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
}

@main struct WalkLabApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var delegate
    init() {
        if CommandLine.arguments.contains("--self-test") {
            do {
                let hardwareResultData = Data(#"{"folder":"test","passed":true,"validFixes":0,"decodedPath":[],"rfEnabled":true,"startUTC":"2026-10-18T10:00:00Z","mode":"current-broadcast","verificationKind":"hardware-stream"}"#.utf8)
                let hardwareResult = try JSONDecoder().decode(ResultInfo.self, from: hardwareResultData)
                precondition(hardwareResult.isHardwareStream && hardwareResult.validFixesText == "—")
                precondition(hardwareResult.hardwareStreamStatus == "流式发送已停止，接收记录单独验收")
                let decodedResultData = Data(#"{"folder":"test","passed":true,"validFixes":123,"decodedPath":[],"rfEnabled":false}"#.utf8)
                let decodedResult = try JSONDecoder().decode(ResultInfo.self, from: decodedResultData)
                precondition(!decodedResult.isHardwareStream && decodedResult.validFixesText == "123")
                let coordinateCheck = try PlaceLookup.coordinates("104.1,30.6"); precondition(coordinateCheck == [30.6, 104.1])
                let addressCheck = try PlaceLookup.coordinates("London Westminster"); precondition(addressCheck == nil)
                do { _ = try PlaceLookup.coordinates("181,30"); fatalError("invalid coordinate accepted") } catch { }
                try MapTileHandler.selfTest()
                let geo = LabModel.geographicPath([[0,0],[10,10]], latitude: 30, longitude: 104, height: 400)
                precondition(geo[0] == [30,104] && geo[1][0] > 30 && geo[1][1] > 104)
                let receiverData = Data(#"{"connected":true,"device":"test","sourceFile":"gnss-1.jsonl","state":"GNSS 定位","fresh":true,"path":[[30,104],[],[30.0001,104.0001]],"mock":false,"satellites":"北斗 8","network":""}"#.utf8)
                let received = try JSONDecoder().decode(ReceiverSnapshot.self, from: receiverData)
                precondition(received.path.count == 3 && received.fresh && received.height == nil)
                let data = try JSONEncoder().encode(Settings())
                let restored = try JSONDecoder().decode(Settings.self, from: data)
                precondition(restored == Settings())
                let suite = "WalkLab-selftest-" + UUID().uuidString
                let storage = UserDefaults(suiteName: suite)!
                defer { storage.removePersistentDomain(forName: suite) }
                let converted = LabModel(defaults: storage)
                precondition(converted.locations.count == 3)
                precondition(Set(converted.locations.map(\.alias)) == Set(["白宫", "唐宁街10号", "国会山"]))
                precondition(LabModel(defaults: storage).locations == converted.locations)
                precondition(converted.mixedTransmissionAccepted)
                converted.mixedTransmissionAccepted = false
                converted.selectedSignals = []
                converted.start(); precondition(!converted.busy && converted.status == "请选择卫星系统")
                converted.selectedSignals = ["gps", "bds"]
                converted.signalMode = "mixed"
                precondition(converted.simulationStartAction == "mixed-start" && !converted.canStartSimulation)
                converted.start("mixed-start"); precondition(!converted.busy && converted.status == "GPS＋北斗混合发送待箱内验收，尚未开放")
                converted.start("mixed"); precondition(!converted.busy && converted.status.contains("不生成整段"))
                converted.choosePlace("地图选点自检", 30.6, 104.1)
                precondition(converted.settings.latitude == 30.6 && converted.settings.longitude == 104.1 && converted.settings.coordinateSystem == "WGS-84")
                converted.saveLocation(add: true)
                precondition(LabModel(defaults: storage).locations.contains { $0.alias == "地图选点自检" && $0.latitude == 30.6 && $0.longitude == 104.1 })
                storage.removePersistentDomain(forName: suite)

                var oldSettings = try JSONSerialization.jsonObject(with: data) as! [String: Any]
                for key in ["speedVariation", "speedSmoothing", "randomHeading", "headingMinimum", "headingMaximum", "headingHoldMinimum", "headingHoldMaximum", "turnSmoothing", "heightSmoothing", "driftSmoothing"] { oldSettings.removeValue(forKey: key) }
                oldSettings["height"] = 523.0; oldSettings["speed"] = 0.75
                storage.set(try JSONSerialization.data(withJSONObject: oldSettings), forKey: "settings")
                let model = LabModel(defaults: storage)
                var bdsSettings = model.settings
                bdsSettings.duration = 90
                let bdsPreview = try LabModel.previewProcess(project: model.project, input: JSONEncoder().encode(bdsSettings), mode: "bds")
                precondition(bdsPreview.estimatedFileMB == 900)
                precondition(bdsPreview.streamMBps == 10 && bdsPreview.streamSampleRate == 5_000_000)
                converted.preview = bdsPreview
                converted.mixedTransmissionAccepted = true
                precondition(!converted.canStartSimulation)
                converted.heightKnown = true
                precondition(converted.canStartSimulation && converted.simulationStartAction == "mixed-start")
                converted.busy = true; precondition(!converted.canStartSimulation)
                converted.stop(); precondition(converted.status == "正在停止并清理本次任务…")
                converted.busy = false; converted.resetFlow(); precondition(converted.status == "准备就绪 · 可重新开始" && !converted.transmitting)
                converted.selectedSignals = ["gps"]; converted.signalMode = "gps"
                precondition(converted.simulationStartAction == "start")
                converted.selectedSignals = ["bds"]; converted.signalMode = "bds"
                precondition(converted.simulationStartAction == "bds-start")
                precondition(model.signalProfiles[model.signalProfile] != nil)
                let routeBefore = model.settings.route
                for (route, expected) in SignalEnvironments.shared.routes { model.settings.route = route; precondition(model.signalProfile == expected) }
                model.settings.route = routeBefore
                model.domesticOnly = true
                precondition(storage.bool(forKey: "domesticOnly"))
                precondition(model.settings.height == 18 && model.settings.speed >= 0.3 && model.settings.speed <= 2.2)
                precondition(model.settings.speedVariation > 0 && model.settings.randomHeading)
                precondition(model.locations.count == 3)
                precondition(model.locations == SavedLocation.presets)
                precondition(SavedLocation.presets.compactMap(\.referenceElevation) == [18, 10, 27])
                var legacy = model.locations
                for index in legacy.indices { legacy[index].referenceElevation = nil; legacy[index].builtinKey = nil }
                storage.set(try JSONEncoder().encode(legacy), forKey: "locations")
                let upgraded = LabModel(defaults: storage)
                precondition(upgraded.locations == model.locations && upgraded.settings.seed != model.settings.seed)
                let previousSimulationSeed = model.settings.seed
                model.renewSimulationSeed()
                precondition(model.settings.seed != previousSimulationSeed)
                let preset = SavedLocation.presets[0]
                upgraded.selectedLocationID = preset.id; upgraded.selectLocation()
                upgraded.locationName = "重命名测试"; upgraded.saveLocation(add: false)
                precondition(upgraded.locations.first(where: { $0.id == preset.id })?.referenceElevation == 18)
                upgraded.settings.longitude += 1; upgraded.saveLocation(add: false)
                precondition(upgraded.locations.first(where: { $0.id == preset.id })?.referenceElevation == nil)
                upgraded.restoreDefaultLocation()
                precondition(upgraded.settings.longitude == preset.longitude)
                precondition(upgraded.settings.height == preset.referenceElevation!)
                precondition(upgraded.locations.first(where: { $0.id == preset.id })?.longitude == preset.longitude)
                let reopened = LabModel(defaults: storage)
                precondition(reopened.locations.first(where: { $0.id == preset.id })?.longitude == preset.longitude)
                precondition(reopened.settings.longitude == preset.longitude && reopened.settings.latitude == preset.latitude)
                storage.set(try JSONEncoder().encode(model.locations), forKey: "locations")
                storage.set(try JSONEncoder().encode(model.settings), forKey: "settings")
                storage.set(model.selectedLocationID, forKey: "selectedLocationID")
                storage.set(try JSONEncoder().encode(Array(model.locations.dropLast(2))), forKey: "locations")
                storage.set(false, forKey: "presetLocations20261017")
                let expanded = LabModel(defaults: storage)
                precondition(expanded.locations == model.locations)
                precondition(LabModel(defaults: storage).locations == model.locations)
                let firstID = model.locations[0].id
                storage.set(false, forKey: "presetLocations20261016")
                let migrated = LabModel(defaults: storage)
                precondition(migrated.locations == model.locations)
                precondition(migrated.selectedLocationID == firstID && migrated.settings.seed != model.settings.seed)
                for preset in SavedLocation.presets {
                    model.selectedLocationID = preset.id; model.selectLocation()
                    precondition(model.settings.latitude == preset.latitude && model.settings.longitude == preset.longitude)
                    precondition(model.settings.coordinateSystem == preset.coordinateSystem)
                    precondition(model.settings.height == preset.referenceElevation!)
                    precondition(model.settings.heightVariation == Settings().heightVariation)
                    let persisted = LabModel(defaults: storage)
                    precondition(persisted.settings.height == preset.referenceElevation!)
                }
                model.selectedLocationID = firstID; model.selectLocation()
                let retainedHeight = model.settings.height
                precondition(retainedHeight == 18)
                model.locationName = "办公室"; model.settings.latitude = 31; model.saveLocation(add: true)
                let officeID = model.selectedLocationID
                precondition(model.locations.count == 4)
                precondition(model.selectedBuiltin == nil)
                model.selectLocation(); precondition(model.settings.height == retainedHeight)
                model.locationName = "办公室修改"; model.settings.longitude = 105; model.saveLocation(add: false)
                precondition(model.locations.last?.alias == "办公室修改")
                let restoredModel = LabModel(defaults: storage)
                precondition(restoredModel.locations == model.locations && restoredModel.selectedLocationID == officeID)
                model.selectedLocationID = firstID; model.selectLocation()
                precondition(model.settings.latitude == Settings().latitude)
                model.selectedLocationID = officeID; model.deleteLocation()
                precondition(model.locations.count == 3)
                while !model.locations.isEmpty { model.deleteLocation() }
                precondition(LabModel(defaults: storage).locations.isEmpty)
                model.locationName = " "; model.saveLocation(add: true); precondition(model.locations.isEmpty)
                for (route, bounds) in SignalEnvironments.shared.startupSpeedRangesCmPerSecond where ["park", "stationary", "circle", "line"].contains(route) {
                    var start = Settings(); start.route = route
                    for _ in 0..<10 {
                        storage.set(try JSONEncoder().encode(start), forKey: "settings")
                        let fresh = LabModel(defaults: storage).settings
                        precondition((2...3).contains(fresh.horizontalDrift) && (0.3...0.8).contains(fresh.verticalDrift) && (8...12).contains(fresh.driftSmoothing))
                        for value in [fresh.horizontalDrift, fresh.verticalDrift, fresh.driftSmoothing] { precondition(abs(value*100-(value*100).rounded()) < 1e-8) }
                        let lower = fresh.speed - fresh.speedVariation, upper = fresh.speed + fresh.speedVariation
                        precondition(lower >= Double(bounds[0])/100 - 1e-9 && lower <= Double(bounds[1])/100 + 1e-9)
                        precondition(upper >= Double(bounds[2])/100 - 1e-9 && upper <= Double(bounds[3])/100 + 1e-9)
                        precondition(upper == 0 || fresh.speed != start.speed || fresh.speedVariation != start.speedVariation)
                        start = fresh
                    }
                }
                var longRun = model.settings;longRun.duration = 600
                storage.set(try JSONEncoder().encode(longRun), forKey: "settings")
                precondition(LabModel(defaults: storage).settings.duration == 600)
                model.settings.route = "stationary"; model.randomizeSpeed(); precondition(model.settings.speed == 0)
                model.settings.route = "line"; model.randomizeSpeed(); precondition(model.settings.speed > 0)
                let retainedSettings = model.settings
                model.deviceInfo = "connected test device";model.progress = 0.7
                model.resetFlow()
                precondition(model.progress == 0 && model.result == nil && model.settings == retainedSettings && model.deviceInfo == "connected test device")
                storage.removePersistentDomain(forName: suite)
                let delivery = LabModel(defaults: storage)
                let first = delivery.locations.first(where: { $0.referenceElevation == 18 })!
                let second = delivery.locations.first(where: { $0.referenceElevation == 10 })!
                delivery.selectedLocationID = first.id; delivery.selectLocation()
                delivery.settings.height = 511.5; delivery.settings.heightVariation = 1.25
                delivery.locationName = first.alias; delivery.saveLocation(add: false)
                delivery.selectedLocationID = second.id; delivery.selectLocation(); precondition(delivery.settings.height == 10)
                delivery.selectedLocationID = first.id; delivery.selectLocation()
                precondition(delivery.settings.height - delivery.settings.heightVariation == 510.25 && delivery.settings.height + delivery.settings.heightVariation == 512.75)
                delivery.txGain = 17; delivery.txAmplifier = false
                delivery.selectSignal("gps"); delivery.selectSignal("bds"); delivery.persistControls()
                let persistent = LabModel(defaults: storage)
                precondition(persistent.signalMode == "bds" && persistent.txGain == 17 && !persistent.txAmplifier)
                precondition(persistent.settings.height == 511.5 && persistent.settings.heightVariation == 1.25)
                persistent.choosePlace("unknown height", 30.1, 104.2)
                precondition(!persistent.heightKnown && persistent.settings.height == 18 && !persistent.canStartSimulation)
                persistent.start("bds-start"); precondition(!persistent.busy && persistent.status.contains("确认当前地点"))
                persistent.persistControls()
                let unknownReopened = LabModel(defaults: storage)
                precondition(!unknownReopened.heightKnown && unknownReopened.selectedLocationID.isEmpty && !unknownReopened.canStartSimulation)
                persistent.resetFlow(message: "导航数据下载失败", failure: true)
                precondition(persistent.failed && persistent.status == "导航数据下载失败" && persistent.phaseTitle == "任务未完成")
                var invalid = Settings(); invalid.route = "unsupported"
                storage.set(try JSONEncoder().encode(invalid), forKey: "settings")
                let recovered = LabModel(defaults: storage)
                precondition(recovered.settings.route == "park" && storage.data(forKey: "settingsRecovery") != nil)
                precondition(recovered.settings.latitude == invalid.latitude && recovered.settings.longitude == invalid.longitude)
                print("PASS: legacy migration, per-place heights, parameter persistence, unknown-height gate, error recovery, system/gain persistence and current-route startup policy")
                exit(0)
            } catch { fatalError(error.localizedDescription) }
        }
    }
    var body: some Scene {
        Window("天书", id: "main") { ContentView() }
            .defaultSize(width: 1320, height: 1000)
    }
}
