(function () {
  if (window.__nsiaHooked) { return; }
  window.__nsiaHooked = true;

  function send(type, payload) {
    try { NsiaBridge.post(type, String(location.href), JSON.stringify(payload)); } catch (e) {}
  }

  function txt(el) {
    if (!el) { return ''; }
    return String(el.innerText || el.textContent || '').replace(/[\s  ]+/g, ' ').trim();
  }

  // Intercepte les réponses AJAX (JSON) du site
  try {
    var oOpen = XMLHttpRequest.prototype.open;
    var oSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (m, u) { this.__nsiaUrl = u; return oOpen.apply(this, arguments); };
    XMLHttpRequest.prototype.send = function () {
      var x = this;
      x.addEventListener('load', function () {
        try {
          var ct = x.getResponseHeader('content-type') || '';
          var t = (x.responseType === '' || x.responseType === 'text') ? x.responseText : null;
          if (t && t.length < 500000 && (ct.indexOf('json') >= 0 || /^\s*[\[{]/.test(t))) {
            send('json', { url: String(x.__nsiaUrl), body: t });
          }
        } catch (e) {}
        schedule(400);
      });
      return oSend.apply(this, arguments);
    };
  } catch (e) {}

  try {
    if (window.fetch) {
      var oFetch = window.fetch;
      window.fetch = function () {
        var a = arguments;
        return oFetch.apply(this, a).then(function (r) {
          try {
            var c = r.clone();
            var ct = c.headers.get('content-type') || '';
            if (ct.indexOf('json') >= 0) {
              c.text().then(function (t) {
                if (t.length < 500000) { send('json', { url: String((a[0] && a[0].url) || a[0]), body: t }); }
              });
            }
          } catch (e) {}
          schedule(400);
          return r;
        });
      };
    }
  } catch (e) {}

  function nearestTitle(t) {
    if (t.caption && txt(t.caption)) { return txt(t.caption); }
    var el = t;
    for (var depth = 0; depth < 6 && el; depth++) {
      var p = el.previousElementSibling;
      var hops = 0;
      while (p && hops < 4) {
        if (/^(H1|H2|H3|H4|H5|LEGEND)$/.test(p.tagName) || /title|titre|header|entete/i.test(p.className || '')) {
          var s = txt(p);
          if (s && s.length < 120) { return s; }
        }
        p = p.previousElementSibling;
        hops++;
      }
      el = el.parentElement;
    }
    return '';
  }

  function scan() {
    try {
      if (!document.body) { return; }
      var tables = [];
      var all = document.querySelectorAll('table');
      for (var i = 0; i < all.length; i++) {
        var t = all[i];
        if (t.querySelector('table')) { continue; }
        var rows = [];
        var trs = t.querySelectorAll('tr');
        for (var j = 0; j < trs.length && j < 400; j++) {
          var tr = trs[j];
          var cells = [];
          var tds = tr.querySelectorAll('th,td');
          var hasText = false;
          for (var k = 0; k < tds.length; k++) {
            var v = txt(tds[k]);
            if (v.length > 300) { v = v.slice(0, 300); }
            if (v) { hasText = true; }
            cells.push(v);
          }
          if (!hasText) { continue; }
          var isHeader = tr.querySelector('td') === null || (tr.parentElement && tr.parentElement.tagName === 'THEAD');
          rows.push({ h: isHeader, c: cells });
        }
        if (rows.length === 0) { continue; }
        if (rows.length === 1 && rows[0].c.length === 1) { continue; }
        tables.push({ title: nearestTitle(t), rows: rows });
      }

      var headings = [];
      var hs = document.querySelectorAll('h1,h2,h3,legend,.page-title,.panel-title,.title');
      for (var h = 0; h < hs.length && headings.length < 10; h++) {
        var hv = txt(hs[h]);
        if (hv && hv.length < 120) { headings.push(hv); }
      }

      var links = [];
      var as = document.querySelectorAll('a');
      for (var l = 0; l < as.length && links.length < 250; l++) {
        var lt = txt(as[l]);
        if (lt && lt.length < 70) {
          links.push({ t: lt, h: as[l].href || '' });
        }
      }

      send('page', {
        title: document.title || '',
        heading: headings[0] || '',
        headings: headings,
        tables: tables,
        links: links,
        text: txt(document.body).slice(0, 15000)
      });
    } catch (e) {}
  }

  var timer = null;
  function schedule(ms) {
    clearTimeout(timer);
    timer = setTimeout(scan, ms);
  }
  window.__nsiaScan = function () { schedule(300); };

  function start() {
    schedule(600);
    try {
      new MutationObserver(function () { schedule(1500); })
        .observe(document.body, { childList: true, subtree: true, characterData: true });
    } catch (e) {}
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
  window.addEventListener('load', function () { schedule(1200); });
})();
