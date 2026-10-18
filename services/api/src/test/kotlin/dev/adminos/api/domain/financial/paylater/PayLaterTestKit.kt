package dev.adminos.api.domain.financial.paylater

import dev.adminos.api.domain.financial.BillRepository
import dev.adminos.api.domain.financial.BillTrackingService
import dev.adminos.api.domain.financial.InMemoryBillRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.TransactionRepository

/** Pay-later bill recording over in-memory stores, for tests that build an SmsIntake (AOS-909). */
fun testPayLaterBills(bills: BillRepository = InMemoryBillRepository(), txns: TransactionRepository = InMemoryTransactionRepository()) =
    PayLaterBills(bills, BillTrackingService(bills, txns))
