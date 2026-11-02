package com.ankit.tallydata

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
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

data class VoucherType(val name: String, val parent: String) {
    /** The reserved base type ("Sales", "Payment", …) this voucher type belongs to. */
    val base: String get() = parent.ifBlank { name }
}

data class PartyInfo(
    val name: String,
    val parent: String = "",
    val address: List<String> = emptyList(),
    val state: String = "",
    val pincode: String = "",
    val gstin: String = "",
    val mobile: String = "",
    val email: String = "",
)

data class CompanyInfo(
    val name: String,
    val address: List<String> = emptyList(),
    val state: String = "",
    val pincode: String = "",
    val gstin: String = "",
    val phone: String = "",
    val email: String = "",
    val pan: String = "",
)

data class Entry(val ledger: String, val amount: Double)

data class InvLine(
    val item: String,
    val qty: String,
    val rate: String,
    val amount: Double,
    val ledger: String = "",
    val hsn: String = "",
    val discount: String = "",
)

data class Voucher(
    val date: LocalDate?,
    val type: String,
    val number: String,
    val party: String,
    val narration: String,
    /** Every accounting line, including ledgers that sit inside inventory allocations. */
    val entries: List<Entry>,
    val items: List<InvLine>,
    val cancelled: Boolean,
    val optional: Boolean,
    val guid: String = "",
    /** Ledger lines outside inventory (party, taxes, round off…). */
    val ledgerEntries: List<Entry> = entries,
    val buyerName: String = "",
    val buyerAddress: List<String> = emptyList(),
    val partyGstin: String = "",
    val placeOfSupply: String = "",
    val reference: String = "",
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

    val isInvoice: Boolean get() = items.isNotEmpty()
}

data class ImportResult(val created: Int, val altered: Int)

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

    fun companyInfo(company: String): CompanyInfo {
        val xml = post(
            Requests.collection(
                "TDVCompanyInfo", "Company",
                "NAME,ADDRESS,STATENAME,PINCODE,PHONENUMBER,EMAIL,GSTREGISTRATIONNUMBER,INCOMETAXNUMBER",
                company, null, null,
            )
        )
        var found: CompanyInfo? = null
        XmlStream.parse(xml, setOf("COMPANY")) { n ->
            val name = nameOf(n)
            if (found == null || name.equals(company, ignoreCase = true)) {
                found = CompanyInfo(
                    name = name.ifBlank { company },
                    address = listTexts(n, "ADDRESS.LIST", "ADDRESS"),
                    state = n.childText("STATENAME"),
                    pincode = n.childText("PINCODE"),
                    gstin = n.childText("GSTREGISTRATIONNUMBER"),
                    phone = n.childText("PHONENUMBER"),
                    email = n.childText("EMAIL"),
                    pan = n.childText("INCOMETAXNUMBER"),
                )
            }
        }
        return found ?: CompanyInfo(company)
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

    /** Address / GSTIN / contact details of every ledger (used for invoices and sharing). */
    fun partyDetails(company: String): Map<String, PartyInfo> {
        val full = "NAME,PARENT,ADDRESS,LEDSTATENAME,PINCODE,PARTYGSTIN,LEDGERMOBILE,EMAIL,LEDGSTREGDETAILS"
        val basic = "NAME,PARENT,ADDRESS,LEDSTATENAME,PARTYGSTIN"
        val xml = try {
            post(Requests.collection("TDVParties", "Ledger", full, company, null, null))
        } catch (e: TallyException) {
            post(Requests.collection("TDVParties", "Ledger", basic, company, null, null))
        }
        val out = HashMap<String, PartyInfo>()
        XmlStream.parse(xml, setOf("LEDGER")) { n ->
            val name = nameOf(n)
            if (name.isNotBlank()) {
                val gstin = n.childText("PARTYGSTIN").ifBlank {
                    n.childrenNamed("LEDGSTREGDETAILS.LIST").map { it.childText("GSTIN") }.firstOrNull { it.isNotBlank() } ?: ""
                }
                out[name.lowercase()] = PartyInfo(
                    name = name,
                    parent = Parse.clean(n.childText("PARENT")),
                    address = listTexts(n, "ADDRESS.LIST", "ADDRESS"),
                    state = n.childText("LEDSTATENAME"),
                    pincode = n.childText("PINCODE"),
                    gstin = gstin,
                    mobile = n.childText("LEDGERMOBILE"),
                    email = n.childText("EMAIL"),
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

    fun voucherTypes(company: String): List<VoucherType> {
        val xml = post(Requests.collection("TDVVchTypes", "VoucherType", "NAME,PARENT", company, null, null))
        val out = ArrayList<VoucherType>()
        XmlStream.parse(xml, setOf("VOUCHERTYPE")) { n ->
            val name = nameOf(n)
            if (name.isNotBlank()) out += VoucherType(name, Parse.clean(n.childText("PARENT")))
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

    /**
     * Sends vouchers / masters to Tally ("Import Data"). [reportName] is "Vouchers" or "All Masters";
     * [tallyMessageBody] is one or more <VOUCHER>/<LEDGER> elements.
     */
    fun importData(reportName: String, company: String, tallyMessageBody: String): ImportResult {
        val text = postRaw(Requests.importEnvelope(reportName, company, tallyMessageBody))
        fun count(tag: String) =
            Regex("<$tag>\\s*(-?\\d+)\\s*</$tag>", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val errors = Regex("<LINEERROR>(.*?)</LINEERROR>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(text).map { Parse.unescape(it.groupValues[1].trim()) }.filter { it.isNotEmpty() }.toList()
        val created = count("CREATED")
        val altered = count("ALTERED")
        if (errors.isNotEmpty()) throw TallyException("Tally ne entry accept nahi ki:\n• " + errors.joinToString("\n• "))
        if (created + altered == 0) {
            throw TallyException(
                "Tally ne kuch save nahi kiya (errors=${count("ERRORS")}, ignored=${count("IGNORED")}).\n" +
                    "Jawab: " + text.replace(Regex("\\s+"), " ").take(300)
            )
        }
        return ImportResult(created, altered)
    }

    // -- HTTP ---------------------------------------------------------------

    fun post(xml: String): String {
        val text = postRaw(xml)
        checkError(text)
        return text
    }

    private fun postRaw(xml: String): String {
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
            return Parse.sanitize(decode(stream.use { it.readBytes() }))
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
        if (lineError != null) throw TallyException("Tally error: " + Parse.unescape(lineError.groupValues[1].trim()))
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

    private fun listTexts(n: XmlNode, listName: String, itemName: String): List<String> =
        n.childrenNamed(listName).flatMap { it.childrenNamed(itemName) }
            .map { it.text.toString().trim() }.filter { it.isNotEmpty() }

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
                    val itemAllocs = c.childrenNamed("ACCOUNTINGALLOCATIONS.LIST").mapNotNull { entryOf(it) }
                    allocations += itemAllocs
                    val item = Parse.clean(c.childText("STOCKITEMNAME"))
                    if (item.isNotBlank()) {
                        val hsn = listOf("GSTHSNNAME", "HSNCODE", "GSTHSNSACCODE", "HSNSACCODE")
                            .map { c.childText(it) }.firstOrNull { it.isNotBlank() } ?: ""
                        items += InvLine(
                            item = item,
                            qty = c.childText("BILLEDQTY").ifBlank { c.childText("ACTUALQTY") },
                            rate = c.childText("RATE"),
                            amount = Parse.amount(c.childText("AMOUNT")),
                            ledger = itemAllocs.firstOrNull()?.ledger ?: "",
                            hsn = hsn,
                            discount = c.childText("DISCOUNT"),
                        )
                    }
                }
            }
        }
        val base = if (all.isNotEmpty()) all else plain
        // A voucher always balances. Inventory-side ledgers (e.g. Sales A/c in an invoice) live in
        // accounting allocations, so add them only when the ledger lists alone don't balance.
        val entries = if (allocations.isEmpty() || abs(base.sumOf { it.amount }) < 0.01) base else base + allocations
        val buyerAddress = listTexts(n, "BASICBUYERADDRESS.LIST", "BASICBUYERADDRESS")
            .ifEmpty { listTexts(n, "ADDRESS.LIST", "ADDRESS") }
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
            guid = n.attr("REMOTEID").ifBlank { n.childText("GUID") },
            ledgerEntries = base,
            buyerName = n.childText("BASICBUYERNAME"),
            buyerAddress = buyerAddress,
            partyGstin = n.childText("PARTYGSTIN"),
            placeOfSupply = n.childText("PLACEOFSUPPLY").ifBlank { n.childText("STATENAME") },
            reference = n.childText("REFERENCE"),
        )
    }
}

// ---------------------------------------------------------------------------
// XML requests
// ---------------------------------------------------------------------------

object Requests {
    fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    fun tallyDate(d: LocalDate): String = d.format(DateTimeFormatter.BASIC_ISO_DATE)

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

    fun importEnvelope(reportName: String, company: String, body: String): String =
        "<ENVELOPE><HEADER><TALLYREQUEST>Import Data</TALLYREQUEST></HEADER>" +
            "<BODY><IMPORTDATA><REQUESTDESC><REPORTNAME>${esc(reportName)}</REPORTNAME>" +
            "<STATICVARIABLES><SVCURRENTCOMPANY>${esc(company)}</SVCURRENTCOMPANY></STATICVARIABLES>" +
            "</REQUESTDESC><REQUESTDATA><TALLYMESSAGE xmlns:UDF=\"TallyUDF\">" + body +
            "</TALLYMESSAGE></REQUESTDATA></IMPORTDATA></BODY></ENVELOPE>"
}

// ---------------------------------------------------------------------------
// Building vouchers / masters to send to Tally
// ---------------------------------------------------------------------------

/** One accounting line. [amount] uses Tally's sign: negative = Debit, positive = Credit. */
data class LineInput(val ledger: String, val amount: Double)

data class ItemInput(
    val item: String,
    val qty: Double,
    val unit: String,
    val rate: Double,
    val amount: Double,
    val ledger: String,
)

object VoucherXml {
    private fun esc(s: String) = Requests.esc(s)
    fun amt(v: Double): String = String.format(Locale.US, "%.2f", v)
    private fun yesNo(b: Boolean) = if (b) "Yes" else "No"

    fun num(q: Double): String {
        val s = String.format(Locale.US, "%.4f", q).trimEnd('0').trimEnd('.')
        return if (s.isEmpty() || s == "-") "0" else s
    }

    fun qty(q: Double, unit: String): String = " " + num(q) + if (unit.isBlank()) "" else " $unit"

    private fun openTag(type: String, guid: String?, invoice: Boolean): String {
        val alter = !guid.isNullOrBlank()
        return buildString {
            append("<VOUCHER")
            if (alter) append(" REMOTEID=\"").append(esc(guid!!)).append('"')
            append(" VCHTYPE=\"").append(esc(type)).append('"')
            append(" ACTION=\"").append(if (alter) "Alter" else "Create").append('"')
            append(" OBJVIEW=\"").append(if (invoice) "Invoice Voucher View" else "Accounting Voucher View").append("\">")
        }
    }

    private fun StringBuilder.tag(name: String, value: String) {
        append('<').append(name).append('>').append(esc(value)).append("</").append(name).append('>')
    }

    private fun StringBuilder.ledgerLine(listName: String, l: LineInput, party: Boolean = false, billRef: String? = null) {
        append('<').append(listName).append('>')
        tag("LEDGERNAME", l.ledger)
        tag("ISDEEMEDPOSITIVE", yesNo(l.amount < 0))
        if (party) tag("ISPARTYLEDGER", "Yes")
        tag("AMOUNT", amt(l.amount))
        if (!billRef.isNullOrBlank()) {
            append("<BILLALLOCATIONS.LIST>")
            tag("NAME", billRef)
            tag("BILLTYPE", "New Ref")
            tag("AMOUNT", amt(l.amount))
            append("</BILLALLOCATIONS.LIST>")
        }
        append("</").append(listName).append('>')
    }

    /** Payment / Receipt / Contra / Journal etc. [guid] set = alter that existing voucher. */
    fun accounting(
        type: String,
        date: LocalDate,
        number: String,
        narration: String,
        lines: List<LineInput>,
        guid: String? = null,
    ): String = buildString {
        append(openTag(type, guid, invoice = false))
        tag("DATE", Requests.tallyDate(date))
        tag("VOUCHERTYPENAME", type)
        if (number.isNotBlank()) tag("VOUCHERNUMBER", number)
        tag("NARRATION", narration)
        tag("PERSISTEDVIEW", "Accounting Voucher View")
        for (l in lines) ledgerLine("ALLLEDGERENTRIES.LIST", l)
        append("</VOUCHER>")
    }

    /**
     * Item invoice. [itemSign] is +1 when stock goes out on the credit side (Sales, Debit Note) and
     * -1 for Purchase / Credit Note. [extras] are tax / freight / round-off lines in Tally sign.
     */
    fun invoice(
        type: String,
        date: LocalDate,
        number: String,
        reference: String,
        narration: String,
        party: String,
        itemSign: Int,
        items: List<ItemInput>,
        extras: List<LineInput>,
        guid: String? = null,
    ): String = buildString {
        val itemsTotal = items.sumOf { it.amount } * itemSign
        val partyAmount = -(itemsTotal + extras.sumOf { it.amount })
        append(openTag(type, guid, invoice = true))
        tag("DATE", Requests.tallyDate(date))
        tag("VOUCHERTYPENAME", type)
        if (number.isNotBlank()) tag("VOUCHERNUMBER", number)
        if (reference.isNotBlank()) tag("REFERENCE", reference)
        tag("PARTYLEDGERNAME", party)
        tag("PARTYNAME", party)
        tag("BASICBUYERNAME", party)
        tag("PERSISTEDVIEW", "Invoice Voucher View")
        tag("ISINVOICE", "Yes")
        tag("NARRATION", narration)
        ledgerLine("LEDGERENTRIES.LIST", LineInput(party, partyAmount), party = true, billRef = number.ifBlank { null })
        for (e in extras) ledgerLine("LEDGERENTRIES.LIST", e)
        for (line in items) {
            val signed = line.amount * itemSign
            val q = qty(line.qty, line.unit)
            append("<ALLINVENTORYENTRIES.LIST>")
            tag("STOCKITEMNAME", line.item)
            tag("ISDEEMEDPOSITIVE", yesNo(signed < 0))
            tag("RATE", amt(line.rate) + if (line.unit.isBlank()) "" else "/${line.unit}")
            tag("AMOUNT", amt(signed))
            tag("ACTUALQTY", q)
            tag("BILLEDQTY", q)
            append("<BATCHALLOCATIONS.LIST>")
            tag("GODOWNNAME", "Main Location")
            tag("AMOUNT", amt(signed))
            tag("ACTUALQTY", q)
            tag("BILLEDQTY", q)
            append("</BATCHALLOCATIONS.LIST>")
            ledgerLine("ACCOUNTINGALLOCATIONS.LIST", LineInput(line.ledger, signed))
            append("</ALLINVENTORYENTRIES.LIST>")
        }
        append("</VOUCHER>")
    }

    fun ledger(p: PartyInfo): String = buildString {
        append("<LEDGER NAME=\"").append(esc(p.name)).append("\" ACTION=\"Create\">")
        append("<NAME.LIST TYPE=\"String\">")
        tag("NAME", p.name)
        append("</NAME.LIST>")
        tag("PARENT", p.parent)
        if (p.parent.startsWith("Sundry", ignoreCase = true)) tag("ISBILLWISEON", "Yes")
        if (p.address.isNotEmpty()) {
            append("<ADDRESS.LIST TYPE=\"String\">")
            for (a in p.address) tag("ADDRESS", a)
            append("</ADDRESS.LIST>")
        }
        if (p.state.isNotBlank()) {
            tag("LEDSTATENAME", p.state)
            tag("COUNTRYNAME", "India")
        }
        if (p.pincode.isNotBlank()) tag("PINCODE", p.pincode)
        if (p.gstin.isNotBlank()) {
            tag("PARTYGSTIN", p.gstin)
            tag("GSTREGISTRATIONTYPE", "Regular")
        }
        if (p.mobile.isNotBlank()) tag("LEDGERMOBILE", p.mobile)
        if (p.email.isNotBlank()) tag("EMAIL", p.email)
        append("</LEDGER>")
    }
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

    fun unescape(s: String): String = s.replace("&apos;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    fun amount(s: String): Double {
        val m = number.find(s.trim()) ?: return 0.0
        return m.value.replace(",", "").toDoubleOrNull() ?: 0.0
    }

    /** " 10.5 Nos" -> (10.5, "Nos") */
    fun qty(s: String): Pair<Double, String> {
        val t = s.trim()
        val m = number.find(t) ?: return 0.0 to ""
        val unit = t.substring(m.range.last + 1).trim().substringBefore(' ')
        return abs(m.value.replace(",", "").toDoubleOrNull() ?: 0.0) to unit
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
