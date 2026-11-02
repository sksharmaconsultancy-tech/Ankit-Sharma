package com.ankit.tallydata

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.round

/** Item invoice (Sales / Purchase / Credit Note / Debit Note): create new or alter existing. */
class InvoiceFormActivity : FormActivity() {

    companion object {
        private const val EX_COMPANY = "company"
        private const val EX_BASE = "base"
        private const val EX_EDIT = "edit"

        fun newIntent(ctx: Context, company: String, base: String, edit: Boolean = false): Intent =
            Intent(ctx, InvoiceFormActivity::class.java)
                .putExtra(EX_COMPANY, company).putExtra(EX_BASE, base).putExtra(EX_EDIT, edit)

        private val BASES = listOf("Sales", "Purchase", "Credit Note", "Debit Note")
    }

    private inner class ItemRow(
        val view: LinearLayout,
        val item: AutoCompleteTextView,
        val qty: EditText,
        val unit: EditText,
        val rate: EditText,
        val amount: TextView,
    ) {
        fun value(): Double = round2((qty.number() ?: 0.0) * (rate.number() ?: 0.0))
    }

    private inner class ExtraRow(val view: LinearLayout, val ledger: AutoCompleteTextView, val percent: EditText, val amount: EditText) {
        fun value(itemsTotal: Double): Double {
            val p = percent.number()
            return if (p != null) round2(itemsTotal * p / 100.0) else amount.number() ?: 0.0
        }
    }

    private var editing: Voucher? = null
    private lateinit var base: String
    private var types: List<VoucherType> = emptyList()
    private var ledgers: List<Ledger> = emptyList()
    private var items: List<StockItem> = emptyList()

    private lateinit var typeSpinner: Spinner
    private lateinit var dateButton: Button
    private lateinit var numberInput: EditText
    private lateinit var refInput: EditText
    private lateinit var partyInput: AutoCompleteTextView
    private lateinit var salesLedgerInput: AutoCompleteTextView
    private lateinit var itemsBox: LinearLayout
    private lateinit var extrasBox: LinearLayout
    private lateinit var roundOff: CheckBox
    private lateinit var totalsText: TextView
    private lateinit var narrationInput: EditText
    private val itemRows = ArrayList<ItemRow>()
    private val extraRows = ArrayList<ExtraRow>()
    private var date: LocalDate = LocalDate.now()

    private val itemSign: Int get() = if (base == "Sales" || base == "Debit Note") 1 else -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val company = intent.getStringExtra(EX_COMPANY) ?: run { finish(); return }
        if (intent.getBooleanExtra(EX_EDIT, false)) editing = VoucherHolder.voucher ?: run { finish(); return }
        base = intent.getStringExtra(EX_BASE) ?: "Sales"
        initForm(company, if (editing != null) "Invoice edit karein" else "Naya $base invoice")
    }

    override fun loadMasters(client: TallyClient) {
        types = Masters.voucherTypes(client, company)
        ledgers = Masters.ledgerNames(client, company)
        items = Masters.stockItems(client, company)
    }

    private fun round2(v: Double) = round(v * 100.0) / 100.0

    private fun ledgersUnder(vararg groups: String): List<String> =
        ledgers.filter { l -> groups.any { it.equals(l.parent, ignoreCase = true) } }.map { it.name }

    override fun buildForm() {
        val v = editing
        if (v != null) {
            base = types.firstOrNull { it.name.equals(v.type, ignoreCase = true) }?.base
                ?: BASES.firstOrNull { v.type.contains(it, ignoreCase = true) } ?: base
        }
        val allLedgers = ledgers.map { it.name }
        val typeNames = types.filter { it.base.equals(base, ignoreCase = true) }.map { it.name }.ifEmpty { listOf(base) }

        form.addView(section("Voucher type"))
        typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@InvoiceFormActivity, android.R.layout.simple_spinner_dropdown_item, typeNames)
            val wanted = v?.type ?: base
            setSelection(typeNames.indexOfFirst { it.equals(wanted, ignoreCase = true) }.coerceAtLeast(0))
            isEnabled = v == null
        }
        form.addView(typeSpinner, matchWrap())

        date = v?.date ?: LocalDate.now()
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dateButton = smallButton("Date: " + Fmt.date(date)) {
            pickDate(date) { date = it; dateButton.text = "Date: " + Fmt.date(it) }
        }
        numberInput = input("Invoice No. (khali = auto)", text = v?.number ?: "")
        row1.addView(dateButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(numberInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        form.addView(row1, matchWrap())
        refInput = input("Reference / Order No. (optional)", text = v?.reference ?: "")
        form.addView(refInput, matchWrap())

        val partyGroups = if (itemSign > 0) arrayOf("Sundry Debtors", "Cash-in-Hand", "Bank Accounts")
        else arrayOf("Sundry Creditors", "Cash-in-Hand", "Bank Accounts")
        val partyOptions = (ledgersUnder(*partyGroups) + allLedgers).distinct()
        form.addView(section(if (itemSign > 0) "Party (Buyer)" else "Party (Supplier)"))
        partyInput = picker("Party A/c name", partyOptions, v?.party ?: "")
        form.addView(partyInput, matchWrap())

        val salesGroup = if (base == "Sales" || base == "Credit Note") "Sales Accounts" else "Purchase Accounts"
        val salesOptions = (ledgersUnder(salesGroup) + allLedgers).distinct()
        form.addView(section(if (salesGroup == "Sales Accounts") "Sales ledger" else "Purchase ledger"))
        salesLedgerInput = picker(
            "Ledger", salesOptions,
            v?.items?.firstOrNull { it.ledger.isNotBlank() }?.ledger ?: ledgersUnder(salesGroup).firstOrNull() ?: "",
        )
        form.addView(salesLedgerInput, matchWrap())

        form.addView(section("Items"))
        itemsBox = vertical(0)
        form.addView(itemsBox, matchWrap())
        if (v != null) {
            for (line in v.items) {
                val (q, unit) = Parse.qty(line.qty)
                addItem(line.item, VoucherXml.num(q), unit, VoucherXml.num(Parse.amount(line.rate)))
            }
        }
        if (itemRows.isEmpty()) addItem("", "", "", "")
        form.addView(smallButton("➕  Item jodein") { addItem("", "", "", "") })

        form.addView(section("Tax / other ledgers (GST, freight, discount…)"))
        extrasBox = vertical(0)
        form.addView(extrasBox, matchWrap())
        val taxLedgers = (ledgersUnder("Duties & Taxes") + allLedgers).distinct()
        if (v != null) {
            for (e in v.ledgerEntries) {
                if (e.ledger.equals(v.party, ignoreCase = true)) continue
                if (isRoundOff(e.ledger)) continue
                addExtra(taxLedgers, e.ledger, "", VoucherXml.num(e.amount * itemSign))
            }
        }
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        quick.addView(smallButton("+ CGST/SGST") {
            addExtra(taxLedgers, findLedger("CGST"), "", "")
            addExtra(taxLedgers, findLedger("SGST"), "", "")
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        quick.addView(smallButton("+ IGST") { addExtra(taxLedgers, findLedger("IGST"), "", "") },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        quick.addView(smallButton("+ Other") { addExtra(taxLedgers, "", "", "") },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        form.addView(quick, matchWrap())
        form.addView(label("% bharenge to amount items total par apne aap lagega. Discount ke liye minus (-) amount.", 12f, C.MUTED))

        roundOff = CheckBox(this).apply {
            text = "Round off (" + (findLedger("Round").ifBlank { "Round Off ledger nahi mila" }) + ")"
            isChecked = v?.ledgerEntries?.any { isRoundOff(it.ledger) } ?: findLedger("Round").isNotBlank()
            setOnCheckedChangeListener { _, _ -> updateTotals() }
        }
        form.addView(roundOff, matchWrap())

        totalsText = label("", 15f, C.TEXT, bold = true).apply { setPadding(0, dp(8), 0, dp(8)) }
        form.addView(totalsText, matchWrap())

        form.addView(section("Narration"))
        narrationInput = input("Narration", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, v?.narration ?: "")
        form.addView(narrationInput, matchWrap())

        form.addView(Button(this).apply {
            text = if (v != null) "Tally me update karein" else "Tally me save karein"
            textSize = 16f
            setOnClickListener { save() }
        }, matchWrap().apply { topMargin = dp(16) })
        updateTotals()
    }

    private fun isRoundOff(name: String) = name.contains("round", ignoreCase = true)

    private fun findLedger(part: String): String =
        ledgersUnder("Duties & Taxes", "Indirect Expenses", "Indirect Incomes").firstOrNull { it.contains(part, ignoreCase = true) }
            ?: ledgers.firstOrNull { it.name.contains(part, ignoreCase = true) }?.name ?: ""

    private fun addItem(name: String, qty: String, unit: String, rate: String) {
        val box = vertical(0).apply { setPadding(0, dp(4), 0, dp(4)) }
        val itemInput = picker("Stock item", items.map { it.name }, name)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(itemInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val remove = Button(this).apply { text = "✕" }
        top.addView(remove, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(top, matchWrap())

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val qtyInput = amountInput("Qty", qty)
        val unitInput = input("Unit", text = unit)
        val rateInput = amountInput("Rate", rate)
        val amountText = label("", 14f, C.TEXT, bold = true).apply { gravity = Gravity.END }
        bottom.addView(qtyInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(unitInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f))
        bottom.addView(rateInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(amountText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f))
        box.addView(bottom, matchWrap())

        val r = ItemRow(box, itemInput, qtyInput, unitInput, rateInput, amountText)
        itemInput.setOnItemClickListener { _, _, _, _ ->
            val s = items.firstOrNull { it.name == itemInput.text.toString() }
            if (s != null && unitInput.text.isBlank()) unitInput.setText(s.unit)
        }
        qtyInput.onChange { updateTotals() }
        rateInput.onChange { updateTotals() }
        remove.setOnClickListener {
            itemsBox.removeView(box)
            itemRows.remove(r)
            updateTotals()
        }
        itemsBox.addView(box, matchWrap())
        itemRows += r
        updateTotals()
    }

    private fun addExtra(options: List<String>, ledger: String, percent: String, amount: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val ledgerInput = picker("Ledger", options, ledger)
        val pct = amountInput("%", percent)
        val amt = amountInput("Amount", amount)
        val remove = Button(this).apply { text = "✕" }
        box.addView(ledgerInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        box.addView(pct, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f))
        box.addView(amt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f))
        box.addView(remove, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT))
        val r = ExtraRow(box, ledgerInput, pct, amt)
        pct.onChange { updateTotals() }
        amt.onChange { updateTotals() }
        remove.setOnClickListener {
            extrasBox.removeView(box)
            extraRows.remove(r)
            updateTotals()
        }
        extrasBox.addView(box, matchWrap())
        extraRows += r
        updateTotals()
    }

    private class Totals(val items: Double, val extras: Double, val roundOff: Double) {
        val grand get() = items + extras + roundOff
    }

    private fun totals(): Totals {
        val itemsTotal = round2(itemRows.sumOf { it.value() })
        val extras = round2(extraRows.filter { it.ledger.text.isNotBlank() }.sumOf { it.value(itemsTotal) })
        val raw = itemsTotal + extras
        val ro = if (::roundOff.isInitialized && roundOff.isChecked) round2(round(raw) - raw) else 0.0
        return Totals(itemsTotal, extras, ro)
    }

    private fun updateTotals() {
        if (!::totalsText.isInitialized) return
        for (r in itemRows) r.amount.text = if (r.value() != 0.0) Fmt.num(r.value()) else ""
        val t = totals()
        for (r in extraRows) if (r.percent.number() != null) r.amount.hint = Fmt.num(r.value(t.items))
        totalsText.text = buildString {
            append("Items: ").append(Fmt.money(t.items))
            if (t.extras != 0.0) append("\nTax / others: ").append(Fmt.money(t.extras))
            if (t.roundOff != 0.0) append("\nRound off: ").append(if (t.roundOff < 0) "-" else "").append(Fmt.money(t.roundOff))
            append("\nGrand Total: ").append(Fmt.money(t.grand))
        }
    }

    private fun save() {
        val type = typeSpinner.selectedItem?.toString() ?: return
        val known = ledgers.associateBy { it.name.lowercase() }
        fun ledgerName(s: String) = known[s.trim().lowercase()]?.name

        val party = ledgerName(partyInput.text.toString())
            ?: return message("Party nahi mili", "Party ledger list me se chunein (ya pehle naya ledger banayein).")
        val salesLedger = ledgerName(salesLedgerInput.text.toString())
            ?: return message("Ledger nahi mila", "Sales / Purchase ledger list me se chunein.")

        val stock = items.associateBy { it.name.lowercase() }
        val itemInputs = ArrayList<ItemInput>()
        for (r in itemRows) {
            val name = r.item.text.toString().trim()
            if (name.isEmpty() && r.qty.text.isBlank() && r.rate.text.isBlank()) continue
            val s = stock[name.lowercase()] ?: return message("Item nahi mila", "\"$name\" stock item Tally me nahi hai.")
            val q = r.qty.number() ?: return message("Qty bharein", "$name ki quantity bharein.")
            val rate = r.rate.number() ?: return message("Rate bharein", "$name ka rate bharein.")
            if (q <= 0) return message("Galat qty", "$name ki quantity 0 se zyada honi chahiye.")
            itemInputs += ItemInput(s.name, q, r.unit.text.toString().trim().ifBlank { s.unit }, rate, r.value(), salesLedger)
        }
        if (itemInputs.isEmpty()) return message("Item jodein", "Kam se kam ek item chahiye.")

        val t = totals()
        val extras = ArrayList<LineInput>()
        for (r in extraRows) {
            val text = r.ledger.text.toString().trim()
            if (text.isEmpty()) continue
            val name = ledgerName(text) ?: return message("Ledger nahi mila", "\"$text\" ledger Tally me nahi hai.")
            val value = r.value(t.items)
            if (value != 0.0) extras += LineInput(name, value * itemSign)
        }
        if (t.roundOff != 0.0) {
            val ro = findLedger("Round")
            if (ro.isBlank()) return message("Round off ledger nahi", "Tally me 'Round Off' ledger banayein ya round off hata dein.")
            extras += LineInput(ro, t.roundOff * itemSign)
        }

        val v = editing
        if (v != null && v.guid.isBlank()) return message("Edit possible nahi", "Is voucher ki Tally ID (GUID) nahi mili.")
        val number = numberInput.text.toString().trim()
        val xml = VoucherXml.invoice(
            type, date, number, refInput.text.toString().trim(), narrationInput.text.toString().trim(),
            party, itemSign, itemInputs, extras, v?.guid,
        )
        val summary = buildString {
            append(type).append(" · ").append(Fmt.date(date)).append('\n')
            append("Party: ").append(party).append("\n\n")
            for (i in itemInputs) append(i.item).append("  ").append(VoucherXml.num(i.qty)).append(' ').append(i.unit)
                .append(" × ").append(Fmt.num(i.rate)).append(" = ").append(Fmt.money(i.amount)).append('\n')
            for (e in extras) append(e.ledger).append("  ").append(Fmt.money(e.amount)).append('\n')
            append("\nGrand Total: ").append(Fmt.money(t.grand))
        }
        confirmAndImport(summary, "Vouchers", xml) { res ->
            // Build the voucher locally so it can be shared as a PDF straight away.
            val extraEntries = extras.map { Entry(it.ledger, it.amount) }
            val entries = listOf(Entry(party, -itemSign * t.grand)) + extraEntries +
                itemInputs.map { Entry(it.ledger, it.amount * itemSign) }
            val saved = Voucher(
                date = date, type = type, number = number, party = party,
                narration = narrationInput.text.toString().trim(),
                entries = entries,
                items = itemInputs.map {
                    InvLine(it.item, VoucherXml.qty(it.qty, it.unit), VoucherXml.amt(it.rate) + "/" + it.unit, it.amount * itemSign, it.ledger)
                },
                cancelled = false, optional = false, guid = v?.guid ?: "",
                ledgerEntries = listOf(Entry(party, -itemSign * t.grand)) + extraEntries,
                reference = refInput.text.toString().trim(),
            )
            afterSave(saved, res)
        }
    }

    private fun afterSave(saved: Voucher, res: ImportResult) {
        val client = Prefs.client(this)
        // Find the voucher Tally just stored to learn the auto-assigned number.
        Bg.run(this, {
            if (saved.number.isNotBlank()) saved
            else client.vouchers(company, saved.date!!, saved.date)
                .lastOrNull { it.type == saved.type && it.party.equals(saved.party, ignoreCase = true) && abs(it.total - saved.total) < 1.0 }
                ?: saved
        }) { r ->
            val v = r.getOrDefault(saved)
            AlertDialog.Builder(this)
                .setTitle(if (res.altered > 0) "Invoice update ho gaya ✓" else "Invoice save ho gaya ✓")
                .setMessage("${v.type} No. ${v.number.ifBlank { "(auto)" }} · ${Fmt.money(v.total)}\n\nAb party ko bhejna hai?")
                .setPositiveButton("PDF bhejein") { _, _ -> Sharing.shareVoucher(this, company, v) { finish() } }
                .setNegativeButton("Band karein") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }
}
