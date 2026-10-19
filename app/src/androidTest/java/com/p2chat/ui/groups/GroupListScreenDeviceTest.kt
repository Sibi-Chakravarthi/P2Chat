package com.p2chat.ui.groups

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.p2chat.ble.BleState
import com.p2chat.crypto.group.GroupManager
import com.p2chat.crypto.keys.EcProvider
import com.p2chat.crypto.keys.IdentityManager
import com.p2chat.crypto.keys.KeyVault
import com.p2chat.data.db.AppDatabase
import com.p2chat.data.identity.LocalIdentity
import com.p2chat.ui.theme.P2ChatTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupListScreenDeviceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var database: AppDatabase
    private lateinit var identityManager: IdentityManager
    private lateinit var groupManager: GroupManager
    private lateinit var viewModel: GroupListViewModel

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = AppDatabase.getInstance(context)
        val keyVault = KeyVault()
        identityManager = IdentityManager(database.localIdentityDao(), keyVault)
        groupManager = GroupManager(database.groupDao(), database.rosterMemberDao(), keyVault)

        runBlocking {
            database.clearAllTables()

            // Seed local identity
            val keyPair = EcProvider.get().generateKeyPair()
            val pubBytes = EcProvider.encodeUncompressedPublicKey(keyPair.public)
            val wrappedPriv = keyVault.wrapPrivateKey(keyPair.private)
            database.localIdentityDao().insertIdentity(
                LocalIdentity(
                    senderFp = 0x1122334455667788L,
                    alias = "DeviceA_Operator",
                    signingPub = pubBytes,
                    ecdhPub = pubBytes,
                    wrappedSigningPriv = wrappedPriv,
                    wrappedEcdhPriv = wrappedPriv
                )
            )
        }

        viewModel = GroupListViewModel(
            groupDao = database.groupDao(),
            rosterMemberDao = database.rosterMemberDao(),
            messageDao = database.messageDao(),
            groupManager = groupManager,
            identityManager = identityManager,
            initialBleState = BleState.READY
        )
    }

    @After
    fun tearDown() {
        runBlocking {
            database.clearAllTables()
        }
    }

    @Test
    fun testEmptyStateOnFirstEntry_AndCreateGroupDisplaysEpoch1() {
        composeTestRule.setContent {
            P2ChatTheme {
                GroupListScreen(
                    viewModel = viewModel,
                    onGroupClick = {}
                )
            }
        }

        // 1. Verify empty state displayed
        composeTestRule.onNodeWithText("No Conversations").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Create Group").assertIsDisplayed()

        // 2. Open Create Group Dialog
        composeTestRule.onNodeWithContentDescription("Create Group").performClick()
        composeTestRule.onNodeWithContentDescription("Create New Group Dialog").assertIsDisplayed()

        // 3. Enter group name and submit
        composeTestRule.onNodeWithContentDescription("Group name input field")
            .performTextInput("Ops Squad Alpha")
        composeTestRule.onNodeWithContentDescription("Confirm group creation").performClick()

        // 4. Verify group created and displays Epoch 1 badge
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Ops Squad Alpha").assertIsDisplayed()
        composeTestRule.onNodeWithText("EPOCH 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 / 16 Members").assertIsDisplayed()
    }

    @Test
    fun testSimulatingBluetoothOff_ShowsOneTapFixBanner() {
        viewModel.setBleState(BleState.BLUETOOTH_OFF)

        var fixClicked = false
        composeTestRule.setContent {
            P2ChatTheme {
                GroupListScreen(
                    viewModel = viewModel,
                    onGroupClick = {},
                    onEnableBluetoothClick = { fixClicked = true }
                )
            }
        }

        // Verify ID-6 banner is displayed
        composeTestRule.onNodeWithContentDescription("One-tap fix: Enable Bluetooth")
            .assertIsDisplayed()
            .performClick()

        assert(fixClicked) { "One-tap fix click handler must be invoked" }
    }

    @Test
    fun testInteractiveElementsHaveContentDescriptions() {
        composeTestRule.setContent {
            P2ChatTheme {
                GroupListScreen(
                    viewModel = viewModel,
                    onGroupClick = {}
                )
            }
        }

        // Top bar buttons
        composeTestRule.onNodeWithContentDescription("Accept Invite QR code scanner").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Create New Group").assertIsDisplayed()
    }
}
