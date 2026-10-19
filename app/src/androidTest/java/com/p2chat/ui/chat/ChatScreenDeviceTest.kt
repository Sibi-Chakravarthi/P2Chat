package com.p2chat.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.p2chat.ai.BlockSimilarService
import com.p2chat.ai.model.SentenceEmbedder
import com.p2chat.ai.score.Scorer
import com.p2chat.ai.score.VectorStore
import com.p2chat.ai.text.WordPieceTokenizer
import com.p2chat.crypto.keys.EcProvider
import com.p2chat.crypto.keys.IdentityManager
import com.p2chat.crypto.keys.KeyVault
import com.p2chat.data.db.AppDatabase
import com.p2chat.data.group.GroupEntity
import com.p2chat.data.group.RosterMember
import com.p2chat.data.identity.LocalIdentity
import com.p2chat.data.message.EncryptedBody
import com.p2chat.data.message.MessageEntity
import com.p2chat.pipeline.SendPipeline
import com.p2chat.ui.theme.P2ChatTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatScreenDeviceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var database: AppDatabase
    private lateinit var identityManager: IdentityManager
    private lateinit var sendPipeline: SendPipeline
    private lateinit var blockSimilarService: BlockSimilarService
    private lateinit var viewModel: ChatViewModel

    private val groupId = 777L
    private val selfFp = 0x1122334455667788L
    private val peerFp = 0x9988776655443322L

    private class MockEmbedder : SentenceEmbedder {
        override val isAvailable: Boolean = true
        override val lastInferenceLatencyMs: Long = 1L
        override suspend fun embed(inputIds: IntArray, attentionMask: IntArray): FloatArray =
            FloatArray(384) { 0.1f }
        override fun reload() {}
        override fun close() {}
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = AppDatabase.getInstance(context)
        val keyVault = KeyVault()
        identityManager = IdentityManager(database.localIdentityDao(), keyVault)

        runBlocking {
            database.clearAllTables()

            val selfKey = EcProvider.get().generateKeyPair()
            val selfPub = EcProvider.encodeUncompressedPublicKey(selfKey.public)
            val wrappedPriv = keyVault.wrapPrivateKey(selfKey.private)

            database.localIdentityDao().insertIdentity(
                LocalIdentity(
                    senderFp = selfFp,
                    alias = "Operator_Self",
                    signingPub = selfPub,
                    ecdhPub = selfPub,
                    wrappedSigningPriv = wrappedPriv,
                    wrappedEcdhPriv = wrappedPriv
                )
            )

            database.groupDao().insertGroup(
                GroupEntity(
                    groupId = groupId,
                    name = "Tactical Unit Bravo",
                    myRole = "ADMIN",
                    currentEpoch = 1,
                    wrappedCurrentKey = keyVault.wrap(ByteArray(32) { 0x55 }),
                    rosterVersion = 1
                )
            )

            database.rosterMemberDao().insertOrUpdate(
                RosterMember(
                    groupId = groupId,
                    senderFp = peerFp,
                    signingPub = selfPub,
                    ecdhPub = selfPub,
                    alias = "Scout_Peer",
                    revoked = false
                )
            )
        }

        sendPipeline = SendPipeline(
            groupDao = database.groupDao(),
            localIdentityDao = database.localIdentityDao(),
            messageDao = database.messageDao(),
            seenMessageDao = database.seenMessageDao(),
            bufferedPacketDao = database.bufferedPacketDao(),
            keyVault = keyVault
        )

        val store = VectorStore(FloatArray(384) { 0f }, emptyList())
        val scorer = Scorer(store, database.blockedVectorDao())
        val tokenizer = WordPieceTokenizer(mapOf("[PAD]" to 0, "[UNK]" to 100, "[CLS]" to 101, "[SEP]" to 102))

        blockSimilarService = BlockSimilarService(
            messageDao = database.messageDao(),
            quarantineDao = database.quarantineDao(),
            blockedVectorDao = database.blockedVectorDao(),
            embedder = MockEmbedder(),
            tokenizer = tokenizer,
            scorer = scorer
        )

        viewModel = ChatViewModel(
            groupId = groupId,
            groupDao = database.groupDao(),
            rosterMemberDao = database.rosterMemberDao(),
            peerDao = database.peerDao(),
            localIdentityDao = database.localIdentityDao(),
            messageDao = database.messageDao(),
            sendPipeline = sendPipeline,
            blockSimilarService = blockSimilarService
        )
    }

    @After
    fun tearDown() {
        runBlocking {
            database.clearAllTables()
        }
    }

    @Test
    fun testTier3RendersClearly_AndTier2RendersBlurredUntilTapped() {
        runBlocking {
            // Tier 3 Clean message
            database.messageDao().insertMessage(
                MessageEntity(
                    msgId = 1L,
                    groupId = groupId,
                    senderFp = peerFp,
                    keyEpoch = 1,
                    sentAtMs = 1000L,
                    receivedAtMs = 1001L,
                    body = EncryptedBody.of("Safe perimeter status"),
                    sigOk = true,
                    tier = 3,
                    score = 0.15f,
                    revealed = true
                )
            )

            // Tier 2 Warn message (initially unrevealed)
            database.messageDao().insertMessage(
                MessageEntity(
                    msgId = 2L,
                    groupId = groupId,
                    senderFp = peerFp,
                    keyEpoch = 1,
                    sentAtMs = 2000L,
                    receivedAtMs = 2001L,
                    body = EncryptedBody.of("Aggressive hostile telemetry"),
                    sigOk = true,
                    tier = 2,
                    score = 0.76f,
                    revealed = false
                )
            )
        }

        composeTestRule.setContent {
            P2ChatTheme {
                ChatScreen(
                    viewModel = viewModel,
                    onBackClick = {},
                    onAdminClick = {}
                )
            }
        }

        // 1. Tier 3 renders clearly
        composeTestRule.onNodeWithText("Safe perimeter status").assertIsDisplayed()

        // 2. Tier 2 initially shows tap-to-reveal button
        val tapToRevealText = "Flagged by on-device AI (s = 0.76) — Tap to reveal"
        composeTestRule.onNodeWithText(tapToRevealText).assertIsDisplayed()

        // 3. Tap to reveal
        composeTestRule.onNodeWithText(tapToRevealText).performClick()
        composeTestRule.waitForIdle()

        // 4. Content is now unmasked and displayed
        composeTestRule.onNodeWithText("Aggressive hostile telemetry").assertIsDisplayed()

        // 5. Verify revealed = true is persisted in Room database
        runBlocking {
            val updated = database.messageDao().getMessage(2L)
            assertTrue("Database must persist revealed = true", updated?.revealed == true)
        }
    }

    @Test
    fun testLongPressBlockSimilarAddsRowToUserBlockedVectors() {
        runBlocking {
            database.messageDao().insertMessage(
                MessageEntity(
                    msgId = 30L,
                    groupId = groupId,
                    senderFp = peerFp,
                    keyEpoch = 1,
                    sentAtMs = 1500L,
                    receivedAtMs = 1501L,
                    body = EncryptedBody.of("Threatening target vector"),
                    sigOk = true,
                    tier = 3,
                    score = 0.20f,
                    revealed = true
                )
            )
        }

        composeTestRule.setContent {
            P2ChatTheme {
                ChatScreen(
                    viewModel = viewModel,
                    onBackClick = {},
                    onAdminClick = {}
                )
            }
        }

        // Long press message bubble to open context menu
        composeTestRule.onNodeWithText("Threatening target vector")
            .performTouchInput { longClick() }

        composeTestRule.waitForIdle()

        // Tap "Block Similar Messages"
        composeTestRule.onNodeWithText("Block Similar Messages")
            .assertIsDisplayed()
            .performClick()

        composeTestRule.waitForIdle()

        // Verify a row was inserted into user_blocked_vectors (AI-9, AI-10)
        runBlocking {
            val blockedCount = database.blockedVectorDao().count()
            assertEquals("One vector must be added to user_blocked_vectors", 1, blockedCount)
        }
    }

    @Test
    fun testComposerByteLimitValidation_351BytesOr88FourByteEmojisDisablesSend() {
        composeTestRule.setContent {
            P2ChatTheme {
                ChatScreen(
                    viewModel = viewModel,
                    onBackClick = {},
                    onAdminClick = {}
                )
            }
        }

        val sendButton = composeTestRule.onNodeWithContentDescription("Transmit packet over mesh")

        // 1. Initial state: blank, disabled
        sendButton.assertIsNotEnabled()

        // 2. 351 ASCII characters (351 UTF-8 bytes > 350 B limit)
        val text351 = "A".repeat(351)
        composeTestRule.onNodeWithContentDescription("Encrypted message input field")
            .performTextInput(text351)

        sendButton.assertIsNotEnabled()

        // 3. Clear and test 88 4-byte emojis (88 * 4 = 352 bytes > 350 B limit)
        // 🛡️ (shield) or 🚀 (rocket) is 4 UTF-8 bytes: F0 9F 9A 80
        val emojis88 = "🚀".repeat(88)
        assertEquals(352, emojis88.toByteArray(Charsets.UTF_8).size)

        // Clear text field by recreating or setting new text
        composeTestRule.onNodeWithContentDescription("Encrypted message input field")
            .performTextInput(emojis88)

        sendButton.assertIsNotEnabled()
    }
}
