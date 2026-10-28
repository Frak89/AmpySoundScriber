package org.ampy.app.platform

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import org.ampy.diagnostics.MemoryProbe

object DeviceInfo {
    fun memoryProbe(context: Context) = MemoryProbe {
        val rt = Runtime.getRuntime()
        val javaUsed = rt.totalMemory() - rt.freeMemory()
        (javaUsed + Debug.getNativeHeapAllocatedSize()) to rt.maxMemory()
    }

    /** Memoria disponibile nel sistema (per il dimensionamento adattivo dei segmenti). */
    fun availableBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        val rt = Runtime.getRuntime()
        val heapFree = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        return minOf(mi.availMem, heapFree * 2)
    }

    fun summary(context: Context): Map<String, String> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        return mapOf(
            "app" to "AmpySoundScriber 0.1.0",
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "abi" to Build.SUPPORTED_ABIS.joinToString(),
            "cpu" to "${Runtime.getRuntime().availableProcessors()} core",
            "ramTotalMb" to (mi.totalMem / 1048576).toString(),
            "ramAvailMb" to (mi.availMem / 1048576).toString(),
            "heapMaxMb" to (Runtime.getRuntime().maxMemory() / 1048576).toString(),
            "lowRam" to am.isLowRamDevice.toString(),
        )
    }
}
