package com.learnlayout.mp_3

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captura cualquier excepcion no atrapada en cualquier hilo de la app y la
 * guarda en un archivo de texto en el almacenamiento interno antes de dejar
 * que el sistema termine el proceso normalmente. Sin esto, un crash fuera
 * de Android Studio (en el celular, en uso normal) es invisible: no queda
 * ningun rastro para diagnosticarlo despues.
 */
class CrashHandler private constructor(
    private val appContext: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            saveCrashReport(thread, throwable)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo guardar el reporte de crash", e)
        }
        // Deja que el sistema maneje el crash normalmente (cierra la app,
        // muestra el dialogo de "la app dejo de funcionar", etc.)
        defaultHandler?.uncaughtException(thread, throwable)
            ?: android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun saveCrashReport(thread: Thread, throwable: Throwable) {
        val dir = crashLogDir(appContext)
        if (!dir.exists()) dir.mkdirs()

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(dir, "crash_$timestamp.txt")

        val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()

        val report = buildString {
            appendLine("Fecha: ${Date()}")
            appendLine("Hilo: ${thread.name}")
            appendLine("Version app: ${appVersionInfo(appContext)}")
            appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine(stackTrace)
        }

        file.writeText(report)
        trimOldReports(dir)
    }

    private fun appVersionInfo(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pInfo.versionName} (${pInfo.longVersionCode})"
        } catch (e: Exception) {
            "desconocida"
        }
    }

    /** Se queda solo con los ultimos [MAX_REPORTS] reportes para no llenar el disco. */
    private fun trimOldReports(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_REPORTS).forEach { it.delete() }
    }

    companion object {
        private const val TAG = "CrashHandler"
        private const val MAX_REPORTS = 10

        fun install(context: Context) {
            val appContext = context.applicationContext
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is CrashHandler) return // ya instalado
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(appContext, current))
        }

        fun crashLogDir(context: Context): File {
            return File(context.applicationContext.filesDir, "crash_logs")
        }

        /** El reporte de crash mas reciente, o null si no hay ninguno. */
        fun getLatestReport(context: Context): File? {
            return crashLogDir(context).listFiles()?.maxByOrNull { it.lastModified() }
        }

        fun getAllReports(context: Context): List<File> {
            return crashLogDir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        }
    }
}