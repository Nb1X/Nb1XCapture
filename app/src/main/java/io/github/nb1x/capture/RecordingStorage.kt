package io.github.nb1x.capture

import android.content.Context
import android.os.Environment
import java.io.File
import kotlin.random.Random

object RecordingStorage {

    /** Public folder: /sdcard/Android/media/io.github.nb1x.capture/ (visible in file managers). */
    fun dir(context: Context): File {
        val base = File(
            Environment.getExternalStorageDirectory(),
            "Android/media/${context.packageName}"
        )
        if (!base.exists()) base.mkdirs()
        return base
    }

    /** Generate a random 8-digit filename. Retry if it already exists. */
    fun newFile(context: Context): File {
        val d = dir(context)
        var attempts = 0
        while (attempts < 1000) {
            val n = Random.nextInt(0, 100_000_000)  // 0 à 99 999 999
            val name = "%08d.mp4".format(n)
            val f = File(d, name)
            if (!f.exists()) return f
            attempts++
        }
        // Improbable fallback (1000 consecutive collisions)
        return File(d, "${System.currentTimeMillis()}.mp4")
    }

    /** List .mp4 files sorted by ascending date (oldest first). */
    fun list(context: Context): List<File> {
        return dir(context).listFiles()
            ?.filter { it.extension == "mp4" }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()
    }

    /** Most recent recording. */
    fun last(context: Context): File? = list(context).lastOrNull()

    fun count(context: Context): Int = list(context).size

    fun deleteLast(context: Context): Boolean {
        val last = last(context) ?: return false
        return last.delete()
    }
}
