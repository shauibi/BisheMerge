package com.llmapp.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.llmapp.R
import com.llmapp.data.database.MessageEntity
import com.llmapp.data.rag.ChunkWithDocInfo
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import java.io.File

// 单条消息气泡组件，支持 Markdown 渲染、长按菜单、删除确认和 RAG 来源展示
@Composable
fun MessageBubble(
    message: MessageEntity,
    displayText: String,
    onDelete: () -> Unit,
    chunks: List<ChunkWithDocInfo> = emptyList()
) {
    val isUser = message.role == MessageEntity.ROLE_USER
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val actualContent = if (message.content.isNotEmpty()) message.content else displayText

    // 用户气泡半透明 primary 色，助理气泡 surface 色
    val bubbleColor = if (isUser)
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    else
        MaterialTheme.colorScheme.surface

    val contentColor = if (isUser)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurface

    // 用户气泡右下方圆角，助理左下方圆角，营造对话方向感
    val bubbleShape = RoundedCornerShape(
        topStart = 16.dp, topEnd = 16.dp,
        bottomStart = if (isUser) 16.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 16.dp
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        // 助理气泡左侧的 accent 竖线
        if (!isUser) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    .align(Alignment.Top)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Box {
                val triggerMenu: () -> Unit = { showMenu = true }

                Card(
                    colors = CardDefaults.cardColors(containerColor = bubbleColor),
                    shape = bubbleShape
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        if (displayText.isBlank() && message.content.isEmpty()) {
                            // 流式生成中，显示加载指示器
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(18.dp)
                                    .padding(4.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            val textColor = contentColor
                            // 使用 AndroidView + Markwon 渲染 Markdown
                            AndroidView(
                                factory = { ctx ->
                                    TextView(ctx).apply {
                                        textSize = 15f
                                        setTextColor(textColor.hashCode())
                                        setTextIsSelectable(false)
                                        setOnLongClickListener {
                                            triggerMenu()
                                            true
                                        }
                                        tag = Markwon.builder(ctx)
                                            .usePlugin(TablePlugin.create(ctx))
                                            .usePlugin(StrikethroughPlugin.create())
                                            .usePlugin(HtmlPlugin.create())
                                            .build()
                                    }
                                },
                                update = { textView ->
                                    val currentText = textView.text.toString()
                                    if (currentText != displayText) {
                                        val markwon = textView.tag as Markwon
                                        markwon.setMarkdown(textView, displayText)
                                        textView.setTextColor(textColor.hashCode())
                                    }
                                }
                            )
                        }
                    }
                }

                // 长按弹出的上下文菜单：复制 / 分享 / 删除
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    offset = DpOffset(0.dp, 0.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy_message)) },
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("message", actualContent))
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share_message)) },
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, actualContent)
                            }
                            context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_message)))
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete_message), color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            showDeleteConfirm = true
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, contentDescription = null,
                                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        }
                    )
                }
            }

            // 助理回复的 RAG 来源（展开后显示检索到的文档片段）
            if (!isUser && chunks.isNotEmpty()) {
                SourcesSection(chunks = chunks)
            }

            // 消息底部的时间戳
            Text(
                text = formatTimestamp(message.timestamp),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }

    // 删除确认对话框
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_message)) },
            text = { Text(stringResource(R.string.confirm_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteConfirm = false
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

// 用户发送的图片缩略图预览（可点击全屏）
@Composable
fun ImagePreview(imagePath: String) {
    val file = File(imagePath)
    var showFullscreen by remember { mutableStateOf(false) }

    if (file.exists()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.End
        ) {
            AsyncImage(
                model = file,
                contentDescription = stringResource(R.string.user_image),
                modifier = Modifier
                    .width(180.dp)
                    .height(130.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showFullscreen = true }
            )
        }
    }

    if (showFullscreen) {
        FullscreenImageViewer(
            imageFile = file,
            onDismiss = { showFullscreen = false }
        )
    }
}

// 全屏图片查看器，支持双指缩放和平移
@Composable
fun FullscreenImageViewer(imageFile: File, onDismiss: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = imageFile,
                contentDescription = stringResource(R.string.user_image),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offset.x, translationY = offset.y
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.5f, 5f)
                            offset += pan
                        }
                    }
            )
            IconButton(
                onClick = {
                    scale = 1f
                    offset = Offset.Zero
                    onDismiss()
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.cancel),
                    tint = Color.White
                )
            }
        }
    }
}

// RAG 检索来源展示组件：可折叠，显示文档名、相似度和片段内容
@Composable
fun SourcesSection(chunks: List<ChunkWithDocInfo>) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(top = 4.dp)) {
        // 可点击的折叠标题行
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Storage,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "${stringResource(R.string.rag_sources)} (${chunks.size})",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
        }

        // 展开后的文档片段卡片列表
        if (expanded) {
            chunks.forEach { chunk ->
                Card(
                    modifier = Modifier.padding(top = 3.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = chunk.documentName,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = stringResource(R.string.rag_similarity, (chunk.score * 100).toInt()),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = chunk.chunkContent,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// 格式化时间戳为 HH:mm 格式
private fun formatTimestamp(timestamp: Long): String {
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = timestamp
    return String.format("%02d:%02d", calendar.get(java.util.Calendar.HOUR_OF_DAY), calendar.get(java.util.Calendar.MINUTE))
}
