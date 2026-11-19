package com.malick.nsiacapital

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Garde en mémoire (et sur le téléphone, dans le stockage privé de l'app)
 * les données lues sur le site NSIA Capital Online.
 * Aucun identifiant ni mot de passe n'est jamais lu ni enregistré.
 */
class DataStore(context: Context) {

    private val prefs = context.getSharedPreferences("nsia_data", Context.MODE_PRIVATE)

    /** Pages lues, dans l'ordre où elles ont été vues pour la première fois. */
    val pages = LinkedHashMap<String, JSONObject>()

    /** Réponses JSON interceptées (appels AJAX du site). */
    val jsons = LinkedHashMap<String, JSONObject>()

    var lastSync: Long = 0L
        private set

    init {
        load()
    }

    fun isEmpty(): Boolean = pages.values.none { (it.optJSONArray("tables")?.length() ?: 0) > 0 }

    fun add(type: String, url: String, json: String): Boolean {
        if (isLoginUrl(url)) return false
        val obj = try { JSONObject(json) } catch (e: Exception) { return false }
        val now = System.currentTimeMillis()
        when (type) {
            "page" -> {
                obj.put("url", url)
                obj.put("ts", now)
                val key = pageKey(url, obj)
                pages[key] = obj
                while (pages.size > 80) {
                    pages.remove(pages.keys.first())
                }
            }
            "json" -> {
                obj.put("ts", now)
                val key = obj.optString("url").substringBefore("?")
                jsons[key] = obj
                while (jsons.size > 40) {
                    jsons.remove(jsons.keys.first())
                }
            }
            else -> return false
        }
        lastSync = now
        return true
    }

    fun clear() {
        pages.clear()
        jsons.clear()
        lastSync = 0L
        save()
    }

    fun markSynced() {
        lastSync = System.currentTimeMillis()
    }

    /** Liens de l'espace client qui mènent à des pages de consultation (jamais d'ordre, de virement ou de déconnexion). */
    fun candidateLinks(): List<String> {
        val include = Regex(
            "portefeuille|position|solde|compte|ordre|historique|mouvement|relev|avoir|titre|esp[eè]ce|" +
                "transaction|op[ée]ration|synth[eè]se|valoris|cours|march[ée]|dividende|coupon|situation|accueil",
            RegexOption.IGNORE_CASE
        )
        val exclude = Regex(
            "d[ée]connex|logout|logoff|log off|quitter|annul|supprim|passer|nouvel|saisi|cr[ée]er|achat|achet|" +
                "vente|vend|retrait|virement|transf|souscri|rachat|modif|param|mot de passe|password|valider|confirm",
            RegexOption.IGNORE_CASE
        )
        val seen = LinkedHashMap<String, String>()
        for (page in pages.values) {
            val links = page.optJSONArray("links") ?: continue
            for (i in 0 until links.length()) {
                val l = links.optJSONObject(i) ?: continue
                val text = l.optString("t")
                val href = l.optString("h")
                if (!href.startsWith("https://clients.nsiafinance.com")) continue
                if (isLoginUrl(href)) continue
                if (!include.containsMatchIn(text) || exclude.containsMatchIn(text) || exclude.containsMatchIn(href)) continue
                val key = text.lowercase()
                if (!seen.containsKey(key)) seen[key] = href
            }
        }
        return seen.values.toList().distinct().take(20)
    }

    fun exportJson(): String {
        val root = JSONObject()
        root.put("app", "NSIA Capital (Android)")
        root.put("exportedAt", System.currentTimeMillis())
        root.put("pages", JSONArray(pages.values.toList()))
        root.put("ajax", JSONArray(jsons.values.toList()))
        return root.toString(2)
    }

    fun save() {
        val root = JSONObject()
        root.put("lastSync", lastSync)
        val p = JSONObject()
        for ((k, v) in pages) p.put(k, v)
        root.put("pages", p)
        val j = JSONObject()
        for ((k, v) in jsons) j.put(k, v)
        root.put("jsons", j)
        prefs.edit().putString("data", root.toString()).apply()
    }

    private fun load() {
        val raw = prefs.getString("data", null) ?: return
        try {
            val root = JSONObject(raw)
            lastSync = root.optLong("lastSync")
            root.optJSONObject("pages")?.let { p ->
                for (k in p.keys()) p.optJSONObject(k)?.let { pages[k] = it }
            }
            root.optJSONObject("jsons")?.let { j ->
                for (k in j.keys()) j.optJSONObject(k)?.let { jsons[k] = it }
            }
        } catch (e: Exception) {
            pages.clear()
            jsons.clear()
        }
    }

    private fun pageKey(url: String, obj: JSONObject): String {
        val path = try { Uri.parse(url).path ?: url } catch (e: Exception) { url }
        return path + "|" + obj.optString("heading").ifBlank { obj.optString("title") }
    }

    companion object {
        fun isLoginUrl(url: String): Boolean = url.contains("/logon/", ignoreCase = true)
    }
}
