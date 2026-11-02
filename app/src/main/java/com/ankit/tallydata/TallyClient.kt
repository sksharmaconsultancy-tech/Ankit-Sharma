package com.ankit.tallydata

import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

class TallyException(message: String) : Exception(message)

// ---------------------------------------------------------------------------
// Models
// ---------------------------------------------------------------------------

data class Company(val name: String, val startingFrom: LocalDate?, val endingAt: LocalDate?)

/** Amounts follow Tally's XML convention: negative = Debit, positive = Credit. */
data class Ledger(val name: String, val parent: String, val opening: Double, val closing: Double)

data class Group(val name: String, val parent: String, val closing: Double)

data class StockItem(
    val name: String,
    val parent: String,
    val unit: String,
    val qty: String,
    val rate: String,
    val value: Double,
)

data class Entry(val ledger: String, val amount: Double)

data class InvLine(val item: String, val qty: String, val rate: String, val amount: Double)

data class Voucher(
    val date: LocalDate?,
    val type: String,
    val number: String,
    val party: String,
    val narration: String,
    val entries: List<Entry>,
    val items: List<InvLine>,
    val cancelled: Boolean,
    val optional: Boolean,
) {
    val total: Double
        get() {
            val dr = entries.filter { it.amount < 0 }.sumOf { -it.amount }
            val cr = entries.filter { it.amount > 0 }.sumOf { it.amount }
            return maxOf(dr, cr)
        }

    /** Party name, or the biggest ledger in the voucher if there is no party. */
    val displayName: String
        get() = party.ifBlank { entries.maxByOrNull { abs(it.amount) }?.ledger ?: "" }
}

fun isPrimary(parent: String): Boolean = parent.isBlank() || parent.equals("Primary", ignoreCase = true)

// ---------------------------------------------------------------------------
// HTTP client for Tally's built-in XML server (default port 9000)
// ---------------------------------------------------------------------------

class TallyClient(hostInput: String, val port: Int) {

    val host: String = hostInput.trim()
        .removePrefix("http://").removePrefix("https://")
        .substringBefore('/').substringBefore(':')

    fun companies(): List<Company> {
        val xml = post(Requests.collection("TDVCompanies", "Company", "NAME,STARTINGFROM,ENDINGAT", null, null, null))
        val out = ArrayList<Company>()
        XmlStream.parse(xml, setOf("COMPANY")) { n ->
            val name = nameOf(n)
            if (name.isNotBlank()) {
                out += Company(name, Parse.date(n.childText("STARTINGFROM")), Parse.date(n.childText("ENDINGAT")))
            }
        }
        return out.distinctBy { it.name }
    }

    fun ledgers(company: String, from: LocalDate, to: LocalDate): List<Ledger> {
        val xml = post(
            Requests.collection(
                "TDVLedgers", "Ledger", "NAME,PARENT,OPENINGBALANCE,CLOSINGBALANCE", company, from, to,
            )
        )
        val out = ArrayList<Ledger>()
        XmlStream.parse(xml, setOf("LEDGER")) { n ->
            val name = nameOf(n)
            if (name.isNotBlank()) {
                out += Ledger(
                    name,
                    Parse.clean(n.childText("PARENT")),
                    Parse.amount(n.childText("OPENINGBALANCE")),
                    Parse.amount(n.childText("CLOSINGBALANCE")),
                )
            }
        }
        return out
    }

    fun groups(company: String, from: LocalDate, to: LocalDate): List<Group> {
        val xml = post(Requests.collection("TDVGroups", "Group", "NAME,PARENT,CLOSINGBALANCE", company, from, to))
        val out = ArrayList<Group>()
        XmlStream.parse(xml, setOf("GROUP")) { n ->
            val name = nameOf(n)
            if (name.isNotBlank()) {
                out += Group(name, Parse.clean(n.childText("PARENT")), Parse.amount(n.childText("CLOSINGBALANCE")))
            }
        }
        return out
    }

    fun stockItems(company: String, from: LocalDate, to: LocalDate): List<StockItem> {
        val xml = post(
            Requests.collection(
                "TDVStock", "StockItem", "NAME,PARENT,BASEUNITS,CLOSINGBALANCE,CLOSINGRATE,CLOSINGVALUE",
                company, from, to,
            )
        )
        val out = ArrayList<StockItem>()
        XmlStream.parse(xml, setOf("STOCKITEM")) { n ->
            val name = nameOf(n)
            if (name.isNotBlank()) {
                out += StockItem(
                    name,
                    Parse.clean(n.childText("PARENT")),
                    n.childText("BASEUNITS"),
                    n.childText("CLOSINGBALANCE"),
                    n.childText("CLOSINGRATE"),
                    Parse.amount(n.childText("CLOSINGVALUE")),
                )
            }
        }
        return out
    }

    /** All vouchers of the period, via Tally's "Day Book" export. */
    fun vouchers(company: String, from: LocalDate, to: LocalDate): List<Voucher> {
        val xml = post(Requests.dayBook(company, from, to))
        val out = ArrayList<Voucher>()
        XmlStream.parse(xml, setOf("VOUCHER")) { n -> out += parseVoucher(n) }
        return out
    }

    // -- HTTP ---------------------------------------------------------------

    fun post(xml: String): String {
        if (host.isBlank()) throw TallyException("Tally PC ka IP address daalein.")
        val conn = URL("http://$host:$port").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 180_000
            conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            val body = xml.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                ?: throw TallyException("Tally ne HTTP $code error diya.")
            val text = Parse.sanitize(decode(stream.use { it.readBytes() }))
            checkError(text)
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun decode(b: ByteArray): String {
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
            return String(b, 3, b.size - 3, Charsets.UTF_8)
        }
        if (b.size >= 2 && b[0] == '<'.code.toByte() && b[1] == 0.toByte()) return String(b, Charsets.UTF_16LE)
        return String(b, Charsets.UTF_8)
    }

    private fun checkError(text: String) {
        val lineError = Regex("<LINEERROR>(.*?)</LINEERROR>", RegexOption.DOT_MATCHES_ALL).find(text)
        if (lineError != null) throw TallyException("Tally error: " + lineError.groupValues[1].trim())
        val trimmed = text.trim()
        if (trimmed.startsWith("<RESPONSE>", ignoreCase = true)) {
            throw TallyException("Tally response: " + trimmed.removePrefix("<RESPONSE>").removeSuffix("</RESPONSE>").trim())
        }
        if (!trimmed.startsWith("<")) throw TallyException("Tally se unexpected jawab aaya: " + trimmed.take(200))
    }

    // -- parsing helpers ------------------------------------------------------

    private fun nameOf(n: XmlNode): String {
        val attr = Parse.clean(n.attr("NAME"))
        if (attr.isNotBlank()) return attr
        val child = Parse.clean(n.childText("NAME"))
        if (child.isNotBlank()) return child
        return Parse.clean(n.child("NAME.LIST")?.childText("NAME") ?: "")
    }

    private fun entryOf(c: XmlNode): Entry? {
        val ledger = Parse.clean(c.childText("LEDGERNAME"))
        if (ledger.isBlank()) return null
        return Entry(ledger, Parse.amount(c.childText("AMOUNT")))
    }

    private fun parseVoucher(n: XmlNode): Voucher {
        val all = ArrayList<Entry>()
        val plain = ArrayList<Entry>()
        val allocations = ArrayList<Entry>()
        val items = ArrayList<InvLine>()
        for (c in n.children) {
            val name = c.name
            when {
                name == "ALLLEDGERENTRIES.LIST" -> entryOf(c)?.let { all += it }
                name == "LEDGERENTRIES.LIST" -> entryOf(c)?.let { plain += it }
                name.endsWith(".LIST") &&
                    (name.startsWith("ALLINVENTORYENTRIES") || name.startsWith("INVENTORYENTRIES")) -> {
                    val item = Parse.clean(c.childText("STOCKITEMNAME"))
                    if (item.isNotBlank()) {
                        items += InvLine(
                            item,
                            c.childText("BILLEDQTY").ifBlank { c.childText("ACTUALQTY") },
                            c.childText("RATE"),
                            Parse.amount(c.childText("AMOUNT")),
                        )
                    }
                    for (a in c.childrenNamed("ACCOUNTINGALLOCATIONS.LIST")) entryOf(a)?.let { allocations += it }
                }
            }
        }
        val base = if (all.isNotEmpty()) all else plain
        // A voucher always balances. Inventory-side ledgers (e.g. Sales A/c in an invoice) live in
        // accounting allocations, so add them only when the ledger lists alone don't balance.
        val entries = if (allocations.isEmpty() || abs(base.sumOf { it.amount }) < 0.01) base else base + allocations
        return Voucher(
            date = Parse.date(n.childText("DATE")),
            type = Parse.clean(n.childText("VOUCHERTYPENAME").ifBlank { n.attr("VCHTYPE") }),
            number = n.childText("VOUCHERNUMBER"),
            party = Parse.clean(n.childText("PARTYLEDGERNAME")),
            narration = n.childText("NARRATION"),
            entries = entries,
            items = items,
            cancelled = n.childText("ISCANCELLED").equals("Yes", ignoreCase = true),
            optional = n.childText("ISOPTIONAL").equals("Yes", ignoreCase = true),
        )
    }
}

// ---------------------------------------------------------------------------
// XML requests
// ---------------------------------------------------------------------------

object Requests {
    fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun tallyDate(d: LocalDate): String = d.format(DateTimeFormatter.BASIC_ISO_DATE)

    private fun staticVars(company: String?, from: LocalDate?, to: LocalDate?): String = buildString {
        append("<STATICVARIABLES>")
        append("<SVEXPORTFORMAT>\$\$SysName:XML</SVEXPORTFORMAT>")
        if (company != null) append("<SVCURRENTCOMPANY>").append(esc(company)).append("</SVCURRENTCOMPANY>")
        if (from != null) append("<SVFROMDATE TYPE=\"Date\">").append(tallyDate(from)).append("</SVFROMDATE>")
        if (to != null) append("<SVTODATE TYPE=\"Date\">").append(tallyDate(to)).append("</SVTODATE>")
        append("</STATICVARIABLES>")
    }

    fun collection(id: String, type: String, fetch: String, company: String?, from: LocalDate?, to: LocalDate?): String =
        "<ENVELOPE><HEADER><VERSION>1</VERSION><TALLYREQUEST>Export</TALLYREQUEST>" +
            "<TYPE>Collection</TYPE><ID>$id</ID></HEADER><BODY><DESC>" +
            staticVars(company, from, to) +
            "<TDL><TDLMESSAGE><COLLECTION NAME=\"$id\" ISMODIFY=\"No\">" +
            "<TYPE>$type</TYPE><FETCH>$fetch</FETCH></COLLECTION></TDLMESSAGE></TDL>" +
            "</DESC></BODY></ENVELOPE>"

    fun dayBook(company: String, from: LocalDate, to: LocalDate): String =
        "<ENVELOPE><HEADER><TALLYREQUEST>Export Data</TALLYREQUEST></HEADER>" +
            "<BODY><EXPORTDATA><REQUESTDESC><REPORTNAME>Day Book</REPORTNAME>" +
            staticVars(company, from, to) +
            "</REQUESTDESC></EXPORTDATA></BODY></ENVELOPE>"
}

// ---------------------------------------------------------------------------
// Value parsing
// ---------------------------------------------------------------------------

object Parse {
    private val charRef = Regex("&#(x[0-9A-Fa-f]+|[0-9]+);")
    private val number = Regex("-?\\d[\\d,]*(\\.\\d+)?")
    private val dmy = DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH)
    private val dmyShort = DateTimeFormatter.ofPattern("d-MMM-yy", Locale.ENGLISH)

    /** Tally emits control-character references like &#4; that are illegal in XML 1.0. */
    fun sanitize(s: String): String {
        val replaced = charRef.replace(s) { m ->
            val v = m.groupValues[1]
            val code = if (v.startsWith("x")) v.substring(1).toIntOrNull(16) else v.toIntOrNull()
            if (code == null || (code < 32 && code != 9 && code != 10 && code != 13)) "" else m.value
        }
        val sb = StringBuilder(replaced.length)
        for (ch in replaced) if (ch == '\t' || ch == '\n' || ch == '\r' || ch >= ' ') sb.append(ch)
        return sb.toString()
    }

    fun clean(s: String): String = s.trim()

    fun amount(s: String): Double {
        val m = number.find(s.trim()) ?: return 0.0
        return m.value.replace(",", "").toDoubleOrNull() ?: 0.0
    }

    fun date(s: String): LocalDate? {
        val t = s.trim()
        if (t.isEmpty()) return null
        for (f in listOf(DateTimeFormatter.BASIC_ISO_DATE, dmy, dmyShort, DateTimeFormatter.ISO_LOCAL_DATE)) {
            try {
                return LocalDate.parse(t, f)
            } catch (_: Exception) {
            }
        }
        return null
    }
}

// ---------------------------------------------------------------------------
// Minimal streaming XML -> tree parser (only keeps the elements we ask for)
// ---------------------------------------------------------------------------

class XmlNode(val name: String, private val attrs: Map<String, String>) {
    val children = ArrayList<XmlNode>()
    val text = StringBuilder()

    fun attr(n: String): String = attrs[n.uppercase()] ?: ""
    fun child(n: String): XmlNode? = children.firstOrNull { it.name.equals(n, ignoreCase = true) }
    fun childText(n: String): String = child(n)?.text?.toString()?.trim() ?: ""
    fun childrenNamed(n: String): List<XmlNode> = children.filter { it.name.equals(n, ignoreCase = true) }
}

object XmlStream {
    fun parse(xml: String, targets: Set<String>, onElement: (XmlNode) -> Unit) {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(StringReader(xml))
        val stack = ArrayList<XmlNode>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val name = p.name.uppercase()
                    if (stack.isNotEmpty() || name in targets) {
                        val attrs = HashMap<String, String>()
                        for (i in 0 until p.attributeCount) attrs[p.getAttributeName(i).uppercase()] = p.getAttributeValue(i)
                        val node = XmlNode(name, attrs)
                        if (stack.isNotEmpty()) stack[stack.size - 1].children += node
                        stack += node
                    }
                }
                XmlPullParser.TEXT -> if (stack.isNotEmpty()) stack[stack.size - 1].text.append(p.text)
                XmlPullParser.END_TAG -> if (stack.isNotEmpty()) {
                    val node = stack.removeAt(stack.size - 1)
                    if (stack.isEmpty()) onElement(node)
                }
            }
            ev = p.next()
        }
    }
}
