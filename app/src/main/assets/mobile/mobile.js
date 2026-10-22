/* Android presentation adapter for bundled DSH 0.2.1-rc.2.
 * Uses official data attributes first; CSS-module *local names* only after
 * checking their owning structure. No hash matching, text matching, React
 * internals, synthetic clicks, key handlers, or edits to contenteditable DOM.
 */
(function () {
  'use strict';
  if (window.__dshAndroidMobile) return;
  var root = document.documentElement;
  var enabled = true;
  var queued = false;
  var viewport = document.querySelector('meta[name="viewport"]');
  var originalViewport = viewport ? viewport.getAttribute('content') : null;
  var observer;
  var waitingForBody = false;

  function localName(element, name) {
    if (!element || !element.classList) return false;
    for (var i = 0; i < element.classList.length; i++) {
      var token = element.classList[i];
      if (token.indexOf('_' + name + '_') === 0 || token.slice(-(name.length + 1)) === '_' + name) return true;
    }
    return false;
  }
  function mark(element, name) {
    if (element && !element.hasAttribute('data-dsh-mobile-' + name)) {
      element.setAttribute('data-dsh-mobile-' + name, '');
    }
  }
  function childWithName(parent, name) {
    for (var i = 0; i < parent.children.length; i++) {
      if (localName(parent.children[i], name)) return parent.children[i];
    }
    return null;
  }
  function annotate() {
    queued = false;
    if (!enabled) return;
    var overlays = document.querySelectorAll('[data-shell-overlay]');
    for (var i = 0; i < overlays.length; i++) {
      var frame = overlays[i].parentElement;
      var sidebar = childWithName(frame, 'sidebarCol');
      var center = childWithName(frame, 'centerCol');
      // Fail closed if the upstream shell contract changes.
      if (!sidebar || !center || !frame.querySelector('[data-rightbar-col]')) continue;
      mark(frame, 'frame'); mark(sidebar, 'sidebar'); mark(center, 'center');
      var sidebarRoot = sidebar.querySelector('[data-slot="sidebar"]');
      sidebarRoot = sidebarRoot && sidebarRoot.firstElementChild;
      if (!sidebarRoot) sidebarRoot = sidebar.firstElementChild;
      if (sidebarRoot && localName(sidebarRoot, 'root')) mark(sidebarRoot, 'sidebar-root');
    }
    var bodies = document.querySelectorAll('[data-conversation-content]');
    for (var j = 0; j < bodies.length; j++) {
      if (bodies[j].querySelector('[data-composer-card]')) mark(bodies[j], 'body');
    }
    var headings = document.querySelectorAll('[data-conversation-header-leading]');
    for (var h = 0; h < headings.length; h++) {
      var header = headings[h].closest('header');
      if (header) mark(header, 'header');
    }
    var cards = document.querySelectorAll('[data-composer-card]');
    for (var k = 0; k < cards.length; k++) {
      var card = cards[k];
      var row = childWithName(card, 'row');
      var draftScroll = childWithName(card, 'scroll');
      if (row) mark(row, 'input-row');
      if (draftScroll) mark(draftScroll, 'draft-scroll');
      var hero = card.parentElement;
      for (var hop = 0; hero && hop < 5 && !localName(hero, 'composerHero') && !hero.hasAttribute('data-composer-seat'); hop++) hero = hero.parentElement;
      if (hero && localName(hero, 'composerHero')) {
        mark(hero, 'hero-stack');
        var brand = hero.firstElementChild;
        var brandStack = brand && childWithName(brand, 'stack');
        if (brand && localName(brand, 'root') && brandStack && childWithName(brandStack, 'headline')) mark(brand, 'hero-brand');
      }
      var ancestor = card.parentElement;
      // The owning seat, not arbitrary surrounding panels or the draft itself.
      for (var depth = 0; ancestor && depth < 6; depth++, ancestor = ancestor.parentElement) {
        if (ancestor.hasAttribute('data-composer-seat')) { mark(ancestor, 'composer-seat'); break; }
      }
    }
    // Scoped to the official settings modal. These are CSS module LOCAL names,
    // not hashes; never inspect credentials, input values or private stores.
    var settings = document.querySelector('[data-shortcut-modal="settings"]');
    if (settings && settings.querySelector(':scope > nav button[aria-current]')) {
      var names = ['rowHead','rowIdentity','rowName','rowActions','editor','editorHeader','editorActions',
        'addCard','setupCard','modelRow','modelAdvanced','modelReasoningRow','modelReasoningHead',
        'modelListHead','fieldLabel','keyInputFilled','cards','tabs','groupTitleRow','cardMainRow','cardTitle','details'];
      var nodes = settings.querySelectorAll('[class]');
      for (var n = 0; n < nodes.length; n++) {
        for (var m = 0; m < names.length; m++) {
          if (localName(nodes[n], names[m])) { mark(nodes[n], 'setting-' + names[m]); break; }
        }
      }
    }
  }
  function schedule() {
    if (!enabled || queued) return;
    queued = true;
    requestAnimationFrame(annotate);
  }
  function observeBody() {
    waitingForBody = false;
    if (!enabled || !document.body) return;
    observer.observe(document.body, { childList: true, subtree: true });
    schedule();
  }
  function waitForBody() {
    if (document.body) {
      observeBody();
      return;
    }
    if (!waitingForBody) {
      waitingForBody = true;
      document.addEventListener('DOMContentLoaded', observeBody, { once: true });
    }
  }
  function measure() {
    if (!enabled) return;
    var vv = window.visualViewport;
    // Pinch zoom must retain normal browser scrolling; don't resize under it.
    var height = vv && Math.abs(vv.scale - 1) < 0.05 ? Math.min(innerHeight, vv.height) : innerHeight;
    root.style.setProperty('--dsh-mobile-height', Math.max(1, Math.round(height)) + 'px');
  }
  function setEnabled(value) {
    enabled = !!value;
    if (enabled) {
      root.setAttribute('data-dsh-android-mobile', '');
      if (viewport) {
        // Keep pinch zoom enabled and let Android adjustResize own IME insets.
        var content = originalViewport || 'width=device-width, initial-scale=1';
        if (content.indexOf('interactive-widget=') < 0) content += ', interactive-widget=resizes-content';
        viewport.setAttribute('content', content);
      }
      waitForBody();
      measure(); schedule();
    } else {
      root.removeAttribute('data-dsh-android-mobile');
      root.style.removeProperty('--dsh-mobile-height');
      if (viewport) {
        if (originalViewport === null) viewport.removeAttribute('content');
        else viewport.setAttribute('content', originalViewport);
      }
      observer.disconnect();
      document.removeEventListener('DOMContentLoaded', observeBody);
      waitingForBody = false;
    }
  }
  observer = new MutationObserver(function (records) {
    // Ignore keystroke/text changes and adapter attributes. Only newly mounted
    // element subtrees may introduce a shell or composer to annotate.
    for (var i = 0; i < records.length; i++) {
      for (var j = 0; j < records[i].addedNodes.length; j++) {
        if (records[i].addedNodes[j].nodeType === 1) { schedule(); return; }
      }
    }
  });
  window.addEventListener('resize', measure, { passive: true });
  if (window.visualViewport) window.visualViewport.addEventListener('resize', measure, { passive: true });
  window.__dshAndroidMobile = { setEnabled: setEnabled, version: '0.2.1-rc.2/mobile-3' };
  setEnabled(true);
})();
