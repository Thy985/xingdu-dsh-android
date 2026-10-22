window.__ModuleLoader__.load({id:"dsh-android-mobile-shell",factory:(require)=>{
var module={exports:{}};var exports=module.exports;
const MOBILE_STYLE="/* Scoped to a mounted additive mobile component and the native presentation flag. */\n@media (max-width:600px) {\n  html[data-dsh-android-mobile][data-dsh-mobile-compose] [data-dsh-mobile-frame] {\n    grid-template-columns: 0 minmax(0,1fr) 0 !important;\n    padding-bottom:52px;\n    box-sizing:border-box;\n  }\n  html[data-dsh-android-mobile][data-dsh-mobile-compose] [data-shell-overlay] { z-index:70; }\n  html[data-dsh-android-mobile][data-dsh-mobile-compose] [data-dsh-mobile-frame] > [data-dsh-mobile-sidebar] {\n    position:absolute; inset:0 auto 52px 0 !important;\n    width:min(320px,calc(100vw - 44px)) !important; max-width:100%;\n    z-index:30; overflow-y:auto; box-shadow:8px 0 24px #0002;\n  }\n  html[data-dsh-android-mobile][data-dsh-mobile-compose] [data-sidebar-collapsed] > [data-dsh-mobile-sidebar] {\n    display:none !important; pointer-events:none;\n  }\n  html[data-dsh-android-mobile][data-dsh-mobile-compose] [data-dsh-mobile-sidebar-root] {\n    width:100% !important; min-height:400px;\n  }\n  html[data-dsh-android-mobile][data-dsh-mobile-compose] [data-conversation-content][data-content-phase='hero'] [data-conversation-scroll] {\n    justify-content:center;\n    /* Fall back to start when the IME leaves less room than the stack needs. */\n    justify-content:safe center;\n  }\n  html[data-dsh-android-mobile][data-dsh-mobile-compose] [data-content-phase='hero'] [data-composer-seat] { margin-top:0; }\n  html[data-dsh-android-mobile][data-dsh-mobile-compose] [data-dsh-mobile-hero-stack] { padding-bottom:0; }\n  html[data-dsh-android-mobile][data-dsh-mobile-compose] [data-sidebar-right-expand] { display:none; }\n  html[data-dsh-android-mobile][data-dsh-mobile-compose] [data-sidebar-right-panel='fullscreen'] { top:44px; bottom:52px; }\n  .dsh-mobile-navigation,.dsh-mobile-tool-actions {\n    position:absolute; display:flex; align-items:center; gap:4px;\n    padding:4px 8px; box-sizing:border-box;\n    color:var(--dsw-alias-label-primary,#111827); background:var(--dsw-alias-bg-base,#fff);\n    pointer-events:auto; border:0; border-top:1px solid var(--dsw-alias-border-l3,#ddd);\n  }\n  .dsh-mobile-navigation { left:0;right:0;bottom:0;height:52px;z-index:72; }\n  .dsh-mobile-navigation button { flex:1; }\n  .dsh-mobile-navigation button,.dsh-mobile-tool-actions button {\n    min-height:44px; min-width:44px; border:0; border-radius:8px;\n    padding:6px 10px; font:inherit; font-size:14px; line-height:1.2;\n    color:inherit; background:transparent; touch-action:manipulation;\n  }\n  .dsh-mobile-navigation button[aria-pressed='true'],.dsh-mobile-navigation button[aria-expanded='true'] {\n    background:var(--dsw-alias-bg-l2,#eef1f8); font-weight:600;\n  }\n  .dsh-mobile-navigation button:disabled,.dsh-mobile-tool-actions button:disabled { opacity:.45; }\n  .dsh-mobile-tool-actions { left:0;right:0;top:0;height:44px;padding-block:0;border-top:0;z-index:71; }\n  .dsh-mobile-tool-actions button { flex:1; padding-block:4px; }\n  .dsh-mobile-backdrop {\n    position:absolute; inset:0 0 52px min(320px,calc(100vw - 44px));\n    padding:0;border:0;border-radius:0;background:#0005;pointer-events:auto;z-index:70;\n  }\n  .dsh-mobile-tools-empty {\n    position:absolute;inset:0 0 52px;overflow-y:auto;box-sizing:border-box;\n    padding:24px 20px;z-index:71;pointer-events:auto;\n    color:var(--dsw-alias-label-primary,#111827);background:var(--dsw-alias-bg-base,#fff);\n  }\n  .dsh-mobile-tools-empty h2 { font-size:20px;margin:0 0 12px; }\n  .dsh-mobile-tools-empty h3 { font-size:16px;margin-top:28px; }\n  .dsh-mobile-tools-empty p { font-size:14px;line-height:1.7; }\n  .dsh-mobile-tools-empty button {\n    min-height:44px;min-width:44px;padding:8px 14px;border-radius:8px;\n    border:1px solid var(--dsw-alias-border-l3,#ddd);font:inherit;color:inherit;\n    background:var(--dsw-alias-bg-l2,#eef1f8);touch-action:manipulation;\n  }\n  .dsh-mobile-error {\n    position:absolute;left:8px;right:8px;bottom:56px;padding:10px;\n    z-index:73;color:#8b1111;background:#fff0ed;border:1px solid #d66;border-radius:8px;pointer-events:auto;\n  }\n}\n@media (max-width:600px) and (max-height:420px) {\n  html[data-dsh-android-mobile][data-dsh-mobile-compose] [data-dsh-mobile-hero-brand] { display:none; }\n  html[data-dsh-android-mobile][data-dsh-mobile-compose] [data-content-phase='hero'] [data-composer-input] { min-height:28px; }\n}\n@media (max-width:600px) and (max-height:300px) {\n  html[data-dsh-android-mobile][data-dsh-mobile-compose] [data-composer-seat] {\n    --dsh-composer-text-max-height:max(24px,calc(var(--dsh-mobile-height,100vh) - 216px));\n  }\n}\n\n/* Settings is an index/detail presentation over the unchanged official modal.\n * No child slot takeover: hidden forms keep their official React draft state. */\n@media (max-width:600px) {\n  html[data-dsh-android-mobile] [data-dsh-mobile-settings-flow] { overflow:hidden; }\n  html[data-dsh-android-mobile] [data-dsh-mobile-settings-flow] > nav {\n    padding:60px 12px 12px; min-height:0; flex:1; gap:12px;\n  }\n  html[data-dsh-android-mobile] [data-dsh-mobile-settings-flow] > nav > div:last-child {\n    flex-direction:column; overflow-x:hidden; overflow-y:auto; min-height:0;\n  }\n  html[data-dsh-android-mobile] [data-dsh-mobile-settings-flow] > nav button {\n    width:100%; min-height:48px; white-space:normal; flex-shrink:0;\n  }\n  html[data-dsh-android-mobile] [data-dsh-mobile-settings-flow][data-dsh-mobile-settings-view='index'] > div {\n    flex:0; min-height:0;\n  }\n  html[data-dsh-android-mobile] [data-dsh-mobile-settings-view='index'] > div > div:last-child { display:none; }\n  html[data-dsh-android-mobile] [data-dsh-mobile-settings-view='detail'] > nav { display:none; }\n  html[data-dsh-android-mobile] [data-dsh-mobile-settings-flow] > div > div:first-child {\n    position:absolute; top:0; left:0; right:0; height:56px; padding:6px 8px;\n    align-items:center; background:var(--dsw-alias-bg-layer-2,#fff); z-index:1;\n  }\n  html[data-dsh-android-mobile] [data-dsh-mobile-settings-view='detail'] > div { padding-top:56px; }\n  html[data-dsh-android-mobile] [data-dsh-mobile-settings-flow] > div > div:first-child > div {\n    margin-left:0; flex:1; justify-content:flex-start; min-width:0;\n  }\n  .dsh-mobile-settings-flow { display:flex; align-items:center; gap:8px; min-width:0; }\n  .dsh-mobile-settings-flow button {\n    color:inherit; background:transparent; border:0; font:inherit; min-height:44px;\n    padding:6px; border-radius:8px; flex:none;\n  }\n  .dsh-mobile-settings-location { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:14px; }\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] [data-dsh-mobile-setting-rowHead],\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] [data-dsh-mobile-setting-editorHeader],\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] [data-dsh-mobile-setting-modelListHead],\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] [data-dsh-mobile-setting-modelReasoningHead],\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] [data-dsh-mobile-setting-groupTitleRow] {\n    flex-direction:column; align-items:stretch; gap:8px;\n  }\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] [data-dsh-mobile-setting-rowIdentity],\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] [data-dsh-mobile-setting-fieldLabel] { flex-wrap:wrap; }\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] [data-dsh-mobile-setting-rowActions],\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] [data-dsh-mobile-setting-editorActions] {\n    margin-left:0; display:flex; flex-wrap:wrap; justify-content:flex-start;\n  }\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] [data-dsh-mobile-setting-rowActions] > *,\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] [data-dsh-mobile-setting-editorActions] > * { flex:1 1 auto; }\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] [data-dsh-mobile-setting-modelRow],\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] [data-dsh-mobile-setting-modelAdvanced],\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] [data-dsh-mobile-setting-modelReasoningRow],\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] [data-dsh-mobile-setting-cards],\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] [data-dsh-mobile-setting-details] {\n    grid-template-columns:minmax(0,1fr);\n  }\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] [data-dsh-mobile-setting-editor],\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] [data-dsh-mobile-setting-addCard],\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] [data-dsh-mobile-setting-setupCard] {\n    padding:12px; min-width:0;\n  }\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] [data-dsh-mobile-setting-tabs] { flex-wrap:wrap; }\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] [data-dsh-mobile-setting-rowName],\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] [data-dsh-mobile-setting-cardTitle] {\n    overflow-wrap:anywhere; white-space:normal;\n  }\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] [data-dsh-mobile-setting-keyInputFilled] { padding-right:48px; }\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] input,\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] select { box-sizing:border-box; }\n  html[data-dsh-android-mobile] [data-shortcut-modal='settings'] summary {\n    min-height:44px; display:flex; align-items:center; box-sizing:border-box;\n  }\n}\n";
/* Independent Android-only composition. Official slots and services own business state.
 * No React internals, DOM reparenting, synthetic clicks, native bridge or private store writes.
 */
const React = require('react');
const {createElement: h, useEffect, useLayoutEffect, useState, useSyncExternalStore, useRef} = React;
const PHONE = '(max-width: 600px)';
function MobileChrome({layout, sidebarRight}) {
  const [view, setView] = useState({phone:false, drawer:false, tools:false});
  const [error, setError] = useState('');
  const [toolsHelp, setToolsHelp] = useState(false);
  const host = useRef(null);
  const mounted = useSyncExternalStore(sidebarRight.mounted.subscribe, sidebarRight.mounted.getSnapshot);
  useLayoutEffect(() => {
    const root = document.documentElement;
    const media = matchMedia(PHONE);
    let frame;
    let queue = false;
    let active = true;
    const sync = () => {
      queue = false;
      if (!active) return;
      // The official attributes report geometry; expanded business state is read via its public service.
      frame = document.querySelector('[data-dsh-mobile-frame]');
      const phone = media.matches && root.hasAttribute('data-dsh-android-mobile') && !!frame;
      const drawer = phone && !frame.hasAttribute('data-sidebar-collapsed');
      const tools = phone && sidebarRight.isExpanded();
      root.toggleAttribute('data-dsh-mobile-compose', phone);
      root.toggleAttribute('data-dsh-mobile-drawer', drawer);
      setView(old => old.phone===phone && old.drawer===drawer && old.tools===tools ? old : {phone,drawer,tools});
    };
    const schedule = () => { if (!queue) { queue=true; requestAnimationFrame(sync); } };
    const observer = new MutationObserver(schedule);
    observer.observe(root, {subtree:true,childList:true,attributes:true,
      attributeFilter:['data-dsh-android-mobile','data-dsh-mobile-frame','data-sidebar-collapsed','data-rightbar-fullscreen','data-rightbar-collapsed']});
    media.addEventListener('change', schedule);
    let previousSession = sidebarRight.mounted.getSnapshot();
    const unmount = sidebarRight.mounted.subscribe(() => {
      const next = sidebarRight.mounted.getSnapshot();
      if (next !== previousSession && media.matches && root.hasAttribute('data-dsh-android-mobile')
          && frame && !frame.hasAttribute('data-sidebar-collapsed')) layout.toggleSidebar();
      previousSession = next;
      schedule();
    });
    const unpanel = layout.panelInfo.subscribe(schedule);
    sync();
    return () => { active=false; observer.disconnect(); media.removeEventListener('change',schedule); unmount(); unpanel();
      root.removeAttribute('data-dsh-mobile-compose'); root.removeAttribute('data-dsh-mobile-drawer'); };
  }, [layout, sidebarRight]);
  useEffect(() => {
    if (!view.phone) return;
    const close = event => {
      if (event.key !== 'Escape' || event.defaultPrevented) return;
      // Let an official modal own Escape first; don't steal editor/terminal keystrokes.
      if (document.querySelector('[role="dialog"][aria-modal="true"]')) return;
      if (view.drawer) { layout.toggleSidebar(); event.preventDefault(); }
    };
    document.addEventListener('keydown',close);
    return () => document.removeEventListener('keydown',close);
  }, [view.phone,view.drawer,layout]);
  function run(action) { try { setError(''); action(); } catch (e) { setError(String(e.message || e)); } }
  function closeDrawer() { if (view.drawer) layout.toggleSidebar(); }
  function conversation() { setToolsHelp(false); run(() => { closeDrawer(); layout.selectPanel(null); if (sidebarRight.isExpanded()) sidebarRight.toggleExpanded(); }); }
  function drawer() { setToolsHelp(false); run(() => { if (sidebarRight.isExpanded()) sidebarRight.toggleExpanded(); layout.toggleSidebar(); }); }
  function tools() { run(() => {
    closeDrawer(); layout.selectPanel(null);
    // The official service cannot act until a Session owns a mounted surface.
    // Keep navigation actionable without manufacturing a Session or launching a shell.
    if (sidebarRight.mounted.getSnapshot() === undefined) { setToolsHelp(true); return; }
    setToolsHelp(false);
    if (!sidebarRight.isExpanded()) sidebarRight.toggleExpanded();
  }); }
  function open(kind) { run(() => { closeDrawer(); layout.selectPanel(null); sidebarRight.openTab(kind); }); }
  if (!view.phone) return null;
  const showingToolsHelp = toolsHelp && mounted===undefined;
  const button = (text, onClick, props={}) => h('button',{type:'button',onClick,...props},text);
  return h(React.Fragment,null,
    h('style',null,MOBILE_STYLE),
    view.drawer && h('button',{className:'dsh-mobile-backdrop',type:'button','aria-label':'关闭会话抽屉',onClick:()=>run(closeDrawer)}),
    h('nav',{ref:host,className:'dsh-mobile-navigation','aria-label':'手机主导航','data-dsh-mobile-navigation':''},
      button(view.drawer?'收起会话':'会话',drawer,{'aria-expanded':view.drawer,'aria-label':'会话抽屉'}),
      button('对话',conversation,{'aria-pressed':!view.drawer&&!view.tools&&!showingToolsHelp,'aria-label':'返回对话'}),
      button('工具',tools,{'aria-pressed':view.tools||showingToolsHelp,'aria-label':'打开工具面板'})),
    showingToolsHelp && !view.drawer && h('section',{
      className:'dsh-mobile-tools-empty','data-dsh-mobile-tools-empty':'','aria-label':'工具使用指引'},
      h('h2',null,'工具需要一个会话'),
      h('p',null,'先在官方会话栏选择工作区或打开会话，再使用文件、预览和终端。不会自动创建会话或执行命令。'),
      button('选择会话或工作区',drawer),
      h('h3',null,'插件与模型'),
      h('p',null,'会话栏 → 账号菜单 → 设置，可查看官方内置插件与模型配置。这里不会自动安装插件、保存密钥或更改审批策略。'),
      button('返回对话',conversation)),
    view.tools && !view.drawer && h('div',{className:'dsh-mobile-tool-actions','data-dsh-mobile-tool-actions':'','aria-label':'手机工具切换'},
      button('返回对话',conversation),button('文件',()=>open('files'),{disabled:mounted===undefined}),
      button('终端',()=>open('terminal'),{disabled:mounted===undefined})),
    error && h('div',{className:'dsh-mobile-error',role:'alert'},error));
}
exports.inject = ['slots','layout','sidebarRight'];
exports.apply = function apply(ctx) {
  // The root and its slot declarations stay owned by the official layout plugin.
  ctx.effect(() => ctx.slots.inject('shell.overlay', () => ctx.slots.register({
    name:'shell.overlay',id:'android.mobile-navigation',order:1000,
    inject:()=>({layout:ctx.layout,sidebarRight:ctx.sidebarRight})
  },MobileChrome)), 'android mobile shell: additive navigation');
  ctx.effect(() => ctx.slots.inject('settings.action', () => ctx.slots.register({
    name:'settings.action',id:'android.mobile-settings-flow',order:-1000
  },MobileSettingsFlow)), 'android mobile shell: settings presentation');
};

// Only presentation state is local. Native section buttons still select their
// official slot; returning to the index hides, rather than unmounts, its form.
function MobileSettingsFlow() {
  const host = useRef(null);
  const [view,setView] = useState({phone:false,index:true,label:''});
  useLayoutEffect(() => {
    const panel = host.current && host.current.closest('[data-shortcut-modal="settings"]');
    const nav = panel && panel.querySelector(':scope > nav');
    if (!panel || !nav) return; // Unknown upstream structure: leave official UI intact.
    const media=matchMedia(PHONE),root=document.documentElement;
    let index=true;
    const sync=()=>{
      const phone=media.matches && root.hasAttribute('data-dsh-android-mobile');
      const current=nav.querySelector('button[aria-current="true"]');
      panel.toggleAttribute('data-dsh-mobile-settings-flow',phone);
      if(phone)panel.setAttribute('data-dsh-mobile-settings-view',index?'index':'detail');
      else panel.removeAttribute('data-dsh-mobile-settings-view');
      setView({phone,index,label:current ? current.textContent.trim() : ''});
    };
    const select=event=>{
      const button=event.target.closest('button');
      if(!button || !nav.contains(button) || !media.matches || !root.hasAttribute('data-dsh-android-mobile'))return;
      index=false;sync();
      // The selected official nav button is now hidden. Keep keyboard focus
      // inside the same official modal without touching its editor or trap.
      requestAnimationFrame(()=>host.current?.querySelector('button')?.focus());
    };
    const back=()=>{index=true;sync();nav.querySelector('button[aria-current="true"]')?.focus();};
    host.current.addEventListener('dsh-mobile-settings-index',back);
    nav.addEventListener('click',select);
    const observer=new MutationObserver(sync);
    observer.observe(root,{attributes:true,attributeFilter:['data-dsh-android-mobile']});
    observer.observe(nav,{subtree:true,attributes:true,attributeFilter:['aria-current'],childList:true});
    media.addEventListener('change',sync);sync();
    const node=host.current;
    return()=>{observer.disconnect();media.removeEventListener('change',sync);nav.removeEventListener('click',select);
      node.removeEventListener('dsh-mobile-settings-index',back);
      panel.removeAttribute('data-dsh-mobile-settings-flow');panel.removeAttribute('data-dsh-mobile-settings-view');};
  },[]);
  return h('div',{ref:host,className:'dsh-mobile-settings-flow'},
    view.phone && !view.index && h('button',{type:'button','aria-label':'返回设置分类',
      onClick:()=>host.current.dispatchEvent(new Event('dsh-mobile-settings-index'))},'‹ 设置分类'),
    view.phone && h('span',{className:'dsh-mobile-settings-location'},view.index?'设置分类':view.label));
}

return module.exports;
}});
