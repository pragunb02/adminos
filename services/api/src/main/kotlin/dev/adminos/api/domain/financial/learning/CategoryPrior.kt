package dev.adminos.api.domain.financial.learning

import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.evaluation.LabelledCase

/**
 * The synthetic prior every user's model starts from (AOS-707): the D-61 golden set, labelled by a careful
 * person, read as training rows. It is synthetic (no personal data), so it can ship with the API.
 * Transfers are left out: the categoriser never guesses Transfer (D-41).
 * [normalize] is the pipeline's merchant normaliser, injected so this domain doesn't reach into ingestion.
 * A row is [TrainingExample.easyForRules] when the built-in rules give its cleaned name a category.
 */
object CategoryPrior {
    const val KEY_PREFIX = "prior:"

    fun examples(
        cases: List<LabelledCase>,
        normalize: (raw: String?, upiVpa: String?) -> String?,
        rules: CategorizationService = CategorizationService()
    ): List<TrainingExample> =
        cases.filter { it.expected.category != TransactionCategory.TRANSFER }.map { case ->
            val input = case.input
            val name = normalize(input.merchantRaw, input.upiVpa)
            val ruled = rules.builtIn(name)
            TrainingExample(
                key = KEY_PREFIX + case.id,
                input = CategoryInput(
                    merchantName = name,
                    merchantRaw = input.merchantRaw,
                    upiVpa = input.upiVpa,
                    paymentMethod = input.paymentMethod,
                    nature = TransactionNature.defaultFor(input.type)
                ),
                label = case.expected.category,
                origin = ExampleOrigin.PRIOR,
                easyForRules = ruled.category != TransactionCategory.OTHER || ruled.subcategory != null
            )
        }

    /** True for a key made by [examples]. */
    fun isPrior(key: String) = key.startsWith(KEY_PREFIX)
}
