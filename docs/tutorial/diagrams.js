/*
 * Tiny sequence-diagram renderer for the tutorial (no libraries, works from file://).
 *
 * Markup:
 *   <figure class="seq wide"><div class="canvas"><script type="text/x-seq">
 *     actors: App | APISIX | mw-app-gateway
 *     App -> APISIX : POST /app/v1/transactions     (HTTP call, solid blue)
 *     APISIX --> App : 200 COMPLETED                (reply, dotted grey)
 *     mw-app-gateway => mw-orchestrator : topic     (over Kafka, dashed purple)
 *     mw-app-gateway -> mw-app-gateway : note-like self step
 *     note mw-orchestrator, mw-core-mock : text across those lifelines
 *   </script></div><figcaption>...</figcaption></figure>
 *
 * Lines starting with # are comments. Steps are numbered so the prose can refer to them.
 */
(function () {
  "use strict";
  var NS = "http://www.w3.org/2000/svg";
  var COL = 158, PAD = 16, HEAD = 40, ROW = 34, FIRST = HEAD + 30;
  var STYLE = {
    "->": { cls: "a-http", marker: "h" },
    "-->": { cls: "a-reply", marker: "r" },
    "=>": { cls: "a-kafka", marker: "k" }
  };
  var COLORS = { h: "#2a5db0", r: "#4a5468", k: "#6b4bb8" };

  function el(name, attrs, text) {
    var e = document.createElementNS(NS, name);
    for (var k in attrs) if (Object.prototype.hasOwnProperty.call(attrs, k)) e.setAttribute(k, attrs[k]);
    if (text != null) e.textContent = text;
    return e;
  }

  function parse(src) {
    var actors = [], steps = [];
    function actor(name) { if (actors.indexOf(name) < 0) actors.push(name); return name; }
    src.split("\n").forEach(function (raw) {
      var line = raw.trim(), m;
      if (!line || line.charAt(0) === "#") return;
      if ((m = line.match(/^actors\s*:\s*(.+)$/i))) { m[1].split("|").forEach(function (a) { actor(a.trim()); }); return; }
      if ((m = line.match(/^note\s+(.+?)\s*:\s*(.+)$/i))) {
        steps.push({ note: true, over: m[1].split(",").map(function (s) { return actor(s.trim()); }), text: m[2] });
        return;
      }
      if ((m = line.match(/^(.+?)\s*(-->|->|=>)\s*(.+?)\s*:\s*(.+)$/))) {
        steps.push({ from: actor(m[1]), to: actor(m[3]), kind: m[2], text: m[4] });
      }
    });
    return { actors: actors, steps: steps };
  }

  function render(host, idx) {
    var d = parse(host.textContent);
    var n = d.actors.length;
    var width = PAD * 2 + COL * n;
    var height = FIRST + d.steps.length * ROW + 12;
    var x = function (name) { return PAD + COL * d.actors.indexOf(name) + COL / 2; };
    var svg = el("svg", { viewBox: "0 0 " + width + " " + height, role: "img" });

    var defs = el("defs");
    Object.keys(COLORS).forEach(function (k) {
      var mk = el("marker", { id: "m" + idx + k, viewBox: "0 0 10 10", refX: "9", refY: "5", markerWidth: "7", markerHeight: "7", orient: "auto-start-reverse" });
      mk.appendChild(el("path", { d: "M0,0 L10,5 L0,10 z", fill: COLORS[k] }));
      defs.appendChild(mk);
    });
    svg.appendChild(defs);

    d.actors.forEach(function (a) {
      var cx = x(a);
      svg.appendChild(el("line", { x1: cx, y1: HEAD, x2: cx, y2: height - 6, stroke: "#cfd3dc", "stroke-width": 1.2, "stroke-dasharray": "4 4" }));
      svg.appendChild(el("rect", { x: cx - COL / 2 + 8, y: 4, width: COL - 16, height: HEAD - 8, rx: 7, "class": /^mw-/.test(a) ? "n-mw" : "n-ext" }));
      svg.appendChild(el("text", { x: cx, y: 4 + (HEAD - 8) / 2 + 4.5, "text-anchor": "middle", "class": "t-md", "font-weight": "600" }, a));
    });

    d.steps.forEach(function (s, i) {
      var y = FIRST + i * ROW;
      var num = String(i + 1);
      if (s.note) {
        var xs = s.over.map(x), lo = Math.min.apply(null, xs) - COL / 2 + 14, hi = Math.max.apply(null, xs) + COL / 2 - 14;
        svg.appendChild(el("rect", { x: lo, y: y - 13, width: hi - lo, height: 24, rx: 5, fill: "#fbf1dc", stroke: "#a86a00", "stroke-width": 1 }));
        var tn = el("text", { x: (lo + hi) / 2, y: y + 3.5, "text-anchor": "middle", "class": "t-sm", fill: "#6b4500" });
        tn.appendChild(el("tspan", { "font-weight": "700" }, num + "  "));
        tn.appendChild(document.createTextNode(s.text));
        svg.appendChild(tn);
        return;
      }
      var st = STYLE[s.kind], x1 = x(s.from), x2 = x(s.to);
      var stroke = { h: "#2a5db0", r: "#4a5468", k: "#6b4bb8" }[st.marker];
      var dash = st.marker === "k" ? "6 4" : st.marker === "r" ? "2 3" : null;
      var attrs = { fill: "none", stroke: stroke, "stroke-width": 1.6, "marker-end": "url(#m" + idx + st.marker + ")" };
      if (dash) attrs["stroke-dasharray"] = dash;
      var lx;
      if (x1 === x2) {
        attrs.d = "M" + x1 + "," + (y - 6) + " h28 v14 h-24";
        svg.appendChild(el("path", attrs));
        lx = x1 + 34;
      } else {
        var dir = x2 > x1 ? 1 : -1;
        attrs.d = "M" + x1 + "," + (y + 4) + " L" + (x2 - dir * 2) + "," + (y + 4);
        svg.appendChild(el("path", attrs));
        lx = Math.min(x1, x2) + 8;
      }
      var t = el("text", { x: lx, y: x1 === x2 ? y + 4 : y - 2, "class": "t-sm halo" });
      var b = el("tspan", { "font-weight": "700", fill: stroke }, num + "  ");
      t.appendChild(b);
      t.appendChild(document.createTextNode(s.text));
      svg.appendChild(t);
    });

    host.parentNode.replaceChild(svg, host);
    // grow the canvas to the drawn content (long labels may run past the last lifeline) and
    // show it at natural size: wide diagrams scroll inside their frame instead of shrinking
    var box = svg.getBBox();
    var w = Math.ceil(Math.max(width, box.x + box.width + PAD));
    svg.setAttribute("viewBox", "0 0 " + w + " " + height);
    svg.setAttribute("style", "width:" + w + "px;max-width:none;min-width:0");
  }

  function run() {
    var hosts = document.querySelectorAll('script[type="text/x-seq"]');
    for (var i = 0; i < hosts.length; i++) render(hosts[i], i);
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", run);
  else run();
})();
