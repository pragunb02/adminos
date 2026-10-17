package dev.adminos.api.domain.financial.learning

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionType
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Synthetic payees and helpers for the AOS-707 tests: realistic Indian shapes, no real people. */
object LearningFixtures {
    val NOW: Instant = Instant.parse("2026-10-17T10:00:00Z")
    val clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

    /** Local shops by kind: the model should learn the kind word ("medicos", "kirana", "classes"). */
    val PHARMACIES = listOf("Sai Medicos", "Shree Balaji Medicos", "Om Medicos", "Lakshmi Medicos", "Ganga Medicos", "Apna Medicos",
        "Jai Ambe Medicos", "Krishna Medicos", "New Life Medicos", "City Medicos")
    val KIRANAS = listOf("Ramesh Kirana Stores", "Patel Kirana Stores", "Bharat Kirana Stores", "Gupta Kirana Stores", "Sagar Kirana Stores",
        "Durga Kirana Stores", "Annapurna Kirana Stores", "Royal Kirana Stores", "Mahalaxmi Kirana Stores", "Janta Kirana Stores")
    val TUTORS = listOf("Vidya Classes", "Bright Future Classes", "Gyan Classes", "Akash Maths Classes", "Excel Classes", "Prerna Classes",
        "Saraswati Classes", "Toppers Classes", "Shiksha Classes", "Pioneer Classes")
    val CHAI = listOf("Ramu Chai Point", "Raju Chai Point", "Anna Chai Point", "Kumar Chai Point", "Sonu Chai Point", "Babu Chai Point",
        "Monu Chai Point", "Chotu Chai Point", "Bhola Chai Point", "Pappu Chai Point")

    fun input(name: String, method: PaymentMethod = PaymentMethod.UPI, vpa: String? = null) =
        CategoryInput(merchantName = name, merchantRaw = "UPI-${name.uppercase()}-PAYMENT", upiVpa = vpa, paymentMethod = method)

    fun example(name: String, label: TransactionCategory, origin: ExampleOrigin = ExampleOrigin.CORRECTION, key: String = "user:$name") =
        TrainingExample(key, input(name), label, origin)

    /** Corrections teaching four kinds of local shop. */
    fun localShopExamples(perKind: Int = 8): List<TrainingExample> =
        PHARMACIES.take(perKind).map { example(it, TransactionCategory.HEALTH) } +
            KIRANAS.take(perKind).map { example(it, TransactionCategory.SHOPPING) } +
            TUTORS.take(perKind).map { example(it, TransactionCategory.EDUCATION) } +
            CHAI.take(perKind).map { example(it, TransactionCategory.FOOD) }

    fun txn(
        userId: UUID,
        name: String?,
        category: TransactionCategory = TransactionCategory.OTHER,
        source: String = "rules",
        type: TransactionType = TransactionType.DEBIT,
        vpa: String? = null,
        at: Instant = NOW.minusSeconds(3600),
        subcategory: String? = null,
        metadata: Map<String, Any?>? = null
    ) = Transaction(
        userId = userId, type = type, amount = BigDecimal("240.00"), merchantName = name,
        merchantRaw = name?.let { "UPI-${it.uppercase()}-PAYMENT" }, upiVpa = vpa, paymentMethod = PaymentMethod.UPI,
        category = category, subcategory = subcategory, categorySource = source, transactedAt = at, metadata = metadata,
        createdAt = at, updatedAt = at
    )

    /** A small calibrated model trained on [localShopExamples]: fast and deterministic. */
    fun trainedModel(examples: List<TrainingExample> = localShopExamples()): FittedModel =
        CategoryModelFitter(SoftmaxRegressionTrainer()).fit(examples)
}
