#!/usr/bin/env node
/* Disposable official DSH UI; no model requests or terminal commands. */
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..'),out=path.join(root,'runtime-build/feedback-validation');
fs.mkdirSync(out,{recursive:true});
const url=process.env.DSH_TEST_URL;assert(url&&new URL(url).hostname==='127.0.0.1');
let browser,page;const results=[],errors=[];const settle=()=>page.waitForTimeout(350);
async function hit(target){
 const box=await target.boundingBox();assert(box&&box.width>=44&&box.height>=44,'44px touch target');
 assert(await target.evaluate(e=>{const r=e.getBoundingClientRect(),hit=document.elementFromPoint(r.x+r.width/2,r.y+r.height/2);return e===hit||e.contains(hit);}),'Touch target is not covered');
 await target.tap();await settle();
}
(async()=>{
 browser=await chromium.launch({executablePath:process.env.CHROMIUM||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
 page=await browser.newPage({viewport:{width:393,height:700},locale:'zh-CN',isMobile:true,hasTouch:true});
 page.on('pageerror',e=>errors.push(e.message));
 await page.goto(url);await page.waitForSelector('[data-shell-overlay]',{timeout:60000});
 await require('./mobile-test-setup.cjs').dismissOnboarding(page);
 await page.addStyleTag({content:fs.readFileSync(path.join(root,'app/src/main/assets/mobile/mobile.css'),'utf8')});
 await page.evaluate(fs.readFileSync(path.join(root,'app/src/main/assets/mobile/mobile.js'),'utf8'));await settle();
 const tools=page.getByRole('button',{name:'打开工具面板',exact:true});await tools.waitFor();
 const initial=await page.evaluate(()=>{const b=e=>{const r=e?.getBoundingClientRect();return r?{x:r.x,y:r.y,w:r.width,h:r.height}:null;};return{toolsDisabled:document.querySelector('[aria-label="打开工具面板"]').disabled,hero:b(document.querySelector('[data-dsh-mobile-hero-stack]')),scroll:b(document.querySelector('[data-conversation-scroll]'))};});
 results.push({test:'initial state',initial});
 assert(await tools.isEnabled(),'Tools must explain prerequisites instead of silently disabling before a session exists');
 await hit(tools);assert(await page.locator('[data-dsh-mobile-tools-empty]').isVisible());
 assert(await page.getByText('工具需要一个会话',{exact:true}).isVisible());
 await hit(page.getByRole('button',{name:'选择会话或工作区',exact:true}));
 assert(await page.locator('[data-dsh-mobile-sidebar]').isVisible());
 await hit(page.getByRole('button',{name:'关闭会话抽屉',exact:true}));
 results.push({test:'no-session tools tap opens actionable official-workspace guidance',passed:true});
 // Create only a blank Session using the official workspace picker.
 const workspace=path.join(root,'runtime-build/feedback-workspace');fs.mkdirSync(workspace,{recursive:true});
 await page.getByRole('button',{name:'选择工作区',exact:true}).click();await settle();
 const picker=page.getByRole('dialog',{name:'选择工作区目录',exact:true});
 if(!await picker.count()){
  const folder=page.getByRole('button',{name:/添加工作区|打开文件夹|打开目录/});
  if(await folder.count())await folder.first().click();else await page.getByRole('menuitem',{name:/添加工作区|打开文件夹|打开目录/}).click();
 }
 for(const height of [700,320]){
  await page.setViewportSize({width:393,height});await settle();
  const box=await picker.boundingBox();
  assert(box.y>=-1&&box.y+box.height<=height+1,'Official picker stays in the viewport');
  assert(Math.abs(box.y+box.height/2-height/2)<=2,'Official modal remains vertically centered');
  results.push({test:'official workspace dialog centered at height '+height,box,passed:true});
 }
 await page.setViewportSize({width:393,height:700});await settle();
 await picker.getByRole('button',{name:'编辑路径',exact:true}).click();
 await picker.getByRole('textbox',{name:'编辑路径',exact:true}).fill(workspace);
 await picker.getByRole('textbox',{name:'编辑路径',exact:true}).press('Enter');
 await picker.getByRole('button',{name:'打开',exact:true}).click();await picker.waitFor({state:'hidden'});await settle();
 const input=page.locator('[data-composer-input][contenteditable=true]');await input.waitFor();
 const editor=await input.elementHandle();await input.fill('居中与工具触控回归，不发送。');
 for(const [width,height] of [[320,640],[360,640],[393,700],[600,800],[360,320],[360,240]]){
  await page.setViewportSize({width,height});await settle();
  const geometry=await page.evaluate(()=>{const b=e=>{const r=e.getBoundingClientRect();return{x:r.x,y:r.y,w:r.width,h:r.height};};return{hero:b(document.querySelector('[data-dsh-mobile-hero-stack]')),scroll:b(document.querySelector('[data-conversation-scroll]')),nav:b(document.querySelector('[data-dsh-mobile-navigation]')),card:b(document.querySelector('[data-composer-card]'))};});
  const {hero,scroll,card,nav}=geometry;
  assert(card.y>=-1&&card.y+card.h<=nav.y+1,'Composer remains above navigation in short viewport');
  if(hero.h<=scroll.h)assert(Math.abs(hero.y+hero.h/2-(scroll.y+scroll.h/2))<=2,'Empty conversation stack vertically centered');
  await hit(tools);await page.waitForSelector('[data-sidebar-right-panel=fullscreen][data-sidebar-right-open]');
  await hit(page.locator('[data-dsh-mobile-tool-actions]').getByRole('button',{name:'文件',exact:true}));
  await hit(page.locator('[data-dsh-mobile-tool-actions]').getByRole('button',{name:'返回对话',exact:true}));
  assert(await editor.evaluate(e=>e.isConnected));assert.equal(await input.innerText(),'居中与工具触控回归，不发送。');
  results.push({test:'center and real touch navigation '+width+'x'+height,geometry,passed:true});
 }
 await page.setViewportSize({width:393,height:700});await settle();
 await page.screenshot({path:path.join(out,'centered-conversation.png')});
 await hit(tools);await page.screenshot({path:path.join(out,'touch-tools.png')});
 assert.deepEqual(errors,[]);results.push({test:'uncaught errors',errors});
 fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({passed:true,checks:results.length,results},null,2)+'\n');
 console.log(JSON.stringify({passed:true,checks:results.length,out}));await browser.close();
})().catch(async e=>{if(page)await page.screenshot({path:path.join(out,'failure.png')}).catch(()=>{});fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({passed:false,error:e.stack,results,errors},null,2)+'\n');console.error(e.message);if(browser)await browser.close();process.exit(1);});
