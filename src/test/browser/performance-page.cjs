const fs = require('node:fs');
const assert = require('node:assert/strict');
const {chromium} = require(process.env.ALOUTE_PLAYWRIGHT_MODULE);
(async () => {
  const base = process.argv[2];
  const browser = await chromium.launch({executablePath:process.env.ALOUTE_BROWSER_EXECUTABLE,headless:true});
  try {
    const context = await browser.newContext({viewport:{width:1365,height:900}});
    await context.addCookies([{name:'ALOUTE_TOKEN',value:process.env.ALOUTE_PERFORMANCE_ACCESS_TOKEN,url:base,httpOnly:true,sameSite:'Lax'}]);
    await context.route('**/*',route => route.request().url().startsWith(base) ? route.continue() : route.abort());
    const page = await context.newPage();
    await page.addInitScript(() => {
      window.lab={lcpMs:null,cls:0,longTaskMs:0};
      new PerformanceObserver(list=>{for(const e of list.getEntries())window.lab.lcpMs=e.startTime;}).observe({type:'largest-contentful-paint',buffered:true});
      new PerformanceObserver(list=>{for(const e of list.getEntries())if(!e.hadRecentInput)window.lab.cls+=e.value;}).observe({type:'layout-shift',buffered:true});
      new PerformanceObserver(list=>{for(const e of list.getEntries())window.lab.longTaskMs+=e.duration;}).observe({type:'longtask',buffered:true});
    });
    const response = await page.goto(base+'/feed',{waitUntil:'load'});assert.equal(response.status(),200);
    await page.waitForSelector('.post');await page.waitForTimeout(1000);
    const metrics = await page.evaluate(()=>{
      const navigation=performance.getEntriesByType('navigation')[0];
      return {...window.lab,fcpMs:performance.getEntriesByName('first-contentful-paint')[0]?.startTime,
        ttfbMs:navigation.responseStart-navigation.requestStart,domNodes:document.querySelectorAll('*').length,
        sameOriginEncodedBytes:performance.getEntriesByType('resource').reduce((sum,r)=>sum+r.encodedBodySize,0),
        videos:document.querySelectorAll('video').length,
        eagerVideos:[...document.querySelectorAll('video')].filter(v=>v.preload!=='none').length};
    });
    metrics.scope='Single after-only desktop Chromium lab navigation; external providers blocked; no INP/field CWV claim';
    assert.equal(metrics.eagerVideos,0);
    fs.writeFileSync('target/performance/browser-page.json',JSON.stringify(metrics,null,2));
    console.log(JSON.stringify(metrics));
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
