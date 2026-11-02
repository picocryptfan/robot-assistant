// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.sums

import java.math.BigDecimal

// Pure. The units Sums converts (docs/plans/everyday-set-plan.md §4.2, D7, Q6), each by an exact factor to its kind's base
// unit (metre, kilogram, litre, metre per second, square metre, second), as a fraction so km/h stays exact. Q6's default:
// Canadian measures first, a cup of 250 mL, a metric teaspoon (5 mL) and tablespoon (15 mL), and the imperial gallon, pint,
// quart and fluid ounce; the US measure is named in the same reply ([Unit.us]). No milligram, microgram, IU or cc: doses are
// never Robot's sums (§4.6), and a word Sums doesn't know leaves the question to the model.

/** What a unit measures: units convert only within one kind ([TEMPERATURE] and [FUEL] by their own rules). */
enum class Dimension { LENGTH, MASS, VOLUME, TEMPERATURE, SPEED, AREA, FUEL, TIME }

/**
 * A unit: [num] / [den] base units in one ([Dimension.TEMPERATURE] and [Dimension.FUEL] use [Units.convert]'s own rules).
 * [said]: the words that name it, accents dropped and lower case, each a list of atoms as SumWords splits them ("km / h").
 * [us]: the US measure of the same name, named beside a Canadian one (Q6). [kitchen]: a cook's measure, shown to one
 * decimal (a cup is never 4.23 of anything). [key]: its catalog entry (core i18n EverydayStrings "unit_<key>").
 */
enum class Unit(val dim: Dimension, num: String, den: String, val key: String, vararg said: String) {
    MM(Dimension.LENGTH, "0.001", "1", "mm", "mm", "millimetre", "millimetres", "millimeter", "millimeters"), // l10n: not text (the words a unit is heard by)
    CM(Dimension.LENGTH, "0.01", "1", "cm", "cm", "centimetre", "centimetres", "centimeter", "centimeters"), // l10n: not text (the words a unit is heard by)
    M(Dimension.LENGTH, "1", "1", "m", "m", "metre", "metres", "meter", "meters"), // l10n: not text (the words a unit is heard by)
    KM(Dimension.LENGTH, "1000", "1", "km", "km", "kms", "kilometre", "kilometres", "kilometer", "kilometers"), // l10n: not text (the words a unit is heard by)
    INCH(Dimension.LENGTH, "0.0254", "1", "inch", "inch", "inches", "pouce", "pouces"), // l10n: not text (the words a unit is heard by)
    FOOT(Dimension.LENGTH, "0.3048", "1", "foot", "foot", "feet", "ft", "pied", "pieds"), // l10n: not text (the words a unit is heard by)
    YARD(Dimension.LENGTH, "0.9144", "1", "yard", "yard", "yards", "yd", "yds", "verge", "verges"), // l10n: not text (the words a unit is heard by)
    MILE(Dimension.LENGTH, "1609.344", "1", "mile", "mile", "miles", "mi", "milles"), // l10n: not text (the words a unit is heard by)
    NAUTICAL_MILE(Dimension.LENGTH, "1852", "1", "nautical_mile", "nautical mile", "nautical miles", "mille marin", "milles marins"), // l10n: not text (the words a unit is heard by)

    G(Dimension.MASS, "0.001", "1", "g", "g", "gram", "grams", "gramme", "grammes"), // l10n: not text (the words a unit is heard by)
    KG(Dimension.MASS, "1", "1", "kg", "kg", "kgs", "kilo", "kilos", "kilogram", "kilograms", "kilogramme", "kilogrammes"), // l10n: not text (the words a unit is heard by)
    LB(Dimension.MASS, "0.45359237", "1", "lb", "lb", "lbs", "pound", "pounds", "livre", "livres"), // l10n: not text (the words a unit is heard by)
    OZ(Dimension.MASS, "0.028349523125", "1", "oz", "oz", "ounce", "ounces", "once", "onces"), // l10n: not text (the words a unit is heard by)
    STONE(Dimension.MASS, "6.35029318", "1", "stone", "stone", "stones"), // l10n: not text (the words a unit is heard by)
    TONNE(Dimension.MASS, "1000", "1", "tonne", "tonne", "tonnes", "metric ton", "metric tons"), // l10n: not text (the words a unit is heard by)

    ML(Dimension.VOLUME, "0.001", "1", "ml", "ml", "millilitre", "millilitres", "milliliter", "milliliters"), // l10n: not text (the words a unit is heard by)
    L(Dimension.VOLUME, "1", "1", "l", "l", "litre", "litres", "liter", "liters"), // l10n: not text (the words a unit is heard by)
    TSP_US(Dimension.VOLUME, "0.00492892159375", "1", "tsp_us", "us teaspoon", "us teaspoons", "us tsp", "cuillere a the americaine", "cuilleres a the americaines"), // l10n: not text (the words a unit is heard by)
    TSP(Dimension.VOLUME, "0.005", "1", "tsp", "teaspoon", "teaspoons", "tsp", "cuillere a the", "cuilleres a the", "cuillere a cafe", "cuilleres a cafe"), // l10n: not text (the words a unit is heard by)
    TBSP_US(Dimension.VOLUME, "0.01478676478125", "1", "tbsp_us", "us tablespoon", "us tablespoons", "us tbsp", "cuillere a soupe americaine", "cuilleres a soupe americaines"), // l10n: not text (the words a unit is heard by)
    TBSP(Dimension.VOLUME, "0.015", "1", "tbsp", "tablespoon", "tablespoons", "tbsp", "cuillere a soupe", "cuilleres a soupe", "cuillere a table", "cuilleres a table"), // l10n: not text (the words a unit is heard by)
    CUP_US(Dimension.VOLUME, "0.2365882365", "1", "cup_us", "us cup", "us cups", "american cup", "american cups", "tasse americaine", "tasses americaines"), // l10n: not text (the words a unit is heard by)
    CUP(Dimension.VOLUME, "0.25", "1", "cup", "cup", "cups", "tasse", "tasses"), // l10n: not text (the words a unit is heard by)
    FLOZ_US(Dimension.VOLUME, "0.0295735295625", "1", "floz_us", "us fluid ounce", "us fluid ounces", "us fl oz", "once liquide americaine", "onces liquides americaines"), // l10n: not text (the words a unit is heard by)
    FLOZ(Dimension.VOLUME, "0.0284130625", "1", "floz", "fluid ounce", "fluid ounces", "fl oz", "imperial fluid ounce", "imperial fluid ounces", "once liquide", "onces liquides"), // l10n: not text (the words a unit is heard by)
    PINT_US(Dimension.VOLUME, "0.473176473", "1", "pint_us", "us pint", "us pints"), // l10n: not text (the words a unit is heard by)
    PINT(Dimension.VOLUME, "0.56826125", "1", "pint", "pint", "pints", "imperial pint", "imperial pints"), // l10n: not text (the words a unit is heard by)
    QUART_US(Dimension.VOLUME, "0.946352946", "1", "quart_us", "us quart", "us quarts"), // l10n: not text (the words a unit is heard by)
    QUART(Dimension.VOLUME, "1.1365225", "1", "quart", "quart", "quarts", "imperial quart", "imperial quarts"), // l10n: not text (the words a unit is heard by)
    GALLON_US(Dimension.VOLUME, "3.785411784", "1", "gallon_us", "us gallon", "us gallons", "american gallon", "american gallons", "gallon americain", "gallons americains"), // l10n: not text (the words a unit is heard by)
    GALLON(Dimension.VOLUME, "4.54609", "1", "gallon", "gallon", "gallons", "gal", "imperial gallon", "imperial gallons", "gallon imperial", "gallons imperiaux"), // l10n: not text (the words a unit is heard by)

    CELSIUS(Dimension.TEMPERATURE, "1", "1", "celsius", "celsius", "°c", "degree celsius", "degrees celsius", "degrees c", "degre celsius", "degres celsius", "centigrade"), // l10n: not text (the words a unit is heard by)
    FAHRENHEIT(Dimension.TEMPERATURE, "1", "1", "fahrenheit", "fahrenheit", "°f", "degree fahrenheit", "degrees fahrenheit", "degrees f", "degre fahrenheit", "degres fahrenheit"), // l10n: not text (the words a unit is heard by)
    KELVIN(Dimension.TEMPERATURE, "1", "1", "kelvin", "kelvin", "kelvins"), // l10n: not text (the words a unit is heard by)

    KMH(Dimension.SPEED, "1000", "3600", "kmh", "km / h", "kmh", "kph", "km h", "kilometres per hour", "kilometers per hour", "kilometres an hour", "kilometers an hour", // l10n: not text (the words a unit is heard by)
        "kilometres a l heure", "kilometres heure", "km a l heure"), // l10n: not text (the words a unit is heard by)
    MPH(Dimension.SPEED, "1609.344", "3600", "mph", "mph", "miles per hour", "miles an hour", "milles a l heure", "milles heure"), // l10n: not text (the words a unit is heard by)
    MPS(Dimension.SPEED, "1", "1", "mps", "m / s", "metres per second", "meters per second", "metres par seconde", "metre par seconde"), // l10n: not text (the words a unit is heard by)
    KNOT(Dimension.SPEED, "1852", "3600", "knot", "knot", "knots", "noeud", "noeuds"), // l10n: not text (the words a unit is heard by)

    SQ_M(Dimension.AREA, "1", "1", "sq_m", "square metre", "square metres", "square meter", "square meters", "m2", "m²", "sq m", "metre carre", "metres carres"), // l10n: not text (the words a unit is heard by)
    SQ_KM(Dimension.AREA, "1000000", "1", "sq_km", "square kilometre", "square kilometres", "square kilometer", "square kilometers", "km2", "km²", // l10n: not text (the words a unit is heard by)
        "kilometre carre", "kilometres carres"), // l10n: not text (the words a unit is heard by)
    SQ_FT(Dimension.AREA, "0.09290304", "1", "sq_ft", "square foot", "square feet", "sq ft", "ft2", "ft²", "pied carre", "pieds carres"), // l10n: not text (the words a unit is heard by)
    SQ_MI(Dimension.AREA, "2589988.110336", "1", "sq_mi", "square mile", "square miles", "sq mi", "mille carre", "milles carres"), // l10n: not text (the words a unit is heard by)
    ACRE(Dimension.AREA, "4046.8564224", "1", "acre", "acre", "acres"), // l10n: not text (the words a unit is heard by)
    HECTARE(Dimension.AREA, "10000", "1", "hectare", "hectare", "hectares", "ha"), // l10n: not text (the words a unit is heard by)

    L_100KM(Dimension.FUEL, "1", "1", "l_100km", "l / 100 km", "l / 100km", "litres per 100 km", "liters per 100 km", "litres per 100 kilometres", // l10n: not text (the words a unit is heard by)
        "litres aux 100 km", "litres aux 100 kilometres", "l aux 100 km", "litres au 100 km"), // l10n: not text (the words a unit is heard by)
    MPG_US(Dimension.FUEL, "1", "1", "mpg_us", "us mpg", "us miles per gallon"), // l10n: not text (the words a unit is heard by)
    MPG(Dimension.FUEL, "1", "1", "mpg", "mpg", "miles per gallon", "miles to the gallon", "milles au gallon", "imperial mpg"), // l10n: not text (the words a unit is heard by)

    SECOND(Dimension.TIME, "1", "1", "second", "second", "seconds", "sec", "secs", "seconde", "secondes"), // l10n: not text (the words a unit is heard by)
    MINUTE(Dimension.TIME, "60", "1", "minute", "minute", "minutes", "min", "mins"), // l10n: not text (the words a unit is heard by)
    HOUR(Dimension.TIME, "3600", "1", "hour", "hour", "hours", "hr", "hrs", "h", "heure", "heures"), // l10n: not text (the words a unit is heard by)
    DAY(Dimension.TIME, "86400", "1", "day", "day", "days", "jour", "jours"), // l10n: not text (the words a unit is heard by)
    WEEK(Dimension.TIME, "604800", "1", "week", "week", "weeks", "semaine", "semaines"), // l10n: not text (the words a unit is heard by)
    ;

    val num: BigDecimal = BigDecimal(num)
    val den: BigDecimal = BigDecimal(den)
    val said: List<List<String>> = said.map { it.split(' ') }

    /** The US measure of the same name (Q6), named beside this Canadian one in a reply; null when there is none. */
    val us: Unit? get() = when (this) {
        TSP -> TSP_US
        TBSP -> TBSP_US
        CUP -> CUP_US
        FLOZ -> FLOZ_US
        PINT -> PINT_US
        QUART -> QUART_US
        GALLON -> GALLON_US
        MPG -> MPG_US
        else -> null
    }

    /** A cook's measure: its amounts are shown to one decimal. */
    val kitchen: Boolean get() = this in setOf(TSP, TSP_US, TBSP, TBSP_US, CUP, CUP_US, FLOZ, FLOZ_US)
}

object Units {
    /** Every name of every unit, longest first, so "square metres" wins over "metres" and "us cups" over "cups". */
    val NAMES: List<Pair<List<String>, Unit>> = Unit.entries.flatMap { u -> u.said.map { it to u } }.sortedByDescending { it.first.size }

    /** Litres per 100 km times miles per imperial gallon: 100 × 4.54609 / 1.609344. */
    private val IMPERIAL_FUEL: BigDecimal = BigDecimal("454.609").divide(BigDecimal("1.609344"), Exact.MC)
    private val US_FUEL: BigDecimal = BigDecimal("378.5411784").divide(BigDecimal("1.609344"), Exact.MC)

    /** [a] and [b] can be converted into each other. */
    fun compatible(a: Unit, b: Unit): Boolean = a.dim == b.dim

    /**
     * [v] [from] in [to], exactly as far as [Exact.MC] goes; null when they measure different things, or a fuel figure of 0
     * (no fuel at all is no mileage either).
     */
    fun convert(v: BigDecimal, from: Unit, to: Unit): BigDecimal? {
        if (!compatible(from, to)) return null
        if (from == to) return v
        return when (from.dim) {
            Dimension.TEMPERATURE -> fromKelvin(toKelvin(v, from), to)
            Dimension.FUEL -> {
                val per100 = when (from) {
                    Unit.L_100KM -> v
                    else -> if (v.signum() == 0) return null else (if (from == Unit.MPG) IMPERIAL_FUEL else US_FUEL).divide(v, Exact.MC)
                }
                when (to) {
                    Unit.L_100KM -> per100
                    else -> if (per100.signum() == 0) null else (if (to == Unit.MPG) IMPERIAL_FUEL else US_FUEL).divide(per100, Exact.MC)
                }
            }
            else -> v.multiply(from.num).multiply(to.den).divide(from.den.multiply(to.num), Exact.MC)
        }
    }

    private val K0 = BigDecimal("273.15")
    private val NINE = BigDecimal(9)
    private val FIVE = BigDecimal(5)
    private val F0 = BigDecimal("459.67")

    private fun toKelvin(v: BigDecimal, u: Unit): BigDecimal = when (u) {
        Unit.CELSIUS -> v.add(K0)
        Unit.FAHRENHEIT -> v.add(F0).multiply(FIVE).divide(NINE, Exact.MC)
        else -> v
    }

    private fun fromKelvin(k: BigDecimal, u: Unit): BigDecimal = when (u) {
        Unit.CELSIUS -> k.subtract(K0)
        Unit.FAHRENHEIT -> k.multiply(NINE).divide(FIVE, Exact.MC).subtract(F0)
        else -> k
    }

    /**
     * How many decimals a reply shows for an amount in [u]: whole degrees (§4.5, "about 177 °C"), a cook's measure to one
     * decimal ("about 4.2 US cups"), everything else to two ("about 8.05 km", "2.54 cm").
     */
    fun decimals(u: Unit): Int = when {
        u.dim == Dimension.TEMPERATURE -> 0
        u.kitchen -> 1
        else -> 2
    }

    /** F83: [decimals], or more when [v] would round away: a small amount keeps two significant digits ("about 0.0042 US cups", not "about 0"). */
    fun decimals(u: Unit, v: BigDecimal): Int {
        val least = decimals(u)
        if (u.dim == Dimension.TEMPERATURE || v.signum() == 0) return least
        return maxOf(least, minOf(v.scale() - v.precision() + 2, MAX_DECIMALS))
    }

    private const val MAX_DECIMALS = 10
}
