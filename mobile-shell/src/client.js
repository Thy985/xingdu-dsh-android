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
