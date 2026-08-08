'use strict';
function qualityList(asset) {
    var out = [], seen = {}, variants = (asset && asset.videoVariants) || [], i, v;
    for (i = 0; i < variants.length; i += 1) {
        v = variants[i]; if (!v.url || seen[v.url]) continue; seen[v.url] = 1; out.push(v);
    }
    if (asset && asset.videoUrl && !seen[asset.videoUrl]) out.unshift({ label: 'Kaynak', height: 0, url: asset.videoUrl, source: true });
    if (!out.length && asset && asset.masterPlaylistUrl) out.push({ label: 'Otomatik', height: 0, url: asset.masterPlaylistUrl, source: true });
    return out;
}

function start(media) {
    if (!media || !media.asset) { toast('Bu içerik oynatılamıyor.'); return; }
    stopTrailer();
    var originalMedia = media, local = window.Film2Offline ? Film2Offline.localAsset(media, media.asset) : null;
    if (local) {
        media = { titleId: media.titleId, title: media.title, subtitle: media.subtitle, season: media.season, episode: media.episode, poster: media.poster, backdrop: media.backdrop, asset: local };
    }
    S.media = media;
    S.qualities = qualityList(media.asset);
    var record = Film2Store.playbackFor(originalMedia), startAt = record && !record.finished ? Number(record.positionMs || 0) : 0;
    var quality = null, i, offlineRecord = local && Film2Offline.record(originalMedia);
    if (!local && record && record.qualityLabel) for (i = 0; i < S.qualities.length; i += 1) if (S.qualities[i].label === record.qualityLabel) quality = S.qualities[i];
    if (!quality) quality = S.qualities[0] || null;
    var store = Film2Store.read();
    S.displayMode = (record && record.displayMode) || store.defaultDisplayMode || 'fit';
    S.selectedQualityLabel = local ? ((offlineRecord && offlineRecord.qualityLabel) || (record && record.qualityLabel) || 'Cihazda') : (quality ? quality.label : '');
    E['player-title'].textContent = media.title;
    E['player-subtitle-title'].textContent = media.subtitle || '';
    E['player-badge'].textContent = local ? ('Cihazda' + (S.selectedQualityLabel && S.selectedQualityLabel !== 'Cihazda' ? ' • ' + S.selectedQualityLabel : '')) : (S.selectedQualityLabel || 'Otomatik');
    E['time-current'].textContent = '00:00';
    E['time-duration'].textContent = Film2TVPlayer.formatTime((media.asset.durationSeconds || 0) * 1000);
    E['timeline-progress'].style.width = '0%';
    applySubtitleAppearance();
    setScreen('player');
    showControls();
    P.open(media, {
        url: quality ? quality.url : (media.asset.videoUrl || media.asset.masterPlaylistUrl),
        start: startAt,
        autoplay: true,
        audio: record ? record.audio : null,
        subtitle: record && record.subtitlesDisabled ? { type: 'off', label: 'Kapalı' } : (record ? record.subtitle : null),
        displayMode: S.displayMode
    });
    setTimeout(function () { focusAction('toggle-play'); }, 220);
}

function initPlayer() {
    P = new Film2TVPlayer({
        buffer: function (on, percent) {
            E.buffering.classList.toggle('hidden', !on);
            E['buffering-text'].textContent = 'Yükleniyor' + (percent ? ' %' + percent : '') + '…';
        },
        time: function (now, duration) {
            E['time-current'].textContent = Film2TVPlayer.formatTime(now);
            E['time-duration'].textContent = Film2TVPlayer.formatTime(duration);
            E['timeline-progress'].style.width = (duration ? Math.min(100, now / duration * 100) : 0) + '%';
            saveProgress(false);
        },
        state: function (state) {
            var button = document.querySelector('[data-action="toggle-play"]');
            if (button) button.textContent = state === 'playing' ? 'Duraklat' : 'Oynat';
        },
        subtitle: function (text) { E['subtitle-layer'].textContent = text || ''; },
        tracks: function (tracks) { S.tracks = tracks || { audio: [], subtitles: [] }; },
        completed: function () { saveProgress(true); toast('Oynatma tamamlandı.'); },
        error: function (message) { E.buffering.classList.add('hidden'); toast(message, 5200); }
    });
}

function saveProgress(force) {
    if (!P || !P.media) return;
    clearTimeout(S.saveTimer);
    var run = function () {
        Film2Store.savePlayback(P.media, {
            positionMs: P.now || 0, durationMs: P.duration || 0,
            audio: P.selectedAudio || null, subtitle: P.selectedSubtitle || null,
            subtitlesDisabled: !P.selectedSubtitle || P.selectedSubtitle.type === 'off',
            qualityLabel: S.selectedQualityLabel || null, displayMode: S.displayMode
        });
    };
    if (force) run(); else S.saveTimer = setTimeout(run, 700);
}

function leavePlayer() {
    saveProgress(true); P.destroy(); E['subtitle-layer'].textContent = ''; E.buffering.classList.add('hidden');
    if (S.title) detail(S.title.id); else renderHome();
}

function showControls() {
    S.controlsVisible = true; E['player-controls'].classList.add('visible'); clearControlTimer();
    S.controlTimer = setTimeout(function () {
        if (S.screen === 'player' && !S.modal && P && P.playing) { E['player-controls'].classList.remove('visible'); S.controlsVisible = false; }
    }, 5500);
}
function clearControlTimer() { clearTimeout(S.controlTimer); S.controlTimer = null; }

function openTracks(kind) {
    var opts = kind === 'audio' ? S.tracks.audio : S.tracks.subtitles;
    openModal(kind === 'audio' ? 'Ses' : 'Altyazı', opts, function (item) {
        if (kind === 'audio') P.selectAudio(item); else P.selectSubtitle(item);
        saveProgress(true);
    });
}
function openQuality() {
    var opts = [], i;
    for (i = 0; i < S.qualities.length; i += 1) {
        opts.push({ label: S.qualities[i].label, meta: S.qualities[i].height ? S.qualities[i].height + 'p' : (S.qualities[i].source ? 'Kaynak' : ''), url: S.qualities[i].url, selected: S.qualities[i].label === S.selectedQualityLabel });
    }
    openModal('Kalite', opts, function (item) {
        S.selectedQualityLabel = item.label || ''; E['player-badge'].textContent = S.selectedQualityLabel; P.changeSource(item.url); saveProgress(true);
    });
}
function openDisplay() {
    var opts = [
        { label: 'Orijinal / Fit', value: 'fit' }, { label: 'Ekranı Doldur / Crop', value: 'crop' },
        { label: 'Esnet', value: 'stretch' }, { label: '16:9', value: 'ratio_16_9' },
        { label: '4:3', value: 'ratio_4_3' }, { label: '21:9', value: 'ratio_21_9' }
    ];
    for (var i = 0; i < opts.length; i += 1) opts[i].selected = opts[i].value === S.displayMode;
    openModal('Görüntü Oranı', opts, function (item) {
        S.displayMode = item.value; P.setDisplayMode(item.value); Film2Store.setDefaultDisplayMode(item.value); saveProgress(true); toast('Görüntü modu: ' + item.label);
    });
}
function openAppearance() {
    openModal('Altyazı Görünümü', [
        { label: 'Boyut', special: 'size' }, { label: 'Dikey Konum', special: 'position' }, { label: 'Renk', special: 'color' },
        { label: 'Arkaplan', special: 'background' }, { label: 'Kenar / Gölge', special: 'edge' }, { label: 'Varsayılana Dön', special: 'reset' }
    ], function (item) {
        if (item.special === 'reset') { Film2Store.resetSubtitleStyle(); applySubtitleAppearance(); toast('Altyazı görünümü sıfırlandı.'); return; }
        openAppearanceSub(item.special);
    });
}
function openAppearanceSub(kind) {
    var style = Film2Store.read().subtitleStyle, opts = [], i;
    if (kind === 'size') opts = [0.75,0.9,1,1.08,1.25,1.5,1.7].map(function (v) { return { label: Math.round(v * 100) + '%', value: v, selected: Math.abs(v - style.fontScale) < 0.01 }; });
    if (kind === 'position') opts = [6,9,12,16,20,25,30].map(function (v) { return { label: 'Alttan %' + v, value: v, selected: v === Math.round(style.bottomPercent) }; });
    if (kind === 'color') opts = [{label:'Beyaz',value:'white'},{label:'Sarı',value:'yellow'},{label:'Camgöbeği',value:'cyan'}];
    if (kind === 'background') opts = [{label:'Yok',value:'none'},{label:'Yumuşak',value:'soft'},{label:'Koyu',value:'strong'}];
    if (kind === 'edge') opts = [{label:'Kontur',value:'outline'},{label:'Gölge',value:'shadow'},{label:'Yok',value:'none'}];
    if (kind === 'color' || kind === 'background' || kind === 'edge') for (i = 0; i < opts.length; i += 1) opts[i].selected = opts[i].value === style[kind === 'color' ? 'color' : kind];
    openModal('Altyazı • ' + ({size:'Boyut',position:'Konum',color:'Renk',background:'Arkaplan',edge:'Kenar'}[kind] || ''), opts, function (item) {
        var patch = {}; if (kind === 'size') patch.fontScale = item.value; else if (kind === 'position') patch.bottomPercent = item.value; else patch[kind] = item.value;
        Film2Store.setSubtitleStyle(patch); applySubtitleAppearance();
    });
}
function applySubtitleAppearance() {
    if (!E['subtitle-layer']) return;
    var style = Film2Store.read().subtitleStyle;
    E['subtitle-layer'].style.fontSize = Math.round(38 * Number(style.fontScale || 1)) + 'px';
    E['subtitle-layer'].style.bottom = Math.round(1080 * Number(style.bottomPercent || 12) / 100) + 'px';
    E['subtitle-layer'].style.color = style.color === 'yellow' ? '#ffe45c' : (style.color === 'cyan' ? '#7ff7ff' : '#ffffff');
    E['subtitle-layer'].style.background = style.background === 'strong' ? 'rgba(0,0,0,.82)' : (style.background === 'soft' ? 'rgba(0,0,0,.42)' : 'transparent');
    E['subtitle-layer'].style.padding = style.background === 'none' ? '0' : '7px 14px';
    E['subtitle-layer'].style.borderRadius = style.background === 'none' ? '0' : '7px';
    E['subtitle-layer'].style.textShadow = style.edge === 'outline' ? '-2px -2px 0 #000,2px -2px 0 #000,-2px 2px 0 #000,2px 2px 0 #000,0 3px 8px #000' : (style.edge === 'shadow' ? '0 4px 8px #000,0 0 16px #000' : 'none');
}

function control(action) {
    showControls();
    if (action === 'toggle-play') P.toggle();
    else if (action === 'rewind') P.seekBy(-10000);
    else if (action === 'forward') P.seekBy(10000);
    else if (action === 'audio') openTracks('audio');
    else if (action === 'subtitles') openTracks('subtitles');
    else if (action === 'quality') openQuality();
    else if (action === 'display') openDisplay();
    else if (action === 'appearance') openAppearance();
    else if (action === 'back') leavePlayer();
}

function openModal(title, options, callback) {
    S.modal = true; S.modalOptions = options || []; S.modalCallback = callback || null;
    E['modal-title'].textContent = title; E['modal-hint'].textContent = 'OK: Seç • Geri: Kapat';
    var html = '';
    for (var i = 0; i < S.modalOptions.length; i += 1) {
        var item = S.modalOptions[i];
        html += '<button class="focusable modal-option' + (item.selected ? ' selected' : '') + '" data-modal-index="' + i + '"><div class="option-title">' + esc(item.label || item.language || ('Seçenek ' + (i + 1))) + '</div><div class="option-meta">' + esc(item.meta || '') + '</div></button>';
    }
    if (!html) html = '<div class="modal-empty">Seçenek bulunamadı.</div>';
    E['modal-list'].innerHTML = html; E.modal.classList.remove('hidden');
    setTimeout(function () { var first = E['modal-list'].querySelector('.focusable'); if (first) focus(first); }, 30);
}
function openTextInput(title, placeholder, callback) {
    S.modal = true; S.modalOptions = []; S.modalCallback = callback;
    E['modal-title'].textContent = title; E['modal-hint'].textContent = 'Metni yazıp Kaydet’i seç';
    E['modal-list'].innerHTML = '<div class="text-entry"><input id="modal-text-input" class="focusable text-input" type="text" maxlength="64" placeholder="' + esc(placeholder || '') + '"><button class="focusable modal-option" data-text-save="1"><div class="option-title">Kaydet</div></button></div>';
    E.modal.classList.remove('hidden');
    setTimeout(function () { var input = $('modal-text-input'); if (input) focus(input); }, 40);
}
function chooseModal(index) {
    var item = S.modalOptions[index], cb = S.modalCallback; closeModal(); if (item && cb) cb(item);
}
function saveTextModal() {
    var input = $('modal-text-input'), value = input ? input.value : '', cb = S.modalCallback; closeModal(); if (cb) cb(value);
}
function closeModal() {
    if (!S.modal) return;
    S.modal = false; S.modalOptions = []; S.modalCallback = null; E.modal.classList.add('hidden'); E['modal-list'].innerHTML = '';
    if (S.screen === 'player') setTimeout(function () { focusAction('toggle-play'); }, 20);
}
