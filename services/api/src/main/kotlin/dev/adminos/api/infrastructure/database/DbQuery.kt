package dev.adminos.api.infrastructure.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Runs [block] in a database transaction on the IO dispatcher.
 *
 * JDBC is blocking, so every repository goes through this single entry point to keep
 * database work off request threads (docs/ENGINEERING.md §2).
 */
suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
    withContext(Dispatchers.IO) { suspendTransaction { block() } }
