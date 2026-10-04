package com.clases.grabador

import android.content.Context
import java.io.File

/** Carpetas = directorios reales. Posición y marcadores se guardan en SharedPreferences. */
object Repo {
    private const val EXT = "aac"

    fun root(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "Grabaciones").also { it.mkdirs() }

    fun relPath(folder: String, name: String) = if (folder.isEmpty()) name else "$folder/$name"
    fun file(ctx: Context, rel: String) = File(root(ctx), rel)

    fun folders(ctx: Context): List<String> =
        root(ctx).listFiles { f -> f.isDirectory }?.map { it.name }?.sortedBy { it.lowercase() } ?: emptyList()

    fun files(ctx: Context, folder: String): List<File> =
        File(root(ctx), folder).listFiles { f -> f.isFile && f.extension == EXT }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun clean(s: String) = s.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")

    // ---- metadatos ----
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("meta", Context.MODE_PRIVATE)

    fun position(ctx: Context, rel: String): Long = prefs(ctx).getLong("pos:$rel", 0L)
    fun setPosition(ctx: Context, rel: String, ms: Long) = prefs(ctx).edit().putLong("pos:$rel", ms).apply()

    fun bookmarks(ctx: Context, rel: String): List<Long> =
        (prefs(ctx).getString("bm:$rel", "") ?: "").split(",").mapNotNull { it.toLongOrNull() }.sorted()

    fun setBookmarks(ctx: Context, rel: String, list: List<Long>) =
        prefs(ctx).edit().putString("bm:$rel", list.sorted().joinToString(",")).apply()

    private fun migrate(ctx: Context, old: String, new: String) {
        val p = prefs(ctx)
        val e = p.edit()
        p.all["pos:$old"]?.let { if (it is Long) e.putLong("pos:$new", it); e.remove("pos:$old") }
        p.all["bm:$old"]?.let { if (it is String) e.putString("bm:$new", it); e.remove("bm:$old") }
        e.apply()
    }

    // ---- operaciones ----
    fun createFolder(ctx: Context, name: String): Boolean {
        val n = clean(name)
        if (n.isEmpty()) return false
        val d = File(root(ctx), n)
        return !d.exists() && d.mkdirs()
    }

    fun renameFolder(ctx: Context, old: String, new: String): Boolean {
        val n = clean(new)
        if (n.isEmpty() || n == old) return false
        val dst = File(root(ctx), n)
        if (dst.exists()) return false
        val names = files(ctx, old).map { it.name }
        if (!File(root(ctx), old).renameTo(dst)) return false
        names.forEach { migrate(ctx, relPath(old, it), relPath(n, it)) }
        return true
    }

    fun deleteFolder(ctx: Context, name: String): Boolean {
        val d = File(root(ctx), name)
        return (d.listFiles()?.isEmpty() ?: false) && d.delete()
    }

    fun renameFile(ctx: Context, relOld: String, newName: String): Boolean {
        val n = clean(newName).removeSuffix(".$EXT")
        if (n.isEmpty()) return false
        val folder = relOld.substringBeforeLast('/', "")
        val relNew = relPath(folder, "$n.$EXT")
        if (relNew == relOld) return false
        val dst = file(ctx, relNew)
        if (dst.exists()) return false
        if (!file(ctx, relOld).renameTo(dst)) return false
        migrate(ctx, relOld, relNew)
        return true
    }

    fun moveFile(ctx: Context, relOld: String, destFolder: String): Boolean {
        val name = relOld.substringAfterLast('/')
        var dst = File(File(root(ctx), destFolder), name)
        if (dst.exists()) dst = File(dst.parentFile, "${dst.nameWithoutExtension}_${System.currentTimeMillis()}.$EXT")
        if (!file(ctx, relOld).renameTo(dst)) return false
        migrate(ctx, relOld, relPath(destFolder, dst.name))
        return true
    }

    fun deleteFile(ctx: Context, rel: String): Boolean {
        val ok = file(ctx, rel).delete()
        prefs(ctx).edit().remove("pos:$rel").remove("bm:$rel").apply()
        return ok
    }
}
