// The normalized tree both the parse5 checks and the browser checks compare (#322).
//
// A node is {t: 'el', name, ns, attrs: [[name, value]], kids}, {t: 'text', v} or {t: 'comment'}.
// No escaper writes a comment (#679), so a comment a hostile value adds is a structure change. A
// <template>'s content is a child named '#content' and an <iframe srcdoc>'s document a child named
// '#srcdoc'. normalizeDom is stringified into the browser page, so it must stay self-contained.

export const MARKER = /tqm\d{4}/i;
export const HTML_NS = 'http://www.w3.org/1999/xhtml';

/** The normalized tree of a parse5 document; parseSrcdoc(html) gives an <iframe srcdoc>'s tree. */
export function normalizeParse5(doc, parseSrcdoc) {
  const walk = (n) => {
    const kids = [];
    const push = (k) => kids.push(k);
    const children = n.childNodes || [];
    if (n.content) push({ t: 'el', name: '#content', ns: '', attrs: [], kids: walk(n.content) });
    for (const c of children) {
      if (c.nodeName === '#text') push({ t: 'text', v: c.value });
      else if (c.nodeName === '#comment') push({ t: 'comment' });
      else if (c.tagName) {
        const el = {
          t: 'el',
          name: c.tagName,
          ns: c.namespaceURI,
          attrs: (c.attrs || []).map((a) => [a.prefix ? a.prefix + ':' + a.name : a.name, a.value]),
          kids: walk(c),
        };
        const srcdoc = el.attrs.find((a) => a[0] === 'srcdoc');
        if (el.name === 'iframe' && el.ns === HTML_NS && srcdoc) {
          el.kids.unshift({ t: 'el', name: '#srcdoc', ns: '', attrs: [], kids: parseSrcdoc(srcdoc[1]) });
        }
        push(el);
      }
    }
    return kids;
  };
  return walk(doc);
}

/** The same normalization over a live DOM. Runs inside the browser. */
export function normalizeDom(doc) {
  const walk = (n) => {
    const kids = [];
    const push = (k) => kids.push(k);
    if (n.localName === 'template' && n.namespaceURI === 'http://www.w3.org/1999/xhtml' && n.content) {
      push({ t: 'el', name: '#content', ns: '', attrs: [], kids: walk(n.content) });
    }
    for (const c of n.childNodes) {
      if (c.nodeType === 3) push({ t: 'text', v: c.data });
      else if (c.nodeType === 8) push({ t: 'comment' });
      else if (c.nodeType === 1) {
        const el = {
          t: 'el',
          name: c.localName,
          ns: c.namespaceURI,
          attrs: Array.from(c.attributes, (a) => [a.name, a.value]),
          kids: walk(c),
        };
        if (c.localName === 'iframe' && c.namespaceURI === 'http://www.w3.org/1999/xhtml' && c.hasAttribute('srcdoc')) {
          // null if the frame is sandboxed without allow-same-origin
          el.kids.unshift({ t: 'el', name: '#srcdoc', ns: '', attrs: [], kids: c.contentDocument ? walk(c.contentDocument) : [] });
        }
        push(el);
      }
    }
    return kids;
  };
  return walk(doc);
}

/** One path step: the element name, then the namespace's last segment if it isn't HTML. */
export function step(el) {
  return el.name + (el.ns && el.ns !== HTML_NS ? '[' + el.ns.split('/').pop() + ']' : '');
}

/**
 * Where the hostile tree first differs from the benign one, or null. Text and attribute values
 * that contain a marker in the benign tree may differ; nothing else may.
 */
export function structureDiff(benign, hostile, path = '') {
  if (benign.length !== hostile.length) {
    return `${path || '/'}: ${benign.length} children, hostile has ${hostile.length}`;
  }
  for (let i = 0; i < benign.length; i++) {
    const b = benign[i];
    const h = hostile[i];
    if (b.t !== h.t) return `${path}/[${i}]: ${b.t} became ${h.t}`;
    if (b.t === 'comment') continue;
    if (b.t === 'text') {
      if (!MARKER.test(b.v) && b.v !== h.v) return `${path}/#text[${i}]: static text changed`;
      continue;
    }
    const p = path + '/' + step(b);
    if (b.name !== h.name || b.ns !== h.ns) return `${p}: element became ${path}/${step(h)}`;
    const bn = b.attrs.map((a) => a[0]).join(' ');
    const hn = h.attrs.map((a) => a[0]).join(' ');
    if (bn !== hn) return `${p}: attributes [${bn}] became [${hn}]`;
    for (let j = 0; j < b.attrs.length; j++) {
      if (!MARKER.test(b.attrs[j][1]) && b.attrs[j][1] !== h.attrs[j][1]) {
        return `${p}@${b.attrs[j][0]}: static value changed`;
      }
    }
    const d = structureDiff(b.kids, h.kids, p);
    if (d) return d;
  }
  return null;
}

/** Calls fn(el, path, parent) for every element, depth first. */
export function eachElement(kids, fn, path = '', parent = null) {
  for (const k of kids) {
    if (k.t !== 'el') continue;
    const p = path + '/' + step(k);
    fn(k, p, parent);
    eachElement(k.kids, fn, p, k);
  }
}

/** The concatenated text children of an element. */
export function textOf(el) {
  return el.kids.filter((k) => k.t === 'text').map((k) => k.v).join('');
}

const URL_ATTRS = new Set([
  'href', 'src', 'action', 'formaction', 'xlink:href', 'data', 'poster', 'cite', 'background',
]);
const ANIMATE_VALUE_ATTRS = new Set(['values', 'to', 'from', 'by']);
const SCRIPT_URL = /^(javascript|vbscript|data):/i;

/** A URL as a browser reads it: trim C0 and space, drop tab/LF/CR. */
export const preprocessUrl = (v) => v.replace(/^[\u0000- ]+|[\u0000- ]+$/g, '').replace(/[\t\n\r]/g, '');

/** The URL of a `<meta http-equiv=refresh>` content value, or null. */
export function refreshUrl(content) {
  const m = /^\s*\d*\.?\d*\s*[;,]?\s*(?:url\s*=\s*)?['"]?([^'"]*)/i.exec(content ?? '');
  return m && m[1] ? m[1] : null;
}

/**
 * #318 checks 2-4 on one element, for parse5 and browser trees alike: a marker in an element or
 * attribute name (MARKER_IN_NAME), in an on* attribute (LIVE_HANDLER), or in a javascript:,
 * vbscript: or data: URL, including a meta refresh URL (JS_URL). Returns [[kind, where]].
 */
export function attrFindings(el, p) {
  const f = [];
  const attr = (name) => (el.attrs.find((a) => a[0] === name) || [null, null])[1];
  if (MARKER.test(el.name)) f.push(['MARKER_IN_NAME', p]);
  for (const [name, value] of el.attrs) {
    if (MARKER.test(name)) f.push(['MARKER_IN_NAME', `${p}@${name}`]);
    if (!MARKER.test(value)) continue;
    if (/^on/i.test(name)) f.push(['LIVE_HANDLER', `${p}@${name}`]);
    let urls = [];
    if (URL_ATTRS.has(name)) urls = [value];
    else if ((el.name === 'animate' || el.name === 'set') && ANIMATE_VALUE_ATTRS.has(name) &&
        /^(xlink:)?href$/.test(attr('attributeName') ?? '')) {
      urls = name === 'values' ? value.split(';') : [value];
    } else if (el.name === 'meta' && name === 'content' && (attr('http-equiv') ?? '').toLowerCase() === 'refresh') {
      urls = [refreshUrl(value) ?? ''];
    }
    if (urls.some((v) => SCRIPT_URL.test(preprocessUrl(v)))) f.push(['JS_URL', `${p}@${name}`]);
  }
  return f;
}

// ---- the engine-drift normal form (#399) ----
//
// The form VnuTrees.java writes for the vnu engine, so a parse5 tree or a browser DOM can be
// compared with them: a text node is a string, an element [name, ns, attrs, kids] with ns 'html',
// 'svg', 'math' (or the URI) and attrs [[name, value]] sorted by name. Comments are dropped and the
// text around them merged; whitespace-only text is
// kept exactly. xmlns and xmlns:* attributes are dropped (vnu's DOM can't hold them). A template's
// contents are its '#content' child; an <iframe srcdoc>'s document is its '#srcdoc' child unless
// the frame has a sandbox attribute (not compared). Change VnuTrees.java with this.

const NS_SHORT = {
  'http://www.w3.org/1999/xhtml': 'html',
  'http://www.w3.org/2000/svg': 'svg',
  'http://www.w3.org/1998/Math/MathML': 'math',
};

/** A normalized tree (normalizeParse5, normalizeDom) in the engine-drift normal form. */
export function compact(kids) {
  const out = [];
  for (const k of kids) {
    if (k.t === 'comment') continue;
    if (k.t === 'text') {
      if (typeof out[out.length - 1] === 'string') out[out.length - 1] += k.v;
      else out.push(k.v);
      continue;
    }
    const attrs = k.attrs
      .filter(([n]) => n !== 'xmlns' && !n.startsWith('xmlns:'))
      .map(([n, v]) => [n, v])
      .sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
    const sandboxed = k.name === 'iframe' && k.ns === HTML_NS && k.attrs.some(([n]) => n === 'sandbox');
    const children = sandboxed ? k.kids.filter((c) => !(c.t === 'el' && c.name === '#srcdoc')) : k.kids;
    out.push([k.name, k.name.startsWith('#') ? '' : (NS_SHORT[k.ns] ?? String(k.ns)), attrs, compact(children)]);
  }
  return out;
}

const showText = (s) => JSON.stringify(s.length > 60 ? s.slice(0, 60) + '...' : s);
const elKey = (e) => (e[1] === 'html' || e[1] === '' ? e[0] : `${e[1]}:${e[0]}`);

/** Where two trees in the normal form first differ (want: a vnu tree; got: the engine's), or null. */
export function treeDiff(want, got, path = '') {
  const n = Math.max(want.length, got.length);
  for (let i = 0; i < n; i++) {
    const w = want[i];
    const g = got[i];
    const at = path || '/';
    const what = (x) => (x === undefined ? 'nothing' : typeof x === 'string' ? `text ${showText(x)}` : `<${elKey(x)}>`);
    if (w === undefined || g === undefined || typeof w !== typeof g) {
      return `${at} child ${i}: vnu has ${what(w)}, engine has ${what(g)}`;
    }
    if (typeof w === 'string') {
      if (w !== g) return `${at} child ${i}: vnu has ${what(w)}, engine has ${what(g)}`;
      continue;
    }
    const p = `${path}/${elKey(w)}`;
    if (w[0] !== g[0] || w[1] !== g[1]) return `${at} child ${i}: vnu has ${what(w)}, engine has ${what(g)}`;
    if (JSON.stringify(w[2]) !== JSON.stringify(g[2])) {
      return `${p}: vnu has attributes ${JSON.stringify(w[2]).slice(0, 200)}, engine has ${JSON.stringify(g[2]).slice(0, 200)}`;
    }
    const d = treeDiff(w[3], g[3], p);
    if (d) return d;
  }
  return null;
}

/**
 * A deliberately wrong normal form, for the self-check (run.sh, --mutate-normal-form): drops
 * whitespace-only text, the kind of policy slip that would make the comparison report drift that
 * isn't there or, worse, agree where it shouldn't. The job must report it as drift.
 */
export function mutateNormalForm(kids) {
  return kids.filter((k) => typeof k !== 'string' || k.trim() !== '').map((k) =>
    typeof k === 'string' ? k : [k[0], k[1], k[2], mutateNormalForm(k[3])]);
}

/**
 * The engine-drift check (#399) for one render: `tree` is the engine's normalized tree
 * (normalizeParse5/normalizeDom) with scripting on or off, `trees` the export's vnu trees for the
 * render (VnuTrees.java). Drift is a tree other than the one the vnu engine (the verifier's one
 * engine) builds with the same scripting flag: the verifier then reasoned about a document this
 * engine doesn't build. Returns null, or where the tree first differs from vnu's.
 */
export function engineDrift(tree, trees, scripting, mutate = false) {
  const mode = scripting ? 'scripting-on' : 'scripting-off';
  const vnu = trees[mode];
  if (!Array.isArray(vnu)) throw new Error('export trees without ' + mode);
  let got = compact(tree);
  if (mutate) got = mutateNormalForm(got);
  if (JSON.stringify(vnu) === JSON.stringify(got)) return null;
  return `${mode}: ${treeDiff(vnu, got)}`;
}
