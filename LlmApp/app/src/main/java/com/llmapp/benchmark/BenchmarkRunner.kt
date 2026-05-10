package com.llmapp.benchmark

import android.os.Build
import com.llmapp.LLMApplication
import com.llmapp.jni.NativeLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BenchmarkRunner(private val app: LLMApplication) {

    private val helper = BenchmarkHelper(app)

    /**
     * Run full benchmark for two configs: Eagle off and Eagle on.
     * Config A: eagle_model OFF  (the non-eagle model directory)
     * Config B: eagle_model ON   (the eagle model directory)
     */
    suspend fun run(
        configA: BenchConfig,
        configB: BenchConfig,
        onProgress: (String) -> Unit = {},
        onConfigComplete: (FullBenchmarkReport) -> Unit = {}
    ): Pair<FullBenchmarkReport, FullBenchmarkReport> = withContext(Dispatchers.IO) {

        // ── Baseline memory (no model loaded) ──
        onProgress("Measuring baseline memory...")
        app.destroyModelSession()
        val baselinePss = helper.measureMemoryKb()

        // ── Config A ──
        onProgress("=== Config A: ${configA.label} ===")
        val reportA = runSingleConfig(configA, baselinePss, onProgress)
        onConfigComplete(reportA)

        // ── Config B ──
        onProgress("=== Config B: ${configB.label} ===")
        val reportB = runSingleConfig(configB, baselinePss, onProgress)
        onConfigComplete(reportB)

        Pair(reportA, reportB)
    }

    private suspend fun runSingleConfig(
        config: BenchConfig,
        baselinePss: Long,
        onProgress: (String) -> Unit
    ): FullBenchmarkReport {
        val loadTimes = mutableListOf<Long>()
        val inferenceResults = mutableListOf<InferenceResult>()
        val ragLatencies = mutableListOf<RagLatencyBreakdown>()
        val filename = "benchmark_${config.label.replace(" ", "_")}_${System.currentTimeMillis()}.txt"

        // 1. Model loading time (3 runs)
        onProgress("  Measuring load time (3 runs)...")
        repeat(3) { i ->
            app.destroyModelSession()
            val t = helper.measureLoadTime(config.modelPath)
            loadTimes.add(t)
            onProgress("    Run ${i + 1}: ${t}ms")
        }

        // 2. Memory after load
        onProgress("  Measuring loaded memory...")
        val loadedPss = helper.measureMemoryKb()
        onProgress("    PSS: ${loadedPss}KB (delta: ${loadedPss - baselinePss}KB)")

        // 3. Inference speed — save incrementally after each round
        for (prompt in BENCHMARK_PROMPTS) {
            onProgress("  Prompt [${prompt.label}]...")
            for (round in 1..3) {
                onProgress("    Round $round/3...")
                try {
                    val result = helper.measureInference(prompt.text)
                    inferenceResults.add(result.copy(promptLabel = prompt.label))
                    onProgress("      TTFT=${result.ttftUs / 1000}ms, tokens=${result.outputTokens}, t/s=%.1f".format(result.tokensPerSec))
                } catch (e: Exception) {
                    onProgress("      ERROR: ${e.message}")
                }
                // Persist after every round so a kill only loses current round
                helper.saveReport(
                    FullBenchmarkReport(
                        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.SDK_INT})",
                        config = config,
                        loadTimeMs = loadTimes,
                        pssBaselineKb = baselinePss,
                        pssLoadedKb = loadedPss,
                        inferenceResults = inferenceResults.toList(),
                        ragLatencies = ragLatencies.toList()
                    ),
                    filename
                )
            }
        }

        // 4. RAG latency (if embedding model loaded)
        if (app.isEmbeddingModelLoaded.value) {
            onProgress("  Measuring RAG latency...")
            val testQuery = "人工智能的应用领域有哪些"

            val breakdown = helper.measureRagPipeline(testQuery)
            onProgress("    Chunks in DB: ${breakdown.chunkCount}")
            onProgress("    Query embedding: ${breakdown.embeddingMs}ms")
            onProgress("    Retrieval (DB+Cosine+Sort): ${breakdown.searchMs}ms")
            onProgress("    Total RAG: ${breakdown.totalMs}ms")

            ragLatencies.add(breakdown)
        }

        val report = FullBenchmarkReport(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.SDK_INT})",
            config = config,
            loadTimeMs = loadTimes,
            pssBaselineKb = baselinePss,
            pssLoadedKb = loadedPss,
            inferenceResults = inferenceResults,
            ragLatencies = ragLatencies
        )
        val savedPath = helper.saveReport(report, filename)
        onProgress("  Report saved to $savedPath")
        onProgress("  Pull with: adb pull $savedPath")

        return report
    }
}
