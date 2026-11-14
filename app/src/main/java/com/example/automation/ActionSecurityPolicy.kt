package com.example.automation

import com.example.data.api.ParsedCommand

enum class ActionRiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

enum class AllowlistedAction(val actionKey: String, val defaultRisk: ActionRiskLevel) {
    CHAT("CHAT", ActionRiskLevel.LOW),
    SYSTEM_QUERY("SYSTEM_QUERY", ActionRiskLevel.LOW),
    OPEN_APP("OPEN_APP", ActionRiskLevel.LOW),
    LIST_APPS("LIST_APPS", ActionRiskLevel.LOW),
    OPEN_SETTINGS("OPEN_SETTINGS", ActionRiskLevel.LOW),
    OPEN_CAMERA("OPEN_CAMERA", ActionRiskLevel.LOW),
    OPEN_CALCULATOR("OPEN_CALCULATOR", ActionRiskLevel.LOW),
    OPEN_CLOCK("OPEN_CLOCK", ActionRiskLevel.LOW),
    FLASHLIGHT("FLASHLIGHT", ActionRiskLevel.LOW),
    VOLUME("VOLUME", ActionRiskLevel.LOW),
    WEB_SEARCH("WEB_SEARCH", ActionRiskLevel.LOW),
    YOUTUBE_SEARCH("YOUTUBE_SEARCH", ActionRiskLevel.LOW),
    DIAL("DIAL", ActionRiskLevel.MEDIUM),
    PAUSE_LISTENING("PAUSE_LISTENING", ActionRiskLevel.LOW),
    RESUME_LISTENING("RESUME_LISTENING", ActionRiskLevel.LOW),
    STOP_AI("STOP_AI", ActionRiskLevel.LOW),
    SHOW_HISTORY("SHOW_HISTORY", ActionRiskLevel.LOW),
    ACCESSIBILITY("ACCESSIBILITY", ActionRiskLevel.MEDIUM),
    SCREEN_CLICK("SCREEN_CLICK", ActionRiskLevel.MEDIUM),
    SCREEN_TYPE("SCREEN_TYPE", ActionRiskLevel.MEDIUM),
    BUILD_WEBSITE("BUILD_WEBSITE", ActionRiskLevel.LOW);

    companion object {
        fun fromKey(key: String): AllowlistedAction? {
            val normalized = when (key.trim().uppercase()) {
                "SETTINGS" -> "OPEN_SETTINGS"
                else -> key.trim().uppercase()
            }
            return entries.firstOrNull { it.actionKey == normalized }
        }
    }
}

data class ActionValidationResult(
    val allowed: Boolean,
    val normalizedAction: String,
    val riskLevel: ActionRiskLevel,
    val requiresConfirmation: Boolean,
    val confirmationPrompt: String? = null,
    val rejectionReason: String? = null
)

/**
 * Enforces strict security rules on all parsed AI intents before device execution:
 * 1. Action must be in the explicit AllowlistedAction set.
 * 2. Gemini must never pass executable code (Kotlin, Java, JS, shell scripts) or root commands.
 * 3. Sensitive or irreversible actions always require explicit user confirmation.
 */
object ActionSecurityPolicy {

    private val forbiddenCodePatterns = listOf(
        "runtime.getruntime",
        "processbuilder",
        "sh -c",
        "bash -c",
        "rm -rf",
        "su -c",
        "eval(",
        "<script",
        "dexclassloader",
        "chmod ",
        "chown ",
        "adb shell",
        "mkfs",
        "dd if="
    )

    private val highRiskClickKeywords = listOf(
        "delete", "uninstall", "erase", "factory reset", "format",
        "pay", "transfer", "send money", "purchase", "buy now",
        "confirm order", "remove account", "clear all"
    )

    fun validate(command: ParsedCommand): ActionValidationResult {
        val combinedPayload = "${command.actionType} ${command.target} ${command.extraData}".lowercase()

        // Block any attempt to inject executable code or arbitrary shell commands
        val matchedForbidden = forbiddenCodePatterns.firstOrNull { combinedPayload.contains(it) }
        if (matchedForbidden != null) {
            return ActionValidationResult(
                allowed = false,
                normalizedAction = "BLOCKED",
                riskLevel = ActionRiskLevel.HIGH,
                requiresConfirmation = false,
                rejectionReason = "Blocked unsafe executable payload ($matchedForbidden). MYRAA only executes allowlisted Android actions."
            )
        }

        val allowlisted = AllowlistedAction.fromKey(command.actionType)
            ?: return ActionValidationResult(
                allowed = false,
                normalizedAction = "BLOCKED",
                riskLevel = ActionRiskLevel.HIGH,
                requiresConfirmation = false,
                rejectionReason = "Action '${command.actionType}' is not in the MYRAA security allowlist."
            )

        // Risk classification & explicit confirmation requirement
        val parsedRisk = try {
            ActionRiskLevel.valueOf(command.riskLevel.uppercase())
        } catch (_: Exception) {
            allowlisted.defaultRisk
        }

        val isIrreversibleAccessibility = allowlisted == AllowlistedAction.ACCESSIBILITY &&
            command.target.lowercase() in listOf("lock", "lock_screen")

        val isSensitiveScreenAction = (allowlisted == AllowlistedAction.SCREEN_CLICK || allowlisted == AllowlistedAction.SCREEN_TYPE) &&
            highRiskClickKeywords.any { command.target.lowercase().contains(it) || command.extraData.lowercase().contains(it) }

        val effectiveRisk = when {
            isIrreversibleAccessibility || isSensitiveScreenAction || parsedRisk == ActionRiskLevel.HIGH -> ActionRiskLevel.HIGH
            parsedRisk == ActionRiskLevel.MEDIUM || allowlisted.defaultRisk == ActionRiskLevel.MEDIUM -> ActionRiskLevel.MEDIUM
            else -> ActionRiskLevel.LOW
        }

        val needsConfirmation = command.requiresConfirmation ||
            effectiveRisk == ActionRiskLevel.HIGH ||
            isIrreversibleAccessibility ||
            isSensitiveScreenAction

        val confirmMessage = if (needsConfirmation) {
            when {
                isIrreversibleAccessibility -> "Confirm locking your device screen?"
                isSensitiveScreenAction -> "Confirm sensitive screen action on \"${command.target}\"?"
                else -> "Allow MYRAA AI to execute ${allowlisted.actionKey} (${command.target})?"
            }
        } else null

        return ActionValidationResult(
            allowed = true,
            normalizedAction = allowlisted.actionKey,
            riskLevel = effectiveRisk,
            requiresConfirmation = needsConfirmation,
            confirmationPrompt = confirmMessage
        )
    }
}
