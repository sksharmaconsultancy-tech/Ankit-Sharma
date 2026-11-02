package com.ankit.tallydata

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import kotlin.math.abs

/** One generic list screen used for every report (ledgers, groups, stock, vouchers, statement). */
class DataListActivity : Activity() {

    companion object {
        const val MODE_LEDGERS = "ledgers"
        const val MODE_GROUPS = "groups"
        const val MODE_GROUP_CHILDREN = "group_children"
        const val MODE_STOCK = "stock"
        const val MODE_VOUCHERS = "vouchers"
        const val MODE_LEDGER_STATEMENT = "ledger_statement"

        private const val EX_MODE = "mode"
        private const val EX_COMPANY = "company"
        private const val EX_FROM = "from"
        private const val EX_TO = "to"
        private const val EX_TITLE = "title"
        private const val EX_PARENTS = "parents"
        private const val EX_LEDGER = "ledger"
        private const val EX_VTYPE = "vtype"

        private const val MENU_REFRESH = 1
        private const val MENU_EXPORT = 2
        private const val REQ_EXPORT = 41

        fun newIntent(
            ctx: Context,
            mode: String,
            company: String,
            from: LocalDate,
            to: LocalDate,
            title: String,
            parents: ArrayList<String>? = null,
            ledger: String? = null,
            vtype: String? = null,
        ): Intent = Intent(ctx, DataListActivity::class.java)
            .putExtra(EX_MODE, mode)
            .putExtra(EX_COMPANY, company)
            .putExtra(EX_FROM, from.toString())
            .putExtra(EX_TO, to.toString())
            .putExtra(EX_TITLE, title)
            .putStringArrayListExtra(EX_PARENTS, parents)
            .putExtra(EX_LEDGER, ledger)
            .putExtra(EX_VTYPE, vtype)
    }

    class Row(
        val title: String,
        val subtitle: String,
        val amount: String,
        val amountColor: Int,
        val payload: Any?,
        val csv: List<String>,
    )

    private class Loaded(val rows: List<Row>, val summary: String, val csvHeader: List<String>)

    private class StatementLine(val voucher: Voucher, val amount: Double)

    private lateinit var mode: String
    private lateinit var company: String
    private lateinit var from: LocalDate
    private lateinit var to: LocalDate
    private lateinit var screenTitle: String

    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var retryButton: Button
    private lateinit var summaryText: TextView
    private lateinit var searchInput: EditText
    private lateinit var listView: ListView

    private var allRows: List<Row> = emptyList()
    private var shownRows: List<Row> = emptyList()
    private var csvHeader: List<String> = emptyList()
    private val adapter = RowAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EX_MODE) ?: MODE_LEDGERS
        company = intent.getStringExtra(EX_COMPANY) ?: run { finish(); return }
        from = LocalDate.parse(intent.getStringExtra(EX_FROM))
        to = LocalDate.parse(intent.getStringExtra(EX_TO))
        screenTitle = intent.getStringExtra(EX_TITLE) ?: "Tally"
        title = screenTitle
        actionBar?.subtitle = "$company · ${Fmt.date(from)} – ${Fmt.date(to)}"

        val root = vertical(0)

        summaryText = label("", 14f, C.TEXT).apply {
            setBackgroundColor(C.SUMMARY_BG)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
        }
        root.addView(summaryText, matchWrap())

        searchInput = EditText(this).apply {
            hint = "🔍  Search…"
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = applyFilter()
            })
        }
        root.addView(searchInput, matchWrap().apply { setMargins(dp(8), 0, dp(8), 0) })

        progress = ProgressBar(this)
        root.addView(progress, matchWrap().apply { topMargin = dp(24) })

        statusText = label("Tally se data aa raha hai…", 14f, C.MUTED).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(statusText, matchWrap())

        retryButton = Button(this).apply {
            text = "Dobara try karein"
            visibility = View.GONE
            setOnClickListener { load(force = true) }
        }
        root.addView(retryButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })

        listView = ListView(this).apply {
            adapter = this@DataListActivity.adapter
            setOnItemClickListener { _, _, position, _ -> onRowClick(shownRows[position]) }
        }
        root.addView(listView, fillRemaining())

        setContentView(root)
        load(force = false)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_REFRESH, 0, "Refresh").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_EXPORT, 1, "Export CSV (Excel)").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_REFRESH -> load(force = true)
            MENU_EXPORT -> startExport()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------

    private fun load(force: Boolean) {
        if (force) Repo.clear()
        progress.visibility = View.VISIBLE
        retryButton.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        statusText.text = if (mode == MODE_VOUCHERS || mode == MODE_LEDGER_STATEMENT) {
            "Tally se vouchers aa rahe hain… (bade period me thoda time lag sakta hai)"
        } else {
            "Tally se data aa raha hai…"
        }
        val client = Prefs.client(this)
        Bg.run(this, { build(client) }) { result ->
            progress.visibility = View.GONE
            result.onSuccess { loaded ->
                allRows = loaded.rows
                csvHeader = loaded.csvHeader
                summaryText.text = loaded.summary
                summaryText.visibility = View.VISIBLE
                statusText.visibility = if (loaded.rows.isEmpty()) View.VISIBLE else View.GONE
                statusText.text = "Is period me koi data nahi mila."
                applyFilter()
            }.onFailure {
                statusText.text = friendlyError(it)
                retryButton.visibility = View.VISIBLE
            }
        }
    }

    private fun build(client: TallyClient): Loaded = when (mode) {
        MODE_LEDGERS -> buildLedgers(client)
        MODE_GROUPS -> buildGroupChildren(client, primary = true, parents = emptyList())
        MODE_GROUP_CHILDREN -> buildGroupChildren(
            client, primary = false, parents = intent.getStringArrayListExtra(EX_PARENTS) ?: arrayListOf(),
        )
        MODE_STOCK -> buildStock(client)
        MODE_VOUCHERS -> buildVouchers(client, intent.getStringExtra(EX_VTYPE))
        MODE_LEDGER_STATEMENT -> buildStatement(client, intent.getStringExtra(EX_LEDGER) ?: "")
        else -> throw IllegalArgumentException("Unknown mode $mode")
    }

    private fun balanceSummary(count: Int, noun: String, balances: List<Double>): String {
        val dr = balances.filter { it < 0 }.sumOf { -it }
        val cr = balances.filter { it > 0 }.sumOf { it }
        return "$count $noun\nTotal Dr: ${Fmt.money(dr)}   ·   Total Cr: ${Fmt.money(cr)}"
    }

    private fun ledgerRow(l: Ledger) = Row(
        l.name, l.parent, Fmt.drCr(l.closing), Fmt.drCrColor(l.closing), l,
        listOf(l.name, l.parent) + Fmt.csvDrCr(l.opening) + Fmt.csvDrCr(l.closing),
    )

    private val ledgerHeader = listOf("Ledger", "Group", "Opening Dr", "Opening Cr", "Closing Dr", "Closing Cr")

    private fun buildLedgers(client: TallyClient): Loaded {
        val list = Repo.ledgers(client, company, from, to).sortedBy { it.name.lowercase() }
        return Loaded(list.map(::ledgerRow), balanceSummary(list.size, "ledgers", list.map { it.closing }), ledgerHeader)
    }

    private fun buildGroupChildren(client: TallyClient, primary: Boolean, parents: List<String>): Loaded {
        fun matches(parent: String) =
            if (primary) isPrimary(parent) else parents.any { it.equals(parent, ignoreCase = true) }

        val groups = Repo.groups(client, company, from, to).filter { matches(it.parent) }.sortedBy { it.name.lowercase() }
        val ledgers = Repo.ledgers(client, company, from, to).filter { matches(it.parent) }.sortedBy { it.name.lowercase() }

        val rows = groups.map { g ->
            Row(
                "📁 " + g.name, "Group", Fmt.drCr(g.closing), Fmt.drCrColor(g.closing), g,
                listOf(g.name, "Group", "", "") + Fmt.csvDrCr(g.closing),
            )
        } + ledgers.map(::ledgerRow)
        val balances = groups.map { it.closing } + ledgers.map { it.closing }
        return Loaded(rows, balanceSummary(rows.size, "items", balances), ledgerHeader)
    }

    private fun buildStock(client: TallyClient): Loaded {
        val items = Repo.stock(client, company, from, to).sortedBy { it.name.lowercase() }
        val rows = items.map { s ->
            val sub = buildString {
                append(s.parent)
                if (s.qty.isNotBlank()) append(" · Qty: ").append(s.qty)
                if (s.rate.isNotBlank()) append(" · Rate: ").append(s.rate)
            }
            Row(
                s.name, sub, Fmt.money(s.value), C.TEXT, s,
                listOf(s.name, s.parent, s.unit, s.qty, s.rate, String.format(java.util.Locale.US, "%.2f", abs(s.value))),
            )
        }
        val total = items.sumOf { abs(it.value) }
        return Loaded(
            rows,
            "${items.size} stock items\nClosing value: ${Fmt.money(total)}",
            listOf("Item", "Group", "Unit", "Closing Qty", "Rate", "Closing Value"),
        )
    }

    private fun voucherSubtitle(v: Voucher): String = buildString {
        append(Fmt.date(v.date)).append(" · ").append(v.type)
        if (v.number.isNotBlank()) append(" #").append(v.number)
        if (v.cancelled) append(" · CANCELLED")
        if (v.optional) append(" · Optional")
    }

    private fun sortedVouchers(client: TallyClient): List<Voucher> =
        Repo.vouchers(client, company, from, to).sortedWith(compareBy({ it.date }, { it.number.toIntOrNull() ?: 0 }))

    private fun buildVouchers(client: TallyClient, vtype: String?): Loaded {
        val list = sortedVouchers(client).filter { vtype == null || it.type.contains(vtype, ignoreCase = true) }
        val rows = list.map { v ->
            Row(
                v.displayName.ifBlank { v.type }, voucherSubtitle(v), Fmt.money(v.total),
                if (v.cancelled) C.MUTED else C.TEXT, v,
                listOf(
                    Fmt.date(v.date), v.type, v.number, v.party,
                    String.format(java.util.Locale.US, "%.2f", v.total), v.narration,
                ),
            )
        }
        val total = list.filter { !it.cancelled && !it.optional }.sumOf { it.total }
        return Loaded(
            rows,
            "${list.size} vouchers\nTotal: ${Fmt.money(total)}",
            listOf("Date", "Voucher Type", "Voucher No", "Party", "Amount", "Narration"),
        )
    }

    private fun buildStatement(client: TallyClient, ledger: String): Loaded {
        val lines = ArrayList<StatementLine>()
        for (v in sortedVouchers(client)) {
            if (v.cancelled || v.optional) continue
            val mine = v.entries.filter { it.ledger.equals(ledger, ignoreCase = true) }
            if (mine.isEmpty()) continue
            lines += StatementLine(v, mine.sumOf { it.amount })
        }
        val rows = lines.map { line ->
            val others = line.voucher.entries.filter { !it.ledger.equals(ledger, ignoreCase = true) }
            val main = others.maxByOrNull { abs(it.amount) }?.ledger ?: line.voucher.type
            val counter = if (others.size > 1) "$main (+${others.size - 1})" else main
            Row(
                counter, voucherSubtitle(line.voucher), Fmt.drCr(line.amount), Fmt.drCrColor(line.amount),
                line.voucher,
                listOf(Fmt.date(line.voucher.date), line.voucher.type, line.voucher.number, counter) +
                    Fmt.csvDrCr(line.amount) + listOf(line.voucher.narration),
            )
        }
        val dr = lines.filter { it.amount < 0 }.sumOf { -it.amount }
        val cr = lines.filter { it.amount > 0 }.sumOf { it.amount }
        val closing = runCatching {
            Repo.ledgers(client, company, from, to).firstOrNull { it.name.equals(ledger, ignoreCase = true) }
        }.getOrNull()
        val summary = buildString {
            append("${lines.size} entries   ·   Dr: ${Fmt.money(dr)}   ·   Cr: ${Fmt.money(cr)}")
            if (closing != null) {
                append("\nOpening: ").append(Fmt.drCr(closing.opening))
                append("   ·   Closing (${Fmt.date(to)}): ").append(Fmt.drCr(closing.closing))
            }
        }
        return Loaded(rows, summary, listOf("Date", "Voucher Type", "Voucher No", "Particulars", "Debit", "Credit", "Narration"))
    }

    // ---------------------------------------------------------------------
    // Interaction
    // ---------------------------------------------------------------------

    private fun applyFilter() {
        val q = searchInput.text.toString().trim()
        shownRows = if (q.isEmpty()) {
            allRows
        } else {
            allRows.filter { it.title.contains(q, ignoreCase = true) || it.subtitle.contains(q, ignoreCase = true) }
        }
        adapter.notifyDataSetChanged()
    }

    private fun onRowClick(row: Row) {
        when (val p = row.payload) {
            is Ledger -> startActivity(
                newIntent(this, MODE_LEDGER_STATEMENT, company, from, to, p.name, ledger = p.name)
            )
            is Group -> startActivity(
                newIntent(this, MODE_GROUP_CHILDREN, company, from, to, p.name, parents = arrayListOf(p.name))
            )
            is Voucher -> showVoucher(p)
            is StockItem -> AlertDialog.Builder(this)
                .setTitle(p.name)
                .setMessage(
                    "Group: ${p.parent}\nUnit: ${p.unit}\nClosing Qty: ${p.qty}\n" +
                        "Rate: ${p.rate}\nClosing Value: ${Fmt.money(p.value)}"
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showVoucher(v: Voucher) {
        val msg = buildString {
            append("Date: ").append(Fmt.date(v.date)).append('\n')
            append("Type: ").append(v.type)
            if (v.number.isNotBlank()) append("   No: ").append(v.number)
            append('\n')
            if (v.party.isNotBlank()) append("Party: ").append(v.party).append('\n')
            if (v.cancelled) append("Status: CANCELLED\n")
            if (v.optional) append("Status: Optional\n")
            append("Amount: ").append(Fmt.money(v.total)).append('\n')
            if (v.entries.isNotEmpty()) {
                append("\nLedger entries:\n")
                for (e in v.entries) append("• ").append(e.ledger).append("\n     ").append(Fmt.drCr(e.amount)).append('\n')
            }
            if (v.items.isNotEmpty()) {
                append("\nItems:\n")
                for (i in v.items) {
                    append("• ").append(i.item).append("\n     ")
                    if (i.qty.isNotBlank()) append(i.qty.trim())
                    if (i.rate.isNotBlank()) append(" @ ").append(i.rate.trim())
                    append(" = ").append(Fmt.money(i.amount)).append('\n')
                }
            }
            if (v.narration.isNotBlank()) append("\nNarration: ").append(v.narration)
        }
        AlertDialog.Builder(this)
            .setTitle(v.displayName.ifBlank { v.type })
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    // ---------------------------------------------------------------------
    // CSV export (Storage Access Framework – no storage permission needed)
    // ---------------------------------------------------------------------

    private fun startExport() {
        if (shownRows.isEmpty()) {
            Toast.makeText(this, "Export ke liye koi data nahi hai.", Toast.LENGTH_SHORT).show()
            return
        }
        val safeName = (screenTitle + "_" + company).replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_TITLE, "$safeName.csv")
        startActivityForResult(i, REQ_EXPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val csv = buildString {
            append('﻿') // BOM so Excel opens ₹ and Hindi text correctly
            append(csvHeader.joinToString(",") { csvCell(it) }).append("\r\n")
            for (r in shownRows) append(r.csv.joinToString(",") { csvCell(it) }).append("\r\n")
        }
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, "CSV save ho gaya ✓", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save nahi ho paaya: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun csvCell(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    // ---------------------------------------------------------------------
    // List adapter
    // ---------------------------------------------------------------------

    private class Holder(val title: TextView, val subtitle: TextView, val amount: TextView)

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount(): Int = shownRows.size
        override fun getItem(position: Int): Any = shownRows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val ctx = this@DataListActivity
            val view: View
            val holder: Holder
            if (convertView == null) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))
                }
                val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                val title = ctx.label("", 15f, C.TEXT, bold = true)
                val subtitle = ctx.label("", 12.5f, C.MUTED)
                left.addView(title)
                left.addView(subtitle)
                val amount = ctx.label("", 14.5f, C.TEXT, bold = true).apply {
                    gravity = Gravity.END
                    setPadding(ctx.dp(8), 0, 0, 0)
                }
                row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(amount, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
                holder = Holder(title, subtitle, amount)
                row.tag = holder
                view = row
            } else {
                view = convertView
                holder = convertView.tag as Holder
            }
            val r = shownRows[position]
            holder.title.text = r.title
            holder.subtitle.text = r.subtitle
            holder.subtitle.visibility = if (r.subtitle.isBlank()) View.GONE else View.VISIBLE
            holder.amount.text = r.amount
            holder.amount.setTextColor(r.amountColor)
            return view
        }
    }
}
