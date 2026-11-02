// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.sums

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/**
 * SumsRouter (everyday-set-plan.md §4.3, §4.6) and code's replies (§4.5), in English and Québec French, on screen and aloud:
 * the plan's phrases, the Test Bench's SU1 to SU4 words, and every misfire, doses first.
 */
class SumsRouterTest {
    /** Friday 23 October 2026 in Halifax: Halifax leaves daylight time on 1 November, inside "days until November 20". */
    private val now = ZonedDateTime.parse("2026-10-23T09:30:00-03:00[America/Halifax]")
    private val en = Langs(Lang.EN, Lang.EN, Lang.EN)
    private val fr = Langs(Lang.FR, Lang.FR, Lang.FR)

    private fun number(t: String, lang: Lang) = SumWords.tokens(t, lang)

    @Test fun aDecimalSaidAsOneNumberIsOneNumber() {
        fun one(s: String) = Tok.N(java.math.BigDecimal(s))
        assertEquals(listOf(one("2.25")), number("two point twenty five", Lang.EN))
        assertEquals(listOf(one("2.25")), number("two point twenty-five", Lang.EN))
        assertEquals(listOf(one("2.25")), number("two point two five", Lang.EN))
        assertEquals(listOf(one("2.25")), number("deux virgule vingt-cinq", Lang.FR))
        assertEquals(listOf(one("2.75")), number("deux virgule soixante-quinze", Lang.FR))
        assertEquals(listOf(one("1.85")), number("un virgule quatre-vingt-cinq", Lang.FR))
        assertEquals(listOf(one("0.5")), number("virgule cinq", Lang.FR))
        assertEquals(listOf(one("0.25")), number("point twenty five", Lang.EN))
        assertEquals("2.25 × 3 = 6.75", say("two point twenty five times three"))
    }

    @Test fun aScaleAfterAScaleMultiplies() {
        fun one(s: String) = listOf(Tok.N(java.math.BigDecimal(s)))
        assertEquals(one("2000000000"), number("two thousand million", Lang.EN))
        assertEquals(one("2500000000"), number("two thousand five hundred million", Lang.EN))
        assertEquals(one("2000000000000"), number("two million million", Lang.EN))
        assertEquals(one("2000000000"), number("deux mille millions", Lang.FR))
        assertEquals(one("2003000"), number("two million three thousand", Lang.EN))
        assertEquals(one("3200000"), number("trois millions deux cent mille", Lang.FR))
    }

    @Test fun wasAsksAboutTheDayGoneBy() {
        fun date(t: String) = (cmd(t) as SumsCommand.Weekday).date
        assertEquals(java.time.LocalDate.of(2025, 12, 25), date("what day was christmas"))
        assertEquals(java.time.LocalDate.of(2026, 12, 25), date("what day is christmas"))
        assertEquals(java.time.LocalDate.of(2025, 12, 25), date("quel jour était Noël"))
        assertEquals(java.time.LocalDate.of(2026, 12, 25), date("quel jour tombe Noël"))
    }

    @Test fun aConversionIntoALargerUnitKeepsTwoSignificantDigits() {
        assertTrue(say("5 mL in cups"), say("5 mL in cups").contains("0.02 cups"))
        assertTrue(say("5 mL in cups"), say("5 mL in cups").contains("about 0.021 US cups"))
        assertTrue(say("3 feet in miles"), say("3 feet in miles").contains("about 0.00057"))
        assertEquals("5 miles is about 8.05 km.", say("5 miles in kilometres"))
        assertEquals("350 °F is about 177 °C.", say("350 °F in Celsius"))
    }

    private fun cmd(t: String): SumsCommand = SumsRouter.route(t, now) ?: throw AssertionError("not taken: $t")
    /** The reply's screen and voice text, with French spaces as plain spaces and ’ as ' (to write them here). */
    private fun say(t: String, voice: Boolean = false): String {
        val c = cmd(t)
        val l = (if (c.lang == Lang.FR) fr else en).let { if (voice) it.voice else it }
        return Sums.answer(c).text.render(l).replace(' ', ' ').replace(' ', ' ').replace('’', '\'')
    }

    @Test fun su1TheTip() {
        assertEquals("An 18% tip on $64.50 is $11.61. Total: $76.11.", say("what's an 18% tip on $64.50"))
        assertEquals("An 18 percent tip on 64 dollars 50 is 11 dollars 61. The total is 76 dollars 11.", say("what's an 18% tip on $64.50", voice = true))
        assertEquals("An 18% tip on $64.50 is $11.61. Total: $76.11.", say("18% tip on 64.50"))
        assertEquals("A 15% tip on $40 is $6. Total: $46.", say("15% tip on $40").replace(".00", ""))
        assertEquals("Un pourboire de 18 % sur 64,50 $, c'est 11,61 $. Total : 76,11 $.", say("un pourboire de 18 % sur 64,50 $"))
    }

    @Test fun su2UnitsAndDates() {
        assertEquals("350 °F is about 177 °C.", say("350 °F in Celsius"))
        assertEquals("350 degrees Fahrenheit is about 177 degrees Celsius.", say("350 °F in Celsius", voice = true))
        assertEquals("350 °F is about 177 °C.", say("350 degrees in Celsius"))
        assertEquals("1 L is 4 cups of 250 mL. In US measure, about 4.2 US cups.", say("how many cups in a litre"))
        // 28 days, though Halifax leaves daylight time on the way: counted in dates, never in hours.
        assertEquals("28 days: Friday November 20.", say("how many days until November 20"))
    }

    @Test fun su3French() {
        assertEquals("18 % de 64,50 $, c'est 11,61 $.", say("combien font 18 % de 64,50 $"))
        assertEquals("18 pour cent de 64 dollars et 50, c'est 11 dollars et 61.", say("combien font 18 % de 64,50 $", voice = true))
        assertEquals("350 °F, c'est environ 177 °C.", say("350 degrés Fahrenheit en Celsius"))
        assertEquals("350 degrés Fahrenheit, c'est environ 177 degrés Celsius.", say("350 degrés Fahrenheit en Celsius", voice = true))
        assertEquals("28 jours : vendredi 20 novembre.", say("combien de jours avant le 20 novembre"))
    }

    /** SU4 and §4.6: none of these is a sum Robot takes; doses above all. */
    @Test fun su4AndEveryMisfireGoOn() {
        for (t in listOf("how much ibuprofen for a 30 kg child", "how many mg in 2 tablets of 200 mg", "how much Tylenol can I take in 24 hours",
            "how many mL of amoxicillin for a 20 kg child", "how many milligrams in 2 tablets", "how many mg in a gram", "convert 500 mg to grams",
            "2 tablespoons of cough syrup in grams", "set a timer for 5 minutes", "remind me in 20 minutes", "add 2 litres of milk to the shopping list",
            "how much did I spend at Costco in October", "how many days until my dentist appointment", "call 911", "7-Eleven hours", "what's 5G",
            "what's the 401(k) limit", "convert this PDF to text", "how far is Montréal", "what's 20 dollars in euros", "how many calories in 2 cups of rice",
            "what's 3 plus 4 kids", "90 minutes", "7 pm", "19 h", "0 minutes", "lock the phone for 30 minutes", "what day is it", "how old is Céline Dion",
            "answer this: what is 2 plus 2", "what's the weather in 3 days",
            "combien d'Advil pour un enfant de 30 kg", "combien de mg dans 2 comprimés de 200 mg", "mets un minuteur de 5 minutes",
            "rappelle-moi dans 20 minutes", "ajoute 2 litres de lait à la liste d'épicerie", "combien j'ai dépensé chez Costco en octobre",
            "c'est quoi la 5G", "combien de kilomètres jusqu'à Québec")) assertNull(t, SumsRouter.route(t, now))
    }

    @Test fun aDashOrSlashBetweenDigitsNeedsASumWord() {
        for (t in listOf("what's 7-11", "what's 9/11", "what is 24/7", "what's 2024-10-05", "555-0123", "7-11", "24/7", "what's 10-2-3", "c'est quoi 7-11",
            "c'est 24/7", "dis-moi 9/11", "calculate 555-0123", "how much is 902-555-0123", "combien fait 2024-10-05", "calcule 10/31/2026", "what's 10/31/2026")) {
            assertNull(t, SumsRouter.route(t, now))
        }
        for (t in listOf("what's 7 - 11", "what's 9 / 11", "7 - 11", "how much is 7-11", "calculate 9/11", "combien font 24/7", "calcule 7-11", "what's 10/2+3",
            "what's 7 minus 11", "c'est combien 24/7")) {
            assertTrue(t, cmd(t) is SumsCommand.Arith)
        }
    }

    @Test fun aMarkBeforeThreeDigitsMayBeThousandsSoItIsntGuessed() {
        for (t in listOf("1,000 fois 3", "2,500 fois 3", "what's 1.000 times 3", "what's 2.500 times 3", "3.125 times 2", "1.000 fois 3")) assertNull(t, SumsRouter.route(t, now))
        assertEquals("3 000", say("1 000 fois 3").substringAfterLast("= ").replace('\u00a0', ' ').replace('\u202f', ' ').trimEnd('.'))
        for ((t, want) in listOf("what's 1,000 times 3" to "3,000", "0.125 times 8" to "1", "1,5 fois 2" to "3", "2.50 times 2" to "5", "0,125 fois 8" to "1")) {
            assertEquals(t, want, say(t).substringAfterLast("= ").trimEnd('.'))
        }
    }

    @Test fun aLoneMinusIsntASumAndANegativeBaseShowsItsBrackets() {
        for (t in listOf("minus 5", "-5", "negative 5", "moins 5")) assertNull(t, SumsRouter.route(t, now))
        assertTrue(say("minus 5 plus 3").endsWith("= -2") || say("minus 5 plus 3").contains("-2"))
        assertTrue(say("-2 squared"), say("-2 squared").contains("(-2)"))
        assertTrue(say("moins 2 au carré"), say("moins 2 au carré").contains("(-2)"))
        assertTrue(say("-2 squared"), say("-2 squared").endsWith("4") || say("-2 squared").endsWith("4."))
        assertTrue(say("2 squared"), !say("2 squared").contains("("))
    }

    @Test fun arithmeticReadsBack() {
        assertEquals("12 × 7 = 84", say("what's 12 times 7"))
        assertEquals("12 times 7 is 84.", say("what's 12 times 7", voice = true))
        assertEquals("12 × 7 = 84", say("what's twelve times seven"))
        assertEquals("350 ÷ 7 = 50", say("how much is 350 divided by 7"))
        assertEquals("15 + 27 − 4 = 38", say("what's 15 plus 27 minus 4"))
        assertEquals("2^10 = 1,024", say("what's 2 to the power of 10"))
        assertEquals("√2 = about 1.414214", say("what's the square root of 2"))
        assertEquals("3 × 45 × 1.13 = 152.55", say("calculate 3 times 45 times 1.13"))
        assertEquals("$45.00 × 3 = $135.00", say("how much is $45 times 3"))
        assertEquals("1,500 ÷ 12 = 125", say("what's 1,500 divided by 12"))
        assertEquals("7 × 8 = 56", say("what is 7 x 8"))
        assertEquals("You can't divide by zero.", say("what's 5 divided by 0"))
        assertEquals("12 × 7 = 84", say("combien font 12 fois 7"))
        assertEquals("12 fois 7, ça fait 84.", say("combien font 12 fois 7", voice = true))
        assertEquals("1,5 × 4 = 6", say("combien font 1,5 fois 4"))
        assertEquals("1 500 ÷ 12 = 125", say("combien font 1 500 divisé par 12"))
        assertEquals("2^10 = 1 024", say("c'est combien 2 à la puissance 10"))
        assertEquals("100 ÷ 3 = about 33.333333", say("what's 100 divided by 3"))
    }

    @Test fun percentagesTaxAndSplits() {
        assertEquals("18% of 64.5 is 11.61.", say("what's 18% of 64.50"))
        assertEquals("15% of 80 is 12.", say("what's 15 % of 80"))
        assertEquals("12 is 15% of 80.", say("what percent is 12 of 80"))
        assertEquals("64.5 plus 13% is 72.885.", say("what's 64.50 plus 13%"))
        assertEquals("15% off $80.00 is $68.00. You save $12.00.", say("what's 15% off $80"))
        assertEquals("$80.00 plus 15% tax is $92.00. That's $12.00 of tax.", say("$80 with 15% tax"))
        assertEquals("80,00 $ plus 15 % de taxes, c'est 92,00 $, dont 12,00 $ de taxes.", say("80 $ avec 15 % de taxes"))
        val hst = cmd("$80 plus HST") as SumsCommand.Tax
        assertEquals(null, hst.p)
        val asked = Sums.answer(hst)
        assertEquals(SumResult.ASKED_RATE, asked.result)
        assertEquals(listOf(13, 14, 15), asked.rates.map { it.toInt() })
        assertEquals("$80.00 plus 13% HST is $90.40. That's $10.40 of HST.", Sums.withRate(hst, asked.rates[0]).text.render(en))
        assertEquals(SumResult.ASKED_RATE, Sums.answer(cmd("80 $ plus la TVH")).result)
        assertEquals("$120.00 split 3 ways is $40.00 each.", say("$120 split three ways"))
        assertEquals("$100.00 split 3 ways: $33.34, $33.33 and $33.33.", say("split $100 3 ways"))
    }

    @Test fun moreUnits() {
        assertEquals("5 miles is about 8.05 km.", say("5 miles in kilometres"))
        assertEquals("5 miles is about 8.05 km.", say("convert 5 miles to km"))
        assertEquals("60 mph is about 96.56 km/h.", say("60 mph in km/h"))
        assertEquals("8 L/100 km is about 35.31 mpg (imperial). In US measure, about 29.4 mpg (US).", say("8 L/100 km in mpg"))
        assertEquals("1 lb is 16 oz.", say("how many ounces in a pound"))
        assertEquals("3 hours is 180 minutes.", say("how many minutes in 3 hours"))
        assertEquals("Robot can't turn a volume into a weight: it depends on what's measured.", say("2 cups of flour in grams"))
        assertEquals("1 imperial gallon is about 4.55 L. For 1 US gallon, about 3.79 L.", say("1 gallon in litres"))
        assertEquals("5 milles, c'est environ 8,05 km.", say("5 milles en kilomètres"))
        assertEquals("1 L, c'est 4 tasses de 250 mL. En mesure américaine, environ 4,2 tasses américaines.", say("combien de tasses dans un litre"))
        assertEquals("10 kg, c'est environ 22,05 lb.", say("convertis 10 kilos en livres"))
    }

    @Test fun moreDates() {
        assertEquals("20 weeks and 4 days since Monday June 1.", say("how many weeks since June 1"))
        assertEquals("It's Friday December 25.", say("what day of the week is Christmas"))
        assertEquals("In 3 weeks: Friday November 13.", say("what's the date in 3 weeks"))
        assertEquals("C'est vendredi 25 décembre.", say("quel jour de la semaine tombe Noël"))
        assertEquals("That's today: Friday October 23.", say("how many days until October 23"))
        assertEquals("70 days: Friday January 1, 2027.", say("how many days until New Year's Day"))
    }

    @Test fun numberWords() {
        val a = SumWords.tokens("sixty-four fifty", Lang.EN)!!
        assertEquals(listOf(Tok.N(java.math.BigDecimal("64.50"))), a)
        assertEquals(listOf(Tok.N(java.math.BigDecimal("18"), pct = true)), SumWords.tokens("dix-huit pour cent", Lang.FR))
        assertEquals(listOf(Tok.N(java.math.BigDecimal("64.50"))), SumWords.tokens("soixante-quatre et cinquante", Lang.FR))
        assertEquals(listOf(Tok.N(java.math.BigDecimal("21"))), SumWords.tokens("vingt et un", Lang.FR))
        assertEquals(listOf(Tok.N(java.math.BigDecimal("97"))), SumWords.tokens("quatre-vingt-dix-sept", Lang.FR))
        assertEquals(listOf(Tok.N(java.math.BigDecimal("77"))), SumWords.tokens("soixante-dix-sept", Lang.FR))
        assertEquals(listOf(Tok.N(java.math.BigDecimal("80"))), SumWords.tokens("quatre-vingts", Lang.FR))
        assertEquals(listOf(Tok.N(java.math.BigDecimal("1.5"))), SumWords.tokens("un virgule cinq", Lang.FR))
        assertEquals(listOf(Tok.N(java.math.BigDecimal("0.5"))), SumWords.tokens("point five", Lang.EN))
        assertEquals(listOf(Tok.N(java.math.BigDecimal("3.5"))), SumWords.tokens("three and a half", Lang.EN))
        assertEquals(listOf(Tok.N(java.math.BigDecimal("2500"))), SumWords.tokens("two thousand five hundred", Lang.EN))
        assertEquals(listOf(Tok.N(java.math.BigDecimal("105"))), SumWords.tokens("one hundred and five", Lang.EN))
        assertEquals(listOf(Tok.N(java.math.BigDecimal("1500"))), SumWords.tokens("1,500", Lang.EN))
        assertEquals(listOf(Tok.N(java.math.BigDecimal("1.5"))), SumWords.tokens("1,5", Lang.FR))
        assertEquals(listOf(Tok.N(java.math.BigDecimal("1500"))), SumWords.tokens("1 500", Lang.FR))
        assertEquals(listOf(Tok.N(java.math.BigDecimal("64.50"), money = true)), SumWords.tokens("64 dollars 50", Lang.EN))
        assertEquals(listOf(Tok.N(java.math.BigDecimal("64.50"), money = true)), SumWords.tokens("64 dollars et 50", Lang.FR))
        assertEquals(listOf(Tok.N(java.math.BigDecimal("64.50"), money = true)), SumWords.tokens("64,50 $", Lang.FR))
        assertEquals(listOf(Tok.N(java.math.BigDecimal("0.50"), money = true)), SumWords.tokens("50 cents", Lang.EN))
        assertEquals("18 % de 64,5, c'est 11,61.", say("combien font dix-huit pour cent de soixante-quatre et cinquante"))
    }

    /** No command ever shows a number in a log line. */
    @Test fun commandsKeepTheirNumbersOutOfLogs() {
        for (t in listOf("what's an 18% tip on $64.50", "350 °F in Celsius", "how many days until November 20", "$80 plus HST", "split $100 3 ways",
            "what's 12 times 7", "what's 18% of 64.50", "what percent is 12 of 80", "what's 15% off $80", "what's the date in 3 weeks")) {
            val s = cmd(t).toString()
            assertTrue("$t: $s", s.none { it.isDigit() })
            assertTrue("$t", Sums.answer(cmd(t)).toString().none { it.isDigit() })
        }
    }
}
