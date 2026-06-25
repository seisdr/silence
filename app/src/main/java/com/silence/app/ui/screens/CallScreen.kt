package com.silence.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp

/**
 * In-call screen with E2EE verification status.
 *
 * States:
 *  - DIALING: outbound call in progress
 *  - RINGING: inbound call
 *  - CONNECTED: media flowing, checks DTLS fingerprint
 *  - VERIFIED: fingerprint matches stored contact
 *  - ENDED
 */
@Composable
fun CallScreen(
    contactName: String,
    callState: CallStateUi,
    e2eeVerified: Boolean,
    e2eeFingerprint: String?,
    muted: Boolean,
    speakerOn: Boolean,
    callDuration: String,
    onHangup: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onAccept: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = when {
            callState == CallStateUi.ENDED -> Color(0xFF37474F)
            e2eeVerified -> Color(0xFF1B5E20)
            callState == CallStateUi.CONNECTED -> Color(0xFF1565C0)
            else -> Color(0xFF263238)
        },
        label = "bg"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // Avatar
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = contactName.take(1).uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = contactName,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White
        )

        // Status
        Text(
            text = when {
                callState == CallStateUi.DIALING -> "Calling…"
                callState == CallStateUi.RINGING -> "Incoming call"
                callState == CallStateUi.ENDED -> "Call ended"
                e2eeVerified -> "E2E Encrypted \u2022 Verified"
            callState == CallStateUi.CONNECTED -> "Encrypted \u2022 Identity unknown"
                else -> ""
            },
            color = Color.White.copy(alpha = 0.85f)
        )

        if (callDuration.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = callDuration,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 4.sp
                ),
                color = Color.White.copy(alpha = 0.9f)
            )
        }

        if (e2eeFingerprint != null && callState == CallStateUi.CONNECTED) {
            Spacer(modifier = Modifier.height(16.dp))
            // Show fingerprint for manual comparison
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.1f))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Security code",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Text(
                        text = e2eeFingerprint.chunked(4).joinToString(" "),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp
                        ),
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Both participants see the same code.\nCompare to confirm you're talking to your contact.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }
        }

        if (e2eeVerified) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.VerifiedUser,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Verified end-to-end encrypted",
                    color = Color(0xFF4CAF50),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Controls
        when (callState) {
            CallStateUi.RINGING -> {
                // Incoming call: Accept / Decline
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    FloatingActionButton(
                        onClick = onHangup,
                        containerColor = Color(0xFFD32F2F),
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = "Decline", tint = Color.White)
                    }
                    FloatingActionButton(
                        onClick = onAccept,
                        containerColor = Color(0xFF4CAF50),
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = "Accept", tint = Color.White)
                    }
                }
            }
            CallStateUi.DIALING -> {
                HangupButton(onHangup)
            }
            CallStateUi.CONNECTED, CallStateUi.IDLE, CallStateUi.ENDED -> {
                // Mute / Speaker / Hangup row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ControlButton(
                        icon = if (muted) Icons.Default.MicOff else Icons.Default.Mic,
                        label = if (muted) "Unmute" else "Mute",
                        active = !muted,
                        enabled = callState != CallStateUi.ENDED,
                        onClick = onToggleMute
                    )
                    HangupButton(onHangup)
                    ControlButton(
                        icon = if (speakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                        label = "Speaker",
                        active = speakerOn,
                        enabled = callState != CallStateUi.ENDED,
                        onClick = onToggleSpeaker
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (active) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f))
        ) {
            Icon(icon, contentDescription = label, tint = Color.White)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
    }
}

@Composable
private fun HangupButton(onHangup: () -> Unit) {
    FloatingActionButton(
        onClick = onHangup,
        containerColor = Color(0xFFD32F2F),
        modifier = Modifier.size(64.dp)
    ) {
        Icon(Icons.Default.CallEnd, contentDescription = "End call", tint = Color.White)
    }
}

enum class CallStateUi { IDLE, DIALING, RINGING, CONNECTED, ENDED }
