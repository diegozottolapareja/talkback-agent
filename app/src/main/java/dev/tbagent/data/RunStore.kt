package dev.tbagent.data

import dev.tbagent.core.RunResult
import java.io.File

class RunStore(private val root: File) {
    fun dirFor(runId: String): File = File(root, runId).apply { mkdirs() }

    fun save(result: RunResult): File {
        val f = File(dirFor(result.id), "result.json")
        f.writeText(JsonCodec.toJson(result).toString(2))
        return f
    }
}
