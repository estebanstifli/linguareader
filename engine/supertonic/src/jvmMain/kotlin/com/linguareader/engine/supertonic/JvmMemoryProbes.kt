package com.linguareader.engine.supertonic

/** Heap Java (no incluye la memoria nativa de ONNX Runtime). */
val MemoryProbe.Companion.JVM: MemoryProbe
    get() = MemoryProbe { Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } }

/** Memoria comprometida del proceso (incluye nativa) si la JVM la expone; si no, heap. */
val MemoryProbe.Companion.PROCESS: MemoryProbe
    get() = MemoryProbe {
        (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
            ?.committedVirtualMemorySize ?: MemoryProbe.JVM.usedBytes()
    }
