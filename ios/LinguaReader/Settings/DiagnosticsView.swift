import SwiftUI
import LinguaShared

/// Líneas de diagnóstico de los motores (voz, caché de audio, descargas, reproducción): lo que en Android va a
/// Logcat, aquí en pantalla para leerlas en una sesión de Device Farm sin descargar el log del dispositivo.
struct DiagnosticsView: View {
    @State private var text = ""
    @State private var watcher: FlowWatcher?
    @State private var copied = false

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                Text(text.isEmpty ? "Nothing yet. Play a passage (for example in “Cached audio” mode) and come back: every synthesis, cache and playback event is listed here." : text)
                    .font(.system(size: 11, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
                    .textSelection(.enabled)
                    .id("end")
            }
            .onChange(of: text) { _, _ in proxy.scrollTo("end", anchor: .bottom) }
            .onAppear { proxy.scrollTo("end", anchor: .bottom) }
        }
        .navigationTitle("Diagnostics")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    UIPasteboard.general.string = text
                    copied = true
                } label: { Image(systemName: copied ? "checkmark" : "doc.on.doc") }
                .disabled(text.isEmpty)
                .accessibilityLabel("Copy")
                Button {
                    LinguaShared.shared.clearDiagnostics()
                    text = ""
                } label: { Image(systemName: "trash") }
                .disabled(text.isEmpty)
                .accessibilityLabel("Clear")
            }
        }
        .onAppear { watcher = LinguaShared.shared.watchDiagnostics { text = $0; copied = false } }
        .onDisappear { watcher?.close(); watcher = nil }
    }
}
