package com.example.data.web

object WebScripts {
    val CAPTURE: String = """
        (function() {
          try {
            function meta(names) {
              for (var i = 0; i < names.length; i++) {
                var el = document.querySelector('meta[property="' + names[i] + '"]') || document.querySelector('meta[name="' + names[i] + '"]');
                if (el && el.content) return el.content;
              }
              return '';
            }
            var title = meta(['og:title', 'twitter:title']) || document.title || '';
            var cover = meta(['og:image', 'twitter:image']) || '';
            var desc = meta(['og:description', 'description']) || '';
            if (cover && cover.indexOf('//') === 0) cover = location.protocol + cover;
            var origin = location.origin;
            var norm = location.pathname.replace(/\/+$/, '');
            function isOwn(u) {
              try {
                var p = new URL(u);
                if (p.origin !== origin) return false;
                if (!norm) return false;
                var pp = p.pathname.replace(/\/+$/, '');
                return pp === norm || pp.indexOf(norm + '/') === 0;
              } catch (e) { return false; }
            }
            function parentOf(u) {
              try {
                var p = new URL(u).pathname.replace(/\/+$/, '');
                var i = p.lastIndexOf('/');
                return i > 0 ? p.substring(0, i) : '';
              } catch (e) { return ''; }
            }
            var noise = /^\/(login|signin|sign-in|signup|sign-up|register|giris|kayit|privacy|terms|contact|dmca|about|iletisim|hakkimizda|gizlilik|sss|faq|rss|feed|donate|discord)/i;
            var re = /(chapter|bölüm|bolum|episode|\bep[.\-_ ]?\d|chap[.\-_ ]?\d|read|oku|capitulo|ch[.\-_ ]?\d)/i;
            function parseNum(raw) {
              var f = parseFloat(String(raw).replace(',', '.'));
              return isNaN(f) ? 0 : f;
            }
            // Bölüm numarasını sırayla dene: etikette anahtar kelime yanındaki sayı,
            // "5. Bölüm" kalıbı, sonra yol içindeki anahtar kelime sayısı, sonra
            // yolun son sayısal parçası. Böylece başlıktaki yıl gibi sayılar
            // bölüm numarası sanılmaz.
            function chapterNumber(label, path) {
              var labelPats = [
                /(?:chapter|bölüm|bolum|episode|ep|chap|ch|capitulo|capítulo|oku)[^\d]{0,8}(\d+(?:[.,]\d+)?)/i,
                /(\d+(?:[.,]\d+)?)\s*[.)\-–]?\s*(?:bölüm|bolum|chapter|episode|chap|capitulo|capítulo)/i
              ];
              for (var i = 0; i < labelPats.length; i++) {
                var m = label.match(labelPats[i]);
                if (m) return parseNum(m[1]);
              }
              var pm = path.match(/(?:chapter|bölüm|bolum|read|oku|ep|chap)[\/\-_]*(\d+(?:[.,]\d+)?)/i);
              if (pm) return parseNum(pm[1]);
              pm = path.match(/(?:chapter|bölüm|bolum|read|oku|ep|chap)[^\d\/]{0,10}(\d+(?:[.,]\d+)?)/i);
              if (pm) return parseNum(pm[1]);
              var segs = path.split('/').filter(function(s) { return s; });
              for (var j = segs.length - 1; j >= 0; j--) {
                var sm = segs[j].match(/\d+(?:[.,]\d+)?/g);
                if (sm && sm.length) return parseNum(sm[sm.length - 1]);
              }
              var lm = label.match(/(\d+(?:[.,]\d+)?)/);
              if (lm) return parseNum(lm[1]);
              return 0;
            }
            var chapters = [];
            var series = [];
            var navLinks = [];
            var seenC = {};
            var seenS = {};
            var seenN = {};
            var links = document.querySelectorAll('a[href]');
            for (var i = 0; i < links.length; i++) {
              var a = links[i];
              var text = (a.textContent || '').replace(/\s+/g, ' ').trim();
              var href = a.href || '';
              if (!href || href.indexOf('http') !== 0) continue;
              var u;
              try { u = new URL(href); } catch (e) { continue; }
              if (u.origin !== origin) continue;
              if (u.pathname === norm || u.pathname === norm + '/') continue;
              if (noise.test(u.pathname)) continue;
              if (a.closest('nav,header,footer,[role="navigation"]')) continue;
              if (text.length >= 1 && text.length <= 40 && !seenN[u.pathname]) {
                seenN[u.pathname] = 1;
                navLinks.push({ t: text, u: href });
              }
              var label = (a.getAttribute('title') || '') + ' ' + (a.getAttribute('aria-label') || '') + ' ' + text;
              var hay = label + ' ' + u.pathname;
              var num = chapterNumber(label, u.pathname);
              if (re.test(hay)) {
                if (!text || text.length > 90) continue;
                if (num <= 0 && !/\d/.test(label) && !/\d/.test(u.pathname)) continue;
                if (seenC[u.pathname]) continue;
                seenC[u.pathname] = 1;
                chapters.push({ t: text, u: href, n: num, o: isOwn(href) ? 1 : 0, p: parentOf(href) });
              } else {
                var img = a.querySelector('img');
                var altTitle = img ? (img.getAttribute('alt') || '').replace(/\s+/g, ' ').trim() : '';
                var seriesTitle = (altTitle.length >= 2 && altTitle.length <= 90) ? altTitle : text;
                if (seriesTitle.length >= 2 && seriesTitle.length <= 80) {
                  if (seenS[u.pathname]) continue;
                  seenS[u.pathname] = 1;
                  var icon = img ? (img.currentSrc || img.src || img.getAttribute('data-src') || img.getAttribute('data-lazy-src') || '') : '';
                  if (icon && icon.indexOf('//') === 0) icon = location.protocol + icon;
                  if (icon && icon.indexOf('http') !== 0) icon = '';
                  series.push({ t: seriesTitle, u: href, c: icon });
                }
              }
            }
            chapters.sort(function(x, y) { return (y.n || 0) - (x.n || 0); });
            if (chapters.length > 1500) chapters = chapters.slice(0, 1500);
            if (series.length > 600) series = series.slice(0, 600);
            if (navLinks.length > 400) navLinks = navLinks.slice(0, 400);
            return { title: title, cover: cover, desc: desc, pageUrl: location.href, chapters: chapters, series: series, links: navLinks };
          } catch (e) {
            return { title: document.title || '', cover: '', desc: '', pageUrl: location.href, chapters: [], series: [], links: [] };
          }
        })()
    """.trimIndent()

    val SCROLL_STEP: String = """
        (function() {
          try {
            var d = document.documentElement || document.body;
            var step = Math.max(320, Math.floor(window.innerHeight * 0.85));
            window.scrollBy(0, step);
            var h = Math.max(
              d ? d.scrollHeight : 0,
              document.body ? document.body.scrollHeight : 0
            );
            return { c: document.images.length, y: window.scrollY, h: h, vh: window.innerHeight };
          } catch (e) {
            return { c: 0, y: 0, h: 400, vh: 400 };
          }
        })()
    """.trimIndent()

    val COLLECT_IMAGES: String = """
        (function() {
          try {
            var out = [];
            var seen = {};
            var tiny = /(favicon|sprite|logo|avatar|icon|emoji|badge|spacer|pixel|blank|placeholder|loading|transparent|banner|thumbnail|thumb|ads?[_.-]|comment|yorum|reply|forum|smile|sticker|profile|userpic|gravatar)/i;
            var coverDir = /(^|\/)(covers?|kapak|kapaklar)(\/|$)/i;
            var imglike = /\.(jpe?g|png|webp|gif|avif|bmp)(\?|#|$)/i;
            function add(u, w, h, k) {
              if (!u) return;
              if (u.indexOf('data:') === 0) return;
              if (u.indexOf('//') === 0) u = location.protocol + u;
              try { u = new URL(u, location.href).href; } catch (e) { return; }
              if (u.indexOf('http') !== 0) return;
              if (coverDir.test(u)) return;
              if (seen[u]) return;
              seen[u] = 1;
              out.push({ u: u, w: w || 0, h: h || 0, k: k || 0 });
            }
            function bestSrcset(attr) {
              if (!attr) return '';
              var best = '';
              var bestW = -1;
              var parts = attr.split(',');
              for (var i = 0; i < parts.length; i++) {
                var seg = parts[i].replace(/^\s+|\s+$/g, '');
                if (!seg) continue;
                var sp = seg.split(/\s+/);
                var u = sp[0];
                var w = 0;
                if (sp.length > 1) {
                  var mw = sp[1].match(/^(\d+)w$/);
                  if (mw) w = parseInt(mw[1], 10);
                  else {
                    var mx = sp[1].match(/^([\d.]+)x$/);
                    if (mx) w = Math.round(parseFloat(mx[1]) * 1000);
                  }
                }
                if (w > bestW) { bestW = w; best = u; }
              }
              return best;
            }
            var imgs = document.querySelectorAll('img');
            for (var i = 0; i < imgs.length; i++) {
              var im = imgs[i];
              var alt = im.getAttribute('alt') || '';
              if (alt && /(logo|avatar|profile|badge|banner|sprite|icon|emoji|yorum|comment)/i.test(alt)) continue;
              var w = im.naturalWidth || 0;
              var h = im.naturalHeight || 0;
              var src = im.currentSrc || im.src || '';
              if (w >= 300 && h >= 300) {
                add(src, w, h, 0);
                continue;
              }
              var known = 0;
              var cand = im.getAttribute('data-src') || im.getAttribute('data-lazy-src') || im.getAttribute('data-original') || im.getAttribute('data-image') || im.getAttribute('data-full') || im.getAttribute('data-url') || '';
              if (cand) known = 1;
              if (!cand) {
                cand = bestSrcset(im.getAttribute('srcset') || im.getAttribute('data-srcset') || '');
                if (cand) known = 1;
              }
              if (!cand && src && imglike.test(src) && !tiny.test(src)) cand = src;
              if (cand && !tiny.test(cand)) {
                add(cand, 0, 0, known);
              }
            }
            return out;
          } catch (e) {
            return [];
          }
        })()
    """.trimIndent()

    val PAGE_INFO: String = """
        (function() {
          try {
            function meta(n) {
              var el = document.querySelector('meta[property="' + n + '"]') || document.querySelector('meta[name="' + n + '"]');
              return el && el.content ? el.content : '';
            }
            var c = meta('og:image') || meta('twitter:image') || '';
            if (c && c.indexOf('//') === 0) c = location.protocol + c;
            return { t: document.title || '', c: c, u: location.href };
          } catch (e) {
            return { t: '', c: '', u: '' };
          }
        })()
    """.trimIndent()
}
