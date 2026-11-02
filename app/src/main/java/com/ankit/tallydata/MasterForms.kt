package com.ankit.tallydata

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.Toast

/** Create a new ledger (party, expense, bank …) in Tally. */
class LedgerFormActivity : FormActivity() {

    companion object {
        private const val EX_COMPANY = "company"
        fun newIntent(ctx: Context, company: String): Intent =
            Intent(ctx, LedgerFormActivity::class.java).putExtra(EX_COMPANY, company)
    }

    private var groups: List<String> = emptyList()
    private var existing: Set<String> = emptySet()

    private lateinit var nameInput: EditText
    private lateinit var groupInput: AutoCompleteTextView
    private lateinit var addressInput: EditText
    private lateinit var stateInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var gstinInput: EditText
    private lateinit var mobileInput: EditText
    private lateinit var emailInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initForm(intent.getStringExtra(EX_COMPANY) ?: run { finish(); return }, "Naya Ledger")
    }

    override fun loadMasters(client: TallyClient) {
        groups = Masters.groupNames(client, company)
        existing = Masters.ledgerNames(client, company).map { it.name.lowercase() }.toSet()
    }

    override fun buildForm() {
        form.addView(section("Ledger"))
        nameInput = input("Ledger ka naam *")
        form.addView(nameInput, matchWrap())
        groupInput = picker("Group * (jaise Sundry Debtors)", groups, "Sundry Debtors")
        form.addView(groupInput, matchWrap())

        form.addView(section("Party details (optional — invoice par chhapte hain)"))
        addressInput = input("Address", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        stateInput = input("State (jaise Rajasthan)")
        pinInput = input("Pincode", InputType.TYPE_CLASS_NUMBER)
        gstinInput = input("GSTIN", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        mobileInput = input("Mobile (WhatsApp ke liye)", InputType.TYPE_CLASS_PHONE)
        emailInput = input("Email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        for (f in listOf(addressInput, stateInput, pinInput, gstinInput, mobileInput, emailInput)) form.addView(f, matchWrap())

        form.addView(Button(this).apply {
            text = "Tally me ledger banayein"
            textSize = 16f
            setOnClickListener { save() }
        }, matchWrap().apply { topMargin = dp(16) })
    }

    private fun save() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) return message("Naam bharein", "Ledger ka naam zaroori hai.")
        if (name.lowercase() in existing) return message("Pehle se hai", "\"$name\" naam ka ledger Tally me pehle se hai.")
        val group = groups.firstOrNull { it.equals(groupInput.text.toString().trim(), ignoreCase = true) }
            ?: return message("Group nahi mila", "Group list me se chunein.")
        val gstin = gstinInput.text.toString().trim().uppercase()
        if (gstin.isNotEmpty() && gstin.length != 15) return message("GSTIN galat", "GSTIN 15 characters ka hota hai.")
        val p = PartyInfo(
            name = name,
            parent = group,
            address = addressInput.text.toString().split('\n').map { it.trim() }.filter { it.isNotEmpty() },
            state = stateInput.text.toString().trim(),
            pincode = pinInput.text.toString().trim(),
            gstin = gstin,
            mobile = mobileInput.text.toString().trim(),
            email = emailInput.text.toString().trim(),
        )
        confirmAndImport("$name\nGroup: $group", "All Masters", VoucherXml.ledger(p)) {
            Toast.makeText(this, "Ledger \"$name\" ban gaya ✓", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}

/** Letter-head details printed on invoice PDFs (stored on the phone, pre-filled from Tally). */
class InvoiceSettingsActivity : FormActivity() {

    companion object {
        private const val EX_COMPANY = "company"
        fun newIntent(ctx: Context, company: String): Intent =
            Intent(ctx, InvoiceSettingsActivity::class.java).putExtra(EX_COMPANY, company)
    }

    private var info: CompanyInfo? = null
    private val fields = LinkedHashMap<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initForm(intent.getStringExtra(EX_COMPANY) ?: run { finish(); return }, "Invoice print settings")
    }

    override fun loadMasters(client: TallyClient) {
        info = Masters.companyInfo(client, company)
    }

    override fun buildForm() {
        val s = InvoiceSettings.load(this, company, info)
        val multi = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        form.addView(label("Ye details invoice PDF ke upar/neeche chhapengi. Tally se jo mila wo pehle se bhara hai.", 13f, C.MUTED))
        fun add(key: String, hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) {
            form.addView(section(hint))
            val e = input(hint, type, value)
            fields[key] = e
            form.addView(e, matchWrap())
        }
        add("name", "Company name", s.companyName)
        add("address", "Address", s.address, multi)
        add("gstin", "GSTIN/UIN", s.gstin, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        add("state", "State name", s.state)
        add("phone", "Contact number", s.phone, InputType.TYPE_CLASS_PHONE)
        add("email", "E-Mail", s.email, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        add("pan", "PAN", s.pan, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        add("bank", "Bank details (Bank name, A/c No., IFSC — har line alag)", s.bank, multi)
        add("terms", "Declaration / Terms", s.terms, multi)
        form.addView(Button(this).apply {
            text = "Save"
            textSize = 16f
            setOnClickListener {
                fun f(k: String) = fields[k]!!.text.toString().trim()
                InvoiceSettings.save(
                    this@InvoiceSettingsActivity, company,
                    InvoiceSettings(f("name"), f("address"), f("gstin"), f("state"), f("phone"), f("email"), f("pan"), f("bank"), f("terms")),
                )
                Toast.makeText(this@InvoiceSettingsActivity, "Save ho gaya ✓", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, matchWrap().apply { topMargin = dp(16) })
    }
}
