package com.llmapp.ui.chat

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.llmapp.LLMApplication
import com.llmapp.data.database.MessageEntity
import com.llmapp.data.database.SessionEntity
import com.llmapp.data.rag.ChunkWithDocInfo
import com.llmapp.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.mock

// ChatViewModel 单元测试（Mockito 模拟依赖）
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @get:Rule
    val instantTaskRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var app: LLMApplication
    private lateinit var chatRepository: ChatRepository
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        app = mock()
        chatRepository = mock()
        // 模拟 LLMApplication 返回的 repository
        `when`(app.chatRepository).thenReturn(chatRepository)
        // 模拟 sessionPtr 为 0（未加载模型时的默认值）
        `when`(app.sessionPtr).thenReturn(0L)
        `when`(app.isEmbeddingModelLoaded).thenReturn(mock())

        // 默认返回空会话列表
        `when`(chatRepository.getAllSessions()).thenReturn(emptyFlow())

        viewModel = ChatViewModel(app)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has default values`() {
        val state = viewModel.uiState.value
        assertEquals(-1L, state.currentSessionId)
        assertTrue(state.messages.isEmpty())
        assertFalse(state.isGenerating)
        assertFalse(state.ragEnabled)
        assertEquals(0, state.retrievedChunkCount)
        assertEquals(20, state.maxHistoryTurns)
    }

    @Test
    fun `setRagEnabled toggles state`() {
        viewModel.setRagEnabled(true)
        assertTrue(viewModel.uiState.value.ragEnabled)
        assertEquals(0, viewModel.uiState.value.retrievedChunkCount)

        viewModel.setRagEnabled(false)
        assertFalse(viewModel.uiState.value.ragEnabled)
    }

    @Test
    fun `setMaxHistoryTurns updates state`() {
        viewModel.setMaxHistoryTurns(10)
        assertEquals(10, viewModel.uiState.value.maxHistoryTurns)

        viewModel.setMaxHistoryTurns(50)
        assertEquals(50, viewModel.uiState.value.maxHistoryTurns)
    }

    @Test
    fun `clearError clears error state`() = runTest {
        // 通过反射设置 error（没有直接的 setError 方法）
        val state = viewModel.uiState.value
        val field = ChatUiState::class.java.getDeclaredField("error")
        field.isAccessible = true
        field.set(state, "test error")

        viewModel.clearError()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `clearSelectedImage clears image path`() {
        viewModel.setSelectedImagePath("/path/to/image.jpg")
        assertEquals("/path/to/image.jpg", viewModel.uiState.value.selectedImagePath)

        viewModel.clearSelectedImage()
        assertNull(viewModel.uiState.value.selectedImagePath)
    }

    @Test
    fun `setSelectedImagePath updates state`() {
        viewModel.setSelectedImagePath("/test/photo.jpg")
        assertEquals("/test/photo.jpg", viewModel.uiState.value.selectedImagePath)
    }

    @Test
    fun `cancelActiveInference resets generating state`() {
        // 先修改 state 模拟生成中
        val state = viewModel.uiState.value
        val genField = ChatUiState::class.java.getDeclaredField("isGenerating")
        genField.isAccessible = true
        genField.set(state, true)

        viewModel.cancelActiveInference()
        val newState = viewModel.uiState.value
        assertFalse(newState.isGenerating)
        assertEquals(-1L, newState.generatingMessageId)
        assertTrue(newState.streamingContents.isEmpty())
    }

    @Test
    fun `ChatUiState default maxHistoryTurns is 20`() {
        val state = ChatUiState()
        assertEquals(20, state.maxHistoryTurns)
    }

    @Test
    fun `ChatUiState retrievedChunks is empty by default`() {
        val state = ChatUiState()
        assertTrue(state.retrievedChunks.isEmpty())
    }
}
