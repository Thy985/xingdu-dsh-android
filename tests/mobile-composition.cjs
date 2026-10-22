#!/usr/bin/env node
/* Disposable loopback DSH only. Does not send a model request or run a terminal command. */
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const fs=require('node:fs'), path=require('node:path'), assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..'), out=path.join(root,'runtime-build/mobile-composition');
fs.mkdirSync(out,{recursive:true});
const url=process.env.DSH_TEST_URL;
assert(url && new URL(url).hostname==='127.0.0.1','Disposable loopback instance required');
const results=[],errors=[];
let page,browser;
const css=fs.readFileSync(path.join(root,'app/src/main/assets/mobile/mobile.css'),'utf8');
const js=fs.readFileSync(path.join(root,'app/src/main/assets/mobile/mobile.js'),'utf8');
const shot=async name=>page.screenshot({path:path.join(out,name+'.png'),fullPage:true});
const settle=()=>page.waitForTimeout(400);
const g=()=>page.evaluate(()=>{
 const b=e=>{if(!e)return null;const r=e.getBoundingClientRect();return{x:r.x,y:r.y,w:r.width,h:r.height};};
 return{width:innerWidth,height:innerHeight,scroll:document.documentElement.scrollWidth,
   center:b(document.querySelector('[data-dsh-mobile-center]')),composer:b(document.querySelector('[data-composer-card]')),
   hero:b(document.querySelector('[data-dsh-mobile-hero-stack]')),conversationScroll:b(document.querySelector('[data-conversation-scroll]')),
   nav:b(document.querySelector('[data-dsh-mobile-navigation]')),panel:b(document.querySelector('[data-sidebar-right-panel][data-sidebar-right-open]'))};
 });
(async()=>{
 browser=await chromium.launch({executablePath:process.env.CHROMIUM||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
 const context=await browser.newContext({viewport:{width:360,height:640},isMobile:true,hasTouch:true,locale:'zh-CN'});
 page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
 page.on('console',m=>{if(m.type()==='error' && /slot entry crashed|TypeError|ReferenceError|mobile-shell/.test(m.text()))errors.push(m.text());});
 await page.goto(url);await page.waitForSelector('[data-shell-overlay]',{timeout:60000});
 await require('./mobile-test-setup.cjs').dismissOnboarding(page);
 await page.addStyleTag({content:css});await page.evaluate(js);
 await page.waitForSelector('[data-dsh-mobile-navigation]',{timeout:20000});await settle();
 let geometry=await g();assert(Math.abs(geometry.center.w-360)<1,'Conversation uses the whole screen, no permanent rail');
 assert.equal(await page.locator('[data-dsh-mobile-sidebar]').isVisible(),false);
 results.push({test:'full-width conversation and additive official slot',geometry});
 await page.getByRole('button',{name:'会话抽屉',exact:true}).click();await settle();
 assert.equal(await page.locator('[data-dsh-mobile-sidebar]').isVisible(),true);
 assert(Math.abs((await g()).center.w-360)<1,'Drawer does not squeeze the conversation');
 await shot('conversation-drawer');
 await page.getByRole('button',{name:'关闭会话抽屉',exact:true}).click();await settle();
 assert.equal(await page.locator('[data-dsh-mobile-sidebar]').isVisible(),false);
 results.push({test:'official drawer actions and outside dismiss',passed:true});
 // Open only a disposable workspace using the actual official picker; this creates a blank Session, no model call.
 const workspace=process.env.DSH_TEST_WORKSPACE||path.join(root,'runtime-build/composition-workspace');
 assert(path.resolve(workspace).startsWith(path.join(root,'runtime-build')+path.sep));fs.mkdirSync(workspace,{recursive:true});
 fs.writeFileSync(path.join(workspace,'mobile-test.txt'),'手机文件预览验证：这是本机测试文件，不调用模型。\n');
 const chooser=page.getByRole('button',{name:'选择工作区',exact:true});
 if(await chooser.count() && !await page.locator('[data-composer-input][contenteditable=true]').count()){
  await chooser.click();await settle();
  const picker=page.getByRole('dialog',{name:'选择工作区目录',exact:true});
  if(!await picker.count()){
   const folder=page.getByRole('button',{name:/添加工作区|打开文件夹|打开目录/});
   if(await folder.count())await folder.first().click();
   else await page.getByRole('menuitem',{name:/添加工作区|打开文件夹|打开目录/}).click();
  }
  await picker.getByRole('button',{name:'编辑路径',exact:true}).click();
  await picker.getByRole('textbox',{name:'编辑路径',exact:true}).fill(workspace);
  await picker.getByRole('textbox',{name:'编辑路径',exact:true}).press('Enter');
  await picker.getByRole('button',{name:'打开',exact:true}).click();await picker.waitFor({state:'hidden'});
 }
 const input=page.locator('[data-composer-input][contenteditable=true]');await input.waitFor();
 const editor=await input.elementHandle();const draft='移动组合草稿：中文输入，不发送。';await input.fill(draft);
 for(const [width,height] of [[320,640],[360,320],[393,640],[600,800]]){
  await page.setViewportSize({width,height});await settle();geometry=await g();
  assert(geometry.scroll<=width+1,'No page-wide overflow');assert(geometry.center.w>=width-1,'Conversation full width');
  assert(geometry.composer.x>=0 && geometry.composer.x+geometry.composer.w<=width+1,'Composer fits width');
  assert(geometry.composer.y>=0 && geometry.composer.y+geometry.composer.h<=geometry.nav.y+1,'Composer clears navigation and reduced viewport');
  assert(Math.abs(geometry.hero.y+geometry.hero.h/2-(geometry.conversationScroll.y+geometry.conversationScroll.h/2))<=2,'Empty conversation is vertically centered');
  results.push({test:'centered empty conversation '+width+'x'+height,geometry});
 }
 await page.setViewportSize({width:360,height:320});await settle();
 const longDraft=Array.from({length:24},(_,i)=>'长草稿 '+i+'：中文，不发送。').join('\n');
 await input.fill(longDraft);await settle();geometry=await g();
 const scroll=await page.locator('[data-dsh-mobile-draft-scroll]').evaluate(e=>({client:e.clientHeight,scroll:e.scrollHeight}));
 assert(scroll.scroll>scroll.client,'Long draft scrolls inside input');
 assert(geometry.composer.y>=0 && geometry.composer.y+geometry.composer.h<=geometry.nav.y+1,'Long draft actions stay above navigation in short viewport');
 results.push({test:'long draft in reduced-height mobile composition',geometry,scroll});
 await input.fill(draft);
 await page.setViewportSize({width:393,height:700});await settle();await shot('phone-conversation');
 await page.getByRole('button',{name:'打开工具面板',exact:true}).click();await settle();
 await page.waitForSelector('[data-sidebar-right-panel=fullscreen][data-sidebar-right-open]');
 assert.equal(await page.locator('[data-dsh-mobile-tool-actions]').isVisible(),true);
 await shot('phone-tools');results.push({test:'official fullscreen tool surface',geometry:await g()});
 await page.locator('[data-dsh-mobile-tool-actions]').getByRole('button',{name:'文件',exact:true}).click();await settle();
 assert.equal(await page.getByRole('alert').count(),0,'No controller error opening files');
 await page.getByText('mobile-test.txt',{exact:true}).click();await settle();
 await page.getByText('手机文件预览验证：这是本机测试文件，不调用模型。',{exact:false}).first().waitFor();
 await shot('phone-files');results.push({test:'files and real file preview via official controllers',passed:true});
 await page.locator('[data-dsh-mobile-tool-actions]').getByRole('button',{name:'终端',exact:true}).click();await page.waitForTimeout(1200);
 assert.equal(await page.getByRole('alert').count(),0,'No controller error opening terminal');
 await shot('phone-terminal');results.push({test:'terminal via official openTab; no command sent',passed:true});
 await page.getByRole('button',{name:'返回对话',exact:true}).first().click();await settle();
 assert.equal(await page.locator('[data-sidebar-right-panel][data-sidebar-right-open]').count(),0);
 assert.equal(await editor.evaluate(e=>e.isConnected),true,'Tool navigation must keep original editor DOM');
 assert.equal(await input.innerText(),draft,'Tool navigation must preserve draft');
 results.push({test:'return to chat preserves official editor and draft',passed:true});
 await page.getByRole('button',{name:'会话抽屉',exact:true}).click();await settle();
 await page.getByRole('button',{name:'账号菜单',exact:true}).click();
 await page.getByRole('menuitem',{name:/设置/}).click();
 await page.waitForSelector('[data-shortcut-modal=settings]');
 for(const width of [320,393,600]){
  await page.setViewportSize({width,height:640});await settle();
  const dialog=await page.locator('[data-shortcut-modal=settings]').boundingBox();
  const navigation=await page.locator('[data-shortcut-modal=settings] > nav').boundingBox();
  assert(dialog.x>=-1 && dialog.x+dialog.width<=width+1,'Settings fits phone');
  assert(navigation.width>=width-20,'Settings category index fills phone width');
  results.push({test:'settings in mobile composition '+width,dialog,navigation});
 }
 await page.getByRole('button',{name:/^(关闭设置|关闭)$/}).click();
 await page.getByRole('button',{name:'返回对话',exact:true}).first().click();await settle();
 await page.evaluate(()=>window.__dshAndroidMobile.setEnabled(false));await settle();
 assert.equal(await page.locator('[data-dsh-mobile-navigation]').count(),0);
 assert.equal(await editor.evaluate(e=>e.isConnected),true);assert.equal(await input.innerText(),draft);
 await page.evaluate(()=>window.__dshAndroidMobile.setEnabled(true));await settle();
 results.push({test:'composition can be disabled without remounting editor',passed:true});
 await page.setViewportSize({width:1280,height:800});await settle();
 assert.equal(await page.locator('[data-dsh-mobile-navigation]').count(),0);
 const desktop=await g();await page.evaluate(()=>window.__dshAndroidMobile.setEnabled(false));await settle();
 assert.deepEqual(await g(),desktop,'Desktop layout unchanged');
 results.push({test:'desktop geometry unchanged with mobile addon loaded',geometry:desktop});
 assert.equal(errors.length,0,'No uncaught page errors: '+errors.join('; '));
 results.push({test:'uncaught page errors',errors});
 fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({passed:true,results},null,2)+'\n');
 console.log(JSON.stringify({passed:true,checks:results.length,out},null,2));await browser.close();
})().catch(async e=>{
 if(page){await shot('failure').catch(()=>{});fs.writeFileSync(path.join(out,'failure-dom.txt'),await page.locator('body').innerText().catch(()=>''));}
 fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({passed:false,error:e.stack,errors,results},null,2)+'\n');
 console.error(e);if(browser)await browser.close();process.exit(1);
});
