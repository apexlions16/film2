'use strict';
function handleClick(event) {
    var node = event.target;
    while (node && node !== document.body) {
        if (node.getAttribute && (
            node.getAttribute('data-detail') || node.getAttribute('data-play-title') || node.getAttribute('data-episode') ||
            node.getAttribute('data-action') || node.getAttribute('data-nav') || node.getAttribute('data-nav-home') ||
            node.getAttribute('data-my-list') || node.getAttribute('data-custom-lists') || node.getAttribute('data-modal-index') != null ||
            node.getAttribute('data-new-list') || node.getAttribute('data-text-save') || node.getAttribute('data-trailer-mute') ||
            node.getAttribute('data-download-current') || node.getAttribute('data-download-episode') || node.getAttribute('data-download-season')
        )) break;
        node = node.parentNode;
    }
    if (!node || node === document.body) return;
    var value;
    if ((value = node.getAttribute('data-detail'))) detail(value);
    else if ((value = node.getAttribute('data-play-title'))) playTitle(value);
    else if ((value = node.getAttribute('data-episode'))) { var z = value.split(':'); start(episodeMedia(S.title, Number(z[0]), Number(z[1]))); }
    else if ((value = node.getAttribute('data-action'))) control(value);
    else if ((value = node.getAttribute('data-nav'))) { if (value === 'home') renderHome(); else if (value === 'search') renderSearch(); else if (value === 'library') renderLibrary(); else if (value === 'refresh') loadAll(true); }
    else if (node.getAttribute('data-nav-home')) renderHome();
    else if ((value = node.getAttribute('data-my-list'))) { var added = Film2Store.toggleMyList(value); toast(added ? 'Listeme eklendi.' : 'Listemden çıkarıldı.'); detail(value); }
    else if ((value = node.getAttribute('data-custom-lists'))) openTitleLists(value);
    else if (node.getAttribute('data-modal-index') != null) chooseModal(Number(node.getAttribute('data-modal-index')));
    else if (node.getAttribute('data-new-list')) openTextInput('Yeni Liste', 'Liste adı', function (name) { if (Film2Store.createCustomList(name)) { toast('Liste oluşturuldu.'); renderLibrary(); } });
    else if (node.getAttribute('data-text-save')) saveTextModal();
    else if (node.getAttribute('data-download-current')) { var lr = Film2Store.latestForTitle(S.title.id), lm = Film2Store.meaningful(lr) ? mediaFromRecord(S.title, lr) : firstMedia(S.title); toggleOffline(lm); }
    else if ((value = node.getAttribute('data-download-episode'))) { var dz = value.split(':'); toggleOffline(episodeMedia(S.title, Number(dz[0]), Number(dz[1]))); }
    else if ((value = node.getAttribute('data-download-season'))) toggleSeasonOffline(Number(value));
    else if (node.getAttribute('data-trailer-mute')) { var video = E['detail-trailer']; if (video) { video.muted = !video.muted; node.textContent = video.muted ? '🔇 Trailer' : '🔊 Trailer'; } }
}

function focus(element) {
    if (!element) return;
    var old = document.querySelector('.focusable.focused'); if (old) old.classList.remove('focused');
    element.classList.add('focused');
    try { element.focus(); } catch (e) {}
    try { element.scrollIntoView({ block: 'nearest', inline: 'nearest' }); } catch (e2) { try { element.scrollIntoView(false); } catch (e3) {} }
}
function visibleFocusables() {
    var selector = S.modal ? '#modal .focusable' : '.screen.active .focusable';
    var all = document.querySelectorAll(selector), out = [];
    for (var i = 0; i < all.length; i += 1) if (all[i].offsetWidth || all[i].offsetHeight) out.push(all[i]);
    return out;
}
function focusFirst() { var all = visibleFocusables(); if (all.length) focus(all[0]); }
function focusAction(action) { var el = document.querySelector('[data-action="' + action + '"]'); if (el) focus(el); }
function move(direction) {
    var all = visibleFocusables(), current = document.activeElement;
    if (!current || all.indexOf(current) < 0) { focusFirst(); return; }
    var r = current.getBoundingClientRect(), cx = (r.left + r.right) / 2, cy = (r.top + r.bottom) / 2, best = null, score = 1e12;
    for (var i = 0; i < all.length; i += 1) {
        var el = all[i]; if (el === current) continue;
        var q = el.getBoundingClientRect(), x = (q.left + q.right) / 2, y = (q.top + q.bottom) / 2, dx = x - cx, dy = y - cy;
        if ((direction === 'left' && dx >= -4) || (direction === 'right' && dx <= 4) || (direction === 'up' && dy >= -4) || (direction === 'down' && dy <= 4)) continue;
        var primary = (direction === 'left' || direction === 'right') ? Math.abs(dx) : Math.abs(dy);
        var cross = (direction === 'left' || direction === 'right') ? Math.abs(dy) : Math.abs(dx);
        var s = primary + cross * 2.25; if (s < score) { score = s; best = el; }
    }
    if (best) focus(best);
}

function back() {
    if (S.modal) { closeModal(); return; }
    if (S.screen === 'player') { leavePlayer(); return; }
    if (S.screen === 'detail' || S.screen === 'search' || S.screen === 'library') { renderHome(); return; }
    if (S.screen === 'home') { try { tizen.application.getCurrentApplication().exit(); } catch (e) { window.close(); } }
}
function key(event) {
    var code = event.keyCode, active = document.activeElement, input = active && active.tagName === 'INPUT';
    if (input) {
        if (code === 40) { var results = E['search-results'] && E['search-results'].querySelector('.focusable'); if (results) { focus(results); event.preventDefault(); } }
        else if (code === 10009 || code === 27) { try { active.blur(); } catch (e) {} back(); event.preventDefault(); }
        return;
    }
    if (code === 37) {
        if (S.screen === 'player' && !S.modal && !S.controlsVisible) { showControls(); P.seekBy(-10000); } else move('left'); event.preventDefault();
    } else if (code === 39) {
        if (S.screen === 'player' && !S.modal && !S.controlsVisible) { showControls(); P.seekBy(10000); } else move('right'); event.preventDefault();
    } else if (code === 38) { if (S.screen === 'player') showControls(); move('up'); event.preventDefault(); }
    else if (code === 40) { if (S.screen === 'player') showControls(); move('down'); event.preventDefault(); }
    else if (code === 13) { var focused = document.activeElement; if (focused && focused.click) focused.click(); event.preventDefault(); }
    else if (code === 10009 || code === 27) { back(); event.preventDefault(); }
    else if (code === 415 || code === 10252) { if (S.screen === 'player') P.toggle(); }
    else if (code === 19) { if (S.screen === 'player' && P.playing) P.toggle(); }
    else if (code === 412) { if (S.screen === 'player') P.seekBy(-10000); }
    else if (code === 417) { if (S.screen === 'player') P.seekBy(10000); }
    else if (code === 413) { if (S.screen === 'player') leavePlayer(); }
}
function registerKeys() {
    try {
        var keys = ['MediaPlay','MediaPause','MediaPlayPause','MediaRewind','MediaFastForward','MediaStop'];
        for (var i = 0; i < keys.length; i += 1) { try { tizen.tvinputdevice.registerKey(keys[i]); } catch (e) {} }
    } catch (x) {}
    document.addEventListener('keydown', key, false);
}

function loadAll(manual) {
    E['splash-message'].textContent = 'Katalog yükleniyor…';
    if (manual && S.screen !== 'splash') toast('Katalog yenileniyor…');
    return Promise.all([Film2Catalog.loadSnapshot(), Film2Catalog.loadHome()]).then(function (result) {
        S.titles = result[0].titles || []; S.revision = result[0].revision || ''; S.home = result[1] || { heroTitleIds: [], shelves: [] };
        renderHome(); if (manual) toast('Katalog güncellendi.');
    }).catch(function (error) {
        E['splash-message'].textContent = 'Katalog yüklenemedi: ' + error.message;
        if (S.screen !== 'splash') toast('Katalog yenilenemedi: ' + error.message, 5000);
        else setTimeout(function () { loadAll(false); }, 5000);
    });
}
function poll() {
    setInterval(function () {
        if (S.screen === 'player') return;
        Film2Catalog.loadRevision().then(function (revision) {
            if (revision && S.revision && revision !== S.revision) loadAll(false);
            else if (revision) S.revision = revision;
        });
    }, 300000);
}
function lifecycle() {
    document.addEventListener('visibilitychange', function () {
        if (S.screen !== 'player') return;
        if (document.hidden) { saveProgress(true); P.suspend(); }
        else P.restore();
    });
    window.addEventListener('beforeunload', function () { saveProgress(true); if (P) P.destroy(); });
}
function bindInputs() {
    E['search-input'].addEventListener('input', renderSearchResults, false);
    E['search-input'].addEventListener('change', renderSearchResults, false);
}
function init() {
    cache(); initPlayer(); applySubtitleAppearance(); registerKeys(); lifecycle(); bindInputs();
    document.addEventListener('click', handleClick, false);
    if (window.Film2Offline) {
        Film2Offline.rehydrate();
        window.addEventListener('film2:offline', function () { if (S.screen === 'detail') updateOfflineIndicators(); }, false);
        setInterval(function () { if (S.screen === 'detail') updateOfflineIndicators(); }, 1000);
    }
    loadAll(false); poll();
}

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
