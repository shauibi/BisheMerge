package com.llmapp.benchmark

import android.os.Debug
import com.llmapp.LLMApplication
import com.llmapp.data.rag.ChunkWithScore
import com.llmapp.jni.InferenceCallback
import com.llmapp.jni.NativeLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.measureTimeMillis

// ── Benchmark prompts at different token lengths ──────────────────────

data class BenchmarkPrompt(val label: String, val text: String)

val BENCHMARK_PROMPTS = listOf(
    BenchmarkPrompt("10t", "你好，请介绍一下你自己。"),
    BenchmarkPrompt("50t", "请详细解释一下什么是人工智能。人工智能是计算机科学的一个分支，它研究如何让计算机模拟人类的智能行为，包括学习、推理、感知、理解自然语言等方面。"),
    BenchmarkPrompt("200t", """请从多个角度分析云计算技术的发展趋势和影响。

一、技术架构演进
云计算从最初的虚拟化技术发展到容器化，再到现在的Serverless架构，技术栈不断演进。Kubernetes已经成为容器编排的事实标准，而服务网格技术如Istio正在改变微服务之间的通信方式。

二、成本优化
企业上云的核心驱动力之一是成本优化。通过弹性伸缩、按需付费等模式，企业可以显著降低IT基础设施支出。多云和混合云策略也日益流行，避免厂商锁定。

三、安全与合规
随着数据主权法规的完善（如GDPR、数据安全法），云安全成为重中之重。零信任架构、机密计算等技术正在被广泛应用于云环境。

四、AI与云的融合
云平台正在深度集成AI能力。从GPU算力租赁到预训练模型API服务，云厂商正在构建完整的AI基础设施栈。MaaS（Model as a Service）成为新趋势。

五、边缘计算
为满足低延迟场景需求，边缘计算补充了集中式云的不足。5G+边缘计算正在推动工业互联网、自动驾驶等领域的创新。

综上所述，云计算正从单纯的IT资源交付向智能化、边缘化、全栈化方向演进。"""),
    BenchmarkPrompt("500t", """请全面分析现代软件工程实践的发展趋势和最佳实践。

第一部分：开发方法论
敏捷开发已经成为行业标准，但实施质量参差不齐。Scrum和Kanban是最流行的框架。真正的敏捷不仅仅是每日站会和Sprint计划，而是深入理解敏捷宣言的核心价值：个体和互动高于流程和工具，工作的软件高于详尽的文档，客户合作高于合同谈判，响应变化高于遵循计划。

DevOps文化打破了开发和运维之间的壁垒。持续集成（CI）确保代码变更频繁合并到主干，持续交付（CD）保证软件始终处于可发布状态。基础设施即代码（IaC）工具如Terraform和Ansible使得环境配置可版本化和自动化。

第二部分：架构模式
微服务架构虽然流行但并非银弹。对于中小型项目，模块化单体可能是更好的选择。领域驱动设计（DDD）帮助团队理解复杂业务领域，限界上下文为微服务拆分提供了理论依据。

事件驱动架构通过消息队列和事件流实现服务间的松耦合。CQRS（命令查询职责分离）和Event Sourcing在处理复杂业务逻辑时很有价值，但增加了系统复杂度。

第三部分：质量保证
测试金字塔依然是有效的策略：大量单元测试、适量集成测试、少量端到端测试。测试驱动开发（TDD）能够提高代码质量和可维护性。契约测试在微服务场景下尤为重要。

代码审查是知识共享和质量保障的重要机制。静态分析工具（如SonarQube）和代码格式化工具（如Prettier）减少了风格争议，让审查聚焦于逻辑正确性。

可观测性三支柱：日志（Logging）、指标（Metrics）、链路追踪（Tracing）。OpenTelemetry正在成为统一的可观测性标准。

第四部分：AI辅助开发
GitHub Copilot、Cursor等AI编程助手正在改变开发者的工作方式。它们能显著提高编写样板代码的效率，但对复杂业务逻辑的理解仍有局限。提示工程（Prompt Engineering）成为一项新技能。

第五部分：可持续性
绿色软件工程关注降低软件的碳排放。选择高效的算法、优化数据存储、利用可再生能源的数据中心，都是可持续实践。碳感知计算根据电网碳排放强度动态调整计算任务。

总结：现代软件工程需要开发者在技术深度和业务理解之间找到平衡，持续学习和适应变化是核心能力。""")
)

// ── Data classes ─────────────────────────────────────────────────────

data class BenchConfig(
    val label: String,
    val modelPath: String,
    val eagleEnabled: Boolean
)

data class InferenceResult(
    val promptLabel: String,
    val ttftUs: Long,
    val outputTokens: Int,
    val prefillUs: Long,
    val decodeUs: Long,
    val tokensPerSec: Double
)

data class RagLatencyBreakdown(
    val chunkCount: Int,
    val embeddingMs: Long,
    val searchMs: Long,
    val totalMs: Long
)

data class FullBenchmarkReport(
    val deviceModel: String,
    val config: BenchConfig,
    val loadTimeMs: List<Long>,
    val pssBaselineKb: Long,
    val pssLoadedKb: Long,
    val inferenceResults: List<InferenceResult>,
    val ragLatencies: List<RagLatencyBreakdown>
)

// ── Benchmark helper ─────────────────────────────────────────────────

class BenchmarkHelper(private val app: LLMApplication) {

    /**
     * Run a single inference and return timing metrics.
     * Blocks until inference completes.
     */
    suspend fun measureInference(prompt: String): InferenceResult = withContext(Dispatchers.IO) {
        val ptr = app.sessionPtr
        if (ptr == 0L) throw IllegalStateException("Model not loaded")

        // Clear chat history before each measurement so prompt length
        // is the only variable — otherwise accumulated context inflates prefill time.
        NativeLib.clearHistory(ptr)

        var ttft = 0L
        var tokens = 0
        var done = false

        NativeLib.inferenceStream(ptr, prompt, null, object : InferenceCallback {
            override fun onToken(token: String) {}
            override fun onComplete(fullText: String) {
                ttft = NativeLib.getLastTTFT()
                tokens = NativeLib.getLastOutputTokens()
                val timing = NativeLib.getLastTiming()
                done = true
            }
            override fun onError(error: String) {
                done = true
            }
        })

        // Wait for completion
        while (!done) {
            kotlinx.coroutines.delay(50)
        }

        val timingStr = NativeLib.getLastTiming()
        val parts = timingStr.split(",").map { it.trim() }
        var prefillMs = 0L
        var decodeMs = 0L
        for (p in parts) {
            when {
                p.startsWith("prefill=") -> prefillMs = p.removePrefix("prefill=").removeSuffix(" ms").toLong()
                p.startsWith("decode=")  -> decodeMs  = p.removePrefix("decode=").removeSuffix(" ms").toLong()
            }
        }

        val tps = if (decodeMs > 0) tokens * 1000.0 / decodeMs else 0.0

        InferenceResult(
            promptLabel = "",
            ttftUs = ttft,
            outputTokens = tokens,
            prefillUs = prefillMs * 1000,
            decodeUs = decodeMs * 1000,
            tokensPerSec = tps
        )
    }

    /**
     * Measure model loading time. Switches to the given model path.
     */
    suspend fun measureLoadTime(modelPath: String): Long = withContext(Dispatchers.IO) {
        val elapsed = measureTimeMillis {
            val ok = app.createModelSession(modelPath)
            if (!ok) throw IllegalStateException("Failed to load model: $modelPath")
        }
        elapsed
    }

    /**
     * Get current process PSS memory in KB.
     */
    fun measureMemoryKb(): Long {
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        return memInfo.totalPss.toLong()
    }

    /**
     * Measure RAG embedding time for a query (JNI call only).
     */
    fun measureEmbeddingLatency(query: String): Long {
        val ptr = app.embeddingSessionPtr
        if (ptr == 0L) return -1
        return measureTimeMillis {
            NativeLib.computeEmbedding(ptr, query)
        }
    }

    /**
     * Measure full RAG pipeline with proper phase breakdown.
     *
     * Phase 1 — embedding: JNI computeEmbedding
     * Phase 2 — search:    DB read + cosine similarity + sort (excludes embedding)
     *
     * searchSimilar() does embedding internally, so searchFull - embed =
     * pure retrieval cost (DB + cosine + sort).
     */
    suspend fun measureRagPipeline(query: String, topK: Int = 3): RagLatencyBreakdown = withContext(Dispatchers.IO) {
        val ptr = app.embeddingSessionPtr
        if (ptr == 0L) return@withContext RagLatencyBreakdown(0, -1, -1, -1)

        val embedMs = measureTimeMillis {
            NativeLib.computeEmbedding(ptr, query)
        }

        val t0 = System.currentTimeMillis()
        val results = app.ragRepository.searchSimilar(query, topK)
        val searchFullMs = System.currentTimeMillis() - t0
        val searchPureMs = searchFullMs - embedMs // strip the embedding that searchSimilar does internally

        val chunkCount = app.database.ragDao().getAllChunks().size

        RagLatencyBreakdown(
            chunkCount = chunkCount,
            embeddingMs = embedMs,
            searchMs = if (searchPureMs > 0) searchPureMs else 0,
            totalMs = embedMs + (if (searchPureMs > 0) searchPureMs else 0)
        )
    }

    /**
     * Get current chunk count in DB.
     */
    suspend fun getChunkCount(): Int = withContext(Dispatchers.IO) {
        app.database.ragDao().getAllChunks().size
    }

    /**
     * Export a single report as CSV rows.
     */
    fun exportCsv(report: FullBenchmarkReport): String {
        val sb = StringBuilder()

        sb.appendLine("=== Benchmark Report ===")
        sb.appendLine("Device: ${report.deviceModel}")
        sb.appendLine("Config: ${report.config.label}")
        sb.appendLine("Eagle: ${report.config.eagleEnabled}")
        sb.appendLine("Model Path: ${report.config.modelPath}")
        sb.appendLine()
        sb.appendLine("--- Load Time (ms) ---")
        sb.appendLine("Runs: ${report.loadTimeMs.joinToString(", ")}")
        sb.appendLine("Average: ${report.loadTimeMs.average().toLong()} ms")
        sb.appendLine()
        sb.appendLine("--- Memory ---")
        sb.appendLine("Baseline PSS: ${report.pssBaselineKb} KB")
        sb.appendLine("Loaded PSS: ${report.pssLoadedKb} KB")
        sb.appendLine("Delta: ${report.pssLoadedKb - report.pssBaselineKb} KB")
        sb.appendLine()
        sb.appendLine("--- Inference ---")
        sb.appendLine("Prompt,TNFT (ms),Output Tokens,Prefill (ms),Decode (ms),Tokens/s")
        for (r in report.inferenceResults) {
            sb.appendLine("${r.promptLabel},${r.ttftUs / 1000},${r.outputTokens},${r.prefillUs / 1000},${r.decodeUs / 1000},%.2f".format(r.tokensPerSec))
        }
        sb.appendLine()
        sb.appendLine("--- RAG Latency (ms) ---")
        sb.appendLine("Chunks,Embedding,Search,Total")
        for (r in report.ragLatencies) {
            sb.appendLine("${r.chunkCount},${r.embeddingMs},${r.searchMs},${r.totalMs}")
        }

        return sb.toString()
    }

    /**
     * Save report to app's external files dir (pullable via adb).
     */
    fun saveReport(report: FullBenchmarkReport, filename: String): String {
        val csv = exportCsv(report)
        val dir = app.getExternalFilesDir(null) ?: app.filesDir
        val file = File(dir, filename)
        file.writeText(csv)
        return file.absolutePath
    }
}
