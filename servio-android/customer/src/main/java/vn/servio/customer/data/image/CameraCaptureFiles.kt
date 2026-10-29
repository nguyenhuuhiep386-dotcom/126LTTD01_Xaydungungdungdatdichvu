package vn.servio.customer.data.image

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Temporary photos taken with the camera on CS-08, in the app cache (`cache/camera/`, see res/xml/file_paths.xml).
 * The camera app writes through a FileProvider uri; the files are deleted with the image or when the draft is dropped.
 */
@Singleton
class CameraCaptureFiles @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val dir: File get() = File(context.cacheDir, DIR).apply { mkdirs() }

    /** A new empty file for TakePicture, as a content:// uri the camera app may write to. */
    fun create(): Uri {
        val file = File.createTempFile("capture_", ".jpg", dir)
        return FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", file)
    }

    /** False when the camera returned without writing anything. */
    fun hasContent(uri: Uri): Boolean = fileOf(uri)?.let { it.length() > 0 } ?: false

    fun delete(uri: Uri) {
        fileOf(uri)?.delete()
    }

    /** Leftovers of a draft lost with the process; [keep] is a capture still waiting for the camera result. */
    fun deleteAllExcept(keep: Uri?) {
        val kept = keep?.let(::fileOf)
        dir.listFiles()?.filter { it != kept }?.forEach { it.delete() }
    }

    /** Only files of our own provider directory, never an arbitrary uri. */
    private fun fileOf(uri: Uri): File? {
        if (uri.authority != "${context.packageName}$AUTHORITY_SUFFIX") return null
        val name = uri.lastPathSegment ?: return null
        return File(dir, name).takeIf { it.parentFile == dir }
    }

    private companion object {
        const val DIR = "camera"
        const val AUTHORITY_SUFFIX = ".fileprovider"
    }
}
