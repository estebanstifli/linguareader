import Foundation

/// Web pública de la app (GitHub Pages, repositorio estebanstifli/lingoaudiobook) y contacto; los mismos enlaces que
/// `legal/AppLinks.kt` en Android.
enum AppLinks {
    static let website = URL(string: "https://estebanstifli.github.io/lingoaudiobook/")!
    static let privacyPolicy = URL(string: "https://estebanstifli.github.io/lingoaudiobook/privacy/")!
    static let contactEmail = "estebandezafra@gmail.com"

    static func contact(subject: String) -> URL {
        var components = URLComponents()
        components.scheme = "mailto"
        components.path = contactEmail
        components.queryItems = [URLQueryItem(name: "subject", value: subject)]
        return components.url ?? URL(string: "mailto:\(contactEmail)")!
    }
}

/// Componente de terceros incluido en la app o descargado por ella, con su licencia.
struct ThirdPartyNotice: Identifiable {
    let name: String
    let author: String
    let license: String
    let url: URL
    var id: String { name }

    init(_ name: String, _ author: String, _ license: String, _ url: String) {
        self.name = name
        self.author = author
        self.license = license
        self.url = URL(string: url)!
    }
}

/// Avisos de terceros para Ajustes › Acerca de. Apache-2.0, MIT y zlib exigen conservar el aviso de copyright y la
/// licencia al redistribuir. Mantener al día con `gradle/libs.versions.toml`, `ios/Podfile` y
/// `docs/ARCHITECTURE.md#licencias`.
enum ThirdPartyNotices {
    static let apache2 = "Apache License 2.0"
    private static let apacheUrl = "https://www.apache.org/licenses/LICENSE-2.0"

    static let included: [ThirdPartyNotice] = {
        var list = [
            ThirdPartyNotice("Kotlin, kotlinx.coroutines, kotlinx.serialization, atomicfu", "JetBrains", apache2, apacheUrl),
            ThirdPartyNotice("Room, DataStore, SQLite (AndroidX, Kotlin Multiplatform)", "The Android Open Source Project", apache2, apacheUrl),
            ThirdPartyNotice("Koin", "Kotzilla and contributors", apache2, apacheUrl),
            ThirdPartyNotice("Okio", "Square, Inc.", apache2, apacheUrl),
            ThirdPartyNotice("Ksoup", "Fleeksoft", "MIT License", "https://github.com/fleeksoft/ksoup/blob/release/LICENSE.md"),
        ]
        #if canImport(MLKitTranslate)
        list += [
            ThirdPartyNotice("ML Kit Translation", "Google", "ML Kit Terms of Service", "https://developers.google.com/ml-kit/terms"),
            ThirdPartyNotice("GoogleDataTransport, GoogleToolboxForMac, GoogleUtilities, GTMSessionFetcher, Promises (ML Kit dependencies)", "Google", apache2, apacheUrl),
            ThirdPartyNotice("nanopb", "Petteri Aimonen", "zlib License", "https://github.com/nanopb/nanopb/blob/master/LICENSE.txt"),
        ]
        #endif
        return list
    }()

    /// Modelos que la app no incluye y que el usuario descarga si quiere.
    static let downloadable: [ThirdPartyNotice] = {
        #if canImport(MLKitTranslate)
        return [ThirdPartyNotice("ML Kit translation models (about 30 MB per language)", "Google", "ML Kit Terms of Service", "https://developers.google.com/ml-kit/terms")]
        #else
        return []
        #endif
    }()
}
