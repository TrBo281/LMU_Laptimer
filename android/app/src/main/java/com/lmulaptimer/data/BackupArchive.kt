package com.lmulaptimer.data

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** ZIP container: backup.json plus one samples/<lap-id>.json entry per lap. */
object BackupArchive {
    fun write(
        output: OutputStream,
        metadata: String,
        samples: Map<String, String>,
        extraEntries: Map<String, String> = emptyMap(),
    ) {
        ZipOutputStream(output).use { zip ->
            entry(zip, "backup.json", metadata)
            samples.forEach { (lapId, payload) -> entry(zip, "samples/$lapId.json", payload) }
            extraEntries.forEach { (name, text) -> entry(zip, name, text) }
        }
    }

    fun read(input: InputStream): Pair<String, Map<String, String>> {
        var metadata: String? = null
        val samples = LinkedHashMap<String, String>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    when {
                        entry.name == "backup.json" -> metadata = text
                        entry.name.startsWith("samples/") && entry.name.endsWith(".json") -> {
                            val lapId = entry.name.removePrefix("samples/").removeSuffix(".json")
                            if (lapId.isNotBlank()) samples[lapId] = text
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return (metadata ?: error("backup.json fehlt")) to samples
    }

    private fun entry(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
