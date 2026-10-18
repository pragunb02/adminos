package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp

/** Maps to db/migration/V70__wealth_store.sql (AOS-802). */
object InvestmentAccountsTable : Table("investment_accounts") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id").references(UsersTable.id)
    val kind = varchar("kind", 20)
    val institution = varchar("institution", 120)
    val currency = varchar("currency", 3)
    val identifierEnc = text("identifier_enc")
    val identifierHmac = varchar("identifier_hmac", 64)
    val identifierLast4 = varchar("identifier_last4", 4)
    val ownerShare = decimal("owner_share", 5, 4)
    val fundingAccountId = javaUUID("funding_account_id").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object InstrumentsTable : Table("instruments") {
    val id = javaUUID("id")
    val name = varchar("name", 255)
    val assetClass = varchar("asset_class", 20)
    val isin = varchar("isin", 12).nullable()
    val amfiCode = varchar("amfi_code", 10).nullable()
    val exchange = varchar("exchange", 10).nullable()
    val ticker = varchar("ticker", 20).nullable()
    val currency = varchar("currency", 3)
    val mfPlan = varchar("mf_plan", 10).nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object WealthImportBatchesTable : Table("wealth_import_batches") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id").references(UsersTable.id)
    val readerId = varchar("reader_id", 40)
    val readerVersion = varchar("reader_version", 40)
    val statementFrom = date("statement_from").nullable()
    val statementTo = date("statement_to").nullable()
    val status = varchar("status", 10)
    val events = integer("events")
    val added = integer("added")
    val duplicates = integer("duplicates")
    val createdAt = timestamp("created_at")
    val undoneAt = timestamp("undone_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object InvestmentTransactionsTable : Table("investment_transactions") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id").references(UsersTable.id)
    val accountId = javaUUID("account_id").references(InvestmentAccountsTable.id)
    val instrumentId = javaUUID("instrument_id").references(InstrumentsTable.id)
    val batchId = javaUUID("batch_id").references(WealthImportBatchesTable.id).nullable()
    val txnType = varchar("txn_type", 20)
    val tradeDate = date("trade_date")
    val units = decimal("units", 38, 18).nullable()
    val unitPrice = decimal("unit_price", 30, 10).nullable()
    val amount = decimal("amount", 18, 2).nullable()
    val currency = varchar("currency", 3)
    val fxRate = decimal("fx_rate", 20, 10).nullable()
    val fxRateType = varchar("fx_rate_type", 10).nullable()
    val fxRateDate = date("fx_rate_date").nullable()
    val inrAmount = decimal("inr_amount", 18, 2).nullable()
    val sourceRef = varchar("source_ref", 64)
    val supersedesId = javaUUID("supersedes_id").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** Maps to db/migration/V72__instrument_prices.sql (AOS-810). */
object InstrumentPricesTable : Table("instrument_prices") {
    val instrumentId = javaUUID("instrument_id").references(InstrumentsTable.id)
    val priceDate = date("price_date")
    val priceSource = varchar("source", 12)
    val price = decimal("price", 30, 10)
    val fetchedAt = timestamp("fetched_at")
    override val primaryKey = PrimaryKey(instrumentId, priceDate, priceSource)
}

/** Maps to db/migration/V71__wealth_holding_checks.sql (AOS-805). */
object WealthHoldingChecksTable : Table("wealth_holding_checks") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id").references(UsersTable.id)
    val batchId = javaUUID("batch_id").references(WealthImportBatchesTable.id)
    val accountId = javaUUID("account_id").references(InvestmentAccountsTable.id)
    val instrumentId = javaUUID("instrument_id").references(InstrumentsTable.id).nullable()
    val schemeName = varchar("scheme_name", 255)
    val statementFrom = date("statement_from").nullable()
    val statementTo = date("statement_to")
    val openingUnits = decimal("opening_units", 38, 18)
    val closingUnits = decimal("closing_units", 38, 18)
    val valuationDate = date("valuation_date").nullable()
    val valuationNav = decimal("valuation_nav", 30, 10).nullable()
    val valuationValue = decimal("valuation_value", 18, 2).nullable()
    val issue = varchar("issue", 20).nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
