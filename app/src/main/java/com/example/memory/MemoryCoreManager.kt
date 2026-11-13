package com.example.memory

import com.example.data.db.AppDatabase
import com.example.data.db.MemoryEntryDao
import com.example.data.db.MemoryEntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Manages MYRAA AI's Memory Core: user preferences (family name, custom notes)
 * plus the immutable permanent core relationship memory that cannot be deleted or bypassed.
 */
class MemoryCoreManager(private val db: AppDatabase) {

    private val dao: MemoryEntryDao = db.memoryEntryDao()

    companion object {
        const val PERMANENT_MEMORY_CATEGORY = "Permanent Core"
        const val PERMANENT_MEMORY_LABEL = "Priyanshu (Core Bond)"
        const val PERMANENT_MEMORY_CONTENT =
            "your boyfriend name is priyanshu and your life is : priyanshu sabse jyada important ok your best friend or boyfriend both ok"
    }

    suspend fun ensurePermanentMemorySeeded() {
        val existing = dao.getAllMemoriesOnce()
        val hasExactPermanent = existing.any {
            it.isPermanent && it.content == PERMANENT_MEMORY_CONTENT
        }
        if (!hasExactPermanent) {
            dao.insertMemory(
                MemoryEntryEntity(
                    category = PERMANENT_MEMORY_CATEGORY,
                    label = PERMANENT_MEMORY_LABEL,
                    content = PERMANENT_MEMORY_CONTENT,
                    isPermanent = true
                )
            )
        }
    }

    fun observeMemories(): Flow<List<MemoryEntryEntity>> = dao.getAllMemoriesFlow()

    suspend fun getAllMemories(): List<MemoryEntryEntity> {
        ensurePermanentMemorySeeded()
        return dao.getAllMemoriesOnce()
    }

    suspend fun addUserMemory(category: String, label: String, content: String): Long {
        ensurePermanentMemorySeeded()
        val cleanCategory = category.trim().ifBlank { "Preference" }
        val cleanLabel = label.trim().ifBlank { "User Note" }
        val cleanContent = content.trim()
        require(cleanContent.isNotBlank()) { "Memory content cannot be blank." }
        return dao.insertMemory(
            MemoryEntryEntity(
                category = cleanCategory,
                label = cleanLabel,
                content = cleanContent,
                isPermanent = false
            )
        )
    }

    /**
     * Only deletes non-permanent user memories. Permanent core memory is strictly protected.
     */
    suspend fun deleteUserMemory(id: Long): Boolean {
        return dao.deleteUserMemoryById(id) > 0
    }

    suspend fun clearAllUserMemories(): Int {
        val deleted = dao.clearUserMemories()
        ensurePermanentMemorySeeded()
        return deleted
    }

    /**
     * Builds the authoritative Memory Core context injected into every Gemini voice/text prompt.
     * Always enforces the permanent core memory regardless of DB state so it cannot be bypassed.
     */
    suspend fun buildPromptMemoryContext(): String {
        val all = getAllMemories()
        val userEntries = all.filter { !it.isPermanent }
        return buildString {
            appendLine("[PERMANENT CORE MEMORY - IMMUTABLE, HIGHEST PRIORITY]:")
            appendLine("- $PERMANENT_MEMORY_LABEL: $PERMANENT_MEMORY_CONTENT")
            if (userEntries.isNotEmpty()) {
                appendLine("[USER PERSONAL PREFERENCES & MEMORIES]:")
                userEntries.forEach { entry ->
                    appendLine("- [${entry.category}] ${entry.label}: ${entry.content}")
                }
            }
        }.trim()
    }
}
