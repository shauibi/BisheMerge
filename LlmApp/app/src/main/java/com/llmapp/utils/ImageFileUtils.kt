package com.llmapp.utils

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 图片文件工具类，提供图片创建、Uri 复制、Bitmap 解码和删除功能
object ImageFileUtils {
    private const val IMAGE_DIR = "images"
    private const val CACHE_DIR = "image_cache"

    // 在应用外部存储 Pictures 目录下创建带时间戳的图片文件
    fun createImageFile(context: Context): File {
        val dir = File(context.getExternalFilesDir("Pictures"), IMAGE_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "IMG_${timestamp}.jpg")
    }

    // 将 Uri 内容复制到缓存目录，返回本地路径
    fun copyUriToCache(context: Context, uri: Uri): String? {
        val cacheDir = File(context.cacheDir, CACHE_DIR)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = getFileNameFromUri(context, uri) ?: "picked_${timestamp}.jpg"
        val extension = fileName.substringAfterLast(".", "jpg")
        val destFile = File(cacheDir, "picked_${timestamp}.$extension")

        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            inputStream?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            // 校验文件是否完整复制
            if (destFile.exists() && destFile.length() > 0) {
                destFile.absolutePath
            } else {
                destFile.delete()
                null
            }
        } catch (e: Exception) {
            destFile.delete()
            Logger.e("Failed to copy URI to cache: ${e.message}", e)
            null
        }
    }

    // 从 Content Uri 获取原始文件名
    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        result = it.getString(nameIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path?.substringAfterLast('/')
        }
        return result
    }

    // 从文件路径解码 Bitmap，支持采样缩放避免 OOM
    fun decodeBitmapFromFile(path: String, maxDim: Int = 1024): Bitmap? {
        return try {
            val file = File(path)
            if (!file.exists()) return null

            // 先读取图片尺寸（不加载像素数据）
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, options)

            val (outWidth, outHeight) = options.run { outWidth to outHeight }
            // 根据目标尺寸计算采样率
            options.inSampleSize = calculateInSampleSize(outWidth, outHeight, maxDim)
            options.inJustDecodeBounds = false

            BitmapFactory.decodeFile(path, options)
        } catch (e: Exception) {
            Logger.e("Failed to decode bitmap: ${e.message}", e)
            null
        }
    }

    // 计算 BitmapFactory 采样率，使图片缩小到 maxDim 以内
    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sampleSize = 1
        if (height > maxDim || width > maxDim) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / sampleSize) >= maxDim && (halfWidth / sampleSize) >= maxDim) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }

    // 删除指定路径的文件
    fun deleteFile(path: String): Boolean {
        return try {
            File(path).delete()
        } catch (e: Exception) {
            false
        }
    }
}
