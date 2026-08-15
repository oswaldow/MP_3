package com.learnlayout.mp_3

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Guarda datos JSON en disco de forma segura, para no depender de
 * SharedPreferences para informacion que no se puede perder (playlists,
 * favoritos, contador de reproducciones, etc.).
 *
 * - Escribe primero a un archivo temporal y solo al final hace rename() al
 *   archivo real. El rename es atomico en el mismo volumen, asi que nunca
 *   queda un archivo a medio escribir si la app truena o se queda sin
 *   bateria a mitad de un guardado.
 * - Antes de aceptar una escritura nueva, valida que el contenido sea JSON
 *   valido. Si no lo es, la escritura se cancela y no se toca nada.
 * - Mantiene una copia de respaldo (.bak) del ultimo estado que se pudo
 *   leer/escribir correctamente. Si el archivo principal aparece corrupto
 *   (por un bug futuro, un cambio de esquema, lo que sea), se recupera del
 *   backup en vez de perder los datos silenciosamente.
 *
 * Los archivos viven en el almacenamiento interno de la app
 * (Context.filesDir), no en SharedPreferences.
 */
class SafeJsonStore(context: Context, fileName: String) {

    private val mainFile = File(context.applicationContext.filesDir, "$fileName.json")
    private val backupFile = File(context.applicationContext.filesDir, "$fileName.bak.json")

    companion object {
        private const val TAG = "SafeJsonStore"
    }

    /** true si ya existe el archivo principal (util para decidir si hay que migrar datos viejos). */
    fun exists(): Boolean = mainFile.exists()

    /**
     * Lee el archivo principal. Si esta corrupto o no existe, intenta el
     * backup. Si tampoco hay backup utilizable, devuelve [default].
     * Nunca lanza excepcion.
     */
    @Synchronized
    fun read(default: String): String {
        readValidated(mainFile)?.let { return it }
        Log.e(TAG, "Archivo '${mainFile.name}' ausente o corrupto, probando backup")

        readValidated(backupFile)?.let { fromBackup ->
            Log.w(TAG, "Datos recuperados desde backup de '${mainFile.name}'")
            // Restaura el principal a partir del backup bueno para la
            // proxima vez que se lea.
            runCatching { atomicWrite(mainFile, fromBackup) }
            return fromBackup
        }

        Log.e(TAG, "Tampoco hay backup utilizable para '${mainFile.name}', usando valor por defecto")
        return default
    }

    /**
     * Escribe [content] de forma atomica. Antes de reemplazar el archivo
     * principal, si su contenido actual es valido lo promueve a backup.
     * Devuelve true si la escritura se completo correctamente, false si
     * [content] no era JSON valido o algo fallo (en cuyo caso no se tocan
     * los archivos existentes).
     */
    @Synchronized
    fun write(content: String): Boolean {
        if (!isValidJson(content)) {
            Log.e(TAG, "Se intento guardar JSON invalido en '${mainFile.name}', operacion cancelada")
            return false
        }
        return try {
            readValidated(mainFile)?.let { currentGood ->
                atomicWrite(backupFile, currentGood)
            }
            atomicWrite(mainFile, content)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error al escribir '${mainFile.name}'", e)
            false
        }
    }

    private fun readValidated(file: File): String? {
        if (!file.exists()) return null
        val raw = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Error al leer '${file.name}'", e)
            return null
        }
        return if (isValidJson(raw)) raw else null
    }

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.fd.sync() // fuerza a disco, no se queda solo en cache
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                throw IOException("No se pudo renombrar ${tmp.name} a ${target.name}")
            }
        }
    }

    private fun isValidJson(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        return try {
            when {
                trimmed.startsWith("{") -> { JSONObject(trimmed); true }
                trimmed.startsWith("[") -> { JSONArray(trimmed); true }
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }
}