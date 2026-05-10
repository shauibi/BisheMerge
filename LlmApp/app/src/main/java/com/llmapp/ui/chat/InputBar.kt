package com.llmapp.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmapp.R

// 底部输入栏组件：文本输入、发送、附件（相机/相册）、语音按钮
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickImage: () -> Unit,
    isGenerating: Boolean,
    isModelLoaded: Boolean = true,
    // Voice input (press-and-hold)
    isListening: Boolean = false,
    onMicPress: (() -> Unit)? = null,
    onMicRelease: (() -> Unit)? = null,
    voicePartialText: String? = null,
    isVoiceInitializing: Boolean = false
) {
    val inputDisabled = !isModelLoaded || isGenerating || isListening
    val keyboardController = LocalSoftwareKeyboardController.current
    var showAttachmentMenu by remember { mutableStateOf(false) }

    // 麦克风按钮的缩放动画（按住时放大）
    val micScale by animateFloatAsState(
        targetValue = if (isListening) 1.15f else 1f,
        label = "mic_scale"
    )

    val inputBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

    Surface(
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            // 语音识别中间结果文本
            if (isListening && !voicePartialText.isNullOrBlank()) {
                Text(
                    text = voicePartialText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            // 附件菜单（相机/相册，可展开收起）
            AnimatedVisibility(
                visible = showAttachmentMenu,
                enter = scaleIn(),
                exit = scaleOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 相册按钮
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = { onPickImage(); showAttachmentMenu = false },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(inputBg)
                        ) {
                            Icon(
                                Icons.Default.PhotoLibrary,
                                contentDescription = stringResource(R.string.pick_image),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    // 相机按钮
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = { onTakePhoto(); showAttachmentMenu = false },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(inputBg)
                        ) {
                            Icon(
                                Icons.Default.AddAPhoto,
                                contentDescription = stringResource(R.string.take_photo),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            // 主输入行：附件按钮 + 语音按钮 + 文本输入框 + 发送按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // "+" 展开按钮（切换附件菜单显示）
                IconButton(
                    onClick = { showAttachmentMenu = !showAttachmentMenu },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        if (showAttachmentMenu) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = stringResource(R.string.pick_image),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // 麦克风按钮 — 按住录音
                if (onMicPress != null && onMicRelease != null) {
                    if (isVoiceInitializing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .then(if (isListening) Modifier.scale(micScale) else Modifier)
                                .pointerInput(isModelLoaded) {
                                    detectTapGestures(
                                        onPress = {
                                            if (isModelLoaded && !isVoiceInitializing) {
                                                onMicPress()
                                                tryAwaitRelease()
                                                onMicRelease()
                                            }
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = stringResource(R.string.voice_input),
                                tint = if (isListening) Color(0xFFE53935)
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // 文本输入框
                OutlinedTextField(
                    value = if (isListening) voicePartialText ?: "" else inputText,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .weight(1f)
                        .onKeyEvent {
                            if (it.key == Key.Enter && inputText.isNotBlank() && !inputDisabled) {
                                keyboardController?.hide()
                                onSend()
                                true
                            } else false
                        },
                    placeholder = {
                        Text(
                            stringResource(R.string.hint_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        )
                    },
                    maxLines = 4,
                    enabled = !inputDisabled,
                    readOnly = isListening,
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        unfocusedContainerColor = inputBg,
                        focusedContainerColor = inputBg
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium
                )

                // 发送按钮（有文本时显示，带动画）
                AnimatedVisibility(
                    visible = inputText.isNotBlank() && !inputDisabled,
                    enter = scaleIn(),
                    exit = scaleOut()
                ) {
                    IconButton(
                        onClick = {
                            keyboardController?.hide()
                            onSend()
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.send),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
