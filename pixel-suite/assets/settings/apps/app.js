(function () {
  'use strict';

  var apps = [];
  var selectedPackages = [];
  var savedPackages = [];
  var savedEnabled = false;
  var loading = false;
  var saving = false;
  var snackbarTimer = 0;

  var list = document.getElementById('apps-list');
  var selectedList = document.getElementById('selected-list');
  var selectedEmpty = document.getElementById('selected-empty');
  var count = document.getElementById('selected-count');
  var subtitle = document.getElementById('apps-subtitle');
  var visibleCount = document.getElementById('visible-count');
  var emptyState = document.getElementById('apps-empty');
  var search = document.getElementById('search-input');
  var clearSearch = document.getElementById('clear-search');
  var saveButton = document.getElementById('save-button');
  var nativeButton = document.getElementById('native-button');
  var reloadButton = document.getElementById('reload-button');
  var status = document.getElementById('save-status');
  var snackbar = document.getElementById('snackbar');
  var activeDrag = null;

  function selectedIndex(packageName) {
    return selectedPackages.indexOf(packageName);
  }

  function getApp(packageName) {
    for (var i = 0; i < apps.length; i++) {
      if (apps[i].packageName === packageName) return apps[i];
    }
    return null;
  }

  function showMessage(message, error) {
    status.textContent = message || '';
    status.classList.toggle('is-error', !!error);
    snackbar.textContent = message || '';
    snackbar.classList.add('is-visible');
    window.clearTimeout(snackbarTimer);
    snackbarTimer = window.setTimeout(function () {
      snackbar.classList.remove('is-visible');
    }, 2600);
  }

  function iconFor(app, className) {
    var image = document.createElement('img');
    image.className = className;
    image.alt = '';
    image.width = 48;
    image.height = 48;
    if (typeof app.icon === 'string' && app.icon.indexOf('data:image/png;base64,') === 0) {
      image.src = app.icon;
    } else {
      image.classList.add('icon-fallback');
      image.src = 'data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 48 48%22%3E%3Crect width=%2248%22 height=%2248%22 rx=%2212%22 fill=%22%235b776f%22/%3E%3Cpath d=%22M15 15h18v18H15z%22 fill=%22%23fff%22/%3E%3C/svg%3E';
    }
    return image;
  }

  function iconButton(icon, label, disabled, action) {
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'order-button';
    button.setAttribute('aria-label', label);
    button.title = label;
    button.disabled = !!disabled;
    var glyph = document.createElement('i');
    glyph.className = 'mdui-icon material-icons';
    glyph.setAttribute('aria-hidden', 'true');
    glyph.textContent = icon;
    button.appendChild(glyph);
    button.addEventListener('click', action);
    return button;
  }

  function setButtonContent(button, icon, label) {
    while (button.firstChild) button.removeChild(button.firstChild);
    var glyph = document.createElement('i');
    glyph.className = 'mdui-icon material-icons';
    glyph.setAttribute('aria-hidden', 'true');
    glyph.textContent = icon;
    button.appendChild(glyph);
    button.appendChild(document.createTextNode(' ' + label));
  }

  function renderSelected() {
    while (selectedList.firstChild) selectedList.removeChild(selectedList.firstChild);
    count.textContent = String(selectedPackages.length);
    selectedEmpty.hidden = selectedPackages.length > 0;
    if (selectedPackages.length === 0) selectedList.appendChild(selectedEmpty);

    for (var i = 0; i < selectedPackages.length; i++) {
      var packageName = selectedPackages[i];
      var app = getApp(packageName);
      var item = document.createElement('div');
      item.className = 'selected-item';
      item.appendChild(iconFor(app || { icon: '' }, 'selected-icon'));

      var text = document.createElement('div');
      text.className = 'selected-copy';
      var label = document.createElement('span');
      label.className = 'selected-label';
      label.textContent = app ? app.label : packageName;
      var position = document.createElement('span');
      position.className = 'selected-position';
      position.textContent = '第 ' + (i + 1) + ' 位';
      text.appendChild(label);
      text.appendChild(position);
      item.appendChild(text);
      var dragHandle = iconButton('drag_indicator', '拖动调整顺序', false, function () { });
      dragHandle.classList.add('drag-handle');
      dragHandle.setAttribute('data-index', String(i));
      item.appendChild(dragHandle);
      item.appendChild(iconButton('arrow_upward', '上移', i === 0, (function (index) {
        return function () { moveSelected(index, -1); };
      })(i)));
      item.appendChild(iconButton('arrow_downward', '下移', i === selectedPackages.length - 1, (function (index) {
        return function () { moveSelected(index, 1); };
      })(i)));
      item.appendChild(iconButton('close', '移除', false, (function (name) {
        return function () { togglePackage(name, false); };
      })(packageName)));
      selectedList.appendChild(item);
    }
    saveButton.disabled = loading || saving || (savedEnabled && arraysEqual(selectedPackages, savedPackages));
  }

  function renderApps() {
    var query = search.value.trim().toLocaleLowerCase();
    var filtered = [];
    for (var i = 0; i < apps.length; i++) {
      var app = apps[i];
      if (!query || app.label.toLocaleLowerCase().indexOf(query) >= 0 ||
          app.packageName.toLocaleLowerCase().indexOf(query) >= 0) filtered.push(app);
    }
    while (list.firstChild) list.removeChild(list.firstChild);
    emptyState.hidden = filtered.length !== 0 || loading;
    visibleCount.textContent = apps.length ? filtered.length + ' / ' + apps.length : '';
    for (var j = 0; j < filtered.length; j++) list.appendChild(appRow(filtered[j]));
    clearSearch.hidden = search.value.length === 0;
  }

  function appRow(app) {
    var row = document.createElement('label');
    row.className = 'app-row';
    var check = document.createElement('input');
    check.type = 'checkbox';
    check.className = 'mdui-checkbox-input';
    check.checked = selectedIndex(app.packageName) >= 0;
    check.setAttribute('aria-label', '选择 ' + app.label);
    check.addEventListener('change', function () {
      togglePackage(app.packageName, check.checked);
    });
    var checkMark = document.createElement('span');
    checkMark.className = 'checkbox-mark';
    row.appendChild(check);
    row.appendChild(checkMark);
    row.appendChild(iconFor(app, 'app-icon'));

    var copy = document.createElement('span');
    copy.className = 'app-copy';
    var name = document.createElement('span');
    name.className = 'app-name';
    name.textContent = app.label;
    var pkg = document.createElement('span');
    pkg.className = 'app-package';
    pkg.textContent = app.packageName;
    copy.appendChild(name);
    copy.appendChild(pkg);
    row.appendChild(copy);

    var order = selectedIndex(app.packageName);
    if (order >= 0) {
      var badge = document.createElement('span');
      badge.className = 'order-badge';
      badge.textContent = String(order + 1);
      row.appendChild(badge);
    }
    return row;
  }

  function togglePackage(packageName, checked) {
    var index = selectedIndex(packageName);
    if (checked && index < 0) selectedPackages.push(packageName);
    else if (!checked && index >= 0) selectedPackages.splice(index, 1);
    renderSelected();
    renderApps();
  }

  function moveSelected(index, delta) {
    var target = index + delta;
    if (target < 0 || target >= selectedPackages.length) return;
    var value = selectedPackages.splice(index, 1)[0];
    selectedPackages.splice(target, 0, value);
    renderSelected();
    renderApps();
  }

  function moveSelectedTo(index, insertionIndex) {
    if (index < 0 || index >= selectedPackages.length) return;
    var remainingCount = selectedPackages.length - 1;
    var target = Math.max(0, Math.min(remainingCount, insertionIndex));
    if (target === index) return;
    var value = selectedPackages.splice(index, 1)[0];
    selectedPackages.splice(target, 0, value);
    renderSelected();
    renderApps();
  }

  function clearDropMarker() {
    var marked = selectedList.querySelectorAll('.is-drop-before, .is-drop-after');
    for (var i = 0; i < marked.length; i++) {
      marked[i].classList.remove('is-drop-before', 'is-drop-after');
    }
  }

  function updateDragTarget(clientY) {
    clearDropMarker();
    var rows = selectedList.querySelectorAll('.selected-item');
    var remaining = [];
    for (var i = 0; i < rows.length; i++) {
      if (rows[i] !== activeDrag.row) remaining.push(rows[i]);
    }
    var insertionIndex = remaining.length;
    for (var j = 0; j < remaining.length; j++) {
      var rect = remaining[j].getBoundingClientRect();
      if (clientY < rect.top + rect.height / 2) {
        insertionIndex = j;
        remaining[j].classList.add('is-drop-before');
        break;
      }
    }
    if (insertionIndex === remaining.length && remaining.length) {
      remaining[remaining.length - 1].classList.add('is-drop-after');
    }
    activeDrag.insertionIndex = insertionIndex;
  }

  function finishDrag(commit) {
    if (!activeDrag) return;
    var drag = activeDrag;
    activeDrag = null;
    if (drag.activated) {
      drag.row.classList.remove('is-dragging');
      drag.handle.classList.remove('is-active');
      drag.handle.removeAttribute('aria-grabbed');
      clearDropMarker();
    }
    if (drag.pointerId !== undefined && drag.handle.hasPointerCapture &&
        drag.handle.hasPointerCapture(drag.pointerId)) {
      try { drag.handle.releasePointerCapture(drag.pointerId); } catch (ignored) { }
    }
    if (commit && drag.activated && drag.insertionIndex !== undefined) {
      var changed = drag.sourceIndex !== drag.insertionIndex;
      moveSelectedTo(drag.sourceIndex, drag.insertionIndex);
      if (changed) showMessage('已移至第 ' + (drag.insertionIndex + 1) + ' 位');
    }
  }

  selectedList.addEventListener('pointerdown', function (event) {
    var handle = event.target.closest('.drag-handle');
    if (!handle || event.button !== 0 || loading || saving) return;
    var row = handle.closest('.selected-item');
    if (!row) return;
    activeDrag = {
      pointerId: event.pointerId,
      sourceIndex: Number(handle.getAttribute('data-index')),
      startY: event.clientY,
      insertionIndex: Number(handle.getAttribute('data-index')),
      row: row,
      handle: handle,
      activated: false
    };
    if (handle.setPointerCapture) {
      try { handle.setPointerCapture(event.pointerId); } catch (ignored) { }
    }
  });

  window.addEventListener('pointermove', function (event) {
    if (!activeDrag || event.pointerId !== activeDrag.pointerId) return;
    if (!activeDrag.activated && Math.abs(event.clientY - activeDrag.startY) < 6) return;
    event.preventDefault();
    if (!activeDrag.activated) {
      activeDrag.activated = true;
      activeDrag.row.classList.add('is-dragging');
      activeDrag.handle.classList.add('is-active');
      activeDrag.handle.setAttribute('aria-grabbed', 'true');
    }
    var viewportEdge = 56;
    if (event.clientY < viewportEdge) window.scrollBy(0, -12);
    else if (event.clientY > window.innerHeight - viewportEdge) window.scrollBy(0, 12);
    updateDragTarget(event.clientY);
  }, { passive: false });

  window.addEventListener('pointerup', function (event) {
    if (activeDrag && event.pointerId === activeDrag.pointerId) finishDrag(true);
  });

  window.addEventListener('pointercancel', function (event) {
    if (activeDrag && event.pointerId === activeDrag.pointerId) finishDrag(false);
  });

  function arraysEqual(left, right) {
    if (left.length !== right.length) return false;
    for (var i = 0; i < left.length; i++) if (left[i] !== right[i]) return false;
    return true;
  }

  function beginLoad() {
    if (loading) return;
    loading = true;
    reloadButton.disabled = true;
    subtitle.textContent = '正在读取可启动的应用';
    renderApps();
    if (window.PixelPicker && typeof window.PixelPicker.loadApps === 'function') window.PixelPicker.loadApps();
    else onPickerLoadError('应用选择服务不可用');
  }

  window.onPickerLoad = function (result) {
    loading = false;
    reloadButton.disabled = false;
    apps = result && Array.isArray(result.apps) ? result.apps : [];
    var selection = result && result.selection ? result.selection : {};
    savedEnabled = !!selection.enabled;
    selectedPackages = savedEnabled && Array.isArray(selection.packages)
      ? selection.packages.slice() : [];
    savedPackages = selectedPackages.slice();
    subtitle.textContent = apps.length ? '选择后会按顺序显示在多任务 Dock' : '没有找到可启动的应用';
    renderSelected();
    renderApps();
  };

  window.onPickerLoadError = function (message) {
    loading = false;
    reloadButton.disabled = false;
    subtitle.textContent = '应用列表读取失败';
    showMessage(message || '读取失败，请重试', true);
  };

  window.onPickerSaved = function (result) {
    saving = false;
    setButtonContent(saveButton, 'check', '保存并应用');
    if (!result || !result.ok) {
      showMessage(result && result.error ? result.error : '保存失败，请重试', true);
      renderSelected();
      return;
    }
    var selection = result.selection || {};
    savedEnabled = !!selection.enabled;
    savedPackages = Array.isArray(selection.packages) ? selection.packages.slice() : selectedPackages.slice();
    selectedPackages = savedPackages.slice();
    showMessage('已保存，Dock 正在应用新顺序');
    renderSelected();
    renderApps();
  };

  window.onPickerNative = function (result) {
    if (!result || !result.ok) {
      showMessage(result && result.error ? result.error : '切换失败', true);
      return;
    }
    selectedPackages = [];
    savedPackages = [];
    savedEnabled = false;
    showMessage('已恢复系统建议');
    renderSelected();
    renderApps();
  };

  saveButton.addEventListener('click', function () {
    if (saving || loading) return;
    saving = true;
    saveButton.disabled = true;
    setButtonContent(saveButton, 'hourglass_empty', '正在保存…');
    if (window.PixelPicker && typeof window.PixelPicker.saveSelection === 'function') {
      window.PixelPicker.saveSelection(selectedPackages.join('\n'));
    } else {
      saving = false;
      showMessage('应用选择服务不可用', true);
    }
  });

  nativeButton.addEventListener('click', function () {
    if (saving || loading) return;
    nativeButton.disabled = true;
    if (window.PixelPicker && typeof window.PixelPicker.useNative === 'function') window.PixelPicker.useNative();
    else showMessage('应用选择服务不可用', true);
    window.setTimeout(function () { nativeButton.disabled = false; }, 500);
  });

  reloadButton.addEventListener('click', beginLoad);
  search.addEventListener('input', renderApps);
  clearSearch.addEventListener('click', function () {
    search.value = '';
    search.focus();
    renderApps();
  });
  beginLoad();
})();
