package com.linguareader.core.platform

/**
 * Equivalente multiplataforma de `"%.Nf".format(Locale.ROOT, value)` de Java: parte de la representación decimal
 * más corta del double (`Double.toString`, idéntica en JVM y Kotlin/Native) y redondea HALF_UP sobre esos dígitos,
 * que es lo que hace `java.util.Formatter`. Se usa en claves de caché: cambiar el resultado invalidaría cachés.
 */
fun formatFixed(value: Double, decimals: Int): String {
    require(decimals >= 0)
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    val raw = value.toString()
    val negative = raw.startsWith('-')
    val body = raw.removePrefix("-")
    val ePos = body.indexOfFirst { it == 'E' || it == 'e' }
    val mantissa = if (ePos >= 0) body.substring(0, ePos) else body
    val exponent = if (ePos >= 0) body.substring(ePos + 1).toInt() else 0
    val dot = mantissa.indexOf('.')
    val digits = StringBuilder(mantissa.replace(".", ""))
    var pointPos = (if (dot >= 0) dot else mantissa.length) + exponent // dígitos enteros
    // Asegura dígitos a ambos lados del punto.
    while (pointPos <= 0) { digits.insert(0, '0'); pointPos++ }
    while (digits.length < pointPos + decimals + 1) digits.append('0')
    // Redondeo HALF_UP en la posición `decimals` tras el punto.
    val cut = pointPos + decimals
    var roundUp = digits[cut] >= '5'
    digits.setLength(cut)
    var i = cut - 1
    while (roundUp && i >= 0) {
        if (digits[i] == '9') { digits[i] = '0'; i-- } else { digits[i] = digits[i] + 1; roundUp = false }
    }
    if (roundUp) { digits.insert(0, '1'); pointPos++ }
    val intPart = digits.substring(0, pointPos).trimStart('0').ifEmpty { "0" }
    val fracPart = digits.substring(pointPos)
    val text = if (decimals == 0) intPart else "$intPart.$fracPart"
    // Java escribe "-0.00" para negativos que redondean a cero; se conserva.
    return if (negative) "-$text" else text
}
