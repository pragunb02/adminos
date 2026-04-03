package dev.adminos.api.domain.financial

import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Discovers bank accounts from ingested transactions.
 * Extracts bank_code + account_last4 and upserts Account records.
 */
class AccountDiscoveryService(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository
) {
    private val logger = LoggerFactory.getLogger(AccountDiscoveryService::class.java)

    // Known bank code → bank name mapping for Indian banks
    private val bankNames = mapOf(
        "HDFC" to "HDFC Bank",
        "ICICI" to "ICICI Bank",
        "SBI" to "State Bank of India",
        "AXIS" to "Axis Bank",
        "KOTAK" to "Kotak Mahindra Bank",
        "YES" to "Yes Bank",
        "INDUSIND" to "IndusInd Bank",
        "PNB" to "Punjab National Bank",
        "BOB" to "Bank of Baroda",
        "CANARA" to "Canara Bank",
        "UNION" to "Union Bank of India",
        "IDBI" to "IDBI Bank",
        "FEDERAL" to "Federal Bank",
        "RBL" to "RBL Bank",
    )

    /**
     * Discover or retrieve an account from a transaction's bank_code + account_last4.
     * Returns the account ID for linking.
     */
    suspend fun discoverAccount(userId: UUID, bankCode: String?, accountLast4: String?): Account? {
        if (accountLast4.isNullOrBlank()) return null
        val code = bankCode?.uppercase()?.trim() ?: "UNKNOWN"

        val existing = accountRepository.findByUserAndBankAndLast4(userId, code, accountLast4)
        if (existing != null) return existing

        val account = Account(
            userId = userId,
            accountType = AccountType.SAVINGS,
            bankName = bankNames[code],
            bankCode = code,
            accountLast4 = accountLast4,
            accountName = "${bankNames[code] ?: code} ****$accountLast4"
        )
        val saved = accountRepository.save(account)
        logger.info("Discovered new account: user={}, bank={}, last4={}", userId, code, accountLast4)
        return saved
    }

    suspend fun getUserAccounts(userId: UUID): List<Account> =
        accountRepository.findByUserId(userId)
}
