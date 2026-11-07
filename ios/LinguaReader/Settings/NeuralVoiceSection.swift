import SwiftUI
import AVFoundation
import LinguaShared

/// Estado de la voz neural (Supertonic 3) para Ajustes: modelo (descarga ~400 MB, verificación, borrado) y la prueba
/// con medición en el iPhone (carga, factor de tiempo real, memoria), como la tarjeta de Supertonic en Android.
@MainActor
final class NeuralVoiceModel: ObservableObject {
    @Published private(set) var state: ModelState?
    @Published private(set) var testing = false
    @Published private(set) var lastTest: NeuralVoiceTest?
    private var watchers: [FlowWatcher] = []
    private var player: AVAudioPlayer?
    private var keepsScreenOn = false

    var supported: Bool { LinguaShared.shared.neuralSupported }

    func start() {
        guard supported, watchers.isEmpty else { return }
        watchers = [LinguaShared.shared.watchNeuralModel { [weak self] s in self?.update(s) }]
    }

    func stop() {
        watchers.forEach { $0.close() }
        watchers = []
        setScreenOn(false)
    }

    private func update(_ s: ModelState) {
        state = s
        // La descarga va en primer plano: con la pantalla apagada iOS la pausa (continúa al volver).
        setScreenOn(s is ModelStateDownloading || s is ModelStateVerifying)
    }

    private func setScreenOn(_ on: Bool) {
        guard on != keepsScreenOn else { return }
        keepsScreenOn = on
        UIApplication.shared.isIdleTimerDisabled = on
    }

    func download() { LinguaShared.shared.downloadNeuralModel() }
    func cancel() { LinguaShared.shared.cancelNeuralDownload() }
    func verify() { LinguaShared.shared.verifyNeuralModel() }
    func delete() { Task { try? await LinguaShared.shared.deleteNeuralModel(); lastTest = nil } }

    func use() { Task { try? await LinguaShared.shared.selectEngine(id: LinguaShared.shared.neuralEngineId) } }

    func test(language: String, voice: String) {
        guard !testing else { return }
        testing = true
        Task {
            defer { testing = false }
            do {
                let result = try await LinguaShared.shared.testNeuralVoice(languageTag: language, voiceId: voice)
                lastTest = result
                if let path = result.wavPath {
                    player = try? AVAudioPlayer(contentsOf: URL(fileURLWithPath: path))
                    player?.play()
                }
            } catch {
                lastTest = NeuralVoiceTest(loadMs: -1, synthMs: 0, audioMs: 0, steps: 0, memoryMb: -1, threads: 0, wavPath: nil, error: error.localizedDescription)
            }
        }
    }
}

struct NeuralVoiceSection: View {
    let p: UserPreferences
    @StateObject private var model = NeuralVoiceModel()
    @State private var confirmLicense = false
    @State private var confirmDelete = false

    private static let voices = ["F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5"]
    private var totalMb: Int { Int(LinguaShared.shared.neuralDownloadBytes / 1_000_000) }

    var body: some View {
        Section {
            if !model.supported {
                Text("The neural voice needs an iPhone with at least 4 GB of memory. This device reads with the Apple voices.")
                    .font(.footnote).foregroundStyle(.secondary)
            } else {
                content
            }
        } header: {
            Text("Neural voice (Supertonic 3)")
        } footer: {
            if model.supported {
                Text("A natural voice generated on the iPhone, offline, with exact word timings. It uses more battery than the Apple voices.")
            }
        }
        .themedRows()
        .onAppear { model.start() }
        .onDisappear { model.stop() }
        .confirmationDialog(Text("Download the neural voice?"), isPresented: $confirmLicense, titleVisibility: .visible) {
            Button("Accept the license and download (\(totalMb) MB)") { model.download() }
        } message: {
            Text("The voice model (Supertone, \(LinguaShared.shared.neuralLicenseName)) is downloaded once from files.lingoaudiobook.eu, about \(totalMb) MB. Use Wi-Fi and keep the app open: if you leave, the download pauses and continues when you come back.")
        }
        .confirmationDialog(Text("Delete the neural voice model?"), isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) { model.delete() }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.state {
        case let s as ModelStateNotInstalled:
            if s.partialBytes > 0 {
                Text("Downloaded \(Int(s.partialBytes / 1_000_000)) of \(Int(s.totalBytes / 1_000_000)) MB").font(.footnote)
                Button("Continue the download") { model.download() }.accessibilityIdentifier("neural.download")
            } else {
                Button("Download the neural voice (\(totalMb) MB)") { confirmLicense = true }.accessibilityIdentifier("neural.download")
            }
            if let url = URL(string: LinguaShared.shared.neuralLicenseUrl) {
                Link("License: \(LinguaShared.shared.neuralLicenseName)", destination: url).font(.footnote)
            }
        case let s as ModelStateDownloading:
            VStack(alignment: .leading, spacing: 6) {
                ProgressView(value: s.totalBytes > 0 ? Double(s.downloadedBytes) / Double(s.totalBytes) : 0)
                Text("\(Int(s.downloadedBytes / 1_000_000)) of \(Int(s.totalBytes / 1_000_000)) MB").font(.footnote).monospacedDigit()
                Text("Keep the app open; if you leave, the download pauses and continues when you come back.").font(.caption).foregroundStyle(.secondary)
            }
            Button("Cancel", role: .cancel) { model.cancel() }
        case let s as ModelStateVerifying:
            VStack(alignment: .leading, spacing: 6) {
                ProgressView(value: Double(s.fraction))
                Text("Verifying the files…").font(.footnote)
            }
        case let s as ModelStateInstalled:
            LabeledContent("Installed", value: "\(Int(s.bytesOnDisk / 1_000_000)) MB")
            Button("Read with the neural voice") { model.use() }.accessibilityIdentifier("neural.use")
            Picker("Voice", selection: Binding(get: { p.supertonicVoice }, set: { v in Task { try? await LinguaShared.shared.setNeuralVoice(voice: v) } })) {
                ForEach(Self.voices, id: \.self) { v in Text(v.hasPrefix("F") ? "Female \(String(v.dropFirst()))" : "Male \(String(v.dropFirst()))").tag(v) }
            }
            Stepper(value: Binding(get: { Int(p.supertonicSteps) }, set: { v in Task { try? await LinguaShared.shared.setNeuralSteps(steps: Int32(v)) } }), in: 2...16) {
                Text("Quality: \(Int(p.supertonicSteps)) steps")
            }
            Toggle("Read numbers and abbreviations as words", isOn: Binding(get: { p.normalizeSpeech }, set: { v in Task { try? await LinguaShared.shared.setNormalizeSpeech(enabled: v) } }))
            Button {
                model.test(language: p.readingLanguage ?? "en", voice: p.supertonicVoice)
            } label: {
                HStack {
                    Text("Test the voice")
                    if model.testing { Spacer(); ProgressView() }
                }
            }
            .disabled(model.testing)
            .accessibilityIdentifier("neural.test")
            if let t = model.lastTest { testResult(t) }
            Button("Delete the model", role: .destructive) { confirmDelete = true }
        case let s as ModelStateFailed:
            Text(PackErrors.text(s.error)).font(.footnote).foregroundStyle(.red)
            Button("Retry") { model.download() }
        default:
            ProgressView()
        }
    }

    /// Resultado de la prueba: lo que decide si la voz neural sirve en este iPhone (RTF < 1 = más rápida que el audio).
    @ViewBuilder
    private func testResult(_ t: NeuralVoiceTest) -> some View {
        if let error = t.error {
            Text(error).font(.footnote).foregroundStyle(.red).accessibilityIdentifier("neural.result")
        } else {
            VStack(alignment: .leading, spacing: 2) {
                Text("\(String(format: "%.1f", Double(t.audioMs) / 1000)) s of audio in \(String(format: "%.1f", Double(t.synthMs) / 1000)) s (real-time factor \(String(format: "%.2f", t.rtf)))")
                if t.loadMs >= 0 { Text("Model loaded in \(String(format: "%.1f", Double(t.loadMs) / 1000)) s") }
                Text("Memory \(t.memoryMb >= 0 ? "\(t.memoryMb) MB" : "–") · \(t.threads) threads · \(t.steps) steps")
                Text(t.rtf < 1 ? "Fast enough for continuous reading on this iPhone." : "Slower than real time: reading may pause between sentences.")
                    .foregroundStyle(t.rtf < 1 ? Color.green : Color.orange)
            }
            .font(.footnote).monospacedDigit()
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("neural.result")
        }
    }
}
