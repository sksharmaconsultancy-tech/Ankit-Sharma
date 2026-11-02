package com.ankit.tallydata

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import kotlin.math.abs

/** Accounting voucher (Payment, Receipt, Contra, Journal, …): create new or alter existing. */
class VoucherFormActivity : FormActivity() {

    companion object {
        private const val EX_COMPANY = "company"
        private const val EX_TYPE = "type"
        private const val EX_EDIT = "edit"

        fun newIntent(ctx: Context, company: String, type: String?, edit: Boolean = false): Intent =
            Intent(ctx, VoucherFormActivity::class.java)
                .putExtra(EX_COMPANY, company).putExtra(EX_TYPE, type).putExtra(EX_EDIT, edit)
    }

    private inner class LineRow(val view: LinearLayout, val drCr: Button, val ledger: AutoCompleteTextView, val amount: EditText) {
        val isDebit get() = drCr.text == "Dr"
    }

    private var editing: Voucher? = null
    private var types: List<VoucherType> = emptyList()
    private var ledgers: List<Ledger> = emptyList()

    private lateinit var typeSpinner: Spinner
    private lateinit var dateButton: Button
    private lateinit var numberInput: EditText
    private lateinit var narrationInput: EditText
    private lateinit var linesBox: LinearLayout
    private lateinit var totalsText: TextView
    private val lines = ArrayList<LineRow>()
    private var date: LocalDate = LocalDate.now()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val company = intent.getStringExtra(EX_COMPANY) ?: run { finish(); return }
        if (intent.getBooleanExtra(EX_EDIT, false)) {
            editing = VoucherHolder.voucher ?: run { finish(); return }
        }
        initForm(company, if (editing != null) "Voucher edit karein" else "Nayi entry")
    }

    override fun loadMasters(client: TallyClient) {
        types = Masters.voucherTypes(client, company)
        ledgers = Masters.ledgerNames(client, company)
    }

    override fun buildForm() {
        val ledgerNames = ledgers.map { it.name }
        val accountingBases = listOf("Payment", "Receipt", "Contra", "Journal", "Sales", "Purchase", "Credit Note", "Debit Note")
        val typeNames = types.filter { t -> accountingBases.any { it.equals(t.base, ignoreCase = true) } }
            .map { it.name }.ifEmpty { listOf("Payment", "Receipt", "Contra", "Journal") }
            .sortedBy { n -> accountingBases.indexOfFirst { n.startsWith(it, ignoreCase = true) }.let { if (it < 0) 99 else it } }
        val v = editing
        val wantedType = v?.type ?: intent.getStringExtra(EX_TYPE) ?: "Payment"

        form.addView(section("Voucher type"))
        typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@VoucherFormActivity, android.R.layout.simple_spinner_dropdown_item, typeNames)
            setSelection(typeNames.indexOfFirst { it.equals(wantedType, ignoreCase = true) }.coerceAtLeast(0))
            isEnabled = v == null
        }
        form.addView(typeSpinner, matchWrap())

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        date = v?.date ?: LocalDate.now()
        dateButton = smallButton("Date: " + Fmt.date(date)) {
            pickDate(date) { date = it; dateButton.text = "Date: " + Fmt.date(it) }
        }
        numberInput = input("Voucher No. (khali = auto)", text = v?.number ?: "")
        row.addView(dateButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(numberInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        form.addView(row, matchWrap())

        form.addView(section("Entries (Dr / Cr button dabakar badlein)"))
        linesBox = vertical(0)
        form.addView(linesBox, matchWrap())
        if (v != null) {
            for (e in v.entries) addLine(ledgerNames, e.amount < 0, e.ledger, VoucherXml.num(abs(e.amount)))
        } else {
            addLine(ledgerNames, true, "", "")
            addLine(ledgerNames, false, "", "")
        }
        form.addView(smallButton("➕  Line jodein") { addLine(ledgerNames, lines.count { it.isDebit } <= lines.count { !it.isDebit }, "", "") })

        totalsText = label("", 14f, C.TEXT, bold = true).apply { setPadding(0, dp(8), 0, dp(8)) }
        form.addView(totalsText, matchWrap())

        form.addView(section("Narration"))
        narrationInput = input("Narration", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, v?.narration ?: "")
        form.addView(narrationInput, matchWrap())

        form.addView(Button(this).apply {
            text = if (v != null) "Tally me update karein" else "Tally me save karein"
            textSize = 16f
            setOnClickListener { save() }
        }, matchWrap().apply { topMargin = dp(16) })

        form.addView(
            label(
                "Dr = Debit (paisa jis account me gaya / kharcha), Cr = Credit (jis account se gaya). " +
                    "Ek line ka amount khali chhodenge to wo apne aap balance ho jayega.",
                12.5f, C.MUTED,
            ).apply { setPadding(0, dp(12), 0, 0) }
        )
        updateTotals()
    }

    private fun addLine(ledgerNames: List<String>, debit: Boolean, ledger: String, amount: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val drCr = Button(this).apply {
            text = if (debit) "Dr" else "Cr"
            setTextColor(if (debit) C.DR else C.CR)
        }
        val ledgerInput = picker("Ledger", ledgerNames, ledger)
        val amountInput = amountInput("Amount", amount)
        val line = LineRow(box, drCr, ledgerInput, amountInput)
        drCr.setOnClickListener {
            drCr.text = if (line.isDebit) "Cr" else "Dr"
            drCr.setTextColor(if (line.isDebit) C.DR else C.CR)
            updateTotals()
        }
        amountInput.onChange { updateTotals() }
        val remove = Button(this).apply {
            text = "✕"
            setOnClickListener {
                linesBox.removeView(box)
                lines.remove(line)
                updateTotals()
            }
        }
        box.addView(drCr, LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(ledgerInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        box.addView(amountInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(remove, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT))
        linesBox.addView(box, matchWrap())
        lines += line
        updateTotals()
    }

    private fun updateTotals() {
        if (!::totalsText.isInitialized) return
        val dr = lines.filter { it.isDebit }.sumOf { it.amount.number() ?: 0.0 }
        val cr = lines.filter { !it.isDebit }.sumOf { it.amount.number() ?: 0.0 }
        val diff = dr - cr
        totalsText.text = "Dr: ${Fmt.money(dr)}   Cr: ${Fmt.money(cr)}" +
            if (abs(diff) >= 0.005) "\nDifference: ${Fmt.money(diff)} ${if (diff > 0) "(Cr kam hai)" else "(Dr kam hai)"}" else "   ✓ Balanced"
        totalsText.setTextColor(if (abs(diff) >= 0.005) C.DR else C.CR)
    }

    private fun save() {
        val type = typeSpinner.selectedItem?.toString() ?: return
        val filled = lines.filter { it.ledger.text.isNotBlank() || it.amount.text.isNotBlank() }
        if (filled.size < 2) return message("Entry adhoori hai", "Kam se kam 2 lines (ek Dr, ek Cr) chahiye.")

        val known = ledgers.associateBy { it.name.lowercase() }
        for (l in filled) {
            val name = l.ledger.text.toString().trim()
            if (name.isEmpty()) return message("Ledger chunein", "Har line me ledger ka naam bharein.")
            if (known[name.lowercase()] == null) {
                return message("Ledger nahi mila", "\"$name\" naam ka ledger Tally me nahi hai. List me se chunein ya pehle naya ledger banayein.")
            }
            val n = l.amount.number()
            if (n != null && n < 0) return message("Galat amount", "Amount negative nahi ho sakta — Dr/Cr button se side badlein.")
        }
        val blanks = filled.filter { it.amount.number() == null }
        if (blanks.size > 1) return message("Amount bharein", "Sirf ek line ka amount khali chhod sakte hain.")
        if (blanks.size == 1) {
            val b = blanks[0]
            val others = filled.filter { it !== b }
            val dr = others.filter { it.isDebit }.sumOf { it.amount.number() ?: 0.0 }
            val cr = others.filter { !it.isDebit }.sumOf { it.amount.number() ?: 0.0 }
            val need = if (b.isDebit) cr - dr else dr - cr
            if (need <= 0.0) return message("Balance nahi ho raha", "Khali line ki side (Dr/Cr) check karein.")
            b.amount.setText(VoucherXml.num(need))
        }

        val input = filled.map {
            val name = known[it.ledger.text.toString().trim().lowercase()]!!.name
            val a = it.amount.number() ?: 0.0
            LineInput(name, if (it.isDebit) -a else a)
        }.filter { abs(it.amount) >= 0.005 }
        val diff = input.sumOf { it.amount }
        if (abs(diff) >= 0.005) return message("Balance nahi hai", "Dr aur Cr total barabar hone chahiye. Difference: ${Fmt.money(diff)}")

        val v = editing
        val xml = VoucherXml.accounting(
            type, date, numberInput.text.toString().trim(), narrationInput.text.toString().trim(), input, v?.guid,
        )
        if (v != null && v.guid.isBlank()) return message("Edit possible nahi", "Is voucher ki Tally ID (GUID) nahi mili, isliye update nahi kar sakte.")
        val summary = buildString {
            append(type).append(" · ").append(Fmt.date(date)).append("\n\n")
            for (l in input) append(if (l.amount < 0) "Dr  " else "Cr  ").append(l.ledger).append("   ").append(Fmt.money(l.amount)).append('\n')
        }
        confirmAndImport(summary, "Vouchers", xml) { res ->
            Toast.makeText(this, if (res.altered > 0) "Voucher update ho gaya ✓" else "Entry Tally me save ho gayi ✓", Toast.LENGTH_LONG).show()
            setResult(RESULT_OK)
            finish()
        }
    }
}
