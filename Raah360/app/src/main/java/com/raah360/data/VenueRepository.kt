package com.raah360.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.raah360.core.Venue
import com.raah360.core.VenueCodec
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Venues live in filesDir/venues/<venueId>/ as venue.json + photos.
 * Maps shipped in assets/venues are copied in on first use; newer surveys upgrade them.
 */
class VenueRepository(private val context: Context) {

    private val root = File(context.filesDir, "venues").apply { mkdirs() }
    private val bitmapCache = HashMap<String, Bitmap?>()

    fun venueDir(id: String) = File(root, safe(id)).apply { mkdirs() }

    fun listVenueIds(): List<String> =
        root.listFiles()?.filter { File(it, "venue.json").exists() }?.map { it.name }?.sorted() ?: emptyList()

    /** Maps shipped with the app (the .json files in assets/venues), by venue id. */
    private val bundled: Map<String, String> by lazy {
        (context.assets.list("venues") ?: emptyArray()).filter { it.endsWith(".json") }.mapNotNull { f ->
            runCatching {
                val text = context.assets.open("venues/$f").bufferedReader().use { it.readText() }
                VenueCodec.decode(text).id to text
            }.getOrNull()
        }.toMap()
    }

    private fun fresh(id: String): Venue? = bundled[id]?.let { runCatching { VenueCodec.decode(it) }.getOrNull() }

    /** Every map on the phone: the shipped ones (copied in on first use) plus any imported. */
    fun catalog(): List<Venue> {
        val have = listVenueIds().toSet()
        for (id in bundled.keys) if (safe(id) !in have) fresh(id)?.let(::save)
        return listVenueIds().mapNotNull { id ->
            runCatching { upgradeFromSeed(VenueCodec.decode(File(venueDir(id), "venue.json").readText())) }.getOrNull()
        }
    }

    /** Loads the given venue, or the first available one, seeding from assets if needed. */
    fun load(preferredId: String?): Venue {
        val ids = listVenueIds()
        val id = preferredId?.takeIf { it in ids } ?: ids.firstOrNull()
        if (id == null) return seed(preferredId)
        val saved = runCatching { VenueCodec.decode(File(venueDir(id), "venue.json").readText()) }.getOrElse { return seed(id) }
        return upgradeFromSeed(saved)
    }

    /**
     * A newer survey shipped with the app replaces the map geometry of an older saved copy,
     * but keeps what was measured on site: located markers, place photos and learnt GPS.
     * Places added in the Mapper are dropped: they were placed on the old geometry.
     */
    private fun upgradeFromSeed(saved: Venue): Venue {
        val fresh = fresh(saved.id) ?: return saved
        if (fresh.survey <= saved.survey) return saved
        val oldPins = saved.pins.associateBy { it.id }
        val oldMarks = saved.landmarks.associateBy { it.id }
        val merged = fresh.copy(
            revision = maxOf(fresh.revision, saved.revision + 1),
            // a surveyed building position / compass offset beats a learnt or one-tap one
            lat = fresh.lat ?: saved.lat,
            lng = fresh.lng ?: saved.lng,
            northDeg = fresh.northDeg ?: saved.northDeg,
            pins = fresh.pins.map { p ->
                val o = oldPins[p.id] ?: return@map p
                p.copy(image = o.image ?: p.image, signature = o.signature ?: p.signature, lat = o.lat, lng = o.lng, geoAccM = o.geoAccM)
            },
            landmarks = fresh.landmarks.map { l -> oldMarks[l.id]?.takeIf { it.located } ?: l },
        )
        save(merged)
        return merged
    }

    /** Copies a shipped map in (the given one, else the surveyed atrium, else any). */
    fun seed(id: String? = null): Venue {
        val v = (id?.let(::fresh) ?: fresh("atrium-blr") ?: bundled.keys.firstOrNull()?.let(::fresh))
            ?: error("No venue maps in assets/venues")
        save(v)
        return v
    }

    /** Throws away local edits of a shipped venue and reloads its original survey. */
    fun resetToSeed(id: String): Venue {
        val v = fresh(id) ?: return load(id)
        File(venueDir(v.id), "venue.json").delete()
        save(v)
        return v
    }

    fun save(v: Venue) {
        val dir = venueDir(v.id)
        val tmp = File(dir, "venue.json.tmp")
        tmp.writeText(VenueCodec.encode(v))
        val dst = File(dir, "venue.json")
        if (!tmp.renameTo(dst)) {
            dst.writeText(tmp.readText()); tmp.delete()
        }
    }

    // ---------- images ----------

    fun saveImage(venueId: String, name: String, bmp: Bitmap): String {
        val file = File(venueDir(venueId), safe(name))
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmapCache.remove("$venueId/${file.name}")
        return file.name
    }

    /** Loads "asset:markers/M1.png" or a file inside the venue folder. */
    fun loadBitmap(venueId: String, ref: String?, maxSide: Int = 1024): Bitmap? {
        if (ref.isNullOrBlank()) return null
        val key = "$venueId/$ref@$maxSide"
        bitmapCache[key]?.let { return it }
        val bmp = runCatching {
            if (ref.startsWith("asset:")) {
                context.assets.open(ref.removePrefix("asset:")).use { decodeScaled(it.readBytes(), maxSide) }
            } else {
                val f = File(venueDir(venueId), safe(ref))
                if (f.exists()) decodeScaled(f.readBytes(), maxSide) else null
            }
        }.getOrNull()
        bitmapCache[key] = bmp
        return bmp
    }

    private fun decodeScaled(bytes: ByteArray, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxSide || o.outHeight / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---------- share / import ----------

    /** Zips venue.json + photos into cache/exports for sharing with another phone. */
    fun exportZip(v: Venue): File {
        save(v)
        val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val out = File(outDir, "${safe(v.id)}.raah360.zip")
        ZipOutputStream(FileOutputStream(out)).use { zip ->
            venueDir(v.id).listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }?.forEach { f ->
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }

    /** Imports a zip produced by [exportZip]. Returns the imported venue. */
    fun importZip(uri: Uri): Venue {
        val staging = File(context.cacheDir, "import-${System.currentTimeMillis()}").apply { mkdirs() }
        context.contentResolver.openInputStream(uri)!!.use { input ->
            ZipInputStream(input).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    // flat archive only: strip any path, refuse traversal
                    val name = safe(e.name.substringAfterLast('/').substringAfterLast('\\'))
                    if (!e.isDirectory && name.isNotBlank()) {
                        FileOutputStream(File(staging, name)).use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
        }
        val json = File(staging, "venue.json")
        require(json.exists()) { "Not a Raah360 venue file" }
        val v = VenueCodec.decode(json.readText())
        val dir = venueDir(v.id)
        staging.listFiles()?.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
        staging.deleteRecursively()
        bitmapCache.clear()
        return v
    }

    companion object {
        fun safe(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.').ifBlank { "x" }
    }
}
