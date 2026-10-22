#!/usr/bin/env node
/* Development-only regression runner against an isolated, real official dsh web.
 * npm install --no-save playwright (or set PLAYWRIGHT_MODULE to its package path).
 * DSH_TEST_URL must name a disposable loopback test instance, never user data.
 * No model requests or permissions changes are performed. A disposable test workspace may be opened.
 */
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const out = path.join(root, 'runtime-build/mobile-web');
fs.mkdirSync(out, {recursive: true});
const testUrl = process.env.DSH_TEST_URL;
if (!testUrl || new URL(testUrl).hostname !== '127.0.0.1') throw Error('DSH_TEST_URL must be an isolated loopback instance');
const css = fs.readFileSync(path.join(root, 'app/src/main/assets/mobile/mobile.css'), 'utf8');
const js = fs.readFileSync(path.join(root, 'app/src/main/assets/mobile/mobile.js'), 'utf8');
const results = [];
async function inject(page) {
  await page.addStyleTag({content: css});
  await page.evaluate(js);
  await page.waitForFunction(() => document.querySelector('[data-dsh-mobile-frame]'));
  await page.waitForTimeout(150);
}
async function geometry(page) {
  return page.evaluate(() => {
    const box = e => { if (!e) return null; const r=e.getBoundingClientRect(); return {x:r.x,y:r.y,w:r.width,h:r.height}; };
    const frame = document.querySelector('[data-shell-overlay]')?.parentElement;
    return {width: innerWidth,height:innerHeight, scroll:document.documentElement.scrollWidth,
      frame:box(frame), sidebar:box(frame?.children[0]), center:box(frame?.children[1]),
      composer:box(document.querySelector('[data-composer-card]')),
      dialog:box(document.querySelector('[role=dialog]'))};
  });
}
async function closeOnboarding(page) {
  await require('./mobile-test-setup.cjs').dismissOnboarding(page);
}
async function screenshot(page, name) { await page.screenshot({path:path.join(out,name+'.png'),fullPage:true}); }
(async () => {
  const browser = await chromium.launch({executablePath:process.env.CHROMIUM || '/usr/bin/chromium',headless:true,args:['--no-sandbox']});
  const context = await browser.newContext({viewport:{width:360,height:640},isMobile:true,hasTouch:true,locale:'zh-CN'});
  const page = await context.newPage();
  const errors=[];
  page.on('pageerror', e=>errors.push(e.message));
  await page.goto(testUrl);
  await page.waitForSelector('[data-shell-overlay]',{timeout:60000});
  await closeOnboarding(page);
  await screenshot(page,'before-360');
  const before = await geometry(page);
  await inject(page);
  const after = await geometry(page);
  assert(after.scroll <= after.width+1, 'No document-wide horizontal overflow');
  results.push({test:'360px actual official UI',before,after});
  await screenshot(page,'after-360');
  // The official sidebar toggle stays in charge: no synthetic application handlers.
  const open = page.getByRole('button',{name:/^(打开侧边栏|展开侧边栏|Expand sidebar|Open sidebar)$/});
  if (await open.count()) {
    await open.click(); await page.waitForTimeout(350);
    const expanded = await geometry(page);
    assert(expanded.center.w >= after.center.w-1,'Expanded sidebar must not squeeze the conversation');
    assert(expanded.sidebar.w >= 264,'Navigation remains readable');
    await screenshot(page,'sidebar-360');
    await page.getByRole('button',{name:/^(收起侧边栏|Collapse sidebar)$/}).click();
    await page.waitForTimeout(350);
    results.push({test:'sidebar overlay, official toggle',expanded});
  } else throw Error('Official sidebar expand control not found');
  // Read menu/settings through actual UI; stop if the upstream contract changes.
  await page.getByRole('button',{name:/^(账号菜单|Account menu)$/}).click();
  const settings = page.getByRole('menuitem',{name:/设置|Settings/});
  if (await settings.count()) await settings.click();
  else await page.getByRole('button',{name:/^(设置|Settings)$/}).click();
  await page.waitForSelector('[data-shortcut-modal=settings]');
  const settingsBefore = await geometry(page);
  for (const width of [320,360,393,600]) {
    await page.setViewportSize({width,height:640});
    await page.waitForTimeout(200);
    const g = await geometry(page);
    assert(g.dialog.x >= -1 && g.dialog.x+g.dialog.w <= width+1,'Settings sheet fits '+width);
    const nav = await page.locator('[data-shortcut-modal=settings] > nav').boundingBox();
    await screenshot(page,'settings-'+width);
    assert(nav.width >= width-20,'Settings nav fills phone width, not desktop side-by-side '+JSON.stringify({width,g,nav}));
    results.push({test:'settings '+width,geometry:g});
    await screenshot(page,'settings-'+width);
  }
  // A reduced viewport approximates resize geometry; real IME is separately tested on Android.
  await page.setViewportSize({width:360,height:320}); await page.waitForTimeout(200);
  const keyboard = await geometry(page);
  assert(keyboard.dialog.y >= -1 && keyboard.dialog.y+keyboard.dialog.h<=321,'Settings fits resized viewport');
  results.push({test:'reduced-height geometry (not real IME)',geometry:keyboard});
  await screenshot(page,'settings-short');
  await page.getByRole('button',{name:/^(关闭设置|关闭|Close settings|Close)$/}).click();
  await page.setViewportSize({width:1280,height:800}); await page.waitForTimeout(200);
  await page.evaluate(()=>window.__dshAndroidMobile.setEnabled(false));
  const desktopBefore=await geometry(page);
  await page.evaluate(()=>window.__dshAndroidMobile.setEnabled(true)); await page.waitForTimeout(150);
  const desktopAfter=await geometry(page);
  assert.deepEqual(desktopAfter, desktopBefore,'No desktop geometry changes above 600px');
  results.push({test:'desktop unchanged at 1280px',geometry:desktopAfter});
  await page.setViewportSize({width:360,height:640}); await page.waitForTimeout(150);
  await page.evaluate(()=>window.__dshAndroidMobile.setEnabled(false));
  const off=await page.evaluate(()=>!document.documentElement.hasAttribute('data-dsh-android-mobile'));
  assert(off,'Adapter can be disabled without navigation or React remount');
  await page.evaluate(()=>window.__dshAndroidMobile.setEnabled(true));
  results.push({test:'adapter toggle without reload',passed:true});
  // Open only a disposable empty workspace through the official directory picker.
  const workspace = process.env.DSH_TEST_WORKSPACE;
  if (workspace) {
    if (!path.resolve(workspace).startsWith(path.join(root,'runtime-build')+path.sep)) throw Error('Workspace must be disposable runtime-build data');
    fs.mkdirSync(workspace,{recursive:true});
    const choose=page.getByRole('button',{name:'选择工作区',exact:true});
    if (await choose.count() && !await page.locator('[data-composer-input][contenteditable=true]').count()) {
      await choose.click();
      await page.waitForTimeout(300);
      const picker=page.getByRole('dialog',{name:'选择工作区目录',exact:true});
      if (!await picker.count()) {
        const folder=page.getByRole('button',{name:/添加工作区|打开文件夹|打开目录|选择其他/});
        if (await folder.count()) await folder.first().click();
        else await page.getByRole('menuitem',{name:/添加工作区|打开文件夹|打开目录|选择其他/}).click();
      }
      await picker.getByRole('button',{name:'编辑路径',exact:true}).click();
      await picker.getByRole('textbox',{name:'编辑路径',exact:true}).fill(workspace);
      await picker.getByRole('textbox',{name:'编辑路径',exact:true}).press('Enter');
      await picker.getByRole('button',{name:'打开',exact:true}).click();
      await picker.waitFor({state:'hidden'});
    }
    const input=page.locator('[data-composer-input][contenteditable=true]');
    await input.waitFor();
    const draft=Array.from({length:24},(_,i)=>'草稿第 '+(i+1)+' 行：移动端滚动测试，不发送。').join('\n');
    await input.fill(draft);
    const sameEditor=await input.elementHandle();
    for (const [width,height] of [[320,640],[360,320],[393,640]]) {
      await page.setViewportSize({width,height}); await page.waitForTimeout(200);
      const g=await geometry(page);
      assert(g.scroll<=width+1,'No page-wide overflow while editing');
      assert(g.composer.x>=0 && g.composer.x+g.composer.w<=width+1,'Composer stays inside phone');
      assert(g.composer.y>=0 && g.composer.y+g.composer.h<=height+1,'Composer actions remain inside reduced-height viewport');
      const draftMetrics=await page.locator('[data-dsh-mobile-draft-scroll]').evaluate(e=>({client:e.clientHeight,scroll:e.scrollHeight}));
      assert(draftMetrics.scroll>draftMetrics.client,'Long draft has its own scroll');
      results.push({test:'multiline draft '+width+'x'+height,geometry:g,draftMetrics});
      await screenshot(page,'draft-'+width+'x'+height);
    }
    await page.evaluate(()=>window.__dshAndroidMobile.setEnabled(false));
    await page.evaluate(()=>window.__dshAndroidMobile.setEnabled(true));
    assert.equal(await sameEditor.evaluate(e=>e.isConnected),true,'Toggle does not remount editor');
    assert.equal(await input.innerText(),draft,'Toggle keeps real composer draft');
    await input.fill('中文输入法测试');
    // A composition Enter must not submit the actual official composer.
    await input.dispatchEvent('compositionstart',{data:'中文'});
    await input.dispatchEvent('keydown',{key:'Enter',code:'Enter',keyCode:229,isComposing:true});
    await input.dispatchEvent('compositionend',{data:'中文'});
    assert.equal(await input.innerText(),'中文输入法测试','Composition Enter preserves draft');
    await page.getByRole('button',{name:'添加文件或调用指令',exact:true}).click();
    await screenshot(page,'composer-menu-393');
    await page.keyboard.press('Escape');
    results.push({test:'real editor identity, draft, composition Enter, plus-menu',passed:true});
  }

  const blank=await context.newPage();
  await blank.setContent('<!doctype html><html><head><meta name="viewport"></head><body></body></html>');
  await blank.evaluate(js);
  await blank.evaluate(()=>window.__dshAndroidMobile.setEnabled(false));
  assert.equal(await blank.locator('meta[name=viewport]').getAttribute('content'),null,'Restore missing viewport content attribute');
  results.push({test:'viewport exact restoration',passed:true});
  await blank.close();
  results.push({test:'uncaught page errors',errors});
  assert.equal(errors.length,0,'No uncaught UI errors');
  fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({passed:true,results},null,2)+'\n');
  console.log(JSON.stringify({passed:true,checks:results.length,out},null,2));
  await browser.close();
})().catch(e=>{fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({passed:false,error:e.stack,results},null,2)+'\n');console.error(e);process.exit(1);});
