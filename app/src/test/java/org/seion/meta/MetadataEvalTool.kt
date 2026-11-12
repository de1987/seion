package org.seion.meta

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.seion.art.CoverMatch

/**
 * Opt-in replay of a labelled set of tracks (with cached lookup candidates) through the production parser and matcher:
 * `SEION_META_EVAL=<dir with gt.json> ./gradlew testDebugUnitTest --tests '*MetadataEvalTool*'`. Prints correct/wrong/abstain.
 */
class MetadataEvalTool {
    @Test fun replay() {
        val dir = System.getenv("SEION_META_EVAL")
        assumeTrue("set SEION_META_EVAL to run", dir != null)
        val json = Json { ignoreUnknownKeys = true }
        val gt = json.parseToJsonElement(File(dir, "gt.json").readText()).jsonArray
        val cands = json.parseToJsonElement(File(dir, "results/candidates.json").readText()).jsonObject
        var correct = 0
        var wrong = 0
        var abstain = 0
        val lines = StringBuilder()
        for (rec in gt.map { it.jsonObject }) {
            val path = rec.getValue("path").jsonPrimitive.content
            val durMs = (rec.getValue("duration").jsonPrimitive.doubleOrNull!! * 1000).toLong()
            val p = FileNameParser.parse(path)
            var pick: RecordingCandidate? = null
            if (!p.junk && p.artist != null) {
                val list = cands.getValue(path).jsonObject["MBdur"]?.jsonArray.orEmpty().map { it.jsonObject.toCandidate() }
                pick = MetadataMatch.best(p, durMs, list)?.candidate
            }
            val truth = rec["gt"]?.takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonPrimitive.content }
            val verdict =
                when {
                    pick == null -> "abstain".also { abstain++ }
                    truth == null -> "wrong".also { wrong++ }
                    CoverMatch.artistSim(
                        truth[0],
                        pick.artist
                    ) >= 0.8 &&
                        MetadataMatch.titleSim(truth[1], pick.title) >= 0.85 -> "correct".also {
                        correct++
                    }
                    else -> "wrong".also { wrong++ }
                }
            lines.append("$verdict\t$path\t${pick?.artist} - ${pick?.title}\n")
        }
        println(lines)
        println("RESULT correct=$correct wrong=$wrong abstain=$abstain n=${gt.size}")
    }

    private fun JsonObject.toCandidate() = RecordingCandidate(
        artist = getValue("artist").jsonPrimitive.content,
        title = getValue("title").jsonPrimitive.content,
        albums = (get("albums") as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
        durationMs = get("dur")?.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).toLong() },
        extra = get("extra")?.takeIf { it !is JsonNull }?.jsonPrimitive?.content.orEmpty(),
        id = get("id")?.toString().orEmpty()
    )
}
