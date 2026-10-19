package com.p2chat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.p2chat.ui.common.EmptyState
import com.p2chat.ui.theme.CanvasBackground

@Composable
fun ChatsScreen(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CanvasBackground)
    ) {
        EmptyState(
            title = "No Conversations",
            message = "Join a mesh group via in-person QR invite to start exchanging end-to-end encrypted messages.",
            actionLabel = "Create Group",
            onActionClick = {}
        )
    }
}

@Composable
fun RadarScreen(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CanvasBackground)
    ) {
        EmptyState(
            title = "Radar Inactive",
            message = "Scan for nearby BLE mesh nodes advertising the P2Chat service UUID.",
            actionLabel = "Scan Mesh",
            onActionClick = {}
        )
    }
}

@Composable
fun FilterScreen(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CanvasBackground)
    ) {
        EmptyState(
            title = "Filter Calibrated",
            message = "Offline semantic filter running all-MiniLM-L6-v2 embeddings post-decryption.",
            actionLabel = "View Quarantine",
            onActionClick = {}
        )
    }
}

@Composable
fun BenchScreen(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CanvasBackground)
    ) {
        EmptyState(
            title = "Benchmark Idle",
            message = "Run 2-device or 3-device multi-hop latency and PDR measurement runs.",
            actionLabel = "Start Probe Run",
            onActionClick = {}
        )
    }
}
