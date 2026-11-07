package com.linguareader.engine.supertonic

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.Foundation.NSProcessInfo
import platform.darwin.KERN_SUCCESS
import platform.darwin.TASK_VM_INFO
import platform.darwin.integer_tVar
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_task_self_
import platform.darwin.task_info
import platform.darwin.task_vm_info_data_t

/**
 * Huella física del proceso (`phys_footprint` de `task_info(TASK_VM_INFO)`): lo que muestra Xcode y lo que vigila
 * jetsam para cerrar la app por memoria; es el análogo del PSS de Android.
 */
@OptIn(ExperimentalForeignApi::class)
val IosMemoryProbe = MemoryProbe {
    memScoped {
        val info = alloc<task_vm_info_data_t>()
        val count = alloc<mach_msg_type_number_tVar>()
        count.value = (sizeOf<task_vm_info_data_t>() / sizeOf<integer_tVar>()).toUInt()
        val result = task_info(mach_task_self_, TASK_VM_INFO.toUInt(), info.ptr.reinterpret(), count.ptr)
        if (result == KERN_SUCCESS) info.phys_footprint.toLong() else null
    }
}

/** Memoria física del aparato en bytes. */
val iosPhysicalMemoryBytes: Long get() = NSProcessInfo.processInfo.physicalMemory.toLong()
