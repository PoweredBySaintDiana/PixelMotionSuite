const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../pixel-suite/assets/suite/app.js'), 'utf8');
const settingsPage = fs.readFileSync(path.join(__dirname, '../pixel-suite/assets/suite/index.html'), 'utf8');
for (const retired of ['recents_card_layout', 'recents_task_shelf']) {
  assert.ok(!settingsPage.includes('data-feature="' + retired + '"'));
  assert.ok(!script.includes(retired));
}
for (const retained of ['recents_overview_dock', 'recents_float_launch']) {
  assert.ok(settingsPage.includes('data-feature="' + retained + '"'));
}

function launch(saved, result) {
  const nodes = new Map();
  function node(key) {
    if (!nodes.has(key)) nodes.set(key, {
      checked: true, handlers: {}, textContent: '',
      classList: {add() {}, remove() {}, toggle() {}}, closest() {return this;},
      getAttribute() {return key;}, addEventListener(event, action) {this.handlers[event] = action;}
    });
    return nodes.get(key);
  }
  const keys = ['motion_page_recoil', 'motion_splash_layers', 'recents_overview_dock', 'aod_restore_earth', 'aod_visibility', 'aod_wallpaper_gain'];
  const document = {
    getElementById: node,
    querySelector(query) {return node(query.match(/"(.*?)"/)[1]);},
    querySelectorAll(query) {
      if (query === 'input[data-feature]') return keys.map(node);
      return Array.from(query.matchAll(/"(.*?)"/g), match => node(match[1]));
    }
  };
  let writes = 0;
  const window = {setTimeout() {}, clearTimeout() {}, PixelSuite: {
    readSettings: () => JSON.stringify(saved), setFeature() {writes++; return result;}
  }};
  vm.runInNewContext(script, {window, document});
  return {node, writes: () => writes};
}

const settings = {ambient_status: 'saved', motion_page_recoil: false, shared_preferences_readable: false};
assert.equal(launch({...settings, aod_restore_earth: false}, 'saved').node('aod_wallpaper_gain').disabled, true);
assert.equal(launch({...settings, aod_restore_earth: true, aod_wallpaper_gain: false}, 'saved').node('aod_wallpaper_gain').checked, false);
const splash = launch({...settings, motion_splash_layers: false}, 'saved');
assert.equal(splash.node('motion_splash_layers').checked, false);
splash.node('motion_splash_layers').checked = true;
splash.node('motion_splash_layers').handlers.change.call(splash.node('motion_splash_layers'));
assert.equal(splash.writes(), 1); assert.equal(splash.node('motion_page_recoil').checked, false);
for (const result of ['saved', 'failed', 'sharing_unavailable']) {
  const view = launch(settings, result);
  assert.equal(view.writes(), 0);
  assert.equal(view.node('snackbar').textContent, '');
  const motion = view.node('motion_page_recoil');
  assert.equal(motion.checked, false);
  motion.checked = true;
  motion.handlers.change.call(motion);
  assert.equal(view.writes(), 1);
  assert.equal(motion.checked, result === 'saved');
  assert.match(view.node('snackbar').textContent, result === 'saved' ? /设置已保存/ : /保存失败|未能保存/);
}
const cold = launch({...settings, ambient_status: 'unavailable'}, 'unavailable');
assert.equal(cold.node('snackbar').textContent, '');
assert.match(cold.node('aod-status').textContent, /未连接系统界面/);
console.log('PASS: cold-start, restart, saved, rollback and independent AOD status');
