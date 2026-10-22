package com.linguareader.engine.supertonic

import android.os.Debug

/** Memoria del proceso en Android: PSS total (Java + nativa, incluye ONNX Runtime). */
val AndroidMemoryProbe = MemoryProbe { Debug.getPss() * 1024L }
