#!/usr/bin/env node
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..');let registration;
const manifest=JSON.parse(fs.readFileSync(path.join(root,'mobile-shell/package.json')));
const generated=fs.readFileSync(path.join(root,'mobile-shell/lib/client.js'),'utf8');
vm.runInNewContext(generated,{window:{__ModuleLoader__:{load:r=>registration=r}}});
assert.equal(registration.id,manifest.name);
let reactLoads=0;
const plugin=registration.factory(name=>{assert.equal(name,'react','Only the shared platform React is required');reactLoads++;return{};});
assert.equal(reactLoads,1);
assert.deepEqual(Array.from(plugin.inject),['slots','layout','sidebarRight']);
let entry,component,disposed=0;const effects=[],entries=[];const layout={},sidebarRight={};
const ctx={layout,sidebarRight,effect:cb=>{const end=cb();effects.push(end);return end;},slots:{
 inject:(name,cb)=>{assert(['shell.overlay','settings.action'].includes(name));return cb();},
 register:(options,render)=>{entries.push({options,render});if(options.name==='shell.overlay'){entry=options;component=render;}return()=>disposed++;}
}};
plugin.apply(ctx);
assert.equal(entry.name,'shell.overlay');assert.equal(entry.id,'android.mobile-navigation');
assert.equal(typeof entry.inject,'function','Renderer requires an inject factory, not a raw object');
const face=entry.inject();assert.equal(face.layout,layout);assert.equal(face.sidebarRight,sidebarRight);
assert.equal(typeof component,'function');assert.equal(entry.children,undefined,'Never take ownership of official child slots');
assert.equal(entries.length,2);
assert.equal(entries[1].options.name,'settings.action');
assert.equal(entries[1].options.children,undefined,'Settings keeps its official child slots');
assert.equal(typeof entries[1].render,'function');
for(const end of effects)end();assert.equal(disposed,2,'Remove both registrations when addon scope is disposed');
for(const relative of ['package.json','lib/index.js','lib/client.js']){
 assert.equal(fs.readFileSync(path.join(root,'mobile-shell',relative),'utf8'),
  fs.readFileSync(path.join(root,'app/src/main/assets/addons/mobile-shell',relative),'utf8'),'Asset must match '+relative);
}
assert(!/addJavascriptInterface|appendChild|insertBefore|\.click\(|innerHTML\s*=/.test(generated),'No native bridge, DOM reparenting or synthetic clicks');
console.log('PASS: independent module identity, shared React, service injections, inject factory, additive slot ownership, disposal, 3 asset matches, no DOM reparenting/native bridge');
