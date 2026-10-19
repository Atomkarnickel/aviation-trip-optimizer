package com.atomkarnickel.aviationoptimizer.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Öffnet eine Kalenderdatei (.ics) mit einer Kalender-App bzw. bietet sie zum Teilen an.
 * Die Datei liegt im App-Cache und wird nur über den FileProvider (res/xml/file_paths.xml) freigegeben.
 */
fun openCalendarFile(context: Context, fileName: String, ics: String) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, fileName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    file.writeText(ics, Charsets.UTF_8)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/calendar").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val send = Intent(Intent.ACTION_SEND).setType("text/calendar").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser = Intent.createChooser(view, "In Kalender übernehmen").putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(send))
    try {
        context.startActivity(chooser)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Keine App zum Öffnen von Kalenderdateien gefunden.", Toast.LENGTH_LONG).show()
    }
}
