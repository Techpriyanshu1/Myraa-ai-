package com.example.ui.navigation

sealed class MyraaNavigationDestination(val route: String) {
    object SetupWizard : MyraaNavigationDestination("setup_wizard")
    object MainDashboard : MyraaNavigationDestination("main_dashboard")
    object ActivityHistory : MyraaNavigationDestination("activity_history")
    object Settings : MyraaNavigationDestination("settings")
    object PermissionCenter : MyraaNavigationDestination("permission_center")
    object AgentFleetStatus : MyraaNavigationDestination("agent_fleet_status")
}
