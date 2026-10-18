package dev.adminos.api.domain.forecast

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The owner's salary choice (AOS-722): at most one per user. */
interface SalaryChoiceRepository {
    suspend fun find(userId: UUID): SalaryChoice?

    /** Stores [choice] as [userId]'s, replacing any earlier one. */
    suspend fun put(userId: UUID, choice: SalaryChoice): SalaryChoice
}

class InMemorySalaryChoiceRepository : SalaryChoiceRepository {
    private val store = ConcurrentHashMap<UUID, SalaryChoice>()

    override suspend fun find(userId: UUID): SalaryChoice? = store[userId]

    override suspend fun put(userId: UUID, choice: SalaryChoice): SalaryChoice {
        store[userId] = choice
        return choice
    }
}
