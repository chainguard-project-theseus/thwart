// The Node oracle (#322): runs the cases ProfileOracleTest exports (#324) through parse5 and headless
// Chromium, Firefox and WebKit, and ratchets the findings against known-holes.txt. Not part of
// `mvn verify`; Chainguard Checks runs it through run.sh
// (.chainguard/ci/oracle-node.yaml).
//
//   node check.mjs [--engines parse5,chromium,firefox,webkit] [--known-holes <file>] [--require-trees]
//     [--mutate-normal-form] <cases.jsonl>
//
// Input: ExportRecord JSONL, {"id","name","template","value","render","verdict","trees"}, one line
// per (case, value). Per case, the benign render is the record whose value is a bare marker
// (`tqm0001`); every other record is hostile.
// Rejected cases (render null) are skipped. A case with hostile renders and no benign one fails.
// "trees" holds the vnu engine's trees of the render (VnuTrees.java); --require-trees makes a
// render without them an error (the real export always has them; the fixture's synthetic renders
// may not).
//
// Engines: parse5 (scripting on) runs #318's checks 1-5 and 7 and an acorn version of #319's script
// check. Chromium (CHROMIUM_PATH), Firefox (FIREFOX_PATH, WebDriver BiDi) and WebKit (WEBKIT_PATH,
// Playwright's build) load each render and report marker calls (LIVE_SCRIPT, LIVE_HANDLER) and
// benign/hostile DOM differences (STRUCTURE_CHANGED); see browsers.mjs. The browsers run at the
// same time (ORACLE_SERIAL=1: one after another).
//
// Engine drift (#399): every engine's tree of every render with trees, in tree.mjs's normal form,
// must be the tree the vnu engine builds with the same scripting flag (parse5: on and off;
// browsers: on). Otherwise it is ENGINE_DRIFT for that engine: the verifier proved its guarantee
// about a document this engine doesn't build (#398). A case's STRUCTURE_CHANGED findings on an
// engine that drifts on it are reported under the drift, not ratcheted on their own: the value
// checks compare trees the verifier never saw. Drift is reported per engine, never assuming vnu is
// the wrong side: Firefox's parser is generated from the same Java as vnu and may share its
// deviations. A parse5 drift on a render where every browser that ran matches vnu is parse5's own
// bug, reported as "parse5-only drift" and not ratcheted. --mutate-normal-form compares a
// deliberately wrong normal form (the self-check's negative).
//
// Ratchet: a finding with no known-holes line fails ("new hole"); a line for an engine this job ran
// (parse5, chromium, firefox, webkit), or a `*` line when all four ran, that matched nothing fails
// ("delete its line"). An
// ENGINE_DRIFT line names one of these four engines, never `*`.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parse } from 'parse5';
import * as acorn from 'acorn';
import {
  MARKER, HTML_NS, normalizeParse5, structureDiff, eachElement, textOf, attrFindings, preprocessUrl, refreshUrl,
  engineDrift,
} from './tree.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const BENIGN = /^tqm\d{4}$/;
const NODE_ENGINES = ['parse5', 'chromium', 'firefox', 'webkit'];
const KINDS = new Set([
  'STRUCTURE_CHANGED', 'MARKER_IN_NAME', 'LIVE_SCRIPT', 'LIVE_HANDLER', 'JS_URL',
  'CROSS_ORIGIN_RESOURCE', 'JS_NOT_LITERAL', 'JS_UNPARSABLE', 'CONTEXT_MISMATCH', 'ENGINE_DRIFT',
]);

// ---- input ----

/**
 * Groups ExportRecord lines by case: [{id, name, template, benign, benignTrees, hostile: [{value,
 * render, trees}]}]. With requireTrees, a render without trees is an error.
 */
export function readCases(text, requireTrees = false) {
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
      byId.set(r.id, { id: r.id, name: r.name, template: r.template, benign: null, benignTrees: null, hostile: [] });
    }
    const c = byId.get(r.id);
    if (r.render == null || r.value == null) return; // REJECTED
    const trees = r.trees ?? null;
    if (trees != null && (typeof trees !== 'object' || !Array.isArray(trees['scripting-on']) || !Array.isArray(trees['scripting-off']))) {
      throw new Error(`line ${i + 1}: trees must hold scripting-on and scripting-off`);
    }
    if (trees == null && requireTrees) throw new Error(`line ${i + 1}: case ${r.id} render without vnu trees`);
    if (BENIGN.test(r.value)) {
      if (c.benign != null) throw new Error(`line ${i + 1}: case ${r.id} has two benign renders`);
      c.benign = r.render;
      c.benignTrees = trees;
    } else {
      c.hostile.push({ value: r.value, render: r.render, trees });
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
const RESOURCE_LINK_RELS = new Set(['stylesheet', 'preload', 'modulepreload', 'import']);

// Names outside HTML (#583 rule 3, #611): every name not in the oracle's own copy of the standard
// HTML, SVG, MathML and ARIA names (the file HostileSinks.STANDARD reads, not the compiler's), other
// than on*. Client code may send a request to what it reads there, whichever library reads it, so a
// slot's value must be framework-neutral (HostileSinks.NEUTRAL): a plain decimal, true/false, or a
// path that starts with exactly one '/' in A-Z a-z 0-9 . _ ~ / - and %XX, with no '--'. Browsers fold
// A-Z only, so a non-ASCII look-alike is outside HTML.
const STANDARD = new Set(
  fs.readFileSync(path.join(HERE, '../resources/oracle/standard-attributes.txt'), 'utf8').split('\n')
    .filter((l) => l.trim() && !l.startsWith('#')).map((l) => l.trim().split(/\s+/)[0]),
);
const NEUTRAL = /^(true|false|-?(0|[1-9][0-9]*)(\.[0-9]+)?|\/(?!\/)([A-Za-z0-9._~/-]|%[0-9A-Fa-f]{2})*)$/;
const asciiLower = (s) => s.replace(/[A-Z]/g, (c) => c.toLowerCase());
const outsideHtml = (name) => !asciiLower(name).startsWith('on') && !STANDARD.has(asciiLower(name));
const neutral = (value) => NEUTRAL.test(value) && !value.includes('--');

/** CROSS_ORIGIN_RESOURCE for each slot (a benign value with a marker) outside HTML whose hostile value isn't neutral. */
function neutralFindings(benign, tree) {
  const slots = new Set();
  eachElement(benign, (el, p) => {
    for (const [name, value] of el.attrs) if (MARKER.test(value)) slots.add(`${p}@${name}`);
  });
  const f = [];
  eachElement(tree, (el, p) => {
    for (const [name, value] of el.attrs) {
      if (slots.has(`${p}@${name}`) && outsideHtml(name) && !neutral(value)) f.push(['CROSS_ORIGIN_RESOURCE', `${p}@${name}`]);
    }
  });
  return f;
}

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
    for (const name of resource) {
      const v = attr(el, name);
      if (v != null && crossOrigin(v, contextRelative)) f.push(['CROSS_ORIGIN_RESOURCE', `${p}@${name}`]);
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
const parseTreeNoScripting = (html) => normalizeParse5(parse(html, { scriptingEnabled: false }), parseTreeNoScripting);

/**
 * parse5 findings for one case: [{kind, engine, where, value}], the unparsable-script count, and
 * how many trees were compared with the vnu trees.
 */
export function checkParse5(c, mutate = false) {
  const contextRelative = /@\{\s*\//.test(c.template);
  const benign = parseTree(c.benign);
  const benignScripts = scripts(benign);
  const out = [];
  let unparsable = 0;
  let compared = 0;
  const add = (kind, where, value) => out.push({ kind, engine: 'parse5', where: 'scripting-on ' + where, value });
  const drift = (render, tree, trees, value) => {
    if (trees == null) return;
    for (const [scripting, t] of [[true, tree], [false, parseTreeNoScripting(render)]]) {
      compared++;
      const d = engineDrift(t, trees, scripting, mutate);
      if (d) out.push({ kind: 'ENGINE_DRIFT', engine: 'parse5', where: d, value });
    }
  };
  for (const [kind, where] of renderFindings(benign, contextRelative)) add(kind, where, '');
  drift(c.benign, benign, c.benignTrees, '');
  for (const { value, render, trees } of c.hostile) {
    const tree = parseTree(render);
    drift(render, tree, trees, value);
    const diff = structureDiff(benign, tree);
    if (diff) add('STRUCTURE_CHANGED', diff, value);
    for (const [kind, where] of renderFindings(tree, contextRelative)) add(kind, where, value);
    for (const [kind, where] of neutralFindings(benign, tree)) add(kind, where, value);
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
  return { findings: out, unparsable, compared };
}

/**
 * Takes parse5's own deviations out of the ratchet: a parse5 ENGINE_DRIFT on a render where every
 * browser that ran matches vnu. parse5 has bugs of its own (the end tag of an integration point
 * while an HTML formatting element is open, #402's review), so it is not the reference; the browsers
 * are. Reported as "parse5-only drift", not failing. With no browser run, parse5 drift counts.
 * Returns {kept, parse5Only}.
 */
export function separateParse5Only(findings, browsersRun) {
  if (!browsersRun.length) return { kept: findings, parse5Only: [] };
  const key = (f) => `${f.caseId}\u0000${f.value}`;
  const browserDrift = new Set(findings.filter((f) => f.kind === 'ENGINE_DRIFT' && f.engine !== 'parse5').map(key));
  const kept = [];
  const parse5Only = [];
  for (const f of findings) {
    (f.kind === 'ENGINE_DRIFT' && f.engine === 'parse5' && !browserDrift.has(key(f)) ? parse5Only : kept).push(f);
  }
  return { kept, parse5Only };
}

/**
 * Moves each case's STRUCTURE_CHANGED findings on an engine that drifts on that case (drift in
 * `drifts`, ratcheted or parse5-only) out of the ratchet: they compare a benign and a hostile tree
 * the verifier never reasoned about, so the drift is what to fix (and list). Returns {kept, folded}.
 */
export function foldUnderDrift(findings, drifts = findings) {
  const drifting = new Set(drifts.filter((f) => f.kind === 'ENGINE_DRIFT').map((f) => `${f.caseId} ${f.engine}`));
  const kept = [];
  const folded = [];
  for (const f of findings) {
    (f.kind === 'STRUCTURE_CHANGED' && drifting.has(`${f.caseId} ${f.engine}`) ? folded : kept).push(f);
  }
  return { kept, folded };
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
    if (m[2] === 'ENGINE_DRIFT' && !NODE_ENGINES.includes(m[3])) {
      throw new Error(`${file}:${i + 1}: an ENGINE_DRIFT line names the engine that drifts, one of ${NODE_ENGINES}, not ${m[3]}`);
    }
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
    const ran = e.engine === '*' ? NODE_ENGINES.every((n) => enginesRun.includes(n)) : enginesRun.includes(e.engine);
    if (!e.used && ran) {
      failures.push(`known hole ${e.caseId} ${e.kind} ${e.engine} (${file}:${e.line}) is fixed (or now rejected): delete its line`);
    }
  }
  return failures;
}

// ---- main ----

async function main(argv) {
  let engines = NODE_ENGINES;
  let holesFile = path.relative(process.cwd(), path.join(HERE, '..', 'resources', 'oracle', 'known-holes.txt'));
  let requireTrees = false;
  let mutate = false;
  const files = [];
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--engines') engines = argv[++i].split(',');
    else if (argv[i] === '--known-holes') holesFile = argv[++i];
    else if (argv[i] === '--require-trees') requireTrees = true;
    else if (argv[i] === '--mutate-normal-form') mutate = true;
    else files.push(argv[i]);
  }
  if (files.length !== 1 || engines.some((e) => !NODE_ENGINES.includes(e))) {
    console.error('usage: node check.mjs [--engines parse5,chromium,firefox,webkit] [--known-holes <file>] '
      + '[--require-trees] [--mutate-normal-form] <cases.jsonl>');
    return 2;
  }
  const cases = readCases(fs.readFileSync(files[0], 'utf8'), requireTrees);
  const entries = parseKnownHoles(holesFile, fs.readFileSync(holesFile, 'utf8'));
  const all = [];
  const tag = (c, fs_) => fs_.map((f) => ({ caseId: c.id, name: c.name, template: c.template, ...f }));
  const started = Date.now();
  const renders = cases.reduce((n, c) => n + 1 + c.hostile.length, 0);
  const withTrees = cases.reduce((n, c) => n + (c.benignTrees ? 1 : 0) + c.hostile.filter((h) => h.trees).length, 0);
  const stats = [];
  const engineStats = (engine, seconds, compared, perCase) => {
    const drift = perCase.flat().filter((f) => f.kind === 'ENGINE_DRIFT');
    stats.push(`oracle-node: ${engine}: ${cases.length} cases, ${renders} renders, ${seconds.toFixed(1)} s; `
      + `tree comparison: ${compared} trees compared with vnu (${withTrees} renders with trees), drift in `
      + `${drift.length} trees of ${new Set(drift.map((f) => f.caseId)).size} cases`);
  };
  let unparsable = 0;
  if (engines.includes('parse5')) {
    const t0 = Date.now();
    let compared = 0;
    const perCase = cases.map((c) => {
      const r = checkParse5(c, mutate);
      unparsable += r.unparsable;
      compared += r.compared;
      return tag(c, r.findings);
    });
    perCase.forEach((f) => all.push(...f));
    engineStats('parse5', (Date.now() - t0) / 1000, compared, perCase);
  }
  const browsers = engines.filter((e) => e !== 'parse5');
  if (browsers.length) {
    const { runBrowser } = await import('./browsers.mjs');
    const one = async (engine) => {
      const t0 = Date.now();
      const r = await runBrowser(engine, cases, { mutate });
      const perCase = cases.map((c, i) => tag(c, r.findings[i]));
      engineStats(engine, (Date.now() - t0) / 1000, r.compared, perCase);
      return perCase;
    };
    const results = [];
    if (process.env.ORACLE_SERIAL === '1') {
      for (const e of browsers) results.push(await one(e));
    } else {
      results.push(...(await Promise.all(browsers.map(one))));
    }
    results.forEach((perCase) => perCase.forEach((f) => all.push(...f)));
  }

  console.log(`oracle-node: ${cases.length} cases, ${renders} renders, engines ${engines.join(',')}, ${((Date.now() - started) / 1000).toFixed(1)} s`);
  for (const line of stats) console.log(line);
  const { kept: ratcheted, parse5Only } = separateParse5Only(all, browsers);
  const { kept: findings, folded } = foldUnderDrift(ratcheted, all);
  const summary = new Map();
  for (const f of findings) {
    const k = `${f.caseId} ${f.name} ${f.kind} ${f.engine}`;
    summary.set(k, (summary.get(k) || 0) + 1);
  }
  for (const [k, n] of [...summary].sort()) console.log(`  finding: ${k} (${n} renders)`);
  const firstDrift = new Map();
  for (const f of findings) {
    const k = `${f.caseId} ${f.engine}`;
    if (f.kind === 'ENGINE_DRIFT' && !firstDrift.has(k)) firstDrift.set(k, f);
  }
  for (const f of firstDrift.values()) {
    console.log(`  drift: ${f.caseId} ${f.engine} ${f.value === '' ? 'benign render' : 'value ' + JSON.stringify(f.value)}: ${f.where.slice(0, 600)}`);
  }
  const p5only = new Map();
  for (const f of parse5Only) if (!p5only.has(f.caseId)) p5only.set(f.caseId, f);
  for (const f of p5only.values()) {
    console.log(`  parse5-only drift (browsers match vnu; parse5's own deviation, not ratcheted): ${f.caseId} ${f.name} `
      + `${f.value === '' ? 'benign render' : 'value ' + JSON.stringify(f.value)}: ${f.where.slice(0, 400)}`);
  }
  const foldedSummary = new Map();
  for (const f of folded) {
    const k = `${f.caseId} ${f.name} ${f.kind} ${f.engine}`;
    foldedSummary.set(k, (foldedSummary.get(k) || 0) + 1);
  }
  for (const [k, n] of [...foldedSummary].sort()) console.log(`  under ENGINE_DRIFT: ${k} (${n} renders)`);
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
