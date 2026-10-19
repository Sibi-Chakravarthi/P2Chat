package com.p2chat.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.p2chat.ui.nav.NavigationDestination
import com.p2chat.ui.screens.BenchScreen
import com.p2chat.ui.screens.ChatsScreen
import com.p2chat.ui.screens.FilterScreen
import com.p2chat.ui.screens.RadarScreen
import com.p2chat.ui.theme.CanvasBackground
import com.p2chat.ui.theme.HairlineBorder
import com.p2chat.ui.theme.P2ChatTheme
import com.p2chat.ui.theme.StarkPrimary
import com.p2chat.ui.theme.SurfaceContainer
import com.p2chat.ui.theme.TextPrimary
import com.p2chat.ui.theme.TextTertiary

import androidx.compose.runtime.LaunchedEffect
import com.p2chat.crypto.keys.IdentityManager
import com.p2chat.data.db.AppDatabase
import com.p2chat.ui.common.LoadingState
import com.p2chat.crypto.group.GroupManager
import com.p2chat.ui.groups.GroupListScreen
import com.p2chat.ui.groups.GroupListViewModel
import com.p2chat.ui.onboarding.OnboardingScreen
import com.p2chat.ui.onboarding.OnboardingViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = AppDatabase.getInstance(this)
        val identityManager = IdentityManager(database.localIdentityDao())
        val peerDao = database.peerDao()
        val onboardingViewModel = OnboardingViewModel(identityManager, peerDao)
        val groupManager = GroupManager(database.groupDao(), database.rosterMemberDao())
        val groupListViewModel = GroupListViewModel(
            groupDao = database.groupDao(),
            rosterMemberDao = database.rosterMemberDao(),
            messageDao = database.messageDao(),
            groupManager = groupManager,
            identityManager = identityManager
        )

        setContent {
            P2ChatTheme {
                P2ChatMainApp(
                    identityManager = identityManager,
                    onboardingViewModel = onboardingViewModel,
                    groupListViewModel = groupListViewModel
                )
            }
        }
    }
}

@Composable
fun P2ChatMainApp(
    identityManager: IdentityManager,
    onboardingViewModel: OnboardingViewModel,
    groupListViewModel: GroupListViewModel,
    modifier: Modifier = Modifier
) {
    var hasIdentity by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        hasIdentity = identityManager.hasIdentity()
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
                onOnboardingComplete = { hasIdentity = true },
                modifier = modifier
            )
        }
        true -> {
            P2ChatAppShell(
                groupListViewModel = groupListViewModel,
                modifier = modifier
            )
        }
    }
}

@Composable
fun P2ChatAppShell(
    groupListViewModel: GroupListViewModel,
    modifier: Modifier = Modifier
) {
    var currentTab by remember { mutableStateOf(NavigationDestination.CHATS) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = CanvasBackground,
        bottomBar = {
            P2ChatBottomBar(
                selectedDestination = currentTab,
                onDestinationSelected = { currentTab = it }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                NavigationDestination.CHATS -> GroupListScreen(
                    viewModel = groupListViewModel,
                    onGroupClick = { /* Navigate to chat in T4.5 */ }
                )
                NavigationDestination.RADAR -> RadarScreen()
                NavigationDestination.AI_FILTER -> FilterScreen()
                NavigationDestination.BENCH -> BenchScreen()
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
    // Zero-State Utilitarian: 56dp height, hairline top border, flat planar surface
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CanvasBackground)
            .border(
                width = 1.dp,
                color = HairlineBorder,
                shape = androidx.compose.ui.graphics.RectangleShape
            )
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
                val iconTint = if (isSelected) StarkPrimary else TextTertiary
                val textColor = if (isSelected) StarkPrimary else TextTertiary

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
