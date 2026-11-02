package com.ankit.tallydata

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

object C {
    val PRIMARY = Color.parseColor("#1565C0")
    val TEXT = Color.parseColor("#212121")
    val MUTED = Color.parseColor("#6B6B6B")
    val DR = Color.parseColor("#C62828")
    val CR = Color.parseColor("#2E7D32")
    val SUMMARY_BG = Color.parseColor("#E3F2FD")
    val DIVIDER = Color.parseColor("#E0E0E0")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.label(s: CharSequence, sizeSp: Float = 15f, color: Int = C.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

fun Context.vertical(paddingDp: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val p = dp(paddingDp)
    setPadding(p, p, p, p)
}

fun matchWrap(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

fun fillRemaining(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

object Fmt {
    private val nf: NumberFormat = NumberFormat.getNumberInstance(Locale("en", "IN")).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }
    private val dateFmt = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    @Synchronized
    fun num(v: Double): String = nf.format(abs(v))

    fun money(v: Double): String = "₹ " + num(v)

    fun drCr(v: Double): String = when {
        abs(v) < 0.005 -> "—"
        v < 0 -> money(v) + " Dr"
        else -> money(v) + " Cr"
    }

    fun drCrColor(v: Double): Int = when {
        abs(v) < 0.005 -> C.MUTED
        v < 0 -> C.DR
        else -> C.CR
    }

    /** Signed plain number for CSV: Debit positive, Credit negative (accountant convention). */
    fun csvDrCr(v: Double): List<String> = listOf(
        if (v < 0) String.format(Locale.US, "%.2f", -v) else "",
        if (v > 0) String.format(Locale.US, "%.2f", v) else "",
    )

    fun date(d: LocalDate?): String = d?.format(dateFmt) ?: ""
}

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("tally", Context.MODE_PRIVATE)
    fun host(ctx: Context): String = sp(ctx).getString("host", "") ?: ""
    fun port(ctx: Context): Int = sp(ctx).getInt("port", 9000)
    fun save(ctx: Context, host: String, port: Int) {
        sp(ctx).edit().putString("host", host).putInt("port", port).apply()
    }
    fun client(ctx: Context) = TallyClient(host(ctx), port(ctx))
}

object Bg {
    private val exec = Executors.newCachedThreadPool()

    fun <T> run(activity: Activity, work: () -> T, done: (Result<T>) -> Unit) {
        exec.execute {
            val r = runCatching(work)
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) done(r)
            }
        }
    }
}

/** In-memory cache so drilling down doesn't re-download the same data from Tally. */
object Repo {
    private val cache = HashMap<String, Any>()

    @Synchronized
    fun clear() = cache.clear()

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> cached(key: String, load: () -> T): T {
        synchronized(this) { cache[key]?.let { return it as T } }
        val v = load()
        synchronized(this) { cache[key] = v }
        return v
    }

    private fun key(kind: String, c: TallyClient, company: String, from: LocalDate, to: LocalDate) =
        "$kind|${c.host}:${c.port}|$company|$from|$to"

    fun ledgers(c: TallyClient, company: String, from: LocalDate, to: LocalDate) =
        cached(key("L", c, company, from, to)) { c.ledgers(company, from, to) }

    fun groups(c: TallyClient, company: String, from: LocalDate, to: LocalDate) =
        cached(key("G", c, company, from, to)) { c.groups(company, from, to) }

    fun stock(c: TallyClient, company: String, from: LocalDate, to: LocalDate) =
        cached(key("S", c, company, from, to)) { c.stockItems(company, from, to) }

    fun vouchers(c: TallyClient, company: String, from: LocalDate, to: LocalDate) =
        cached(key("V", c, company, from, to)) { c.vouchers(company, from, to) }
}

fun friendlyError(e: Throwable): String = when (e) {
    is ConnectException, is NoRouteToHostException ->
        "Tally PC se connect nahi ho paaya.\n\n" +
            "• Tally PC par chal raha hai aur company open hai?\n" +
            "• Tally me Client/Server = Both aur Port = 9000 set hai?\n" +
            "• Phone aur PC ek hi Wi-Fi / network par hain?\n" +
            "• Windows Firewall me port 9000 allow hai?\n\n(${e.message})"
    is SocketTimeoutException ->
        "Tally ne time par jawab nahi diya (timeout). Tally busy ho sakta hai ya data bahut bada hai — " +
            "chhota period chun kar dobara try karein."
    is UnknownHostException -> "IP address galat lag raha hai: ${e.message}"
    is TallyException -> e.message ?: "Tally error"
    is OutOfMemoryError -> "Data bahut bada hai. Chhota period (jaise 1 mahina) chun kar try karein."
    else -> "${e.javaClass.simpleName}: ${e.message}"
}

const val HELP_TEXT = """Ye app aapke computer par chal rahe Tally se seedha (live) data padhta hai.

1) PC par TallyPrime kholein aur apni company open karein.

2) Tally ka server ON karein:
   • TallyPrime: F1 (Help) > Settings > Connectivity > Client/Server configuration
     – TallyPrime acts as = Both
     – Port = 9000
   • Tally.ERP 9: F12 (Configure) > Advanced Configuration
     – Tally.ERP 9 is acting as = Both, Port = 9000
   Settings save karke Tally ek baar restart karein.

3) PC ka IP pata karein: Command Prompt kholkar  ipconfig  likhein.
   "IPv4 Address" (jaise 192.168.1.5) is app me daalein.

4) Windows Firewall me port 9000 allow karein:
   Windows Defender Firewall > Advanced settings > Inbound Rules > New Rule >
   Port > TCP 9000 > Allow the connection.

5) Phone aur PC ek hi Wi-Fi / LAN par hone chahiye.
   Office ke bahar se dekhna ho to dono par "Tailscale" (free VPN) install karein
   aur PC ka Tailscale IP (100.x.x.x) yahan daalein. Router par port 9000 ko
   internet par open na karein — Tally ke server me password nahi hota.

Note: App sirf data padhta hai (read-only). Tally me kuch bhi change nahi karta.
Jo company Tally me open (loaded) hai, wahi list me dikhegi."""
