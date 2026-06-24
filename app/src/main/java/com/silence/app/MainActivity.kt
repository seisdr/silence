package com.silence.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silence.app.service.CallService
import com.silence.app.ui.MainViewModel
import com.silence.app.ui.screens.*
import com.silence.app.ui.theme.SilenceTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()

        setContent {
            SilenceTheme {
                val viewModel = this@MainActivity.viewModel
                val identity by viewModel.identity.collectAsStateWithLifecycle()
                val contacts by viewModel.contacts.collectAsStateWithLifecycle()
                val callState by viewModel.callState.collectAsStateWithLifecycle()
                val activeContact by viewModel.activeContact.collectAsStateWithLifecycle()
                val e2eeFp by viewModel.e2eeFingerprint.collectAsStateWithLifecycle()
                val e2eeV by viewModel.e2eeVerified.collectAsStateWithLifecycle()
                val muted by viewModel.muted.collectAsStateWithLifecycle()
                val speakerOn by viewModel.speakerOn.collectAsStateWithLifecycle()
                val inCall by viewModel.inCall.collectAsStateWithLifecycle()
                val signalingUrl by viewModel.signalingUrl.collectAsStateWithLifecycle()
                val authState by viewModel.authState.collectAsStateWithLifecycle()
                val authError by viewModel.authError.collectAsStateWithLifecycle()
                val authLoading by viewModel.authLoading.collectAsStateWithLifecycle()
                // Simple screen routing based on state
                var screen by remember { mutableStateOf<Screen>(Screen.Identity) }
                var scannedKey by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    viewModel.startService()
                    startCallService()
                }

                // Handle incoming call launched from FCM push notification
                LaunchedEffect(Unit) {
                    handleIncomingFcmIntent(intent)
                }

                LaunchedEffect(viewModel.navigateTo) {
                    viewModel.navigateTo.collect { target ->
                        when (target) {
                            is MainViewModel.NavTarget.Identity -> screen = Screen.Identity
                            is MainViewModel.NavTarget.Contacts -> screen = Screen.Contacts
                            is MainViewModel.NavTarget.Scan -> screen = Screen.Scanner
                            is MainViewModel.NavTarget.Call -> screen = Screen.Call
                        }
                    }
                }

                if (authState?.isLoggedIn != true) {
                    if (screen == Screen.Register) {
                        RegisterScreen(
                            onRegister = { u, p -> viewModel.registerUser(u, p) },
                            onSwitchToLogin = { screen = Screen.Login },
                            loading = authLoading,
                            error = authError
                        )
                    } else {
                        LoginScreen(
                            onLogin = { u, p -> viewModel.login(u, p) },
                            onSwitchToRegister = { screen = Screen.Register },
                            loading = authLoading,
                            error = authError
                        )
                    }
                } else {
                when (screen) {
                    Screen.Login, Screen.Register -> screen = Screen.Identity
                    Screen.Identity -> {
                        IdentityScreen(
                            identity = identity,
                            signalingUrl = signalingUrl,
                            onSignalingUrlChange = { viewModel.setSignalingUrl(it) },
                            onContinue = {
                                viewModel.registerWithSignaling()
                                screen = Screen.Contacts
                            }
                        )
                    }
                    Screen.Contacts -> {
                        ContactsScreen(
                            contacts = contacts,
                            ownFingerprint = identity?.fingerprint ?: "",
                            username = authState?.username,
                            onCall = { contact -> viewModel.callContact(contact) },
                            onCallUser = { username -> viewModel.callByUsername(username) },
                            onAddContact = { screen = Screen.Scanner },
                            onViewIdentity = { screen = Screen.Identity }
                        )
                    }
                    Screen.Scanner -> {
                        QrScanScreen(
                            onScanned = { key ->
                                scannedKey = key
                            },
                            onCancel = { screen = Screen.Contacts }
                        )
                    }
                    Screen.Call -> {
                        CallScreen(
                            contactName = activeContact?.name ?: "Unknown",
                            callState = callState,
                            e2eeVerified = e2eeV,
                            e2eeFingerprint = e2eeFp,
                            muted = muted,
                            speakerOn = speakerOn,
                            onHangup = { viewModel.hangup() },
                            onToggleMute = { viewModel.toggleMute() },
                            onToggleSpeaker = { viewModel.toggleSpeaker() },
                            onAccept = { viewModel.acceptCall() }
                        )
                    }
                }
                } // end auth gate
                // After scanning a key, show name dialog
                val key = scannedKey
                if (key != null) {
                    var contactName by remember { mutableStateOf("") }
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { scannedKey = null },
                        title = { androidx.compose.material3.Text("Add Contact") },
                        text = {
                            androidx.compose.material3.OutlinedTextField(
                                value = contactName,
                                onValueChange = { contactName = it },
                                label = { androidx.compose.material3.Text("Name") },
                                singleLine = true
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    if (contactName.isNotBlank()) {
                                        viewModel.addContact(contactName.trim(), key)
                                        scannedKey = null
                                    }
                                }
                            ) {
                                androidx.compose.material3.Text("Save")
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { scannedKey = null }) {
                                androidx.compose.material3.Text("Cancel")
                            }
                        }
                    )
                }
            }
        }
    }

    private fun startCallService() {
        val intent = Intent(this, CallService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to start CallService", e)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    /** Handle the intent that launched us — check for FCM incoming call extras. */
    private fun handleIncomingFcmIntent(intent: Intent?) {
        if (intent?.action != com.silence.app.service.FcmService.ACTION_INCOMING_CALL) return
        val room = intent.getStringExtra(com.silence.app.service.FcmService.EXTRA_ROOM) ?: return
        val fromFp = intent.getStringExtra(com.silence.app.service.FcmService.EXTRA_FROM_FP) ?: ""
        viewModel.handleIncomingFcmCall(room, fromFp)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIncomingFcmIntent(intent)
        setIntent(intent) // update the stored intent for future recompositions
    }

    private fun requestPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            audioPermissionLauncher.launch(needed.first())
        }
    }
}

private enum class Screen { Login, Register, Identity, Contacts, Scanner, Call }
