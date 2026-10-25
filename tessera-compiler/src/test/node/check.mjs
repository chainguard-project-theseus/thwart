// The Node oracle (#322): runs the cases ProfileOracleTest exports (#324) through parse5 and headless
// Chromium and Firefox, and ratchets the findings against known-holes.txt like the Java gate does.
// Not part of `mvn verify`; Chainguard Checks runs it through run.sh (.chainguard/ci/oracle-node.yaml).
//
//   node check.mjs [--engines parse5,chromium,firefox] [--known-holes <file>] <cases.jsonl>
//
// Input: ExportRecord JSONL, {"id","name","template","value","render","verdict"}, one line per
// (case, value). Per case, the benign render is the record whose value is a bare marker
// (`tqm0001`); fingerprint records (`tqf...`, #323) are skipped, every other record is hostile.
// Rejected cases (render null) are skipped. A case with hostile renders and no benign one fails.
//
// Engines: parse5 (scripting on) runs #318's checks 1-5 and 7 and an acorn version of #319's script
// check. Chromium (CHROMIUM_PATH) and Firefox (FIREFOX_PATH, WebDriver BiDi) load each render and
// report marker calls (LIVE_SCRIPT, LIVE_HANDLER) and benign/hostile DOM differences
// (STRUCTURE_CHANGED); see browsers.mjs.
//
// Ratchet: a finding with no known-holes line fails ("new hole"); a line for an engine this job ran
// (parse5, chromium, firefox) that matched nothing fails ("delete its line"). `*` lines cover
// findings here but only the Java gate reports them stale.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parse } from 'parse5';
import * as acorn from 'acorn';
import {
  MARKER, HTML_NS, normalizeParse5, structureDiff, eachElement, textOf, attrFindings, preprocessUrl, refreshUrl,
} from './tree.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const BENIGN = /^tqm\d{4}$/;
const FINGERPRINT = /^tqf\d{4}/;
const NODE_ENGINES = ['parse5', 'chromium', 'firefox'];
const KINDS = new Set([
  'STRUCTURE_CHANGED', 'MARKER_IN_NAME', 'LIVE_SCRIPT', 'LIVE_HANDLER', 'JS_URL',
  'CROSS_ORIGIN_RESOURCE', 'JS_NOT_LITERAL', 'JS_UNPARSABLE', 'CONTEXT_MISMATCH', 'CSTI',
]);

// ---- input ----

/** Groups ExportRecord lines by case: [{id, name, template, benign, hostile: [{value, render}]}]. */
export function readCases(text) {
  const byId = new Map();
  text.split('\n').forEach((line, i) => {
    if (!line.trim()) return;
    let r;
    try {
      r = JSON.parse(line);
    } catch (e) {
      throw new Error(`line ${i + 1}: not JSON: ${e.message}`);
    }
    for (const k of ['id', 'name', 'template', 'verdict']) {
      if (typeof r[k] !== 'string') throw new Error(`line ${i + 1}: missing ${k}`);
    }
    if (!byId.has(r.id)) {
      byId.set(r.id, { id: r.id, name: r.name, template: r.template, benign: null, hostile: [] });
    }
    const c = byId.get(r.id);
    if (r.render == null || r.value == null) return; // REJECTED
    if (FINGERPRINT.test(r.value)) return; // the context check (#323) is Java-only
    if (BENIGN.test(r.value)) {
      if (c.benign != null) throw new Error(`line ${i + 1}: case ${r.id} has two benign renders`);
      c.benign = r.render;
    } else {
      c.hostile.push({ value: r.value, render: r.render });
    }
  });
  const cases = [...byId.values()];
  for (const c of cases) {
    if (c.hostile.length && c.benign == null) {
      throw new Error(`case ${c.id} (${c.name}) has hostile renders but no benign (bare marker) render`);
    }
  }
  return cases.filter((c) => c.benign != null);
}

// ---- parse5 checks ----

const JS_TYPES = new Set([
  '', 'text/javascript', 'application/javascript', 'module', 'text/ecmascript',
  'application/ecmascript', 'text/jscript', 'text/livescript', 'application/x-javascript',
  'application/x-ecmascript', 'text/x-javascript', 'text/x-ecmascript', 'text/javascript1.0',
  'text/javascript1.1', 'text/javascript1.2', 'text/javascript1.3', 'text/javascript1.4',
  'text/javascript1.5',
]);
// Elements whose text Tessera doesn't break `{{` in (raw text and RCDATA): a documented limitation.
const NOT_FLOW = new Set([
  'script', 'style', 'textarea', 'title', 'xmp', 'iframe', 'noembed', 'noframes', 'noscript', 'plaintext',
]);
const RESOURCE_LINK_RELS = new Set(['stylesheet', 'preload', 'modulepreload', 'import']);
const HTMX = /^(data-)?hx-(get|post|put|patch|delete)$/;

const attr = (el, name) => (el.attrs.find((a) => a[0] === name) || [null, null])[1];

/** Whether a resource URL is protocol-relative or absolute by shape (`\` read as `/`). */
function crossOrigin(value, contextRelative) {
  const v = preprocessUrl(value);
  if (/^[\\/][\\/]/.test(v) && v.slice(0, 2) !== '//') return true; // /\x \\x \/x: never legitimate
  if (!contextRelative) return false;
  return v.replace(/\\/g, '/').startsWith('//') || /^[a-z][a-z0-9+.-]*:/i.test(v);
}

function isJsScript(el) {
  if (el.name !== 'script') return false;
  const type = attr(el, 'type');
  return JS_TYPES.has((type ?? '').trim().toLowerCase());
}

/** Findings for one parsed render that don't need the benign tree. */
function renderFindings(tree, contextRelative) {
  const f = [];
  eachElement(tree, (el, p) => {
    f.push(...attrFindings(el, p));
    const resource = [];
    if (el.ns === HTML_NS) {
      if (el.name === 'script' || ['iframe', 'frame', 'embed'].includes(el.name)) resource.push('src');
      if (el.name === 'base') resource.push('href');
      if (el.name === 'object') resource.push('data');
      if (el.name === 'link') {
        const rels = (attr(el, 'rel') ?? '').toLowerCase().split(/\s+/);
        if (rels.some((r) => RESOURCE_LINK_RELS.has(r))) resource.push('href');
      }
      if (el.name === 'meta' && (attr(el, 'http-equiv') ?? '').toLowerCase() === 'refresh') {
        const u = refreshUrl(attr(el, 'content'));
        if (u && crossOrigin(u, contextRelative)) f.push(['CROSS_ORIGIN_RESOURCE', `${p}@content`]);
      }
    }
    if (el.name === 'script' && el.ns !== HTML_NS) resource.push('href', 'xlink:href');
    for (const [name] of el.attrs) if (HTMX.test(name)) resource.push(name);
    for (const name of resource) {
      const v = attr(el, name);
      if (v != null && crossOrigin(v, contextRelative)) f.push(['CROSS_ORIGIN_RESOURCE', `${p}@${name}`]);
    }
    const flow = el.ns !== HTML_NS || !NOT_FLOW.has(el.name);
    if (flow) {
      for (const k of el.kids) {
        if (k.t === 'text' && k.runs.some((r) => /\{\{[\s\S]*?tqm\d{4}[\s\S]*?\}\}/i.test(r))) {
          f.push(['CSTI', `${p}/#text`]);
        }
      }
    }
  });
  return f;
}

function tokens(src, module) {
  const opts = { ecmaVersion: 'latest', sourceType: module ? 'module' : 'script', allowHashBang: true };
  acorn.parse(src, opts); // the tokenizer alone doesn't reject every syntax error
  return [...acorn.tokenizer(src, opts)].map((t) => ({ type: t.type.label, value: String(t.value ?? '') }));
}

const LITERAL_TOKENS = new Set(['string', 'num']);

/**
 * #319 with acorn: the hostile script must tokenize exactly like the benign one except at the slot,
 * where both tokens are literals. Returns a finding kind, 'UNPARSABLE', or null.
 */
export function scriptVerdict(benign, hostile, module) {
  let b;
  try {
    b = tokens(benign, module);
  } catch {
    return 'UNPARSABLE';
  }
  let h;
  try {
    h = tokens(hostile, module);
  } catch {
    return 'JS_NOT_LITERAL';
  }
  if (h.some((t) => t.type === 'name' && MARKER.test(t.value))) return 'LIVE_SCRIPT';
  const slots = b.filter((t) => MARKER.test(t.value));
  if ((benign.match(new RegExp(MARKER.source, 'gi')) || []).length !== slots.length) return 'JS_NOT_LITERAL';
  if (slots.some((t) => !LITERAL_TOKENS.has(t.type))) return 'JS_NOT_LITERAL';
  if (b.length !== h.length) return 'JS_NOT_LITERAL';
  for (let i = 0; i < b.length; i++) {
    if (b[i].type !== h[i].type) return 'JS_NOT_LITERAL';
    if (b[i].value !== h[i].value && !MARKER.test(b[i].value)) return 'JS_NOT_LITERAL';
  }
  return null;
}

function scripts(tree) {
  const m = new Map();
  eachElement(tree, (el, p) => {
    if (isJsScript(el)) m.set(p + '#' + m.size, { text: textOf(el), module: (attr(el, 'type') ?? '').trim().toLowerCase() === 'module' });
  });
  return m;
}

const parseTree = (html) => normalizeParse5(parse(html, { scriptingEnabled: true }), parseTree);

/** parse5 findings for one case: [{kind, engine, where, value}], plus the unparsable-script count. */
export function checkParse5(c) {
  const contextRelative = /@\{\s*\//.test(c.template);
  const benign = parseTree(c.benign);
  const benignScripts = scripts(benign);
  const out = [];
  let unparsable = 0;
  const add = (kind, where, value) => out.push({ kind, engine: 'parse5', where: 'scripting-on ' + where, value });
  for (const [kind, where] of renderFindings(benign, contextRelative)) add(kind, where, '');
  for (const { value, render } of c.hostile) {
    const tree = parseTree(render);
    const diff = structureDiff(benign, tree);
    if (diff) add('STRUCTURE_CHANGED', diff, value);
    for (const [kind, where] of renderFindings(tree, contextRelative)) add(kind, where, value);
    for (const [key, s] of scripts(tree)) {
      if (!MARKER.test(s.text)) continue;
      const b = benignScripts.get(key);
      if (!b) {
        add('LIVE_SCRIPT', key.replace(/#\d+$/, ''), value);
        continue;
      }
      const v = scriptVerdict(b.text, s.text, s.module);
      if (v === 'UNPARSABLE') unparsable++;
      else if (v) add(v, key.replace(/#\d+$/, ''), value);
    }
  }
  return { findings: out, unparsable };
}

// ---- ratchet ----

const HOLE_LINE = /^([0-9a-f]{12})\s+([A-Z_]+)\s+(\*|[a-z0-9][a-z0-9.-]*)\s+#([1-9][0-9]{0,8})(?:\s+(.*))?$/;

/** Parses known-holes.txt (KnownHoles' format). */
export function parseKnownHoles(file, text) {
  const entries = [];
  text.split(/\r?\n/).forEach((raw, i) => {
    const line = raw.trim();
    if (!line || line.startsWith('#')) return;
    const m = HOLE_LINE.exec(line);
    if (!m) throw new Error(`${file}:${i + 1}: expected '<case-id> <KIND> <engine or *> #<issue> <note>', got: ${line}`);
    if (!KINDS.has(m[2])) throw new Error(`${file}:${i + 1}: unknown finding kind ${m[2]}`);
    entries.push({ caseId: m[1], kind: m[2], engine: m[3], issue: Number(m[4]), line: i + 1, used: false });
  });
  return entries;
}

/** Ratchets findings ({caseId, name, template, kind, engine, where, value}); returns failure messages. */
export function ratchet(findings, entries, enginesRun, file) {
  const failures = [];
  const reported = new Set();
  for (const f of findings) {
    const e = entries.find(
      (e) => e.caseId === f.caseId && e.kind === f.kind && (e.engine === '*' || e.engine === f.engine));
    if (e) {
      e.used = true;
      continue;
    }
    const key = `${f.caseId} ${f.kind} ${f.engine}`;
    if (reported.has(key)) continue;
    reported.add(key);
    failures.push(
      `new hole: ${f.caseId} (${f.name}) ${f.kind} ${f.engine} at ${f.where}; value: ${JSON.stringify(f.value)}; ` +
        `template: ${JSON.stringify(f.template)}; fix it, or if it is an already-tracked open hole add ` +
        `\`${f.caseId} ${f.kind} ${f.engine} #<issue>\` to ${file}`);
  }
  for (const e of entries) {
    if (!e.used && enginesRun.includes(e.engine)) {
      failures.push(`known hole ${e.caseId} ${e.kind} ${e.engine} (${file}:${e.line}) is fixed (or now rejected): delete its line`);
    }
  }
  return failures;
}

// ---- main ----

async function main(argv) {
  let engines = NODE_ENGINES;
  let holesFile = path.relative(process.cwd(), path.join(HERE, '..', 'resources', 'oracle', 'known-holes.txt'));
  const files = [];
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--engines') engines = argv[++i].split(',');
    else if (argv[i] === '--known-holes') holesFile = argv[++i];
    else files.push(argv[i]);
  }
  if (files.length !== 1 || engines.some((e) => !NODE_ENGINES.includes(e))) {
    console.error('usage: node check.mjs [--engines parse5,chromium,firefox] [--known-holes <file>] <cases.jsonl>');
    return 2;
  }
  const cases = readCases(fs.readFileSync(files[0], 'utf8'));
  const entries = parseKnownHoles(holesFile, fs.readFileSync(holesFile, 'utf8'));
  const findings = [];
  const tag = (c, fs_) => fs_.map((f) => ({ caseId: c.id, name: c.name, template: c.template, ...f }));
  const started = Date.now();
  let unparsable = 0;
  if (engines.includes('parse5')) {
    for (const c of cases) {
      const r = checkParse5(c);
      unparsable += r.unparsable;
      findings.push(...tag(c, r.findings));
    }
  }
  for (const engine of engines.filter((e) => e !== 'parse5')) {
    const { runBrowser } = await import('./browsers.mjs');
    const perCase = await runBrowser(engine, cases);
    cases.forEach((c, i) => findings.push(...tag(c, perCase[i])));
  }

  const renders = cases.reduce((n, c) => n + 1 + c.hostile.length, 0);
  console.log(`oracle-node: ${cases.length} cases, ${renders} renders, engines ${engines.join(',')}, ${((Date.now() - started) / 1000).toFixed(1)} s`);
  const summary = new Map();
  for (const f of findings) {
    const k = `${f.caseId} ${f.name} ${f.kind} ${f.engine}`;
    summary.set(k, (summary.get(k) || 0) + 1);
  }
  for (const [k, n] of [...summary].sort()) console.log(`  finding: ${k} (${n} renders)`);
  console.log(`  JS_UNPARSABLE (benign script acorn can't parse, unchecked): ${unparsable}`);

  const failures = ratchet(findings, entries, engines, holesFile);
  for (const m of failures) console.log('FAIL ' + m);
  console.log(failures.length ? `oracle-node: ${failures.length} failures` : 'oracle-node: ok');
  return failures.length ? 1 : 0;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).then(
    (code) => process.exit(code),
    (e) => {
      console.error(e);
      process.exit(2);
    });
}
