package dev.adminos.api.domain.ingestion.fallback

import dev.adminos.api.domain.ai.AiPrompt
import dev.adminos.api.domain.ai.AiReading
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.statistics.Statistics
import java.time.LocalDate

/**
 * AOS-705, D-67, as properties over random bank-like messages and random model answers:
 * - what is sent never holds a run of 5+ digits that isn't a money figure (card, account, phone, Aadhaar);
 * - an accepted answer's amount, last 4 and date are always found in the text that was sent.
 */
class MessageReadingPropertyTest {
    private val on = LocalDate.of(2026, 10, 16)

    /** Bank-like messages from random parts (so some answers ground), plus random noise words and numbers. */
    @Provide
    fun messages(): Arbitrary<String> {
        val currency = Arbitraries.of("Rs", "Rs.", "INR", "₹")
        val amount = Arbitraries.of("249.00", "1,250.50", "12,999.00", "1,05,000.00", "49")
        val verb = Arbitraries.of("Dr. from", "Cr. to", "charged on", "debited from", "credited to", "spent on", "cashback to", "txn on")
        val account = Arbitraries.of("A/c XX4321", "card ending 1234", "A/c 5021 0042 9988 4321", "A/c 12344321", "account")
        val merchant = Arbitraries.of("at ZEPTO", "to CAFE", "from RAHUL K", "")
        val date = Arbitraries.of("on 16-10-26", "on 15 Oct 2026", "on 2025-10-16", "")
        val tail = Arbitraries.of("", ". Avl Bal Rs 1,000.00", ". Ref 123456789012", ". Call 98000 00012", ". Fee Rs 10", ". Aadhaar 2345 6789 0123", " 999999")
        val parts = Combinators.combine(currency, amount, verb, account, merchant, date, tail).`as` { c, a, v, acc, m, d, t -> "$c $a $v $acc $m $d$t" }
        val noise = Arbitraries.strings().withCharRange(' ', '~').withChars('₹').ofMaxLength(12)
        return Combinators.combine(parts, noise).`as` { p, n -> if (n.isBlank()) p else "$p $n" }
    }

    @Provide
    fun answers(): Arbitrary<String> {
        val amount = Arbitraries.of("249.00", "249", "1250.50", "12999.00", "105000.00", "49", "999999", "1000.00", "16", "2026", "1234")
        val last4 = Arbitraries.of("4321", "1234", "0012", "0123", "2026", null)
        val date = Arbitraries.of("2026-10-16", "2026-10-15", "2025-10-16", null)
        val direction = Arbitraries.of("debit", "credit")
        val merchant = Arbitraries.of("ZEPTO", "zepto", "CAFE", "RAHUL K", "Zepto Pvt Ltd", null)
        return Combinators.combine(amount, last4, date, direction, merchant).`as` { a, l, d, dir, m ->
            fun s(v: String?) = v?.let { "\"$it\"" } ?: "null"
            """{"kind":"transaction","direction":"$dir","amount":"$a","accountLast4":${s(l)},"merchant":${s(m)},"date":${s(d)}}"""
        }
    }

    private val longBareNumber = Regex("(?<![0-9.,])[0-9]{5,}(?![0-9]|[.,][0-9])")
    private val currencyBefore = Regex("(?:Rs\\.?|INR|₹)\\s*$")
    private val currencyAfter = Regex("^\\s*(?:INR|Rs\\b|rupees\\b)")

    @Property(tries = 500)
    fun `what is sent never holds an unmasked long number`(@ForAll("messages") text: String) {
        val p = ReadMessageTask.prompt(MessageQuestion.of(text, on)) as? AiPrompt.Send ?: return
        val sent = Json.parseToJsonElement(p.user).jsonObject.getValue("message").jsonPrimitive.content
        longBareNumber.findAll(sent).forEach { m ->
            val money = currencyBefore.containsMatchIn(sent.substring(0, m.range.first)) || currencyAfter.containsMatchIn(sent.substring(m.range.last + 1))
            check(money) { "an unmasked number left in what is sent" }
        }
    }

    @Property(tries = 2_000)
    fun `an accepted answer is always grounded in the text that was sent`(@ForAll("messages") text: String, @ForAll("answers") answer: String) {
        val q = MessageQuestion.of(text, on)
        val sent = (q.redacted as? RedactedMessage.Ready)?.text
        val t = sent?.let { (ReadMessageTask.read(answer, q) as? AiReading.Valid)?.value as? MessageAnswer.Transaction }
        Statistics.collect(t != null)
        // The property must not hold vacuously: some random answers are accepted.
        Statistics.coverage { it.check(true).count(java.util.function.Predicate<Int> { n -> n > 0 }) }
        if (sent == null || t == null) return
        val moved = MessageGrounding.amounts(sent).filterNot { it.isBalance }.map { it.amount }
        check(moved.isNotEmpty() && moved.all { it.compareTo(t.amount) == 0 }) { "an ungrounded or ambiguous amount was accepted" }
        t.accountLast4?.let { check(it in MessageGrounding.lastFours(sent)) { "an ungrounded last 4 was accepted" } }
        t.date?.let { check(it in MessageGrounding.dates(sent)) { "an ungrounded date was accepted" } }
        t.merchant?.let { check(sent.contains(it, ignoreCase = true)) { "an ungrounded merchant was accepted" } }
        check(MessageGrounding.direction(sent) == t.direction) { "a direction the words don't say was accepted" }
    }
}
