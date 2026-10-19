package com.p2chat.ui.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.window.Dialog
import com.p2chat.ui.theme.DestructiveRose
import com.p2chat.ui.theme.HairlineBorder
import com.p2chat.ui.theme.OnStarkPrimary
import com.p2chat.ui.theme.StarkPrimary
import com.p2chat.ui.theme.SurfaceContainer
import com.p2chat.ui.theme.TextPrimary
import com.p2chat.ui.theme.TextSecondary
import com.p2chat.ui.theme.TextTertiary

/**
 * Dialog for creating a new end-to-end encrypted mesh group (T4.4, SEC-8).
 * Enforces 1–32 character name limit and provides clear feedback.
 */
@Composable
fun CreateGroupDialog(
    onDismissRequest: () -> Unit,
    onConfirmCreate: (name: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var groupName by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(SurfaceContainer, RoundedCornerShape(12.dp))
                .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp))
                .padding(20.dp)
                .semantics {
                    contentDescription = "Create New Group Dialog"
                }
        ) {
            Text(
                text = "Create Group",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Enter a name for the new end-to-end encrypted mesh group. You will be assigned the ADMIN role (Epoch 1).",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = groupName,
                onValueChange = {
                    groupName = it
                    errorText = when {
                        it.trim().isEmpty() -> "Name cannot be empty"
                        it.length > 32 -> "Maximum 32 characters"
                        else -> null
                    }
                },
                label = { Text("Group Name") },
                placeholder = { Text("e.g. Ops Squad Alpha") },
                singleLine = true,
                isError = errorText != null,
                supportingText = {
                    if (errorText != null) {
                        Text(
                            text = errorText!!,
                            color = DestructiveRose,
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else {
                        Text(
                            text = "${groupName.length}/32",
                            color = TextTertiary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = StarkPrimary,
                    unfocusedBorderColor = HairlineBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    errorBorderColor = DestructiveRose
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Group name input field"
                    }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Cancel group creation" },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, HairlineBorder)
                ) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = {
                        val trimmed = groupName.trim()
                        if (trimmed.isNotEmpty() && trimmed.length <= 32) {
                            onConfirmCreate(trimmed)
                        } else {
                            errorText = "Name must be 1 to 32 characters"
                        }
                    },
                    enabled = groupName.trim().isNotEmpty() && groupName.length <= 32,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Confirm group creation" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StarkPrimary,
                        contentColor = OnStarkPrimary
                    )
                ) {
                    Text("Create")
                }
            }
        }
    }
}
