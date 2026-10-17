package dev.adminos.api.infrastructure.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.sql.Connection

/**
 * Runs [block] in a database transaction on the IO dispatcher.
 *
 * JDBC is blocking, so every repository goes through this single entry point to keep
 * database work off request threads (docs/ENGINEERING.md §2).
 */
suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
    withContext(Dispatchers.IO) { suspendTransaction { block() } }

/**
 * Like [dbQuery], but at READ COMMITTED instead of the pool's REPEATABLE READ (AOS-702). For a short
 * transaction that serialises on a row lock (`SELECT … FOR UPDATE`) and must then see the latest committed
 * row: under REPEATABLE READ a concurrent update of that row fails with "could not serialize access".
 */
suspend fun <T> dbQueryReadCommitted(block: suspend JdbcTransaction.() -> T): T =
    withContext(Dispatchers.IO) { suspendTransaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) { block() } }
