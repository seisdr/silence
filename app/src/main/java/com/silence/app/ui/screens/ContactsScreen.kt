package com.silence.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.silence.app.data.Contact

/**
 * Contact list — shows saved contacts and allows initiating calls.
 *
 * Each contact entry shows name + truncated fingerprint.
 * Tap to call, long-press to delete.
 */
@Composable
fun ContactsScreen(
    contacts: List<Contact>,
    ownFingerprint: String,
    username: String?,
    onCall: (Contact) -> Unit,
    onCallUser: (String) -> Unit,
    onAddContact: () -> Unit,
    onViewIdentity: () -> Unit,
    onDeleteContact: (Contact) -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf<Contact?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Silence",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            IconButton(onClick = onViewIdentity) {
                Icon(Icons.Default.QrCode, contentDescription = "My identity")
            }
        }

        // Own fingerprint (subtle)
        Text(
            text = "You: ${ownFingerprint.chunked(4).joinToString(" ")}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        // Call by username (lets you call someone without a scanned QR contact)
        var callUser by remember { mutableStateOf("") }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = callUser,
                onValueChange = { callUser = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' } },
                label = { Text("Call username") },
            singleLine = true,
            modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onCallUser(callUser.trim()); callUser = "" },
            enabled = callUser.isNotBlank()
            ) { Text("Call") }
        }

        if (contacts.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.PeopleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No contacts yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Scan a friend's QR code to add them",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = onAddContact) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Contact")
                    }
                }
            }
        } else {
            // Contact list
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                items(contacts, key = { it.publicKeyB64 }) { contact ->
                    ContactRow(
                        contact = contact,
                        onClick = { onCall(contact) },
                        onLongClick = { showDeleteDialog = contact }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onAddContact,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Contact")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    // Delete confirmation
    showDeleteDialog?.let { contact ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Remove ${contact.name}?") },
            text = { Text("You'll need to scan their QR code again to re-add them.") },
            confirmButton = {
                TextButton(onClick = { onDeleteContact(contact); showDeleteDialog = null }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ContactRow(
    contact: Contact,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contact.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = fingerprint(contact.publicKeyB64),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                Icons.Default.Call,
                contentDescription = "Call",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// Contact fingerprint for display. MUST match IdentityManager.fingerprint
// (first 8 bytes / 16 hex chars) so it aligns with the user's own fingerprint.
private fun fingerprint(pubB64: String): String {
    val hash = java.security.MessageDigest.getInstance("SHA-256")
        .digest(android.util.Base64.decode(pubB64, android.util.Base64.NO_WRAP))
    return hash.take(8).joinToString("") { "%02x".format(it) }
}
