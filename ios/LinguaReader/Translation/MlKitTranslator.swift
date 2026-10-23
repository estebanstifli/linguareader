#if canImport(MLKitTranslate)
import Foundation
import LinguaShared
import MLKitTranslate

/// Traductor ML Kit (mismos modelos y resultados que la app Android) detrás del puente `IosTranslator` del código
/// compartido. ML Kit se usa desde el hilo principal; las respuestas vuelven por los callbacks del puente.
final class MlKitTranslator: NSObject, IosTranslator {
    private let manager = ModelManager.modelManager()
    /// Callbacks a la espera de que termine la descarga de cada modelo (ML Kit avisa por notificaciones).
    private var pending: [String: [(String?) -> Void]] = [:]
    /// Un cliente por par de idiomas; se conservan los dos últimos (cada uno retiene memoria del modelo).
    private var translators: [String: Translator] = [:]
    private var recent: [String] = []

    override init() {
        super.init()
        NotificationCenter.default.addObserver(forName: .mlkitModelDownloadDidSucceed, object: nil, queue: .main) { [weak self] n in
            self?.finished(n, error: nil)
        }
        NotificationCenter.default.addObserver(forName: .mlkitModelDownloadDidFail, object: nil, queue: .main) { [weak self] n in
            let error = n.userInfo?[ModelDownloadUserInfoKey.error.rawValue] as? Error
            self?.finished(n, error: error?.localizedDescription ?? "download failed")
        }
    }

    private func finished(_ n: Notification, error: String?) {
        guard let model = n.userInfo?[ModelDownloadUserInfoKey.remoteModel.rawValue] as? TranslateRemoteModel else { return }
        let code = model.language.rawValue
        let callbacks = pending.removeValue(forKey: code) ?? []
        callbacks.forEach { $0(error) }
    }

    func supportedCodes() -> [String] { TranslateLanguage.allLanguages().map { $0.rawValue } }

    func code(languageTag: String) -> String? {
        let primary = languageTag.split(separator: "-").first.map(String.init)?.lowercased() ?? languageTag.lowercased()
        return TranslateLanguage.allLanguages().contains(TranslateLanguage(rawValue: primary)) ? primary : nil
    }

    func downloadedCodes(completion: @escaping ([String]) -> Void) {
        DispatchQueue.main.async { completion(self.manager.downloadedTranslateModels.map { $0.language.rawValue }) }
    }

    func download(code: String, wifiOnly: Bool, completion: @escaping (String?) -> Void) {
        DispatchQueue.main.async {
            let model = TranslateRemoteModel.translateRemoteModel(language: TranslateLanguage(rawValue: code))
            if self.manager.isModelDownloaded(model) { completion(nil); return }
            self.pending[code, default: []].append(completion)
            if self.pending[code]?.count == 1 {
                let conditions = ModelDownloadConditions(allowsCellularAccess: !wifiOnly, allowsBackgroundDownloading: true)
                _ = self.manager.download(model, conditions: conditions)
            }
        }
    }

    func delete(code: String, completion: @escaping (String?) -> Void) {
        DispatchQueue.main.async {
            let model = TranslateRemoteModel.translateRemoteModel(language: TranslateLanguage(rawValue: code))
            self.manager.deleteDownloadedModel(model) { error in completion(error?.localizedDescription) }
        }
    }

    func translate(text: String, source: String, target: String, completion: @escaping (String?, String?) -> Void) {
        DispatchQueue.main.async {
            self.client(source, target).translate(text) { translated, error in completion(translated, error?.localizedDescription) }
        }
    }

    private func client(_ source: String, _ target: String) -> Translator {
        let key = "\(source)>\(target)"
        if let existing = translators[key] {
            recent.removeAll { $0 == key }
            recent.append(key)
            return existing
        }
        let options = TranslatorOptions(sourceLanguage: TranslateLanguage(rawValue: source), targetLanguage: TranslateLanguage(rawValue: target))
        let created = Translator.translator(options: options)
        translators[key] = created
        recent.append(key)
        while recent.count > 2 {
            let eldest = recent.removeFirst()
            translators.removeValue(forKey: eldest)
        }
        return created
    }
}
#endif
