// Headless Chromium and Firefox for the Node oracle (#322). Each render is loaded as a top-level
// data: document, so it goes through the browser's normal network parser. A preload script defines
// every marker of the case as a function that records its call: a call while an event is being
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

import puppeteer from 'puppeteer-core';
import { MARKER, normalizeDom, structureDiff, eachElement, attrFindings } from './tree.mjs';

const CONCURRENCY = Number(process.env.ORACLE_CONCURRENCY || 4);
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

async function checkCase(page, engine, c) {
  const markers = [...new Set((c.benign + c.hostile.map((h) => h.render).join('')).match(new RegExp(MARKER.source, 'gi')) || [])];
  const { identifier } = await page.evaluateOnNewDocument(defineMarkers, markers);
  try {
    const out = [];
    const add = (kind, where, value) => out.push({ kind, engine, where, value });
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
    for (const { value, render } of c.hostile) {
      const r = await load(page, render);
      const diff = structureDiff(benign.tree, r.tree);
      if (diff) add('STRUCTURE_CHANGED', diff, value);
      findings(r, value);
    }
    return out;
  } finally {
    await page.removeScriptToEvaluateOnNewDocument(identifier);
  }
}

/** Findings per case, in the order of cases. Any browser error fails the run: never guess. */
export async function runBrowser(engine, cases) {
  const browser = await puppeteer.launch(launchOptions(engine));
  try {
    console.log(`oracle-node: ${engine} ${await browser.version()}`);
    const results = new Array(cases.length);
    let next = 0;
    const worker = async () => {
      const page = await browser.newPage();
      page.on('dialog', (d) => d.dismiss().catch(() => {}));
      while (next < cases.length) {
        const i = next++;
        try {
          results[i] = await checkCase(page, engine, cases[i]);
        } catch (e) {
          throw new Error(`${engine}: case ${cases[i].id} (${cases[i].name}): ${e.message}`, { cause: e });
        }
      }
    };
    await Promise.all(Array.from({ length: Math.min(CONCURRENCY, cases.length) }, worker));
    return results;
  } finally {
    await browser.close();
  }
}
