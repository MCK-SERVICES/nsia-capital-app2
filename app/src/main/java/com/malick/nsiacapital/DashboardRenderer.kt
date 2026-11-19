package com.malick.nsiacapital

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Transforme les données lues sur le site en écrans natifs (cartes, synthèse). */
class DashboardRenderer(
    private val ctx: Context,
    private val root: LinearLayout,
    private val actions: Actions
) {

    interface Actions {
        fun onSync()
        fun onExport()
        fun onClear()
        fun onOpenLink(url: String)
    }

    private val navy = Color.parseColor("#0B3D91")
    private val gold = Color.parseColor("#B8860B")
    private val green = Color.parseColor("#1B7F3B")
    private val red = Color.parseColor("#C62828")
    private val grey = Color.parseColor("#6B7280")

    private val summaryLabel = Regex(
        "valori|total|solde|esp[eè]ce|disponible|plus.?value|moins.?value|\\+/-|performance|avoir|liquidit|encours|net|montant|actions|obligation|opcvm",
        RegexOption.IGNORE_CASE
    )
    private val signedHeader = Regex("plus|moins|variation|var\\.|perf|gain|perte|\\+/-|%", RegexOption.IGNORE_CASE)
    private val numberRegex = Regex("^[+-]?\\s?[\\d\\s  .,]+\\s?(%|F|FCFA|F CFA|XOF|CFA)?$", RegexOption.IGNORE_CASE)
    private val inlineKv = Regex(
        "([A-Za-zÀ-ÿ'’./+\\- ]{3,40}?)\\s*:\\s*([+-]?\\d[\\d\\s  .,]*(?:\\s?(?:FCFA|F CFA|XOF|F|%))?)"
    )

    fun render(store: DataStore) {
        root.removeAllViews()

        val dateTxt = if (store.lastSync > 0) {
            "Dernière mise à jour : " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(store.lastSync))
        } else "Pas encore de données"
        root.addView(text(dateTxt, 13f, grey))

        root.addView(buttonsRow())

        if (store.isEmpty()) {
            root.addView(card().apply {
                addInner(this, text("Bienvenue", 18f, navy, bold = true))
                addInner(
                    this, text(
                        "1. Va dans l'onglet « Espace client » et connecte-toi (identifiant, mot de passe, OTP).\n" +
                            "2. Ouvre tes pages : portefeuille, compte espèces, ordres, historique…\n" +
                            "3. Reviens ici : tout s'affiche automatiquement. Tu peux aussi appuyer sur « Tout synchroniser ».",
                        15f, Color.DKGRAY
                    )
                )
            })
        }

        renderSummary(store)

        val portfolioFirst = Regex("portefeuille|valorisation|liquidit|compte|position|titres", RegexOption.IGNORE_CASE)
        val ordered = store.pages.values.sortedBy { p ->
            val head = p.optString("heading") + " " + p.optString("title") + " " + p.optString("text").take(600)
            if (portfolioFirst.containsMatchIn(head)) 0 else 1
        }
        for (page in ordered) {
            val tables = page.optJSONArray("tables") ?: continue
            val useful = (0 until tables.length()).mapNotNull { tables.optJSONObject(it) }.filter { isUseful(it) }
            if (useful.isEmpty()) continue
            val title = page.optString("heading").ifBlank { page.optString("title") }.ifBlank { "Page" }
            root.addView(sectionTitle(cleanTitle(title)))
            for (t in useful) renderTable(t)
        }

        renderLinks(store)
    }

    // ---------- Synthèse ----------

    private fun renderSummary(store: DataStore) {
        val found = LinkedHashMap<String, String>()
        for (page in store.pages.values) {
            val tables = page.optJSONArray("tables")
            if (tables != null) {
                for (i in 0 until tables.length()) {
                    val t = tables.optJSONObject(i) ?: continue
                    for ((k, v) in keyValues(t)) {
                        if (summaryLabel.containsMatchIn(k) && isNumber(v) && !found.containsKey(k.lowercase())) {
                            found[k.lowercase()] = "$k\u0000$v"
                        }
                    }
                }
            }
            for (m in inlineKv.findAll(page.optString("text"))) {
                val k = m.groupValues[1].trim()
                val v = m.groupValues[2].trim()
                if (summaryLabel.containsMatchIn(k) && v.any { it.isDigit() } && !found.containsKey(k.lowercase())) {
                    found[k.lowercase()] = "$k\u0000$v"
                }
            }
        }
        val alloc = Regex("(Actions|Liquidit[ée]s|Obligations|OPCVM)\\s+(\\d+[.,]\\d+\\s?%)", RegexOption.IGNORE_CASE)
        for (page in store.pages.values) {
            for (m in alloc.findAll(page.optString("text"))) {
                val k = "Part " + m.groupValues[1]
                if (!found.containsKey(k.lowercase())) found[k.lowercase()] = "$k\u0000${m.groupValues[2]}"
            }
        }
        if (found.isEmpty()) return
        root.addView(sectionTitle("Synthèse"))
        val c = card()
        for (entry in found.values.take(10)) {
            val (k, v) = entry.split("\u0000", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(text(k, 13f, grey))
            row.addView(text(v, 20f, colorFor(k, v, navy), bold = true))
            addInner(c, row)
        }
        root.addView(c)
    }

    // ---------- Tableaux ----------

    private fun isUseful(t: JSONObject): Boolean {
        val rows = t.optJSONArray("rows") ?: return false
        var cells = 0
        var chars = 0
        for (i in 0 until rows.length()) {
            val c = rows.optJSONObject(i)?.optJSONArray("c") ?: continue
            for (j in 0 until c.length()) {
                val s = c.optString(j)
                if (s.isNotBlank()) { cells++; chars += s.length }
            }
        }
        if (cells < 2) return false
        // tableau de mise en page : très peu de cellules mais énormément de texte
        if (cells <= 3 && chars > 400) return false
        return true
    }

    private val junk = Regex("bienvenue|derni[eè]re tentative de connexion|aucune notification", RegexOption.IGNORE_CASE)

    private fun cleanTitle(s: String): String =
        s.replace(Regex("\\s*Plus\\s*(\\.\\.\\.|…)\\s*$"), "").trim()

    private fun renderTable(t: JSONObject) {
        val rows = t.optJSONArray("rows") ?: return
        val list = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
        val header: List<String>? = list.firstOrNull { it.optBoolean("h") }?.let { cells(it) }
        val data = list.filter { !it.optBoolean("h") }.map { cells(it) }
            .filter { r -> r.any { it.isNotBlank() } }
            .filter { r -> !junk.containsMatchIn(r.joinToString(" ")) }
        if (data.isEmpty()) return

        cleanTitle(t.optString("title")).takeIf { it.isNotBlank() }?.let {
            root.addView(text(it, 15f, gold, bold = true).apply { setPadding(dp(4), dp(10), 0, dp(2)) })
        }

        // Tableau « libellé | valeur » -> une carte avec des lignes
        val isKv = header == null && data.all { it.count { s -> s.isNotBlank() } <= 2 }
        if (isKv) {
            val c = card()
            for (r in data.take(60)) {
                val nonEmpty = r.filter { it.isNotBlank() }
                if (nonEmpty.size == 2) addInner(c, kvLine(nonEmpty[0], nonEmpty[1]))
                else if (nonEmpty.size == 1) addInner(c, text(nonEmpty[0], 14f, Color.DKGRAY, bold = true))
            }
            root.addView(c)
            return
        }

        // Tableau avec en-tête et peu de colonnes -> grille compacte comme sur le site
        if (header != null && header.count { it.isNotBlank() } in 2..6) {
            val keep = header.indices.filter { i -> header[i].isNotBlank() || data.any { it.getOrElse(i) { "" }.isNotBlank() } }
            val c = card()
            addInner(c, gridRow(keep.map { header.getOrElse(it) { "" } }, keep.map { header.getOrElse(it) { "" } }, isHeader = true))
            for (r in data.take(60)) {
                addInner(c, gridRow(keep.map { r.getOrElse(it) { "" } }, keep.map { header.getOrElse(it) { "" } }, isHeader = false))
            }
            root.addView(c)
            if (data.size > 60) root.addView(text("… et ${data.size - 60} autres lignes", 13f, grey))
            return
        }

        // Lignes sans en-tête -> une ligne par élément : libellé à gauche, valeurs à droite
        if (header == null) {
            val c = card()
            for (r in data.take(60)) {
                val vals = r.filter { it.isNotBlank() && it != "+" && it != "-" }
                if (vals.isEmpty()) continue
                val label = vals.firstOrNull { !isNumber(it) } ?: vals[0]
                val rest = vals.filter { it !== label }
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(6), 0, dp(6))
                }
                row.addView(text(label, 15f, navy, bold = true).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                val right = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END
                }
                for (v in rest) right.addView(text(v, 15f, colorFor("variation", v, Color.BLACK), bold = isNumber(v)).apply { gravity = Gravity.END })
                row.addView(right)
                addInner(c, row)
            }
            root.addView(c)
            return
        }

        // Grand tableau avec beaucoup de colonnes -> une carte par ligne
        val shown = data.take(100)
        for (r in shown) {
            val c = card()
            val titleIdx = pickTitleIndex(header, r)
            addInner(c, text(r.getOrElse(titleIdx) { "" }.ifBlank { "—" }, 16f, navy, bold = true))
            for ((i, v) in r.withIndex()) {
                if (i == titleIdx || v.isBlank()) continue
                val label = header.getOrNull(i)?.ifBlank { null } ?: continue
                addInner(c, kvLine(label, v))
            }
            root.addView(c)
        }
        if (data.size > shown.size) {
            root.addView(text("… et ${data.size - shown.size} autres lignes (voir l'onglet Espace client)", 13f, grey))
        }
    }

    private fun gridRow(values: List<String>, headers: List<String>, isHeader: Boolean): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        for ((i, v) in values.withIndex()) {
            val color = when {
                isHeader -> Color.BLACK
                i == 0 -> navy
                else -> colorFor(headers.getOrElse(i) { "" }, v, Color.DKGRAY)
            }
            row.addView(text(v, if (isHeader) 13f else 14f, color, bold = isHeader || i == 0).apply {
                gravity = if (i == 0) Gravity.START else Gravity.END
                maxLines = 2
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, if (i == 0) 1.4f else 1f)
            })
        }
        return row
    }

    private fun pickTitleIndex(header: List<String>?, r: List<String>): Int {
        if (header != null) {
            val pref = Regex("libell|titre|valeur|d[ée]sign|nom|instrument|action|soci[ée]t[ée]|symbole|code|date", RegexOption.IGNORE_CASE)
            val idx = header.indexOfFirst { pref.containsMatchIn(it) }
            if (idx >= 0 && r.getOrElse(idx) { "" }.isNotBlank()) return idx
        }
        val firstText = r.indexOfFirst { it.isNotBlank() && !isNumber(it) }
        return if (firstText >= 0) firstText else 0
    }

    private fun keyValues(t: JSONObject): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val rows = t.optJSONArray("rows") ?: return out
        val list = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
        for (r in list) {
            val c = cells(r).filter { it.isNotBlank() }
            if (c.size == 2) out.add(c[0] to c[1])
            if (c.size == 4 && !isNumber(c[2])) { out.add(c[0] to c[1]); out.add(c[2] to c[3]) }
            if (c.size >= 3) {
                val label = c.firstOrNull { !isNumber(it) && it.length > 1 }
                val amount = c.firstOrNull { isNumber(it) && !it.endsWith("%") }
                if (label != null && amount != null) out.add(label to amount)
            }
        }
        // en-tête + une seule ligne de données → couples en-tête/valeur
        val header = list.firstOrNull { it.optBoolean("h") }?.let { cells(it) }
        val data = list.filter { !it.optBoolean("h") }
        if (header != null && data.size == 1) {
            val d = cells(data[0])
            for ((i, h) in header.withIndex()) {
                val v = d.getOrNull(i) ?: continue
                if (h.isNotBlank() && v.isNotBlank()) out.add(h to v)
            }
        }
        // ligne « Total » d'un tableau
        for (r in data) {
            val c = cells(r)
            val first = c.firstOrNull { it.isNotBlank() } ?: continue
            if (first.contains("total", ignoreCase = true)) {
                val lastNum = c.lastOrNull { isNumber(it) } ?: continue
                out.add(first to lastNum)
            }
        }
        return out
    }

    // ---------- Liens détectés ----------

    private fun renderLinks(store: DataStore) {
        val links = LinkedHashMap<String, String>()
        val nav = Regex(
            "portefeuille|position|solde|compte|ordre|historique|mouvement|relev|avoir|titre|esp[eè]ce|op[ée]ration|synth|valoris|march|dividende",
            RegexOption.IGNORE_CASE
        )
        for (page in store.pages.values) {
            val arr: JSONArray = page.optJSONArray("links") ?: continue
            for (i in 0 until arr.length()) {
                val l = arr.optJSONObject(i) ?: continue
                val t = l.optString("t")
                val h = l.optString("h")
                if (h.startsWith("https://clients.nsiafinance.com") && nav.containsMatchIn(t) && !links.containsKey(t)) links[t] = h
            }
        }
        if (links.isEmpty()) return
        root.addView(sectionTitle("Pages de ton espace client"))
        val c = card()
        for ((t, h) in links.entries.take(25)) {
            addInner(c, text("›  $t", 15f, navy).apply {
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { actions.onOpenLink(h) }
            })
        }
        root.addView(c)
    }

    // ---------- Petites briques d'interface ----------

    private fun buttonsRow(): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
        }
        row.addView(button("Tout synchroniser") { actions.onSync() })
        row.addView(button("Exporter") { actions.onExport() })
        row.addView(button("Effacer") { actions.onClear() })
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun button(label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(ctx).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }

    private fun sectionTitle(s: String): TextView =
        text(s, 19f, navy, bold = true).apply { setPadding(dp(4), dp(18), dp(4), dp(6)) }

    private fun card(): MaterialCardView =
        MaterialCardView(ctx).apply {
            radius = dp(14).toFloat()
            cardElevation = dp(1).toFloat()
            useCompatPadding = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
            })
        }

    private fun addInner(card: MaterialCardView, v: View) {
        (card.getChildAt(0) as LinearLayout).addView(v)
    }

    private fun kvLine(label: String, value: String): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
        }
        row.addView(text(label, 14f, grey).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(text(value, 14f, colorFor(label, value, Color.BLACK), bold = isNumber(value)).apply {
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        })
        return row
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setTextIsSelectable(false)
        }

    private fun colorFor(label: String, value: String, default: Int): Int {
        val v = value.trim()
        if (!isNumber(v)) return default
        if (v.startsWith("-")) return red
        if (signedHeader.containsMatchIn(label) && v.any { it in '1'..'9' }) return green
        return default
    }

    private fun isNumber(s: String): Boolean {
        val t = s.trim()
        return t.isNotEmpty() && t.any { it.isDigit() } && numberRegex.matches(t)
    }

    private fun cells(row: JSONObject): List<String> {
        val c = row.optJSONArray("c") ?: return emptyList()
        return (0 until c.length()).map { c.optString(it).trim() }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
}
