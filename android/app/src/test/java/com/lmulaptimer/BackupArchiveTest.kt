package com.lmulaptimer

import com.lmulaptimer.data.BackupArchive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupArchiveTest {
    @Test fun zipRoundtripKeepsMetadataAndSamples() {
        val output = ByteArrayOutputStream()
        BackupArchive.write(
            output,
            metadata = "{\"format\":2}",
            samples = mapOf("lap-1" to "{\"speed_kmh\":[1,2]}", "lap-2" to "{}"),
        )
        val (metadata, samples) = BackupArchive.read(ByteArrayInputStream(output.toByteArray()))
        assertEquals("{\"format\":2}", metadata)
        assertEquals(2, samples.size)
        assertEquals("{\"speed_kmh\":[1,2]}", samples["lap-1"])
    }
}
