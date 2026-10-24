// The normalized tree both the parse5 checks and the browser checks compare (#322).
//
// A node is {t: 'el', name, ns, attrs: [[name, value]], kids} or {t: 'text', v, runs}. Comments
// are dropped and the text around them merged into v: the TEXT escaper breaks `{{` with `<!---->`,
// so a hostile value may add comments the benign render doesn't have, and that is not a structure
// change. runs keeps the text between comments apart, which is what a client framework sees. A
// <template>'s content is a child named '#content' and an <iframe srcdoc>'s document a child named
// '#srcdoc'. normalizeDom is stringified into the browser page, so it must stay self-contained.

export const MARKER = /tqm\d{4}/i;
export const HTML_NS = 'http://www.w3.org/1999/xhtml';

/** The normalized tree of a parse5 document; parseSrcdoc(html) gives an <iframe srcdoc>'s tree. */
export function normalizeParse5(doc, parseSrcdoc) {
  const walk = (n) => {
    const kids = [];
    let comment = false;
    const push = (k) => {
      const last = kids[kids.length - 1];
      if (k.t === 'text' && last && last.t === 'text') {
        last.v += k.v;
        if (comment) last.runs.push(k.v);
        else last.runs[last.runs.length - 1] += k.v;
      } else kids.push(k.t === 'text' ? { ...k, runs: [k.v] } : k);
      comment = false;
    };
    const children = n.childNodes || [];
    if (n.content) push({ t: 'el', name: '#content', ns: '', attrs: [], kids: walk(n.content) });
    for (const c of children) {
      if (c.nodeName === '#text') push({ t: 'text', v: c.value });
      else if (c.nodeName === '#comment') comment = true;
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
    let comment = false;
    const push = (k) => {
      const last = kids[kids.length - 1];
      if (k.t === 'text' && last && last.t === 'text') {
        last.v += k.v;
        if (comment) last.runs.push(k.v);
        else last.runs[last.runs.length - 1] += k.v;
      } else kids.push(k.t === 'text' ? { ...k, runs: [k.v] } : k);
      comment = false;
    };
    if (n.localName === 'template' && n.namespaceURI === 'http://www.w3.org/1999/xhtml' && n.content) {
      push({ t: 'el', name: '#content', ns: '', attrs: [], kids: walk(n.content) });
    }
    for (const c of n.childNodes) {
      if (c.nodeType === 3) push({ t: 'text', v: c.data });
      else if (c.nodeType === 8) comment = true;
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
