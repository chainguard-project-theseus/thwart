// Headless Chromium, Firefox and WebKit for the Node oracle (#322, #354). Each render is loaded as a
// top-level data: document, so it goes through the browser's normal network parser. A preload script
// defines every marker of the case as a function that records its call: a call while an event is being
// dispatched is LIVE_HANDLER, any other is LIVE_SCRIPT. Calls in child frames (srcdoc, javascript:
// and data: iframes, object documents) count too. The DOM gets the parse5 attribute checks (#318
// 2-4: an `on*` attribute holding a marker is LIVE_HANDLER even if its event never fires, a marker
// in a javascript: URL is JS_URL even if nothing follows it), and the hostile DOM must have the
// benign DOM's structure (STRUCTURE_CHANGED). A render that navigates the page away is
// STRUCTURE_CHANGED.
//
// Chromium: CHROMIUM_PATH (default /usr/bin/chromium), over CDP. Firefox: FIREFOX_PATH, over
// WebDriver BiDi. One browser per engine and CONCURRENCY pages, each reused case after case
// (opening and closing a tab per case races in Firefox's BiDi: "BrowsingContext does no longer exist").
// WebKit: WEBKIT_PATH (Playwright's WebKit build, its pw_run.sh), driven by playwright-core, since
// puppeteer has no WebKit. Playwright can't remove an init script, so each case gets a fresh browser
// context (cheap in WebKit) with the case's markers.
//
// Engine drift (#399): each render's DOM, in tree.mjs's normal form, must be the tree the vnu engine
// builds with scripting on (engineDrift); otherwise ENGINE_DRIFT.

import puppeteer from 'puppeteer-core';
import { webkit } from 'playwright-core';
import { MARKER, normalizeDom, structureDiff, eachElement, attrFindings, engineDrift } from './tree.mjs';

// Pages per browser. Firefox over BiDi spends most of a render waiting on round trips (#399: 768 s
// for 8,588 renders at 4 pages, against 282 s for Chromium), so it gets more pages by default.
const CONCURRENCY = Number(process.env.ORACLE_CONCURRENCY || 4);
const CONCURRENCY_FIREFOX = Number(process.env.ORACLE_CONCURRENCY_FIREFOX || 12);
const TIMEOUT_MS = 15000;

function launchOptions(engine) {
  if (engine === 'chromium') {
    return {
      browser: 'chrome',
      executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium',
      headless: true,
      // --no-sandbox: the CI guest is a throwaway VM and may not allow Chromium's namespace sandbox.
      args: ['--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage'],
    };
  }
  if (!process.env.FIREFOX_PATH) throw new Error('FIREFOX_PATH is not set');
  return {
    browser: 'firefox',
    protocol: 'webDriverBiDi',
    executablePath: process.env.FIREFOX_PATH,
    headless: true,
    extraPrefsFirefox: {
      // Automation-initiated top-level data: loads are what we want here.
      'security.data_uri.block_toplevel_data_uri_navigations': false,
    },
  };
}

// Runs in every frame before any of its scripts.
function defineMarkers(markers) {
  const calls = [];
  Object.defineProperty(window, '__tesseraOracleCalls', { value: calls });
  for (const m of markers) {
    window[m] = function () {
      calls.push({ marker: m, how: window.event ? 'handler' : 'script' });
    };
  }
}

/** Loads one render and returns {calls, tree, url}. */
async function load(page, html) {
  const url = 'data:text/html;charset=utf-8,' + encodeURIComponent(html);
  await page.goto(url, { waitUntil: 'load', timeout: TIMEOUT_MS });
  // Let error events of failed loads (img src=x) that race the load event run.
  await page.evaluate(() => new Promise((r) => setTimeout(r, 50)));
  const top = await page.evaluate(
    `({url: location.href, calls: window.__tesseraOracleCalls || null, tree: (${normalizeDom.toString()})(document)})`);
  if (top.calls === null) throw new Error('marker preload script did not run');
  for (const frame of page.frames()) {
    if (frame === page.mainFrame()) continue;
    const calls = await frame.evaluate(() => window.__tesseraOracleCalls || null).catch(() => null);
    top.calls.push(...(calls || []));
  }
  return { ...top, navigated: top.url !== url };
}

const markersOf = (c) =>
  [...new Set((c.benign + c.hostile.map((h) => h.render).join('')).match(new RegExp(MARKER.source, 'gi')) || [])];

/**
 * Findings for one case on a page whose documents already run defineMarkers(markersOf(c)), and the
 * number of DOMs compared with the vnu trees.
 */
async function checkCase(page, engine, c, options) {
  const out = [];
  let compared = 0;
  const add = (kind, where, value) => out.push({ kind, engine, where, value });
  const drift = (r, trees, value) => {
    if (trees == null || r.navigated) return; // a navigated page is STRUCTURE_CHANGED already
    compared++;
    const d = engineDrift(r.tree, trees, true, options.mutate);
    if (d) add('ENGINE_DRIFT', d, value);
  };
  const findings = (r, value) => {
    if (r.navigated) add('STRUCTURE_CHANGED', `the render navigated the page to ${r.url.slice(0, 200)}`, value);
    for (const call of r.calls) {
      add(call.how === 'handler' ? 'LIVE_HANDLER' : 'LIVE_SCRIPT', `call ${call.marker}()`, value);
    }
    eachElement(r.tree, (el, p) => {
      for (const [kind, where] of attrFindings(el, p)) add(kind, where, value);
    });
  };
  const benign = await load(page, c.benign);
  findings(benign, '');
  drift(benign, c.benignTrees, '');
  for (const { value, render, trees } of c.hostile) {
    const r = await load(page, render);
    const diff = structureDiff(benign.tree, r.tree);
    if (diff) add('STRUCTURE_CHANGED', diff, value);
    findings(r, value);
    drift(r, trees, value);
  }
  return { findings: out, compared };
}

/** Puppeteer (Chromium, Firefox): one page reused case after case, markers swapped per case. */
async function puppeteerCase(page, engine, c, options) {
  const { identifier } = await page.evaluateOnNewDocument(defineMarkers, markersOf(c));
  try {
    return await checkCase(page, engine, c, options);
  } finally {
    await page.removeScriptToEvaluateOnNewDocument(identifier);
  }
}

/** Playwright (WebKit): a fresh context per case, since init scripts can't be removed. */
async function playwrightCase(browser, c, options) {
  const context = await browser.newContext();
  try {
    await context.addInitScript(defineMarkers, markersOf(c));
    const page = await context.newPage();
    page.on('dialog', (d) => d.dismiss().catch(() => {}));
    return await checkCase(page, 'webkit', c, options);
  } finally {
    await context.close();
  }
}

async function launch(engine, options) {
  if (engine === 'webkit') {
    if (!process.env.WEBKIT_PATH) throw new Error('WEBKIT_PATH is not set');
    const browser = await webkit.launch({ executablePath: process.env.WEBKIT_PATH, headless: true });
    return {
      version: browser.version(),
      worker: async (run) => run((c) => playwrightCase(browser, c, options)),
      close: () => browser.close(),
    };
  }
  const browser = await puppeteer.launch(launchOptions(engine));
  return {
    version: await browser.version(),
    worker: async (run) => {
      const page = await browser.newPage();
      page.on('dialog', (d) => d.dismiss().catch(() => {}));
      await run((c) => puppeteerCase(page, engine, c, options));
    },
    close: () => browser.close(),
  };
}

/**
 * {findings: findings per case, in the order of cases; compared: DOMs compared with the vnu trees}.
 * Any browser error fails the run: never guess. options.mutate: compare a deliberately wrong normal
 * form (tree.mjs, mutateNormalForm).
 */
export async function runBrowser(engine, cases, options = {}) {
  const browser = await launch(engine, options);
  try {
    console.log(`oracle-node: ${engine} ${browser.version}`);
    const results = new Array(cases.length);
    let compared = 0;
    let next = 0;
    const run = async (check) => {
      while (next < cases.length) {
        const i = next++;
        try {
          const r = await check(cases[i]);
          results[i] = r.findings;
          compared += r.compared;
        } catch (e) {
          throw new Error(`${engine}: case ${cases[i].id} (${cases[i].name}): ${e.message}`, { cause: e });
        }
      }
    };
    const pages = engine === 'firefox' ? CONCURRENCY_FIREFOX : CONCURRENCY;
    await Promise.all(Array.from({ length: Math.min(pages, cases.length) }, () => browser.worker(run)));
    return { findings: results, compared };
  } finally {
    await browser.close();
  }
}
