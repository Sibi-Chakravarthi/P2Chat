package com.p2chat.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.p2chat.ble.BleState
import com.p2chat.crypto.group.GroupManager
import com.p2chat.crypto.keys.IdentityManager
import com.p2chat.data.group.GroupDao
import com.p2chat.data.group.GroupEntity
import com.p2chat.data.group.RosterMemberDao
import com.p2chat.data.message.MessageDao
import com.p2chat.util.SecureLog
import com.p2chat.wire.BleConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * UI representation of an encrypted group in the group list (T4.4).
 */
data class GroupListItem(
    val groupId: Long,
    val name: String,
    val myRole: String,
    val currentEpoch: Int,
    val memberCount: Int,
    val maxMembers: Int = BleConstants.MAX_ROSTER_MEMBERS,
    val unreadCount: Int = 0,
    val lastMessageText: String? = null
)

/**
 * State representing GroupList screen content (T4.4, UI-6).
 */
sealed class GroupListUiState {
    object Loading : GroupListUiState()
    object Empty : GroupListUiState()
    data class Success(val groups: List<GroupListItem>) : GroupListUiState()
    data class Error(val message: String) : GroupListUiState()
}

/**
 * ViewModel managing GroupList state, group creation, and BLE status (T4.4, ID-6, SEC-8).
 */
class GroupListViewModel(
    private val groupDao: GroupDao,
    private val rosterMemberDao: RosterMemberDao,
    private val messageDao: MessageDao,
    private val groupManager: GroupManager,
    private val identityManager: IdentityManager,
    initialBleState: BleState = BleState.READY
) : ViewModel() {
    companion object {
        private const val TAG = "GroupListViewModel"
    }

    private val _uiState = MutableStateFlow<GroupListUiState>(GroupListUiState.Loading)
    val uiState: StateFlow<GroupListUiState> = _uiState.asStateFlow()

    private val _bleState = MutableStateFlow(initialBleState)
    val bleState: StateFlow<BleState> = _bleState.asStateFlow()

    init {
        observeGroups()
    }

    fun observeGroups() {
        viewModelScope.launch {
            try {
                groupDao.observeAllGroups().collectLatest { groupEntities ->
                    if (groupEntities.isEmpty()) {
                        _uiState.value = GroupListUiState.Empty
                    } else {
                        val items = groupEntities.map { group ->
                            val activeMembers = rosterMemberDao.getActiveMembers(group.groupId)
                            val messages = messageDao.getMessagesForGroup(group.groupId)
                            val lastMsg = messages.maxByOrNull { it.sentAtMs }
                            GroupListItem(
                                groupId = group.groupId,
                                name = group.name,
                                myRole = group.myRole,
                                currentEpoch = group.currentEpoch,
                                memberCount = activeMembers.size.coerceAtLeast(1),
                                maxMembers = BleConstants.MAX_ROSTER_MEMBERS,
                                unreadCount = 0,
                                lastMessageText = lastMsg?.body?.text
                            )
                        }
                        _uiState.value = GroupListUiState.Success(items)
                    }
                }
            } catch (e: Exception) {
                SecureLog.w(TAG, "Error observing groups: ${e.message}")
                _uiState.value = GroupListUiState.Error(e.message ?: "Failed to load groups")
            }
        }
    }

    fun setBleState(state: BleState) {
        _bleState.value = state
    }

    suspend fun createGroup(name: String): Result<Long> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Group name cannot be empty"))
        }
        if (trimmed.length > 32) {
            return Result.failure(IllegalArgumentException("Group name exceeds 32 characters"))
        }

        return try {
            val identity = identityManager.getIdentity()
                ?: return Result.failure(IllegalStateException("Local identity not found"))
            val signingKey = identityManager.unwrapSigningPrivateKey(identity)
            val (groupEntity, _) = groupManager.createGroup(
                name = trimmed,
                adminIdentity = identity,
                adminSigningPrivateKey = signingKey
            )
            Result.success(groupEntity.groupId)
        } catch (t: Throwable) {
            SecureLog.w(TAG, "Failed to create group: ${t.message}")
            Result.failure(t)
        }
    }
}
