package com.example.data.web

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

data class CaptureChapter(
    val title: String,
    val url: String,
    val number: Double,
    val own: Boolean,
    val parent: String
)

data class CaptureSeries(
    val title: String,
    val url: String,
    val cover: String
)

data class CaptureLink(
    val title: String,
    val url: String
)

data class CaptureResult(
    val title: String,
    val cover: String,
    val description: String,
    val pageUrl: String,
    val chapters: List<CaptureChapter>,
    val series: List<CaptureSeries>,
    val links: List<CaptureLink> = emptyList(),
    val searchAction: String? = null,
    val searchParam: String? = null
)

sealed class WebCaptureMode {
    data class Series(val chapters: List<CaptureChapter>) : WebCaptureMode()
    data class Listing(val series: List<CaptureSeries>) : WebCaptureMode()
}

data class WebImageEntry(
    val url: String,
    val width: Int,
    val height: Int,
    val known: Boolean
)

object WebCaptureParser {

    fun parseImages(raw: String?): List<WebImageEntry> {
        if (raw.isNullOrBlank() || raw == "null") return emptyList()
        val array = runCatching { JSONArray(raw.trim()) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<WebImageEntry>()
        for (i in 0 until array.length()) {
            when (val entry = array.opt(i)) {
                is JSONObject -> {
                    val url = entry.optString("u", "")
                    if (url.startsWith("http")) {
                        out.add(
                            WebImageEntry(
                                url = url,
                                width = entry.optInt("w", 0),
                                height = entry.optInt("h", 0),
                                known = entry.optInt("k", 0) == 1
                            )
                        )
                    }
                }
                is String -> {
                    if (entry.startsWith("http")) out.add(WebImageEntry(entry, 0, 0, true))
                }
            }
        }
        return out
    }

    fun pickReadablePages(entries: List<WebImageEntry>): List<String> {
        if (entries.isEmpty()) return emptyList()
        val big = entries.filter { it.height >= 600 || it.width >= 800 }
        if (big.size < 2) return entries.map { it.url }
        return entries
            .filter { it.height >= 600 || it.width >= 800 || (it.width == 0 && it.height == 0 && it.known) }
            .map { it.url }
    }

    fun parse(raw: String?): CaptureResult? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val json = runCatching { JSONObject(raw.trim()) }.getOrNull() ?: return null

        val chapters = mutableListOf<CaptureChapter>()
        val chaptersArray = json.optJSONArray("chapters") ?: JSONArray()
        for (i in 0 until chaptersArray.length()) {
            val obj = chaptersArray.optJSONObject(i) ?: continue
            val url = obj.optString("u", "")
            if (url.isBlank()) continue
            chapters.add(
                CaptureChapter(
                    title = obj.optString("t", "").ifBlank { "Bölüm ${i + 1}" },
                    url = url,
                    number = obj.optDouble("n", 0.0),
                    own = obj.optInt("o", 0) == 1,
                    parent = obj.optString("p", "")
                )
            )
        }

        val series = mutableListOf<CaptureSeries>()
        val seriesArray = json.optJSONArray("series") ?: JSONArray()
        for (i in 0 until seriesArray.length()) {
            val obj = seriesArray.optJSONObject(i) ?: continue
            val url = obj.optString("u", "")
            val title = obj.optString("t", "")
            if (url.isBlank() || title.isBlank()) continue
            series.add(CaptureSeries(title = title, url = url, cover = obj.optString("c", "")))
        }
        val filteredSeries = filterSeriesByShape(series)

        val links = mutableListOf<CaptureLink>()
        val linksArray = json.optJSONArray("links") ?: JSONArray()
        for (i in 0 until linksArray.length()) {
            val obj = linksArray.optJSONObject(i) ?: continue
            val url = obj.optString("u", "")
            val title = obj.optString("t", "")
            if (url.isBlank() || title.isBlank()) continue
            links.add(CaptureLink(title = title, url = url))
        }

        val searchObj = json.optJSONObject("search")

        return CaptureResult(
            title = json.optString("title", ""),
            cover = json.optString("cover", ""),
            description = json.optString("desc", ""),
            pageUrl = json.optString("pageUrl", ""),
            chapters = chapters,
            series = filteredSeries,
            links = links,
            searchAction = searchObj?.optString("a")?.takeIf { it.isNotBlank() },
            searchParam = searchObj?.optString("p")?.takeIf { it.isNotBlank() }
        )
    }

    fun decide(result: CaptureResult): WebCaptureMode? {
        val chapters = result.chapters
        val own = chapters.filter { it.own }
        if (own.size >= 3) return WebCaptureMode.Series(own)
        if (own.isNotEmpty() && own.size == chapters.size) return WebCaptureMode.Series(own)

        val biggestCluster = chapters
            .filter { it.parent.isNotBlank() }
            .groupBy { it.parent }
            .values
            .maxByOrNull { it.size }
        if (biggestCluster != null && biggestCluster.size >= 6 && biggestCluster.size * 2 >= chapters.size) {
            return WebCaptureMode.Series(biggestCluster)
        }

        if (result.series.size >= 2) return WebCaptureMode.Listing(result.series)
        if (chapters.size >= 2) return WebCaptureMode.Series(chapters)
        return null
    }

    fun seriesChaptersOf(result: CaptureResult): List<CaptureChapter> =
        when (val mode = decide(result)) {
            is WebCaptureMode.Series -> mode.chapters
            else -> result.chapters
        }

    // ---- HTML (HTTP) tabanlı ayrıştırma: WebView açmadan hızlı yol ----

    private val tinyRegex = Regex(
        "(favicon|sprite|logo|avatar|icon|emoji|badge|spacer|pixel|blank|placeholder|loading|transparent|banner|thumbnail|thumb|ads?[_.-]|comment|yorum|reply|forum|smile|sticker|profile|userpic|gravatar)",
        RegexOption.IGNORE_CASE
    )
    private val altJunkRegex = Regex(
        "(logo|avatar|profil|profile|badge|banner|sprite|icon|emoji|yorum|comment)",
        RegexOption.IGNORE_CASE
    )
    private val coverDirRegex = Regex(
        "(^|/)(covers?|kapak|kapaklar)(/|$)",
        RegexOption.IGNORE_CASE
    )
    private val imgLikeRegex = Regex("\\.(jpe?g|png|webp|gif|avif|bmp)(\\?|#|$)", RegexOption.IGNORE_CASE)
    private val noiseRegex = Regex(
        "^/(login|signin|sign-in|signup|sign-up|register|giris|kayit|privacy|terms|contact|dmca|about|iletisim|hakkimizda|gizlilik|sss|faq|rss|feed|donate|discord)",
        RegexOption.IGNORE_CASE
    )
    private val navTags = setOf("nav", "header", "footer")
    private val chapterLinkRegex = Regex(
        "(chapter|bölüm|bolum|episode|\\bep[.\\-_ ]?\\d|chap[.\\-_ ]?\\d|read|oku|capitulo|ch[.\\-_ ]?\\d)",
        RegexOption.IGNORE_CASE
    )
    private val labelNumberPatterns = listOf(
        Regex(
            "(?:chapter|bölüm|bolum|episode|ep|chap|ch|capitulo|capítulo|oku)[^\\d]{0,8}(\\d+(?:[.,]\\d+)?)",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "(\\d+(?:[.,]\\d+)?)\\s*[.)\\-–]?\\s*(?:bölüm|bolum|chapter|episode|chap|capitulo|capítulo)",
            RegexOption.IGNORE_CASE
        )
    )
    private val pathNumberPatterns = listOf(
        Regex(
            "(?:chapter|bölüm|bolum|read|oku|ep|chap)[\\/\\-_]*(\\d+(?:[.,]\\d+)?)",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "(?:chapter|bölüm|bolum|read|oku|ep|chap)[^\\d/]{0,10}(\\d+(?:[.,]\\d+)?)",
            RegexOption.IGNORE_CASE
        )
    )
    private val anyNumberRegex = Regex("(\\d+(?:[.,]\\d+)?)")
    private val numberTokenRegex = Regex("\\d+(?:[.,]\\d+)?")
    private val whitespaceRegex = Regex("\\s+")
    private val srcsetWeightRegex = Regex("^(\\d+)w$")
    private val srcsetScaleRegex = Regex("^([\\d.]+)x$")

    private fun parseChapterNumber(raw: String): Double =
        raw.replace(',', '.').toDoubleOrNull() ?: 0.0

    internal fun chapterNumberFrom(label: String, path: String): Double {
        for (pattern in labelNumberPatterns) {
            val match = pattern.find(label) ?: continue
            return parseChapterNumber(match.groupValues[1])
        }
        for (pattern in pathNumberPatterns) {
            val match = pattern.find(path) ?: continue
            return parseChapterNumber(match.groupValues[1])
        }
        val segments = path.split('/').filter { it.isNotEmpty() }
        for (segment in segments.asReversed()) {
            val tokens = numberTokenRegex.findAll(segment).map { it.value }.toList()
            if (tokens.isNotEmpty()) return parseChapterNumber(tokens.last())
        }
        val fallback = anyNumberRegex.find(label) ?: return 0.0
        return parseChapterNumber(fallback.groupValues[1])
    }

    fun captureJsonFromHtml(html: String, baseUrl: String): String? {
        if (html.isBlank()) return null
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return null
        val title = metaContent(doc, "og:title") ?: metaContent(doc, "twitter:title") ?: doc.title()
        var cover = metaContent(doc, "og:image") ?: metaContent(doc, "twitter:image") ?: ""
        if (cover.startsWith("//")) cover = "https:$cover"
        val description = metaContent(doc, "og:description") ?: metaContent(doc, "description") ?: ""

        val base = runCatching { URI(baseUrl) }.getOrNull()
        val origin = base?.let { "${it.scheme}://${it.authority}" }.orEmpty()
        val norm = base?.path?.trimEnd('/').orEmpty()

        val chapters = mutableListOf<CaptureChapter>()
        val series = mutableListOf<CaptureSeries>()
        val navLinks = mutableListOf<CaptureLink>()
        val seenChapters = HashSet<String>()
        val seenSeries = HashSet<String>()
        val seenNav = HashSet<String>()

        for (anchor in doc.select("a[href]")) {
            val href = anchor.absUrl("href")
            if (href.isEmpty() || !href.startsWith("http")) continue
            val uri = runCatching { URI(href) }.getOrNull() ?: continue
            if (origin.isNotEmpty() && "${uri.scheme}://${uri.authority}" != origin) continue
            val pathname = uri.path.orEmpty()
            if (pathname.isEmpty()) continue
            if (pathname == norm || pathname == "$norm/") continue
            if (noiseRegex.containsMatchIn(pathname)) continue
            if (anchor.parents().any { it.tagName().lowercase() in navTags }) continue
            val text = anchor.text().replace(whitespaceRegex, " ").trim()
            if (text.length in 1..40 && seenNav.add(pathname)) {
                navLinks.add(CaptureLink(title = text, url = href))
            }
            val label = listOf(anchor.attr("title"), anchor.attr("aria-label"), text)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .trim()
            val hay = "$label $pathname"
            if (chapterLinkRegex.containsMatchIn(hay)) {
                if (text.isEmpty() || text.length > 90) continue
                val number = chapterNumberFrom(label, pathname)
                if (number <= 0.0 && !label.any { it.isDigit() } && !pathname.any { it.isDigit() }) continue
                if (!seenChapters.add(pathname)) continue
                chapters.add(
                    CaptureChapter(
                        title = text,
                        url = href,
                        number = number,
                        own = isOwnPage(uri, origin, norm),
                        parent = parentPath(pathname)
                    )
                )
            } else {
                val image = anchor.selectFirst("img")
                val altTitle = image?.attr("alt")?.replace(whitespaceRegex, " ")?.trim().orEmpty()
                val seriesTitle = if (altTitle.length in 2..90) altTitle else text
                if (seriesTitle.length in 2..80 && seenSeries.add(pathname)) {
                    val icon = if (image == null) {
                        ""
                    } else {
                        listOf("src", "data-src", "data-lazy-src")
                            .asSequence()
                            .map { image.absUrl(it) }
                            .firstOrNull { it.startsWith("http") }
                            .orEmpty()
                    }
                    series.add(CaptureSeries(title = seriesTitle, url = href, cover = icon))
                }
            }
        }

        val root = JSONObject()
        root.put("title", title.orEmpty())
        root.put("cover", cover)
        root.put("desc", description)
        root.put("pageUrl", baseUrl)
        val chapterArray = JSONArray()
        for (chapter in chapters.sortedByDescending { it.number }.take(1500)) {
            chapterArray.put(
                JSONObject()
                    .put("t", chapter.title)
                    .put("u", chapter.url)
                    .put("n", chapter.number)
                    .put("o", if (chapter.own) 1 else 0)
                    .put("p", chapter.parent)
            )
        }
        root.put("chapters", chapterArray)
        val seriesArray = JSONArray()
        for (entry in series.take(600)) {
            seriesArray.put(JSONObject().put("t", entry.title).put("u", entry.url).put("c", entry.cover))
        }
        root.put("series", seriesArray)
        val linkArray = JSONArray()
        for (entry in navLinks.take(400)) {
            linkArray.put(JSONObject().put("t", entry.title).put("u", entry.url))
        }
        root.put("links", linkArray)
        detectSearchForm(doc, baseUrl)?.let { (action, param) ->
            root.put("search", JSONObject().put("a", action).put("p", param))
        }
        return root.toString()
    }

    private val searchInputNames = listOf("s", "q", "query", "ara", "search", "keyword", "arama")

    private fun detectSearchForm(doc: Document, baseUrl: String): Pair<String, String>? {
        for (form in doc.select("form")) {
            if (form.attr("method").equals("post", ignoreCase = true)) continue
            val input = form.selectFirst("input[type=text], input[type=search], input[name]") ?: continue
            val name = input.attr("name").trim()
            if (name.lowercase() !in searchInputNames) continue
            val action = form.absUrl("action").takeIf { it.startsWith("http") } ?: baseUrl
            return action to name
        }
        return null
    }

    private val lazyImageAttrs = listOf("data-src", "data-lazy-src", "data-original", "data-image", "data-full", "data-url")
    private val srcsetAttrs = listOf("srcset", "data-srcset")

    fun parseImagesFromHtml(html: String, baseUrl: String): List<String> {
        if (html.isBlank()) return emptyList()
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return emptyList()
        val entries = mutableListOf<WebImageEntry>()
        val seen = HashSet<String>()
        for (image in doc.select("img")) {
            val alt = image.attr("alt")
            if (alt.isNotBlank() && altJunkRegex.containsMatchIn(alt)) continue
            val widthAttr = image.attr("width").toIntOrNull() ?: 0
            val heightAttr = image.attr("height").toIntOrNull() ?: 0
            val src = image.absUrl("src")
            if (widthAttr >= 300 && heightAttr >= 300 && src.startsWith("http") &&
                !coverDirRegex.containsMatchIn(src) && !tinyRegex.containsMatchIn(src)
            ) {
                if (seen.add(src)) {
                    entries.add(WebImageEntry(url = src, width = widthAttr, height = heightAttr, known = false))
                }
                continue
            }
            var candidate = ""
            var known = false
            for (attr in lazyImageAttrs) {
                val value = image.absUrl(attr)
                if (value.startsWith("http")) {
                    candidate = value
                    known = true
                    break
                }
            }
            if (candidate.isEmpty()) {
                for (attr in srcsetAttrs) {
                    val best = bestFromSrcset(image.attr(attr), baseUrl)
                    if (best.isNotEmpty()) {
                        candidate = best
                        known = true
                        break
                    }
                }
            }
            if (candidate.isEmpty() && src.startsWith("http") && imgLikeRegex.containsMatchIn(src) && !tinyRegex.containsMatchIn(src)) {
                candidate = src
            }
            if (candidate.isNotEmpty() && !tinyRegex.containsMatchIn(candidate) &&
                !coverDirRegex.containsMatchIn(candidate) && seen.add(candidate)
            ) {
                entries.add(WebImageEntry(url = candidate, width = 0, height = 0, known = known))
            }
        }
        return pickReadablePages(entries)
    }

    private fun bestFromSrcset(attr: String, baseUrl: String): String {
        if (attr.isBlank()) return ""
        var best = ""
        var bestWeight = -1
        for (segment in attr.split(',')) {
            val trimmed = segment.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(whitespaceRegex)
            val rawUrl = parts.getOrNull(0).orEmpty()
            if (rawUrl.isEmpty()) continue
            val absolute = runCatching { URI(baseUrl).resolve(rawUrl).toString() }.getOrElse { rawUrl }
            if (!absolute.startsWith("http")) continue
            var weight = 0
            val descriptor = parts.getOrNull(1).orEmpty()
            val widthMatch = srcsetWeightRegex.find(descriptor)
            if (widthMatch != null) {
                weight = widthMatch.groupValues[1].toIntOrNull() ?: 0
            } else {
                val scaleMatch = srcsetScaleRegex.find(descriptor)
                if (scaleMatch != null) {
                    weight = ((scaleMatch.groupValues[1].toDoubleOrNull() ?: 0.0) * 1000).toInt()
                }
            }
            if (weight > bestWeight) {
                bestWeight = weight
                best = absolute
            }
        }
        return best
    }

    private fun isOwnPage(uri: URI, origin: String, norm: String): Boolean {
        if (norm.isEmpty()) return false
        if (origin.isEmpty() || "${uri.scheme}://${uri.authority}" != origin) return false
        val path = uri.path.orEmpty().trimEnd('/')
        return path == norm || path.startsWith("$norm/")
    }

    private fun parentPath(pathname: String): String {
        val path = pathname.trimEnd('/')
        val index = path.lastIndexOf('/')
        return if (index > 0) path.substring(0, index) else ""
    }

    // ---- Seri listesi temizliği: menü/gezinme linklerini ayıkla ----
    // Aynı URL şekline sahip (ör. /manga/<slug>) tekrarlanan link grubunu bulur;
    // "Anasayfa", "Market", "Trend" gibi tek kopya vitrin linklerini eler.

    private val navWordSet = setOf(
        "anasayfa", "seriler", "manga", "market", "mağaza", "çeviri", "ceviri",
        "sıralama", "siralama", "trend seriler", "trend", "rehber", "destek",
        "gizlilik", "şartlar", "sartlar", "kullanım şartları", "dmca", "blog",
        "öneriler", "oneriler", "şans çarkı", "sans carki", "kütüphane", "kutuphane",
        "profil", "giriş", "giris", "kayıt", "kayit", "iletişim", "iletisim",
        "hakkımızda", "hakkimizda", "sss", "yardım", "yardim", "tümü", "tumu",
        "tümünü gör", "tumunu gor", "devamı", "devami", "daha fazla"
    )

    private fun seriesShape(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val segments = uri.path.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        val first = segments[0].lowercase()
        return if (segments.size == 1) first else "$first/*"
    }

    fun filterSeriesByShape(series: List<CaptureSeries>): List<CaptureSeries> {
        if (series.size <= 2) return series
        val cleaned = series.filter { it.title.trim().lowercase() !in navWordSet }
        if (cleaned.size <= 2) return cleaned
        val groups = LinkedHashMap<String, MutableList<CaptureSeries>>()
        for (item in cleaned) {
            val shape = seriesShape(item.url) ?: continue
            groups.getOrPut(shape) { mutableListOf() }.add(item)
        }
        val best = groups.entries
            .filter { it.key.endsWith("/*") && it.value.size >= 3 }
            .maxByOrNull { it.value.size }
        if (best != null) return best.value
        val withCover = cleaned.filter { it.cover.isNotBlank() }
        return withCover.ifEmpty { cleaned }
    }

    private fun metaContent(doc: Document, name: String): String? {
        val element = doc.selectFirst("meta[property=$name]") ?: doc.selectFirst("meta[name=$name]")
        val content = element?.attr("content")?.trim().orEmpty()
        return content.ifBlank { null }
    }

    // ---- Bölüm sayfası görsel seçimi: kapak/yorum görsellerini ayıkla ----

    data class ChapterImageSelection(val urls: List<String>, val clustered: Boolean)

    private val numericStemRegex = Regex("(?:[^0-9]|^)([0-9]{1,4})$")

    private fun numericStem(url: String): Int? {
        val clean = url.substringBefore('?').substringBefore('#')
        val last = clean.substringAfterLast('/')
        if (!last.contains('.')) return null
        val stem = last.substringBeforeLast('.')
        val match = numericStemRegex.find(stem) ?: return null
        return match.groupValues[1].toIntOrNull()?.takeIf { it in 1..9999 }
    }

    fun selectChapterImages(urls: List<String>): ChapterImageSelection {
        if (urls.size <= 2) return ChapterImageSelection(urls, false)

        val numbered = urls.mapIndexedNotNull { index, url ->
            numericStem(url)?.let { number -> index to number }
        }
        if (numbered.size >= 3) {
            val sorted = numbered.sortedBy { it.second }
            var bestStart = 0
            var bestEnd = 0
            var start = 0
            for (i in 1..sorted.size) {
                val broken = i == sorted.size || sorted[i].second - sorted[i - 1].second > 2
                if (broken) {
                    if (i - 1 - start > bestEnd - bestStart) {
                        bestStart = start
                        bestEnd = i - 1
                    }
                    start = i
                }
            }
            val run = sorted.subList(bestStart, bestEnd + 1)
            var consecutive = 1
            var bestConsecutive = 1
            for (i in 1 until run.size) {
                if (run[i].second - run[i - 1].second == 1) {
                    consecutive++
                    if (consecutive > bestConsecutive) bestConsecutive = consecutive
                } else {
                    consecutive = 1
                }
            }
            if (run.size >= 3 && bestConsecutive >= 3) {
                val keep = run.map { it.first }.toHashSet()
                val runUrls = urls.filterIndexed { index, _ -> index in keep }
                val runDirs = LinkedHashMap<String, Int>()
                for (url in runUrls) {
                    val dir = directoryOf(url)
                    runDirs[dir] = (runDirs[dir] ?: 0) + 1
                }
                val dominant = runDirs.maxByOrNull { it.value }
                if (runDirs.size > 1 && dominant != null &&
                    dominant.value * 100 >= runUrls.size * 60
                ) {
                    return ChapterImageSelection(runUrls.filter { directoryOf(it) == dominant.key }, true)
                }
                return ChapterImageSelection(runUrls, true)
            }
        }

        val byDir = LinkedHashMap<String, MutableList<String>>()
        for (url in urls) {
            byDir.getOrPut(directoryOf(url)) { mutableListOf() }.add(url)
        }
        if (byDir.size == 1) return ChapterImageSelection(urls, true)
        val ranked = byDir.entries.sortedByDescending { it.value.size }
        val top = ranked[0]
        if (top.value.size >= 3 && top.value.size * 100 >= urls.size * 60) {
            return ChapterImageSelection(top.value, true)
        }
        if (ranked.size >= 2) {
            val second = ranked[1]
            if (top.value.size >= 3 && second.value.size >= 3 &&
                (top.value.size + second.value.size) * 100 >= urls.size * 80
            ) {
                val keep = (top.value + second.value).toHashSet()
                return ChapterImageSelection(urls.filter { it in keep }, true)
            }
        }
        return ChapterImageSelection(urls, false)
    }

    private fun directoryOf(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#')
        val index = clean.lastIndexOf('/')
        return if (index > 8) clean.substring(0, index) else clean
    }
}
