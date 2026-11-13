package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.data.pref.SecurePreferences
import com.example.ui.screens.AboutScreen
import com.example.ui.screens.AgentFleetScreen
import com.example.ui.screens.AuditLogScreen
import com.example.ui.screens.MainAssistantScreen
import com.example.ui.screens.PermissionCenterScreen
import com.example.ui.screens.SecurityPrivacyScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SetupWizardScreen
import com.example.ui.screens.SplashScreen
import com.example.ui.screens.WelcomeScreen
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.MyApplicationTheme

enum class AppScreen {
    SPLASH,
    WELCOME,
    SETUP,
    MAIN,
    AUDIT_LOGS,
    SETTINGS,
    PERMISSION_CENTER,
    AGENT_FLEET,
    SECURITY_PRIVACY,
    ABOUT
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = SecurePreferences(this)

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    var currentScreen by remember { mutableStateOf(AppScreen.SPLASH) }

                    Crossfade(targetState = currentScreen, label = "screen_crossfade") { screen ->
                        when (screen) {
                            AppScreen.SPLASH -> {
                                SplashScreen(
                                    onSplashFinished = {
                                        currentScreen = if (prefs.isSetupCompleted()) {
                                            AppScreen.MAIN
                                        } else {
                                            AppScreen.WELCOME
                                        }
                                    }
                                )
                            }
                            AppScreen.WELCOME -> {
                                WelcomeScreen(
                                    onGetStarted = {
                                        currentScreen = AppScreen.SETUP
                                    }
                                )
                            }
                            AppScreen.SETUP -> {
                                SetupWizardScreen(
                                    onSetupCompleted = {
                                        currentScreen = AppScreen.MAIN
                                    }
                                )
                            }
                            AppScreen.MAIN -> {
                                MainAssistantScreen(
                                    onNavigateToLogs = { currentScreen = AppScreen.AUDIT_LOGS },
                                    onNavigateToSettings = { currentScreen = AppScreen.SETTINGS }
                                )
                            }
                            AppScreen.AUDIT_LOGS -> {
                                BackHandler { currentScreen = AppScreen.MAIN }
                                AuditLogScreen(
                                    onNavigateBack = { currentScreen = AppScreen.MAIN }
                                )
                            }
                            AppScreen.SETTINGS -> {
                                BackHandler { currentScreen = AppScreen.MAIN }
                                SettingsScreen(
                                    onNavigateBack = { currentScreen = AppScreen.MAIN },
                                    onNavigateToPermissions = { currentScreen = AppScreen.PERMISSION_CENTER },
                                    onNavigateToAgentFleet = { currentScreen = AppScreen.AGENT_FLEET },
                                    onNavigateToSecurity = { currentScreen = AppScreen.SECURITY_PRIVACY },
                                    onNavigateToAbout = { currentScreen = AppScreen.ABOUT },
                                    onRerunSetup = { currentScreen = AppScreen.SETUP }
                                )
                            }
                            AppScreen.PERMISSION_CENTER -> {
                                BackHandler { currentScreen = AppScreen.SETTINGS }
                                PermissionCenterScreen(
                                    onNavigateBack = { currentScreen = AppScreen.SETTINGS }
                                )
                            }
                            AppScreen.AGENT_FLEET -> {
                                BackHandler { currentScreen = AppScreen.SETTINGS }
                                AgentFleetScreen(
                                    onNavigateBack = { currentScreen = AppScreen.SETTINGS }
                                )
                            }
                            AppScreen.SECURITY_PRIVACY -> {
                                BackHandler { currentScreen = AppScreen.SETTINGS }
                                SecurityPrivacyScreen(
                                    onNavigateBack = { currentScreen = AppScreen.SETTINGS }
                                )
                            }
                            AppScreen.ABOUT -> {
                                BackHandler { currentScreen = AppScreen.SETTINGS }
                                AboutScreen(
                                    onNavigateBack = { currentScreen = AppScreen.SETTINGS }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
