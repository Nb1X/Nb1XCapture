package io.github.nb1x.capture

import android.content.Context
import android.os.Environment
import java.io.File
import kotlin.random.Random

object RecordingStorage {

    /**
     * Dossier public : /sdcard/Android/media/io.github.nb1x.capture/
     * Accessible depuis un gestionnaire de fichiers, contrairement à getExternalFilesDir().
     */
    fun dir(context: Context): File {
        val base = File(
            Environment.getExternalStorageDirectory(),
            "Android/media/${context.packageName}"
        )
        if (!base.exists()) base.mkdirs()
        return base
    }

    /**
     * Génère un nom de fichier à 8 chiffres aléatoires (00000000 à 99999999).
     * Reboucle tant que le fichier existe déjà.
     */
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
        // Fallback improbable (1000 collisions consécutives)
        return File(d, "${System.currentTimeMillis()}.mp4")
    }

    /** Liste des fichiers .mp4, triés par date croissante (plus ancien en premier). */
    fun list(context: Context): List<File> {
        return dir(context).listFiles()
            ?.filter { it.extension == "mp4" }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()
    }

    /** Le plus récent (dernier enregistrement). */
    fun last(context: Context): File? = list(context).lastOrNull()

    fun count(context: Context): Int = list(context).size

    fun deleteLast(context: Context): Boolean {
        val last = last(context) ?: return false
        return last.delete()
    }
}
