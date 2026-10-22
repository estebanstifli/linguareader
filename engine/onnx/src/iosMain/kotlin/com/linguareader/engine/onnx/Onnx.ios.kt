package com.linguareader.engine.onnx

/**
 * iOS: pendiente. La implementación real enlaza la API C de ONNX Runtime (`onnxruntime-c`, XCFramework) por
 * cinterop y requiere compilar en un Mac (Fase 1 del plan iOS). Hasta entonces el framework compila y cualquier
 * motor ONNX informa de que no está disponible, sin romper el resto de la app.
 */
actual fun platformOnnxRuntime(): OnnxRuntime = throw UnsupportedOperationException(
    "ONNX Runtime no está enlazado en iOS todavía (ver docs/ARCHITECTURE.md, módulo engine/onnx)",
)
