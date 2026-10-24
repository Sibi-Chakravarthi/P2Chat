package com.p2chat.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.p2chat.ai.BlockSimilarService
import com.p2chat.ai.FilterService
import com.p2chat.ai.FilterSettingsRepository
import com.p2chat.ai.QuarantineRepository
import com.p2chat.ai.model.TfliteEmbedder
import com.p2chat.ai.score.Scorer
import com.p2chat.ai.score.VectorStore
import com.p2chat.ai.text.WordPieceTokenizer
import com.p2chat.bench.BenchRunner
import com.p2chat.ble.BlePermissions
import com.p2chat.ble.BleState
import com.p2chat.ble.DefaultMeshScanner
import com.p2chat.ble.MeshService
import com.p2chat.crypto.group.GroupManager
import com.p2chat.crypto.keys.IdentityManager
import com.p2chat.crypto.keys.KeyVault
import com.p2chat.crypto.rotation.RotationService
import com.p2chat.data.db.AppDatabase
import com.p2chat.pipeline.ControlPacketHandler
import com.p2chat.pipeline.ReceivePipeline
import com.p2chat.pipeline.SendPipeline
import com.p2chat.ui.admin.GroupAdminScreen
import com.p2chat.ui.admin.GroupAdminViewModel
import com.p2chat.ui.aifilter.AiFilterScreen
import com.p2chat.ui.aifilter.AiFilterViewModel
import com.p2chat.ui.bench.BenchScreen
import com.p2chat.ui.bench.BenchViewModel
import com.p2chat.ui.chat.ChatScreen
import com.p2chat.ui.chat.ChatViewModel
import com.p2chat.ui.common.LoadingState
import com.p2chat.ui.groups.GroupListScreen
import com.p2chat.ui.groups.GroupListViewModel
import com.p2chat.ui.nav.NavigationDestination
import com.p2chat.ui.onboarding.OnboardingScreen
import com.p2chat.ui.onboarding.OnboardingViewModel
import com.p2chat.ui.radar.RadarScreen
import com.p2chat.ui.radar.RadarViewModel
import com.p2chat.ui.theme.CanvasBackground
import com.p2chat.ui.theme.HairlineBorder
import com.p2chat.ui.theme.NavUnselected
import com.p2chat.ui.theme.P2ChatTheme
import com.p2chat.ui.theme.StarkPrimary
import com.p2chat.util.Clock
import com.p2chat.util.SecureLog

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var requestBlePermissionsAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val database = AppDatabase.getInstance(this)
        val keyVault = KeyVault()
        val identityManager = IdentityManager(database.localIdentityDao(), keyVault)
        val peerDao = database.peerDao()
        val groupDao = database.groupDao()
        val rosterMemberDao = database.rosterMemberDao()
        val messageDao = database.messageDao()
        val quarantineDao = database.quarantineDao()
        val blockedVectorDao = database.blockedVectorDao()
        val benchDao = database.benchDao()
        val bufferedPacketDao = database.bufferedPacketDao()
        val seenMessageDao = database.seenMessageDao()
        val settingDao = database.settingDao()

        val groupManager = GroupManager(groupDao, rosterMemberDao, keyVault)
        val onboardingViewModel = OnboardingViewModel(identityManager, peerDao, groupManager)

        val initialBleState = BlePermissions.getBleState(this)
        val groupListViewModel = GroupListViewModel(
            groupDao = groupDao,
            rosterMemberDao = rosterMemberDao,
            messageDao = messageDao,
            groupManager = groupManager,
            identityManager = identityManager,
            initialBleState = initialBleState
        )

        val meshTransport = MeshService.sharedTransport
        val sendPipeline = SendPipeline(
            groupDao = groupDao,
            localIdentityDao = database.localIdentityDao(),
            messageDao = messageDao,
            seenMessageDao = seenMessageDao,
            bufferedPacketDao = bufferedPacketDao,
            keyVault = keyVault,
            meshTransport = meshTransport
        )

        val filterService = try {
            FilterService.create(this, database)
        } catch (t: Throwable) {
            SecureLog.w(TAG, "FilterService init failed: ${t.message}")
            FilterService(null, null, null, settingDao)
        }

        val quarantineRepo = QuarantineRepository(quarantineDao, messageDao)
        val filterSettingsRepo = FilterSettingsRepository(settingDao)
        val aiFilterViewModel = AiFilterViewModel(
            filterService = filterService,
            settingsRepository = filterSettingsRepo,
            quarantineRepository = quarantineRepo,
            blockedVectorDao = blockedVectorDao
        )

        val rotationService = RotationService(groupDao, rosterMemberDao, keyVault)

        val blockSimilarService = try {
            val embedder = TfliteEmbedder.fromAsset(this)
            val tokenizer = assets.open("vocab.txt").use { WordPieceTokenizer.fromInputStream(it) }
            val store = VectorStore.fromAssets(this)
            val scorer = Scorer(store, blockedVectorDao)
            BlockSimilarService(messageDao, quarantineDao, blockedVectorDao, embedder, tokenizer, scorer)
        } catch (t: Throwable) {
            SecureLog.w(TAG, "BlockSimilarService optional init skipped: ${t.message}")
            null
        }

        val controlPacketHandler = ControlPacketHandler(
            groupDao = groupDao,
            rosterMemberDao = rosterMemberDao,
            onEpochRotated = { gid, epoch ->
                SecureLog.d(TAG, "Epoch rotated for group $gid to $epoch")
            },
            identityManager = identityManager,
            peerDao = peerDao,
            groupManager = groupManager,
            messageDao = messageDao
        )

        val receivePipeline = ReceivePipeline(
            groupDao = groupDao,
            rosterMemberDao = rosterMemberDao,
            messageDao = messageDao,
            quarantineDao = quarantineDao,
            keyVault = keyVault,
            filterService = filterService,
            packetRouter = com.p2chat.ble.DefaultPacketRouter(seenMessageDao, bufferedPacketDao),
            controlPacketHandler = controlPacketHandler,
            clock = Clock.System,
            peerDao = peerDao
        )
        MeshService.sharedReceivePipeline = receivePipeline

        val benchRunner = BenchRunner(
            benchDao = benchDao,
            meshTransport = meshTransport,
            identityManager = identityManager,
            filterService = filterService
        )
        val benchViewModel = BenchViewModel(benchRunner, benchDao)

        val connectionPool = MeshService.sharedConnectionPool
        val meshController = MeshService.sharedMeshController
        val meshScanner = MeshService.sharedScanner ?: DefaultMeshScanner()

        val radarViewModel = RadarViewModel(
            meshScanner = meshScanner,
            connectionPool = connectionPool,
            meshController = meshController,
            bufferedPacketDao = bufferedPacketDao,
            peerDao = peerDao
        )

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            val currentBleState = BlePermissions.getBleState(this)
            groupListViewModel.setBleState(currentBleState)
            if (currentBleState == BleState.READY) {
                MeshService.start(this)
            }
        }

        requestBlePermissionsAction = {
            permissionLauncher.launch(BlePermissions.getRequiredPermissions())
        }

        // Auto-check and request permissions on launch
        if (!BlePermissions.hasPermissions(this)) {
            permissionLauncher.launch(BlePermissions.getRequiredPermissions())
        }

        setContent {
            P2ChatTheme {
                P2ChatMainApp(
                    database = database,
                    identityManager = identityManager,
                    groupManager = groupManager,
                    onboardingViewModel = onboardingViewModel,
                    groupListViewModel = groupListViewModel,
                    radarViewModel = radarViewModel,
                    aiFilterViewModel = aiFilterViewModel,
                    benchViewModel = benchViewModel,
                    sendPipeline = sendPipeline,
                    rotationService = rotationService,
                    keyVault = keyVault,
                    blockSimilarService = blockSimilarService,
                    onRequestBlePermissions = { requestBlePermissionsAction?.invoke() }
                )
            }
        }
    }
}

@Composable
fun P2ChatMainApp(
    database: AppDatabase,
    identityManager: IdentityManager,
    groupManager: GroupManager,
    onboardingViewModel: OnboardingViewModel,
    groupListViewModel: GroupListViewModel,
    radarViewModel: RadarViewModel,
    aiFilterViewModel: AiFilterViewModel,
    benchViewModel: BenchViewModel,
    sendPipeline: SendPipeline,
    rotationService: RotationService,
    keyVault: KeyVault,
    blockSimilarService: BlockSimilarService?,
    onRequestBlePermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    var hasIdentity by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        hasIdentity = identityManager.hasIdentity()
        if (hasIdentity == true) {
            onboardingViewModel.checkExistingIdentity()
        }
    }

    when (hasIdentity) {
        null -> {
            LoadingState(
                message = "Initializing secure enclave...",
                modifier = modifier
            )
        }
        false -> {
            OnboardingScreen(
                viewModel = onboardingViewModel,
                onOnboardingComplete = {
                    hasIdentity = true
                    onRequestBlePermissions()
                },
                modifier = modifier
            )
        }
        true -> {
            P2ChatAppShell(
                database = database,
                identityManager = identityManager,
                groupManager = groupManager,
                groupListViewModel = groupListViewModel,
                onboardingViewModel = onboardingViewModel,
                radarViewModel = radarViewModel,
                aiFilterViewModel = aiFilterViewModel,
                benchViewModel = benchViewModel,
                sendPipeline = sendPipeline,
                rotationService = rotationService,
                keyVault = keyVault,
                blockSimilarService = blockSimilarService,
                onRequestBlePermissions = onRequestBlePermissions,
                modifier = modifier
            )
        }
    }
}

@Composable
fun P2ChatAppShell(
    database: AppDatabase,
    identityManager: IdentityManager,
    groupManager: GroupManager,
    groupListViewModel: GroupListViewModel,
    onboardingViewModel: OnboardingViewModel,
    radarViewModel: RadarViewModel,
    aiFilterViewModel: AiFilterViewModel,
    benchViewModel: BenchViewModel,
    sendPipeline: SendPipeline,
    rotationService: RotationService,
    keyVault: KeyVault,
    blockSimilarService: BlockSimilarService?,
    onRequestBlePermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentTab by remember { mutableStateOf(NavigationDestination.CHATS) }
    var selectedGroupId by remember { mutableStateOf<Long?>(null) }
    var showGroupAdmin by remember { mutableStateOf(false) }
    var showIdentityProfile by remember { mutableStateOf(false) }

    val serviceRunning by MeshService.isServiceRunning.collectAsState()
    val meshBleState by MeshService.bleState.collectAsState()

    LaunchedEffect(serviceRunning, meshBleState) {
        val effectiveState = if (serviceRunning && meshBleState == BleState.READY) {
            BleState.READY
        } else {
            meshBleState
        }
        groupListViewModel.setBleState(effectiveState)
    }

    val allGroups by database.groupDao().observeAllGroups().collectAsState(initial = emptyList())
    LaunchedEffect(selectedGroupId, allGroups) {
        val currentGid = selectedGroupId
        if (currentGid != null && allGroups.none { it.groupId == currentGid }) {
            selectedGroupId = null
            showGroupAdmin = false
        }
    }

    // Handle back button to pop backstack instead of closing app (Issue 4)
    if (showIdentityProfile) {
        BackHandler { showIdentityProfile = false }
        OnboardingScreen(
            viewModel = onboardingViewModel,
            onOnboardingComplete = { showIdentityProfile = false },
            isProfileMode = true,
            onBackClick = { showIdentityProfile = false },
            modifier = modifier
        )
        return
    }

    if (selectedGroupId != null) {
        if (showGroupAdmin) {
            BackHandler { showGroupAdmin = false }
        } else {
            BackHandler { selectedGroupId = null }
        }
    } else if (currentTab != NavigationDestination.CHATS) {
        BackHandler { currentTab = NavigationDestination.CHATS }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = CanvasBackground,
        bottomBar = {
            if (selectedGroupId == null) {
                P2ChatBottomBar(
                    selectedDestination = currentTab,
                    onDestinationSelected = { currentTab = it }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                NavigationDestination.CHATS -> {
                    val currentGid = selectedGroupId
                    if (currentGid != null) {
                        if (showGroupAdmin) {
                            val adminViewModel = remember(currentGid) {
                                GroupAdminViewModel(
                                    groupId = currentGid,
                                    groupDao = database.groupDao(),
                                    rosterMemberDao = database.rosterMemberDao(),
                                    localIdentityDao = database.localIdentityDao(),
                                    peerDao = database.peerDao(),
                                    identityManager = identityManager,
                                    rotationService = rotationService,
                                    sendPipeline = sendPipeline,
                                    keyVault = keyVault
                                )
                            }
                            GroupAdminScreen(
                                viewModel = adminViewModel,
                                onBackClick = { showGroupAdmin = false }
                            )
                        } else {
                            val chatViewModel = remember(currentGid) {
                                ChatViewModel(
                                    groupId = currentGid,
                                    groupDao = database.groupDao(),
                                    rosterMemberDao = database.rosterMemberDao(),
                                    peerDao = database.peerDao(),
                                    localIdentityDao = database.localIdentityDao(),
                                    messageDao = database.messageDao(),
                                    sendPipeline = sendPipeline,
                                    blockSimilarService = blockSimilarService
                                )
                            }
                            ChatScreen(
                                viewModel = chatViewModel,
                                onBackClick = { selectedGroupId = null },
                                onAdminClick = { showGroupAdmin = true }
                            )
                        }
                    } else {
                        GroupListScreen(
                            viewModel = groupListViewModel,
                            onGroupClick = { groupId -> selectedGroupId = groupId },
                            onScanQrClick = { showIdentityProfile = true },
                            onEnableBluetoothClick = onRequestBlePermissions,
                            onRequestPermissionsClick = onRequestBlePermissions
                        )
                    }
                }
                NavigationDestination.RADAR -> RadarScreen(
                    viewModel = radarViewModel,
                    onEnableBluetoothClick = onRequestBlePermissions,
                    onRequestPermissionsClick = onRequestBlePermissions,
                    onProfileClick = { showIdentityProfile = true }
                )
                NavigationDestination.AI_FILTER -> AiFilterScreen(
                    viewModel = aiFilterViewModel,
                    onProfileClick = { showIdentityProfile = true }
                )
                NavigationDestination.BENCH -> BenchScreen(
                    viewModel = benchViewModel
                )
            }
        }
    }
}

@Composable
fun P2ChatBottomBar(
    selectedDestination: NavigationDestination,
    onDestinationSelected: (NavigationDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CanvasBackground)
            .border(
                width = 1.dp,
                color = HairlineBorder,
                shape = androidx.compose.ui.graphics.RectangleShape
            )
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavigationDestination.entries.forEach { destination ->
                val isSelected = destination == selectedDestination
                val iconTint = if (isSelected) StarkPrimary else NavUnselected
                val textColor = if (isSelected) StarkPrimary else NavUnselected

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onDestinationSelected(destination) }
                        .padding(vertical = 4.dp)
                        .semantics {
                            contentDescription = destination.contentDescription
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = destination.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor
                    )
                }
            }
        }
    }
}
