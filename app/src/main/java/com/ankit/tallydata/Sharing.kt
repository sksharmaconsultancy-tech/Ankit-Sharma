package com.ankit.tallydata

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Serves files from cache/shared to WhatsApp, Gmail etc. (minimal FileProvider replacement). */
class ShareProvider : ContentProvider() {
    companion object {
        fun authority(ctx: Context) = ctx.packageName + ".share"
        fun dir(ctx: Context) = File(ctx.cacheDir, "shared").apply { mkdirs() }
        fun uriFor(ctx: Context, file: File): Uri =
            Uri.Builder().scheme("content").authority(authority(ctx)).appendPath(file.name).build()
    }

    override fun onCreate(): Boolean = true

    private fun file(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        val dir = dir(ctx)
        val f = File(dir, name)
        if (f.parentFile?.canonicalPath != dir.canonicalPath || !f.exists()) throw FileNotFoundException(name)
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri): String =
        if (uri.lastPathSegment?.endsWith(".pdf", ignoreCase = true) == true) "application/pdf" else "application/octet-stream"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = file(uri)
        val cols = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }.toTypedArray()
        val cursor = MatrixCursor(cols)
        cursor.addRow(cols.map { if (it == OpenableColumns.DISPLAY_NAME) f.name else f.length() }.toTypedArray())
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

object Sharing {

    fun docTitle(v: Voucher): String = when {
        v.isInvoice && v.type.contains("Purchase", true) -> "Purchase Invoice"
        v.isInvoice && v.type.contains("Credit", true) -> "Credit Note"
        v.isInvoice && v.type.contains("Debit", true) -> "Debit Note"
        v.isInvoice -> "Tax Invoice"
        else -> "${v.type} Voucher"
    }

    /** Builds the PDF in the background, then lets the user pick WhatsApp / Email / other apps. */
    fun shareVoucher(activity: Activity, company: String, v: Voucher, onDone: (() -> Unit)? = null) {
        val wait = AlertDialog.Builder(activity).setMessage("PDF ban raha hai…").setCancelable(false).show()
        val client = Prefs.client(activity)
        Bg.run(activity, {
            val info = Masters.companyInfo(client, company)
            val party = Masters.parties(client, company)[v.party.lowercase()]
            val settings = InvoiceSettings.load(activity, company, info)
            val safe = (docTitle(v) + "_" + v.number.ifBlank { Fmt.date(v.date) } + "_" + v.displayName)
                .replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(80)
            val dir = ShareProvider.dir(activity)
            dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() }
            val file = File(dir, "$safe.pdf")
            InvoicePdf.write(file, v, settings, party, docTitle(v))
            Triple(file, party, settings)
        }) { r ->
            wait.dismiss()
            r.onSuccess { (file, party, settings) ->
                val msg = buildString {
                    append("Dear ").append(v.displayName).append(",\n")
                    append("Please find attached ").append(docTitle(v))
                    if (v.number.isNotBlank()) append(" No. ").append(v.number)
                    append(" dated ").append(Fmt.date(v.date))
                    append(" for ").append(Fmt.money(v.total)).append(".\n\nRegards,\n").append(settings.companyName)
                }
                val options = arrayOf(
                    "WhatsApp" + (party?.mobile?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""),
                    "Email" + (party?.email?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""),
                    "Doosre apps (Drive, Files, Telegram…)",
                    "PDF dekhein",
                )
                AlertDialog.Builder(activity)
                    .setTitle("${file.name} — kahan bhejein?")
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> whatsapp(activity, file, msg, party?.mobile)
                            1 -> email(activity, file, msg, party?.email, docTitle(v) + " " + v.number + " - " + settings.companyName)
                            2 -> other(activity, file, msg)
                            3 -> view(activity, file)
                        }
                        onDone?.invoke()
                    }
                    .setOnCancelListener { onDone?.invoke() }
                    .show()
            }.onFailure {
                activity.message("PDF nahi bana", friendlyError(it)) { onDone?.invoke() }
            }
        }
    }

    private fun base(activity: Activity, file: File, text: String): Intent {
        val uri = ShareProvider.uriFor(activity, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun whatsapp(activity: Activity, file: File, text: String, mobile: String?) {
        val digits = mobile?.filter { it.isDigit() } ?: ""
        val phone = when {
            digits.length == 10 -> "91$digits"
            digits.length > 10 -> digits.takeLast(12).let { if (it.startsWith("0")) "91" + it.takeLast(10) else it }
            else -> ""
        }
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            val i = base(activity, file, text).setPackage(pkg)
            if (phone.isNotEmpty()) i.putExtra("jid", "$phone@s.whatsapp.net")
            try {
                activity.startActivity(i)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        other(activity, file, text)
    }

    private fun email(activity: Activity, file: File, text: String, to: String?, subject: String) {
        val i = base(activity, file, text).apply {
            putExtra(Intent.EXTRA_SUBJECT, subject)
            if (!to.isNullOrBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
        }
        activity.startActivity(Intent.createChooser(i, "Email app chunein"))
    }

    private fun other(activity: Activity, file: File, text: String) {
        activity.startActivity(Intent.createChooser(base(activity, file, text), "Bhejein"))
    }

    private fun view(activity: Activity, file: File) {
        val uri = ShareProvider.uriFor(activity, file)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(i)
        } catch (_: ActivityNotFoundException) {
            activity.message("PDF viewer nahi mila", "Phone me koi PDF viewer app install karein, ya 'Doosre apps' se bhejein.")
        }
    }
}
