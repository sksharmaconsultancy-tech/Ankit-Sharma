package com.ankit.tallydata

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar

class MainActivity : Activity() {

    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var connectButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var statusText: android.widget.TextView
    private lateinit var listView: ListView
    private var companies: List<Company> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Tally Data Viewer"

        val root = vertical(16)

        root.addView(label("Tally PC se connect karein", 18f, C.PRIMARY, bold = true))
        root.addView(label("Jis computer par Tally chal raha hai uska IP address aur port daalein.", 14f, C.MUTED).apply {
            setPadding(0, dp(4), 0, dp(12))
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hostInput = EditText(this).apply {
            hint = "IP address (jaise 192.168.1.5)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(Prefs.host(this@MainActivity))
        }
        portInput = EditText(this).apply {
            hint = "Port"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setText(Prefs.port(this@MainActivity).toString())
        }
        row.addView(hostInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f))
        row.addView(portInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row, matchWrap())

        connectButton = Button(this).apply {
            text = "Connect karein / Companies dikhayein"
            setOnClickListener { connect() }
        }
        root.addView(connectButton, matchWrap())

        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, matchWrap())

        statusText = label("", 14f, C.MUTED).apply { setPadding(0, dp(8), 0, dp(8)) }
        root.addView(statusText, matchWrap())

        listView = ListView(this)
        listView.setOnItemClickListener { _, _, position, _ -> openCompany(companies[position]) }
        root.addView(listView, fillRemaining())

        setContentView(root)

        if (Prefs.host(this).isNotBlank()) connect() else showHelp()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Setup help").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            showHelp()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("Setup kaise karein")
            .setMessage(HELP_TEXT)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun connect() {
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull() ?: 9000
        if (host.isBlank()) {
            statusText.text = "Pehle Tally PC ka IP address daalein."
            return
        }
        Prefs.save(this, host, port)
        Repo.clear()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(hostInput.windowToken, 0)

        connectButton.isEnabled = false
        progress.visibility = View.VISIBLE
        statusText.text = "Tally ($host:$port) se connect ho raha hai…"
        listView.adapter = null

        val client = TallyClient(host, port)
        Bg.run(this, { client.companies() }) { result ->
            connectButton.isEnabled = true
            progress.visibility = View.GONE
            result.onSuccess { list ->
                companies = list
                if (list.isEmpty()) {
                    statusText.text = "Connect ho gaya, lekin Tally me koi company open nahi hai. " +
                        "Tally me company open (load) karke dobara try karein."
                } else {
                    statusText.text = "Connected ✓  Company chunein:"
                    listView.adapter = ArrayAdapter(
                        this,
                        android.R.layout.simple_list_item_1,
                        list.map { it.name },
                    )
                }
            }.onFailure { statusText.text = friendlyError(it) }
        }
    }

    private fun openCompany(c: Company) {
        startActivity(
            Intent(this, CompanyActivity::class.java)
                .putExtra(CompanyActivity.EX_NAME, c.name)
                .putExtra(CompanyActivity.EX_START, c.startingFrom?.toString())
                .putExtra(CompanyActivity.EX_END, c.endingAt?.toString())
        )
    }
}
