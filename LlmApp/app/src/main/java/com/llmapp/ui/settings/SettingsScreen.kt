package com.llmapp.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmapp.R
import com.llmapp.benchmark.FullBenchmarkReport
import com.llmapp.benchmark.InferenceResult

// 模型设置主界面 Compose
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onAddModelDir: () -> Unit
) {
    val availableModels by viewModel.availableModels.collectAsState()
    val selectedModelPath by viewModel.selectedModelPath.collectAsState()
    val isModelLoaded by viewModel.isModelLoaded.collectAsState()
    val isModelLoading by viewModel.isModelLoading.collectAsState()
    val nativeVersion by viewModel.nativeVersion.collectAsState()
    val benchmarkReportA by viewModel.benchmarkReportA.collectAsState()
    val benchmarkReportB by viewModel.benchmarkReportB.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.tab_settings),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 当前模型状态卡片（显示加载状态和版本号）
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isModelLoaded)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isModelLoaded)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (isModelLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        if (isModelLoaded) Icons.Default.CheckCircle else Icons.Default.Info,
                                        contentDescription = null,
                                        tint = if (isModelLoaded) MaterialTheme.colorScheme.primary
                                               else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isModelLoading) stringResource(R.string.loading_model)
                                       else if (isModelLoaded && selectedModelPath != null)
                                           selectedModelPath!!.substringAfterLast('/')
                                       else stringResource(R.string.no_model_selected),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (isModelLoading) ""
                                       else if (isModelLoaded) stringResource(R.string.status_loaded).trim()
                                       else stringResource(R.string.status_not_loaded).trim(),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        // 显示 native 库版本号
                        Text(
                            text = nativeVersion,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        )
                    }
                }
            }

            // 添加模型目录按钮
            item {
                OutlinedButton(
                    onClick = onAddModelDir,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        Icons.Outlined.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.add_model_dir))
                }
            }

            // 模型列表分区标题
            item {
                Text(
                    stringResource(R.string.model_variant),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // 模型列表（空时显示提示文字）
            if (availableModels.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.no_model_selected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        )
                    }
                }
            } else {
                items(availableModels, key = { it.second }) { (name, path) ->
                    val isSelected = path == selectedModelPath
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setSelectedModel(path) },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else
                                MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (isSelected && isModelLoaded) stringResource(R.string.status_loaded).trim()
                                           else "",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            // ── Benchmark section ──
            item {
                val benchmarkStatus by viewModel.benchmarkStatus.collectAsState()
                val benchmarkRunning by viewModel.benchmarkRunning.collectAsState()
                val benchmarkProgress by viewModel.benchmarkProgress.collectAsState()

                var modelPathA by remember { mutableStateOf(selectedModelPath ?: "") }
                var modelPathB by remember { mutableStateOf("") }
                var expandedA by remember { mutableStateOf(false) }
                var expandedB by remember { mutableStateOf(false) }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            "Benchmark",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // Model A (no Eagle) dropdown
                        Text("Config A (Eagle OFF):", fontSize = 12.sp)
                        Box {
                            OutlinedTextField(
                                value = modelPathA.let { if (it.isNotEmpty()) it.substringAfterLast('/') else "Select model..." },
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    IconButton(onClick = { expandedA = true }) {
                                        Icon(Icons.Default.ArrowDropDown, null)
                                    }
                                }
                            )
                            DropdownMenu(expanded = expandedA, onDismissRequest = { expandedA = false }) {
                                availableModels.forEach { (name, path) ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            modelPathA = path
                                            expandedA = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Model B (Eagle) dropdown
                        Text("Config B (Eagle ON):", fontSize = 12.sp)
                        Box {
                            OutlinedTextField(
                                value = modelPathB.let { if (it.isNotEmpty()) it.substringAfterLast('/') else "Select model..." },
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    IconButton(onClick = { expandedB = true }) {
                                        Icon(Icons.Default.ArrowDropDown, null)
                                    }
                                }
                            )
                            DropdownMenu(expanded = expandedB, onDismissRequest = { expandedB = false }) {
                                availableModels.forEach { (name, path) ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            modelPathB = path
                                            expandedB = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Run button
                        Button(
                            onClick = { viewModel.runBenchmark(modelPathA, modelPathB) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = modelPathA.isNotEmpty() && modelPathB.isNotEmpty() && !benchmarkRunning,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (benchmarkRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(if (benchmarkRunning) "Running..." else "Run Benchmark")
                        }

                        // Progress bar and status
                        if (benchmarkStatus.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            if (benchmarkRunning && benchmarkProgress > 0f) {
                                LinearProgressIndicator(
                                    progress = { benchmarkProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "${(benchmarkProgress * 100).toInt()}% (${(benchmarkProgress * 24).toInt()}/24 inference rounds)",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                            Text(
                                benchmarkStatus,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            // ── Benchmark results table ──
            if (benchmarkReportA != null && benchmarkReportB != null) {
                item {
                    BenchmarkResultsCard(benchmarkReportA!!, benchmarkReportB!!)
                }
            }

            // 底部导航栏留白
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

// ── Benchmark results table composables ──────────────────────────────────

@Composable
fun BenchmarkResultsCard(reportA: FullBenchmarkReport, reportB: FullBenchmarkReport) {
    val avgTpsA = reportA.inferenceResults.map { it.tokensPerSec }.average()
    val avgTpsB = reportB.inferenceResults.map { it.tokensPerSec }.average()
    val avgTtftA = reportA.inferenceResults.map { it.ttftUs / 1000.0 }.average()
    val avgTtftB = reportB.inferenceResults.map { it.ttftUs / 1000.0 }.average()
    val memMbA = (reportA.pssLoadedKb - reportA.pssBaselineKb) / 1024.0
    val memMbB = (reportB.pssLoadedKb - reportB.pssBaselineKb) / 1024.0
    val loadSecA = reportA.loadTimeMs.average() / 1000.0
    val loadSecB = reportB.loadTimeMs.average() / 1000.0

    val prompts = reportA.inferenceResults.map { it.promptLabel }.distinct()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "Benchmark Results",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(10.dp))

            // ── Summary table ──
            Text("Summary", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            BenchmarkTableHeader(listOf("指标", "Eagle OFF", "Eagle ON", "提升"))
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            BenchmarkSummaryRow("推理速度 (tokens/s)", avgTpsA, avgTpsB, "%.1f".format(avgTpsA), "%.1f".format(avgTpsB), higherIsBetter = true)
            BenchmarkSummaryRow("首 token 延迟 (ms)", avgTtftA, avgTtftB, "%.0f".format(avgTtftA), "%.0f".format(avgTtftB), higherIsBetter = false)
            BenchmarkSummaryRow("模型内存占用 (MB)", memMbA, memMbB, "%.1f".format(memMbA), "%.1f".format(memMbB), higherIsBetter = false)
            BenchmarkSummaryRow("模型加载时间 (s)", loadSecA, loadSecB, "%.1f".format(loadSecA), "%.1f".format(loadSecB), higherIsBetter = false)

            // RAG timing (only if embedding model was loaded)
            if (reportA.ragLatencies.isNotEmpty() && reportB.ragLatencies.isNotEmpty()) {
                val ragA = reportA.ragLatencies.first()
                val ragB = reportB.ragLatencies.first()
                BenchmarkSummaryRow("Query embedding (ms)", ragA.embeddingMs.toDouble(), ragB.embeddingMs.toDouble(), ragA.embeddingMs.toString(), ragB.embeddingMs.toString(), higherIsBetter = false)
                BenchmarkSummaryRow("全库检索 (ms)", ragA.searchMs.toDouble(), ragB.searchMs.toDouble(), ragA.searchMs.toString(), ragB.searchMs.toString(), higherIsBetter = false)
                BenchmarkSummaryRow("RAG 总耗时 (ms)", ragA.totalMs.toDouble(), ragB.totalMs.toDouble(), ragA.totalMs.toString(), ragB.totalMs.toString(), higherIsBetter = false)
            }

            if (prompts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Per-Prompt Breakdown", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                BenchmarkTableHeader(listOf("Prompt", "OFF t/s", "ON t/s", "OFF TTFT", "ON TTFT"))
                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                for (label in prompts) {
                    val ra = reportA.inferenceResults.filter { it.promptLabel == label }
                    val rb = reportB.inferenceResults.filter { it.promptLabel == label }
                    val tpsA = ra.map { it.tokensPerSec }.average()
                    val tpsB = rb.map { it.tokensPerSec }.average()
                    val ttftA = ra.map { it.ttftUs / 1000.0 }.average()
                    val ttftB = rb.map { it.ttftUs / 1000.0 }.average()
                    BenchmarkPromptRow(
                        label,
                        "%.1f".format(tpsA), "%.1f".format(tpsB),
                        "%.0f".format(ttftA), "%.0f".format(ttftB)
                    )
                }
            }
        }
    }
}

@Composable
fun BenchmarkTableHeader(columns: List<String>) {
    Row(modifier = Modifier.fillMaxWidth()) {
        columns.forEachIndexed { i, col ->
            Text(
                col,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f),
                textAlign = if (i == 0) TextAlign.Start else TextAlign.End
            )
        }
    }
    Spacer(modifier = Modifier.height(2.dp))
}

fun improvement(a: Double, b: Double, higherIsBetter: Boolean): String {
    if (a == 0.0) return "-"
    val pct = if (higherIsBetter) ((b - a) / a) * 100 else ((a - b) / a) * 100
    return if (pct >= 0) "+%.1f%%".format(pct) else "%.1f%%".format(pct)
}

@Composable
fun BenchmarkSummaryRow(
    label: String,
    valueA: Double,
    valueB: Double,
    displayA: String,
    displayB: String,
    higherIsBetter: Boolean
) {
    val imp = improvement(valueA, valueB, higherIsBetter)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(displayA, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(displayB, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(
            imp,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = when {
                imp == "-" -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                imp.startsWith("+") -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.error
            },
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun BenchmarkPromptRow(
    label: String, tpsA: String, tpsB: String, ttftA: String, ttftB: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(tpsA, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(tpsB, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(ttftA, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(ttftB, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}
