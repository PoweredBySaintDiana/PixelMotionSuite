(function () {
  'use strict';

  var snackbar = document.getElementById('snackbar');
  var timer = 0;
  var ambientStatus = 'unavailable';

  function message(text) {
    snackbar.textContent = text;
    snackbar.classList.add('is-visible');
    window.clearTimeout(timer);
    timer = window.setTimeout(function () { snackbar.classList.remove('is-visible'); }, 2400);
  }

  function readSettings() {
    if (!window.PixelSuite || typeof window.PixelSuite.readSettings !== 'function') {
      message('设置服务暂不可用');
      return;
    }
    try {
      var settings = JSON.parse(window.PixelSuite.readSettings());
      ambientStatus = settings.ambient_status;
      var switches = document.querySelectorAll('input[data-feature]');
      for (var i = 0; i < switches.length; i++) {
        var input = switches[i];
        var key = input.getAttribute('data-feature');
        input.checked = settings[key] !== false;
      }
      document.getElementById('custom-apps').checked = settings.recents_custom_apps === true;
      updateDependencies();
      document.getElementById('aod-status').textContent = ambientStatus === 'saved' ? '' : ambientStatus === 'write_failed'
        ? '系统界面未能读取息屏设置。' : '未连接系统界面。请确认系统界面作用域已启用，升级后重启手机，再重新打开此页。';
    } catch (error) {
      message('读取设置失败');
    }
  }

  function updateDependencies() {
    var dockEnabled = document.querySelector('[data-feature="recents_overview_dock"]').checked;
    var dockChildren = document.querySelectorAll(
      '[data-feature="recents_float_launch"], #custom-apps'
    );
    for (var i = 0; i < dockChildren.length; i++) {
      dockChildren[i].disabled = !dockEnabled;
      dockChildren[i].closest('.setting-row').classList.toggle('is-disabled', !dockEnabled);
    }
    var earthEnabled = document.querySelector('[data-feature="aod_restore_earth"]').checked;
    document.querySelector('[data-feature="aod_restore_earth"]').disabled = ambientStatus !== 'saved';
    var earthChildren = document.querySelectorAll('[data-feature="aod_visibility"], [data-feature="aod_wallpaper_gain"]');
    for (var k = 0; k < earthChildren.length; k++) {
      earthChildren[k].disabled = ambientStatus !== 'saved' || !earthEnabled;
      earthChildren[k].closest('.setting-row').classList.toggle('is-disabled', earthChildren[k].disabled);
    }
  }

  function switchPage(name) {
    var pages = document.querySelectorAll('.page');
    var navItems = document.querySelectorAll('.nav-item');
    for (var i = 0; i < pages.length; i++) {
      var active = pages[i].getAttribute('data-page') === name;
      pages[i].hidden = !active;
      pages[i].classList.toggle('is-active', active);
    }
    for (var j = 0; j < navItems.length; j++) {
      var selected = navItems[j].getAttribute('data-target') === name;
      navItems[j].classList.toggle('is-selected', selected);
      if (selected) navItems[j].setAttribute('aria-current', 'page');
      else navItems[j].removeAttribute('aria-current');
    }
    window.scrollTo(0, 0);
  }

  var navItems = document.querySelectorAll('.nav-item');
  for (var i = 0; i < navItems.length; i++) {
    navItems[i].addEventListener('click', function () {
      switchPage(this.getAttribute('data-target'));
    });
  }

  var featureInputs = document.querySelectorAll('input[data-feature]');
  for (var j = 0; j < featureInputs.length; j++) {
    featureInputs[j].addEventListener('change', function () {
      var key = this.getAttribute('data-feature');
      var result = 'failed';
      try { if (window.PixelSuite) result = window.PixelSuite.setFeature(key, this.checked); }
      catch (ignored) { result = 'failed'; }
      if (key.indexOf('aod_') === 0 && result !== 'saved') {
        readSettings();
        message(result === 'unavailable' ? '系统界面未响应，请核对作用域并重启手机' : result === 'unsupported'
          ? '当前系统界面不支持全屏息屏壁纸或十秒计时，未开启' : '系统未能保存息屏设置，请检查模块日志');
      } else if (result !== 'saved') {
        this.checked = !this.checked;
        message(result === 'sharing_unavailable' ? '未能保存：模块共享设置服务不可用' : '保存失败，请重试');
      } else {
        updateDependencies();
        message('设置已保存');
      }
    });
  }

  var customApps = document.getElementById('custom-apps');
  customApps.addEventListener('change', function () {
    var saved = false;
    try { saved = !!window.PixelSuite && window.PixelSuite.setCustomAppsEnabled(this.checked); }
    catch (ignored) { saved = false; }
    if (!saved) {
      this.checked = !this.checked;
      message('保存失败，请重试');
    } else {
      message(this.checked ? '已启用自选应用顺序' : '已恢复系统应用建议');
    }
  });

  document.getElementById('open-picker').addEventListener('click', function () {
    if (window.PixelSuite && typeof window.PixelSuite.openAppPicker === 'function') {
      window.PixelSuite.openAppPicker();
    } else {
      message('应用选择页面暂不可用');
    }
  });

  readSettings();
})();
