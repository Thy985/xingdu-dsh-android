#!/usr/bin/env node
/* Disposable loopback DSH, real official UI. No save, key, model call, plugin
 * enable/disable/install, permission change, or external endpoint discovery. */
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..'),out=path.join(root,'runtime-build/settings-validation');fs.mkdirSync(out,{recursive:true});
const url=process.env.DSH_TEST_URL;assert(url&&new URL(url).hostname==='127.0.0.1');
const results=[],errors=[];let page,browser;
const settle=()=>page.waitForTimeout(250),shot=name=>page.screenshot({path:path.join(out,name+'.png')});
const record=(test,extra={})=>results.push({test,passed:true,...extra});
async function fits(dialog){const geometry=await dialog.evaluate(e=>{const r=e.getBoundingClientRect();return{x:r.x,y:r.y,w:r.width,h:r.height,scroll:e.scrollWidth,client:e.clientWidth,width:innerWidth,height:innerHeight};});assert(geometry.x>=-1&&geometry.x+geometry.w<=geometry.width+1);assert(geometry.y>=-1&&geometry.y+geometry.h<=geometry.height+1);assert(geometry.scroll<=geometry.client+1,'Settings must not overflow sideways');return geometry;}
async function controlsFit(scope){const bad=await scope.locator('input,select,button').evaluateAll(es=>es.filter(e=>e.getClientRects().length).filter(e=>{const r=e.getBoundingClientRect();const panel=e.closest('[data-shortcut-modal=settings]').getBoundingClientRect();return r.width>panel.width||r.x<panel.x-1||r.right>panel.right+1;}).map(e=>({tag:e.tagName,label:e.getAttribute('aria-label'),text:e.textContent.slice(0,60)})));assert.deepEqual(bad,[],'All controls fit the settings width');}
(async()=>{
 browser=await chromium.launch({executablePath:process.env.CHROMIUM||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
 const context=await browser.newContext({viewport:{width:360,height:640},locale:'zh-CN',isMobile:true,hasTouch:true});page=await context.newPage();
 page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error'&&/slot entry crashed|TypeError|ReferenceError/.test(m.text()))errors.push(m.text());});
 await page.goto(url);await page.waitForSelector('[data-shell-overlay]',{timeout:60000});await settle();
 await require('./mobile-test-setup.cjs').dismissOnboarding(page);
 await page.addStyleTag({content:fs.readFileSync(path.join(root,'app/src/main/assets/mobile/mobile.css'),'utf8')});await page.evaluate(fs.readFileSync(path.join(root,'app/src/main/assets/mobile/mobile.js'),'utf8'));await settle();
 await page.getByRole('button',{name:'会话抽屉',exact:true}).click();await page.getByRole('button',{name:'账号菜单',exact:true}).click();await page.getByRole('menuitem',{name:/设置/}).click();
 const dialog=page.locator('[data-shortcut-modal=settings]'),nav=dialog.locator(':scope > nav');await dialog.waitFor();await settle();
 assert.equal(await dialog.getAttribute('data-dsh-mobile-settings-view'),'index');
 for(const [width,height] of [[320,640],[360,320],[360,240],[393,700],[600,800]]){
  await page.setViewportSize({width,height});await settle();const geometry=await fits(dialog);
  const boxes=await nav.locator('button').evaluateAll(es=>es.map(e=>{const r=e.getBoundingClientRect();return{x:r.x,y:r.y,w:r.width,h:r.height};}));
  assert(boxes.every(b=>b.h>=44&&b.w>=width-48));assert(boxes.every((b,i)=>i===0||b.y>=boxes[i-1].y+boxes[i-1].h));
  await nav.getByRole('button',{name:'模型',exact:true}).click();await settle();assert.equal(await nav.isVisible(),false);
  const back=dialog.getByRole('button',{name:'返回设置分类',exact:true});assert(await back.isVisible());
  await controlsFit(dialog);assert(await back.evaluate(e=>document.activeElement===e),'Focus moves to visible back control');
  record('single-column category/detail '+width+'x'+height,{geometry});
  await back.click();await settle();assert(await nav.isVisible());assert(await nav.getByRole('button',{name:'模型',exact:true}).evaluate(e=>document.activeElement===e));
 }
 await page.setViewportSize({width:360,height:640});await settle();await shot('settings-index-360');
 await nav.getByRole('button',{name:'模型',exact:true}).click();await settle();
 await dialog.getByText('自定义设置',{exact:true}).click();await settle();await controlsFit(dialog);
 const base=dialog.getByLabel('API 地址',{exact:true}).first();await base.fill('https://mobile-draft.invalid/v1');const handle=await base.elementHandle();
 await dialog.getByRole('button',{name:'返回设置分类',exact:true}).click();await settle();assert(await handle.evaluate(e=>e.isConnected));
 await nav.getByRole('button',{name:'模型',exact:true}).click();await settle();assert.equal(await base.inputValue(),'https://mobile-draft.invalid/v1');assert(await handle.evaluate(e=>e.isConnected));record('category return preserves original provider editor and unsaved draft');
 await dialog.getByRole('button',{name:'添加模型提供商',exact:true}).click();await settle();
 await dialog.getByRole('tab',{name:'自定义模型 API',exact:true}).click();await settle();
 const custom=dialog.getByRole('tabpanel',{name:'自定义模型 API',exact:true});await custom.getByLabel('Provider ID',{exact:true}).fill('mobile-unsaved');await custom.getByLabel('显示名称',{exact:true}).fill('手机草稿，不保存');
 const customHandle=await custom.getByLabel('Provider ID',{exact:true}).elementHandle();
 await dialog.getByRole('tab',{name:'第三方模型提供商',exact:true}).click();await settle();assert(!await custom.isVisible());
 await dialog.getByRole('tab',{name:'自定义模型 API',exact:true}).click();await settle();assert.equal(await custom.getByLabel('Provider ID',{exact:true}).inputValue(),'mobile-unsaved');assert(await customHandle.evaluate(e=>e.isConnected));
 record('official add-provider mode switch preserves hidden custom draft');
 await custom.getByRole('button',{name:'添加模型',exact:true}).click();await settle();
 const row=custom.locator('[data-dsh-mobile-setting-modelRow]').first();await row.waitFor();
 assert.equal(await row.evaluate(e=>getComputedStyle(e).gridTemplateColumns.split(' ').length),1);
 record('official editable model row uses a single grid column');
 for(const [width,height] of [[320,640],[360,320],[360,240],[393,700],[600,800]]){await page.setViewportSize({width,height});await settle();await fits(dialog);await controlsFit(dialog);
  const save=custom.getByRole('button',{name:'创建提供商',exact:true});await save.scrollIntoViewIfNeeded();const b=await save.boundingBox();assert(b.y>=0&&b.y+b.height<=height+1);record('custom model actions reachable '+width+'x'+height);}
 await page.setViewportSize({width:393,height:700});await settle();await shot('models-custom-393');
 await page.evaluate(()=>window.__dshAndroidMobile.setEnabled(false));await settle();assert.equal(await dialog.getAttribute('data-dsh-mobile-settings-flow'),null);assert(await handle.evaluate(e=>e.isConnected));assert(await customHandle.evaluate(e=>e.isConnected));
 await page.evaluate(()=>window.__dshAndroidMobile.setEnabled(true));await settle();assert.equal(await base.inputValue(),'https://mobile-draft.invalid/v1');record('disabling/re-enabling presentation keeps official model fields and drafts');
 await dialog.getByRole('button',{name:'返回设置分类',exact:true}).click();await settle();await nav.getByRole('button',{name:'内置插件',exact:true}).click();await settle();
 await dialog.getByRole('searchbox',{name:'搜索插件',exact:true}).waitFor();await settle();
 record('official plugin inventory loaded');
 const tabs=dialog.getByRole('tab');const tabCount=await tabs.count();
 for(let i=0;i<tabCount;i++){await tabs.nth(i).click();await settle();await fits(dialog);await controlsFit(dialog);record('official plugin tab '+await tabs.nth(i).innerText());}
 const search=dialog.getByRole('searchbox',{name:'搜索插件',exact:true});{
  await search.fill('no-such-plugin-mobile-xxxxxxxx');await settle();assert(await dialog.getByText('没有匹配的插件。',{exact:true}).isVisible());record('plugin inventory local search and empty results');
  await search.fill('');await settle();const card=dialog.locator('[data-plugin-module]').first();await card.waitFor();{const disclosure=card.locator('button').first();await disclosure.click();await settle();assert.equal(await card.getAttribute('data-open'),'true');record('official plugin metadata disclosure (no toggle/install)');}
 }
 for(const [width,height] of [[320,640],[360,320],[360,240],[393,700],[600,800]]){await page.setViewportSize({width,height});await settle();await fits(dialog);await controlsFit(dialog);record('plugin single-column fit '+width+'x'+height);}
 await page.setViewportSize({width:393,height:700});await settle();await shot('plugins-393');
 if(tabCount>1){await tabs.first().click();await settle();await tabs.last().click();await settle();record('official plugin tabs stay operable after disclosure');}
 await page.setViewportSize({width:1280,height:800});await settle();assert.equal(await dialog.getAttribute('data-dsh-mobile-settings-flow'),null);const before=await dialog.boundingBox();
 await page.evaluate(()=>window.__dshAndroidMobile.setEnabled(false));await settle();assert.deepEqual(await dialog.boundingBox(),before);record('1280px official desktop settings geometry unchanged');
 await page.setViewportSize({width:360,height:640});await page.evaluate(()=>window.__dshAndroidMobile.setEnabled(true));await settle();
 await page.keyboard.press('Escape');await settle();assert.equal(await dialog.count(),0);record('official modal Escape closes settings without intercepted key handling');
 assert.deepEqual(errors,[]);record('no uncaught page or slot errors');
 fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({passed:true,checks:results.length,results,errors},null,2)+'\n');console.log(JSON.stringify({passed:true,checks:results.length,out},null,2));await browser.close();
})().catch(async e=>{if(page){await shot('failure').catch(()=>{});fs.writeFileSync(path.join(out,'failure-dom.txt'),await page.locator('body').innerText().catch(()=>''));}fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({passed:false,error:e.stack,results,errors},null,2)+'\n');console.error(e);if(browser)await browser.close();process.exit(1);});
