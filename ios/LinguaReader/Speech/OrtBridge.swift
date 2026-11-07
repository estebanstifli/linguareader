import Foundation
import LinguaShared
#if canImport(onnxruntime_objc)
import onnxruntime_objc

/// ONNX Runtime para la voz neural (Supertonic 3) con la API Objective-C del pod `onnxruntime-objc` (ORT 1.30,
/// enlazado estático en la app). Kotlin solo ve `OnnxNativeBridge` (`engine/onnx`, `Onnx.ios.kt`): los tensores
/// viajan como bytes float32/int64 con su forma. Solo en la app de iPhone; el job de simulador compila sin pods.
final class OrtBridge: NSObject, OnnxNativeBridge {
    private let env: ORTEnv

    private init(env: ORTEnv) { self.env = env }

    static func make() -> OrtBridge? {
        do {
            return OrtBridge(env: try ORTEnv(loggingLevel: .warning))
        } catch {
            NSLog("ONNX Runtime could not start: %@", error.localizedDescription)
            return nil
        }
    }

    func createSession(modelPath: String, intraOpThreads: Int32, interOpThreads: Int32, optimization: Int32) throws -> OnnxNativeSession {
        let options = try ORTSessionOptions()
        try options.setIntraOpNumThreads(intraOpThreads)
        let level: ORTGraphOptimizationLevel = switch optimization {
        case 0: .basic
        case 1: .extended
        default: .all
        }
        try options.setGraphOptimizationLevel(level)
        let session = try ORTSession(env: env, modelPath: modelPath, sessionOptions: options)
        return OrtSessionBridge(session: session, inputs: try session.inputNames(), outputs: try session.outputNames())
    }
}

/// Una sesión ONNX; las salidas se devuelven en el orden del modelo y se copian (el búfer de `ORTValue` muere con él).
final class OrtSessionBridge: NSObject, OnnxNativeSession {
    private var session: ORTSession?
    let inputNames: [String]
    private let outputs: [String]

    init(session: ORTSession, inputs: [String], outputs: [String]) {
        self.session = session
        self.inputNames = inputs
        self.outputs = outputs
    }

    func run(names: [String], data: [Data], kinds: [KotlinInt], shapes: [[KotlinLong]]) throws -> [OnnxNativeTensor] {
        guard let session else { throw OrtBridgeError.closed }
        var inputs: [String: ORTValue] = [:]
        for i in names.indices {
            let type: ORTTensorElementDataType = kinds[i].int32Value == OnnxNativeTensor.companion.INT64 ? .int64 : .float
            let shape = shapes[i].map { NSNumber(value: $0.int64Value) }
            inputs[names[i]] = try ORTValue(tensorData: NSMutableData(data: data[i]), elementType: type, shape: shape)
        }
        let result = try session.run(withInputs: inputs, outputNames: Set(outputs), runOptions: nil)
        return try outputs.map { name in
            guard let value = result[name] else { throw OrtBridgeError.missingOutput(name) }
            let info = try value.tensorTypeAndShapeInfo()
            let kind: Int32
            switch info.elementType {
            case .float: kind = OnnxNativeTensor.companion.FLOAT
            case .int64: kind = OnnxNativeTensor.companion.INT64
            default: throw OrtBridgeError.unsupportedType(name)
            }
            let bytes = try value.tensorData()
            let copy = Data(bytes: bytes.bytes, count: bytes.length)
            return OnnxNativeTensor(data: copy, kind: kind, shape: info.shape.map { KotlinLong(value: $0.int64Value) })
        }
    }

    func close() { session = nil }
}

enum OrtBridgeError: LocalizedError {
    case closed, missingOutput(String), unsupportedType(String)
    var errorDescription: String? {
        switch self {
        case .closed: "ONNX session already closed"
        case .missingOutput(let name): "ONNX output \(name) missing"
        case .unsupportedType(let name): "ONNX output \(name) has an unsupported element type"
        }
    }
}
#endif

enum NeuralRuntime {
    /// Instala ONNX Runtime para Kotlin antes de `LinguaShared.start` (que decide si ofrece la voz neural).
    static func install() {
        #if canImport(onnxruntime_objc)
        IosOnnx.shared.bridge = OrtBridge.make()
        #endif
    }
}
