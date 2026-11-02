package com.ankit.tallydata

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Draws a voucher as an A4 PDF laid out like Tally's standard GST "Tax Invoice" print:
 * seller block, invoice no/date block, buyer block, item table, tax lines, total,
 * amount in words, bank details, declaration and signatory.
 */
object InvoicePdf {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val M = 28f
    private const val RIGHT = PAGE_W - M
    private const val BOTTOM = PAGE_H - M
    private const val WIDTH = RIGHT - M

    private val regular = paint(8.5f)
    private val small = paint(7.5f)
    private val bold = paint(8.5f, Typeface.BOLD)
    private val big = paint(11f, Typeface.BOLD)
    private val title = paint(13f, Typeface.BOLD)
    private val line = Paint().apply { color = Color.BLACK; strokeWidth = 0.6f; style = Paint.Style.STROKE }

    private fun paint(size: Float, style: Int = Typeface.NORMAL) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = Color.BLACK
        typeface = Typeface.create(Typeface.SANS_SERIF, style)
    }

    private class Col(val title: String, val width: Float, val alignRight: Boolean)

    private class Ctx(val doc: PdfDocument) {
        var pageNo = 0
        lateinit var page: PdfDocument.Page
        lateinit var c: Canvas
        var y = M
        var pageTop = M

        fun newPage() {
            if (pageNo > 0) finishPage()
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            c = page.canvas
            y = M
            pageTop = M
        }

        fun finishPage() {
            c.drawRect(M, pageTop, RIGHT, y, line)
            doc.finishPage(page)
        }
    }

    fun write(
        file: File,
        v: Voucher,
        s: InvoiceSettings,
        party: PartyInfo?,
        docTitle: String,
    ) {
        val doc = PdfDocument()
        val k = Ctx(doc)
        k.newPage()

        // Title
        k.c.drawText(docTitle, (PAGE_W - title.measureText(docTitle)) / 2, k.y + 12, title)
        k.y += 20
        k.pageTop = k.y

        // ---- Seller (left) and invoice details (right) ----
        val split = M + WIDTH * 0.55f
        val sellerTop = k.y
        var ly = sellerTop + 12
        k.c.drawText(s.companyName, M + 4, ly, big); ly += 13
        for (a in s.address.split('\n').map { it.trim() }.filter { it.isNotEmpty() }) {
            for (w in wrap(a, split - M - 8, regular)) { k.c.drawText(w, M + 4, ly, regular); ly += 10.5f }
        }
        fun sellerLine(lbl: String, value: String) {
            if (value.isBlank()) return
            k.c.drawText("$lbl: $value", M + 4, ly, regular); ly += 10.5f
        }
        sellerLine("GSTIN/UIN", s.gstin)
        sellerLine("State Name", s.state)
        sellerLine("PAN", s.pan)
        sellerLine("Contact", s.phone)
        sellerLine("E-Mail", s.email)

        val details = listOf(
            "Invoice No." to v.number, "Dated" to Fmt.date(v.date),
            "Voucher Type" to v.type, "Reference No." to v.reference,
            "Place of Supply" to v.placeOfSupply.ifBlank { party?.state ?: "" }, "" to "",
        )
        val cellH = 24f
        val cellW = (RIGHT - split) / 2
        for ((i, d) in details.withIndex()) {
            val cx = split + (i % 2) * cellW
            val cy = sellerTop + (i / 2) * cellH
            k.c.drawRect(cx, cy, cx + cellW, cy + cellH, line)
            if (d.first.isNotEmpty()) {
                k.c.drawText(d.first, cx + 3, cy + 9, small)
                k.c.drawText(ellipsize(d.second, cellW - 6, bold), cx + 3, cy + 20, bold)
            }
        }
        k.y = max(ly + 2, sellerTop + 3 * cellH)
        k.c.drawLine(M, k.y, RIGHT, k.y, line)
        k.c.drawLine(split, sellerTop, split, k.y, line)

        // ---- Buyer ----
        val buyerTop = k.y
        ly = buyerTop + 10
        k.c.drawText(if (docTitle.contains("Purchase", true)) "Supplier (Bill from)" else "Buyer (Bill to)", M + 4, ly, small); ly += 12
        val buyerName = v.buyerName.ifBlank { v.party.ifBlank { v.displayName } }
        k.c.drawText(buyerName, M + 4, ly, big); ly += 12
        val address = v.buyerAddress.ifEmpty { party?.address ?: emptyList() }
        for (a in address) for (w in wrap(a, WIDTH - 8, regular)) { k.c.drawText(w, M + 4, ly, regular); ly += 10.5f }
        val gstin = v.partyGstin.ifBlank { party?.gstin ?: "" }
        if (gstin.isNotBlank()) { k.c.drawText("GSTIN/UIN: $gstin", M + 4, ly, regular); ly += 10.5f }
        val state = party?.state ?: ""
        if (state.isNotBlank()) { k.c.drawText("State Name: $state", M + 4, ly, regular); ly += 10.5f }
        if (!party?.mobile.isNullOrBlank()) { k.c.drawText("Contact: ${party!!.mobile}", M + 4, ly, regular); ly += 10.5f }
        k.y = ly + 2
        k.c.drawLine(M, k.y, RIGHT, k.y, line)

        if (v.isInvoice) drawItemTable(k, v) else drawLedgerTable(k, v)

        // ---- Amount in words, bank, declaration, signature (needs ~150pt) ----
        if (k.y + 150 > BOTTOM) {
            k.finishPage()
            k.newPage()
        }
        val wordsTop = k.y
        k.c.drawText("Amount Chargeable (in words)", M + 4, wordsTop + 10, small)
        var wy = wordsTop + 22
        for (w in wrap(AmountWords.inr(v.total), WIDTH - 8, bold)) { k.c.drawText(w, M + 4, wy, bold); wy += 11 }
        if (v.narration.isNotBlank()) {
            wy += 2
            for (w in wrap("Remarks: " + v.narration, WIDTH - 8, regular)) { k.c.drawText(w, M + 4, wy, regular); wy += 10.5f }
        }
        k.y = wy + 2

        val half = M + WIDTH / 2
        val blockTop = k.y
        var by = blockTop + 10
        if (s.bank.isNotBlank()) {
            k.c.drawText("Company's Bank Details", half + 4, by, small); by += 11
            for (b in s.bank.split('\n').filter { it.isNotBlank() }) {
                for (w in wrap(b.trim(), RIGHT - half - 8, regular)) { k.c.drawText(w, half + 4, by, regular); by += 10.5f }
            }
        }
        var dy = blockTop + 10
        if (s.terms.isNotBlank()) {
            k.c.drawText("Declaration", M + 4, dy, small); dy += 11
            for (w in wrap(s.terms, half - M - 8, small)) { k.c.drawText(w, M + 4, dy, small); dy += 9.5f }
        }
        val signTop = max(by, dy) + 6
        k.c.drawLine(half, blockTop, half, signTop + 46, line)
        val forText = "for " + s.companyName
        k.c.drawText(ellipsize(forText, RIGHT - half - 8, bold), RIGHT - 4 - bold.measureText(ellipsize(forText, RIGHT - half - 8, bold)), signTop + 8, bold)
        val auth = "Authorised Signatory"
        k.c.drawText(auth, RIGHT - 4 - regular.measureText(auth), signTop + 42, regular)
        k.y = signTop + 46

        val footer = "This is a Computer Generated " + if (v.isInvoice) "Invoice" else "Voucher"
        k.c.drawText(footer, (PAGE_W - small.measureText(footer)) / 2, k.y + 12, small)
        k.finishPage()
        try {
            file.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    private fun drawHeaderRow(k: Ctx, cols: List<Col>) {
        val top = k.y
        var x = M
        for (c in cols) {
            val tw = bold.measureText(c.title)
            k.c.drawText(c.title, x + (c.width - tw) / 2, top + 11, bold)
            x += c.width
        }
        k.y = top + 16
        k.c.drawLine(M, k.y, RIGHT, k.y, line)
    }

    private fun verticals(k: Ctx, cols: List<Col>, from: Float, to: Float) {
        var x = M
        for (c in cols.dropLast(1)) {
            x += c.width
            k.c.drawLine(x, from, x, to, line)
        }
    }

    private fun drawItemTable(k: Ctx, v: Voucher) {
        val fixed = 22f + 52f + 62f + 58f + 30f + 76f
        val cols = listOf(
            Col("Sl", 22f, false), Col("Description of Goods", WIDTH - fixed, false), Col("HSN/SAC", 52f, false),
            Col("Quantity", 62f, true), Col("Rate", 58f, true), Col("per", 30f, false), Col("Amount", 76f, true),
        )
        var tableTop = k.y
        drawHeaderRow(k, cols)

        val rows = ArrayList<List<String>>()
        var sl = 1
        for (i in v.items) {
            val (q, unit) = Parse.qty(i.qty)
            rows += listOf(
                (sl++).toString(), i.item, i.hsn, if (q != 0.0) VoucherXml.num(q) + " " + unit else "",
                if (i.rate.isNotBlank()) Fmt.num(Parse.amount(i.rate)) else "", unit, Fmt.num(abs(i.amount)),
            )
        }
        val partyAbs = v.ledgerEntries.firstOrNull { it.ledger.equals(v.party, true) }
        val extras = v.ledgerEntries.filter { it !== partyAbs && !it.ledger.equals(v.party, true) }
        val itemSide = v.items.firstOrNull()?.amount?.let { if (it >= 0) 1 else -1 } ?: 1
        val extraRows = extras.map { e ->
            val signed = e.amount * itemSide // positive = adds to the bill, negative = less
            listOf("", e.ledger, "", "", "", "", (if (signed < 0) "(-) " else "") + Fmt.num(abs(e.amount)))
        }

        fun drawRow(r: List<String>, isExtra: Boolean) {
            val descLines = wrap(r[1], cols[1].width - 6, bold)
            val h = 4 + descLines.size * 10.5f + 3
            if (k.y + h > BOTTOM - 40) {
                verticals(k, cols, tableTop, k.y)
                k.finishPage()
                k.newPage()
                tableTop = k.y
                drawHeaderRow(k, cols)
            }
            var x = M
            for ((ci, c) in cols.withIndex()) {
                if (ci == 1) {
                    var yy = k.y + 11
                    for (d in descLines) {
                        val dx = if (isExtra) x + c.width - 4 - bold.measureText(d) else x + 3
                        k.c.drawText(d, dx, yy, bold)
                        yy += 10.5f
                    }
                } else {
                    val t = ellipsize(r[ci], c.width - 4, regular)
                    val p = if (ci == cols.size - 1) bold else regular
                    val tx = if (c.alignRight) x + c.width - 3 - p.measureText(t) else x + 3
                    k.c.drawText(t, tx, k.y + 11, p)
                }
                x += c.width
            }
            k.y += h
        }

        for (r in rows) drawRow(r, false)
        if (extraRows.isNotEmpty()) k.y += 4
        for (r in extraRows) drawRow(r, true)
        k.y += 6
        verticals(k, cols, tableTop, k.y)
        k.c.drawLine(M, k.y, RIGHT, k.y, line)

        // Total row
        val units = v.items.map { Parse.qty(it.qty).second }.distinct()
        val totalQty = if (units.size == 1) VoucherXml.num(v.items.sumOf { Parse.qty(it.qty).first }) + " " + units[0] else ""
        val top = k.y
        k.c.drawText("Total", M + cols[0].width + cols[1].width - 4 - bold.measureText("Total"), top + 12, bold)
        val qx = M + cols.take(4).sumOf { it.width.toDouble() }.toFloat()
        k.c.drawText(totalQty, qx - 3 - bold.measureText(totalQty), top + 12, bold)
        val tot = "₹ " + Fmt.num(v.total)
        k.c.drawText(tot, RIGHT - 3 - big.measureText(tot), top + 12, big)
        k.y = top + 17
        verticals(k, cols, top, k.y)
        k.c.drawLine(M, k.y, RIGHT, k.y, line)
    }

    private fun drawLedgerTable(k: Ctx, v: Voucher) {
        val cols = listOf(Col("Particulars", WIDTH - 180, false), Col("Debit", 90f, true), Col("Credit", 90f, true))
        var tableTop = k.y
        drawHeaderRow(k, cols)
        for (e in v.entries) {
            if (k.y + 16 > BOTTOM - 40) {
                verticals(k, cols, tableTop, k.y)
                k.finishPage(); k.newPage(); tableTop = k.y; drawHeaderRow(k, cols)
            }
            k.c.drawText(ellipsize(e.ledger, cols[0].width - 6, bold), M + 3, k.y + 11, bold)
            val amt = Fmt.num(e.amount)
            val x = if (e.amount < 0) M + cols[0].width + cols[1].width else RIGHT
            k.c.drawText(amt, x - 3 - regular.measureText(amt), k.y + 11, regular)
            k.y += 15
        }
        k.y += 4
        verticals(k, cols, tableTop, k.y)
        k.c.drawLine(M, k.y, RIGHT, k.y, line)
        val top = k.y
        val tot = Fmt.num(v.total)
        k.c.drawText("Total", M + cols[0].width - 4 - bold.measureText("Total"), top + 12, bold)
        k.c.drawText(tot, M + cols[0].width + cols[1].width - 3 - bold.measureText(tot), top + 12, bold)
        k.c.drawText(tot, RIGHT - 3 - bold.measureText(tot), top + 12, bold)
        k.y = top + 17
        verticals(k, cols, top, k.y)
        k.c.drawLine(M, k.y, RIGHT, k.y, line)
    }

    private fun wrap(text: String, width: Float, p: Paint): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            var cur = ""
            for (word in para.split(' ')) {
                val cand = if (cur.isEmpty()) word else "$cur $word"
                if (p.measureText(cand) <= width || cur.isEmpty()) {
                    cur = cand
                } else {
                    out += cur
                    cur = word
                }
            }
            if (cur.isNotEmpty()) out += cur
        }
        return out.map { ellipsize(it, width, p) }.ifEmpty { listOf("") }
    }

    private fun ellipsize(text: String, width: Float, p: Paint): String {
        if (p.measureText(text) <= width) return text
        var t = text
        while (t.isNotEmpty() && p.measureText("$t…") > width) t = t.dropLast(1)
        return "$t…"
    }
}

/** Indian-system amount in words: "INR One Lakh Twenty Three Thousand … Only". */
object AmountWords {
    private val ones = arrayOf(
        "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve",
        "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen",
    )
    private val tens = arrayOf("", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety")

    private fun twoDigits(n: Int): String = if (n < 20) ones[n] else (tens[n / 10] + " " + ones[n % 10]).trim()

    private fun threeDigits(n: Int): String {
        val h = n / 100
        val r = n % 100
        return listOf(if (h > 0) ones[h] + " Hundred" else "", twoDigits(r)).filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun words(n: Long): String {
        if (n == 0L) return "Zero"
        val parts = ArrayList<String>()
        var x = n
        val crore = x / 10_000_000; x %= 10_000_000
        val lakh = x / 100_000; x %= 100_000
        val thousand = x / 1000; x %= 1000
        if (crore > 0) parts += words(crore) + " Crore"
        if (lakh > 0) parts += twoDigits(lakh.toInt()) + " Lakh"
        if (thousand > 0) parts += twoDigits(thousand.toInt()) + " Thousand"
        if (x > 0) parts += threeDigits(x.toInt())
        return parts.joinToString(" ")
    }

    fun inr(amount: Double): String {
        val paiseTotal = (abs(amount) * 100).roundToLong()
        val rupees = paiseTotal / 100
        val paise = (paiseTotal % 100).toInt()
        return "INR " + words(rupees) + (if (paise > 0) " and " + twoDigits(paise) + " paise" else "") + " Only"
    }
}
