package com.micu.studio

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/** 本地图库 + 保存到系统相册 */
object ImageStore {

    private fun libraryDir(context: Context): File {
        val dir = File(context.filesDir, "images")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun saveToLibrary(context: Context, image: Bitmap, prompt: String): String {
        val dir = libraryDir(context)
        val name = "img_${System.currentTimeMillis()}.jpg"
        val file = File(dir, name)
        val bos = java.io.ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.JPEG, 92, bos)
        file.writeBytes(bos.toByteArray())
        context.getSharedPreferences("prompts", Context.MODE_PRIVATE)
            .edit().putString(name, prompt).apply()
        return file.absolutePath
    }

    fun loadLibrary(context: Context): List<SavedImage> {
        val dir = libraryDir(context)
        val prompts = context.getSharedPreferences("prompts", Context.MODE_PRIVATE)
        return dir.listFiles { f -> f.extension == "jpg" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                SavedImage(
                    uri = f.absolutePath,
                    prompt = prompts.getString(f.name, "") ?: "",
                    date = f.lastModified()
                )
            } ?: emptyList()
    }

    /** Android 10+ 免权限保存相册；9 及以下需要 WRITE_EXTERNAL_STORAGE（Manifest 已声明 maxSdk 28） */
    fun saveToAlbum(context: Context, image: Bitmap): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "shengtutai_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/生图台")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                image.compress(Bitmap.CompressFormat.JPEG, 95, out)
            } ?: return false
            if (Build.VERSION.SDK_INT >= 29) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
