package com.ankit.tallydata

import android.app.Activity
import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import java.time.LocalDate

class CompanyActivity : Activity() {

    companion object {
        const val EX_NAME = "name"
        const val EX_START = "start"
        const val EX_END = "end"
    }

    private lateinit var company: String
    private lateinit var from: LocalDate
    private lateinit var to: LocalDate
    private lateinit var fromButton: Button
    private lateinit var toButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        company = intent.getStringExtra(EX_NAME) ?: run { finish(); return }
        title = company

        val savedFrom = savedInstanceState?.getString("from")
        val savedTo = savedInstanceState?.getString("to")
        if (savedFrom != null && savedTo != null) {
            from = LocalDate.parse(savedFrom)
            to = LocalDate.parse(savedTo)
        } else {
            // Default: the financial year (Apr–Mar) that contains the company's last date, or today.
            val end = intent.getStringExtra(EX_END)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: LocalDate.now()
            val fyStart = if (end.monthValue >= 4) end.year else end.year - 1
            from = LocalDate.of(fyStart, 4, 1)
            to = LocalDate.of(fyStart + 1, 3, 31)
        }

        val root = vertical(16)
        root.addView(label(company, 20f, C.PRIMARY, bold = true))
        root.addView(label("Period chunein (balances 'To' date tak ke dikhenge):", 14f, C.MUTED).apply {
            setPadding(0, dp(8), 0, dp(4))
        })

        val periodRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fromButton = Button(this).apply { setOnClickListener { pickDate(true) } }
        toButton = Button(this).apply { setOnClickListener { pickDate(false) } }
        periodRow.addView(fromButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        periodRow.addView(toButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(periodRow, matchWrap())
        updatePeriodButtons()

        root.addView(label("Reports", 16f, C.TEXT, bold = true).apply { setPadding(0, dp(16), 0, dp(4)) })

        addReport(root, "📒  Sabhi Ledgers (balances)") {
            open(DataListActivity.MODE_LEDGERS, "Ledgers")
        }
        addReport(root, "⚖️  Trial Balance (groups)") {
            open(DataListActivity.MODE_GROUPS, "Trial Balance")
        }
        addReport(root, "🧾  Sundry Debtors (lena hai)") {
            open(DataListActivity.MODE_GROUP_CHILDREN, "Sundry Debtors", parents = arrayListOf("Sundry Debtors"))
        }
        addReport(root, "🧾  Sundry Creditors (dena hai)") {
            open(DataListActivity.MODE_GROUP_CHILDREN, "Sundry Creditors", parents = arrayListOf("Sundry Creditors"))
        }
        addReport(root, "💰  Cash & Bank") {
            open(
                DataListActivity.MODE_GROUP_CHILDREN, "Cash & Bank",
                parents = arrayListOf("Cash-in-Hand", "Bank Accounts", "Bank OD A/c"),
            )
        }
        addReport(root, "📦  Stock Items") {
            open(DataListActivity.MODE_STOCK, "Stock Items")
        }
        addReport(root, "📅  Day Book (sabhi vouchers)") {
            open(DataListActivity.MODE_VOUCHERS, "Day Book")
        }
        addReport(root, "🛒  Sales vouchers") {
            open(DataListActivity.MODE_VOUCHERS, "Sales", vtype = "Sales")
        }
        addReport(root, "🚚  Purchase vouchers") {
            open(DataListActivity.MODE_VOUCHERS, "Purchase", vtype = "Purchase")
        }
        addReport(root, "💸  Payment vouchers") {
            open(DataListActivity.MODE_VOUCHERS, "Payment", vtype = "Payment")
        }
        addReport(root, "💵  Receipt vouchers") {
            open(DataListActivity.MODE_VOUCHERS, "Receipt", vtype = "Receipt")
        }

        root.addView(
            label(
                "Tip: Kisi bhi ledger par tap karke uski entries (ledger statement) dekhein. " +
                    "Har list ko ⋮ menu se CSV (Excel) me export kar sakte hain.",
                13f, C.MUTED,
            ).apply { setPadding(0, dp(16), 0, 0) }
        )

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("from", from.toString())
        outState.putString("to", to.toString())
    }

    private fun addReport(root: LinearLayout, text: String, onClick: () -> Unit) {
        root.addView(Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 16f
            setOnClickListener { onClick() }
        }, matchWrap())
    }

    private fun updatePeriodButtons() {
        fromButton.text = "From: " + Fmt.date(from)
        toButton.text = "To: " + Fmt.date(to)
    }

    private fun pickDate(isFrom: Boolean) {
        val d = if (isFrom) from else to
        DatePickerDialog(this, { _, y, m, day ->
            val picked = LocalDate.of(y, m + 1, day)
            if (isFrom) from = picked else to = picked
            if (from.isAfter(to)) {
                if (isFrom) to = from else from = to
            }
            updatePeriodButtons()
        }, d.year, d.monthValue - 1, d.dayOfMonth).show()
    }

    private fun open(
        mode: String,
        title: String,
        parents: ArrayList<String>? = null,
        vtype: String? = null,
    ) {
        startActivity(DataListActivity.newIntent(this, mode, company, from, to, title, parents = parents, vtype = vtype))
    }
}
