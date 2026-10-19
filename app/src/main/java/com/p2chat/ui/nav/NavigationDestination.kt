package com.p2chat.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Shield
import androidx.compose.ui.graphics.vector.ImageVector

enum class NavigationDestination(
    val route: String,
    val title: String,
    val contentDescription: String,
    val icon: ImageVector
) {
    CHATS(
        route = "chats",
        title = "Chats",
        contentDescription = "Navigate to encrypted chats",
        icon = Icons.Default.ChatBubbleOutline
    ),
    RADAR(
        route = "radar",
        title = "Radar",
        contentDescription = "Navigate to BLE mesh radar peer discovery",
        icon = Icons.Default.Radar
    ),
    AI_FILTER(
        route = "ai_filter",
        title = "AI Filter",
        contentDescription = "Navigate to on-device semantic toxicity filter",
        icon = Icons.Default.Shield
    ),
    BENCH(
        route = "bench",
        title = "Bench",
        contentDescription = "Navigate to multi-hop benchmark telemetry suite",
        icon = Icons.Default.Assessment
    )
}
