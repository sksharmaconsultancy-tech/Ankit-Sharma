package com.ankit.tallydata

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate

fun Context.input(hint: String, type: Int = InputType.TYPE_CLASS_TEXT, text: String = ""): EditText =
    EditText(this).apply {
        this.hint = hint
        inputType = type
        if (type and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0) setSingleLine()
        setText(text)
    }

fun Context.amountInput(hint: String = "Amount", text: String = ""): EditText =
    input(hint, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED, text)

fun Context.picker(hint: String, options: List<String>, text: String = ""): AutoCompleteTextView =
    AutoCompleteTextView(this).apply {
        this.hint = hint
        setSingleLine()
        threshold = 1
        setAdapter(ArrayAdapter(this@picker, android.R.layout.simple_dropdown_item_1line, options))
        setText(text, false)
        setOnFocusChangeListener { _, focused -> if (focused && this.text.isEmpty()) post { showDropDown() } }
    }

fun Context.section(title: String): TextView =
    label(title, 15f, C.PRIMARY, bold = true).apply { setPadding(0, dp(16), 0, dp(4)) }

fun Context.smallButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
    this.text = text
    isAllCaps = false
    setOnClickListener { onClick() }
}

fun EditText.number(): Double? = text.toString().trim().replace(",", "").toDoubleOrNull()

fun EditText.onChange(block: () -> Unit) = addTextChangedListener(object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: Editable?) = block()
})

fun Activity.pickDate(current: LocalDate, onPicked: (LocalDate) -> Unit) {
    DatePickerDialog(this, { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) },
        current.year, current.monthValue - 1, current.dayOfMonth).show()
}

fun Activity.message(title: String, msg: String, onOk: (() -> Unit)? = null) {
    AlertDialog.Builder(this).setTitle(title).setMessage(msg)
        .setPositiveButton("OK") { _, _ -> onOk?.invoke() }
        .setCancelable(onOk == null)
        .show()
}

/**
 * Base for screens that first load masters from Tally, then show a scrolling form.
 * Subclasses implement [loadMasters] (background thread) and [buildForm] (UI thread).
 */
abstract class FormActivity : Activity() {
    protected lateinit var company: String
    protected lateinit var form: LinearLayout
    private lateinit var root: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    protected var busy = false

    protected abstract fun loadMasters(client: TallyClient)
    protected abstract fun buildForm()

    protected fun initForm(company: String, title: String) {
        this.company = company
        this.title = title
        actionBar?.subtitle = company
        root = vertical(0)
        progress = ProgressBar(this)
        status = label("Tally se ledgers / items laa rahe hain…", 14f, C.MUTED).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(progress, matchWrap().apply { topMargin = dp(24) })
        root.addView(status, matchWrap())
        form = vertical(16)
        form.visibility = View.GONE
        root.addView(ScrollView(this).apply { addView(form) }, fillRemaining())
        setContentView(root)
        reload()
    }

    private fun reload() {
        progress.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        val client = Prefs.client(this)
        Bg.run(this, { loadMasters(client) }) { r ->
            progress.visibility = View.GONE
            r.onSuccess {
                status.visibility = View.GONE
                form.visibility = View.VISIBLE
                form.removeAllViews()
                buildForm()
            }.onFailure {
                status.text = friendlyError(it)
                root.addView(smallButton("Dobara try karein") { recreate() }, 2)
            }
        }
    }

    /** Sends [body] to Tally after a confirmation, then runs [onSaved]. */
    protected fun confirmAndImport(
        summary: String,
        reportName: String,
        body: String,
        onSaved: (ImportResult) -> Unit,
    ) {
        if (busy) return
        AlertDialog.Builder(this)
            .setTitle("Tally me save karein?")
            .setMessage(summary)
            .setPositiveButton("Save") { _, _ ->
                busy = true
                val dlg = AlertDialog.Builder(this).setMessage("Tally me save ho raha hai…").setCancelable(false).show()
                val client = Prefs.client(this)
                Bg.run(this, { client.importData(reportName, company, body) }) { r ->
                    busy = false
                    dlg.dismiss()
                    r.onSuccess { res ->
                        invalidateAll()
                        onSaved(res)
                    }.onFailure { message("Save nahi hua", friendlyError(it)) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
