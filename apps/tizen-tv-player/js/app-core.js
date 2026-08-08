'use strict';
var S = {
    titles: [], home: { heroTitleIds: [], shelves: [] }, revision: '', seed: Math.floor(Date.now() / 86400000),
    screen: 'splash', title: null, media: null, tracks: { audio: [], subtitles: [] }, qualities: [],
    modal: false, modalOptions: [], modalCallback: null, controlsVisible: true, controlTimer: null,
    toastTimer: null, saveTimer: null, searchQuery: '', selectedQualityLabel: '', displayMode: 'fit'
};
var E = {}, P = null;

function $(id) { return document.getElementById(id); }
function esc(value) {
    return String(value == null ? '' : value)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
function clamp(n, min, max) { return Math.max(min, Math.min(max, Number(n) || 0)); }
function cache() {
    var ids = [
        'splash','splash-message','home-screen','home-content','catalog-status','search-screen','search-input','search-results',
        'library-screen','library-content','detail-screen','detail-backdrop','detail-trailer','detail-content','player-screen',
        'player-controls','player-title','player-subtitle-title','player-badge','timeline-progress','time-current','time-duration',
        'buffering','buffering-text','subtitle-layer','modal','modal-title','modal-hint','modal-list','toast'
    ];
    for (var i = 0; i < ids.length; i += 1) E[ids[i]] = $(ids[i]);
}

function stopTrailer() {
    var video = E['detail-trailer'];
    if (!video) return;
    try { video.pause(); video.removeAttribute('src'); video.load(); } catch (e) {}
    video.classList.add('hidden');
}
function startTrailer(title) {
    var video = E['detail-trailer'];
    if (!video || !title || !title.trailerUrl) { stopTrailer(); return; }
    try {
        video.muted = true; video.loop = true; video.src = title.trailerUrl; video.classList.remove('hidden');
        var promise = video.play(); if (promise && promise.catch) promise.catch(function () { video.classList.add('hidden'); });
    } catch (e) { video.classList.add('hidden'); }
}

function setScreen(name) {
    var map = { splash: 'splash', home: 'home-screen', search: 'search-screen', library: 'library-screen', detail: 'detail-screen', player: 'player-screen' };
    var names = ['splash','home','search','library','detail','player'];
    for (var i = 0; i < names.length; i += 1) E[map[names[i]]].classList.toggle('active', names[i] === name);
    if (name !== 'detail') stopTrailer();
    if (name !== 'player') clearControlTimer();
    S.screen = name;
    closeModal();
}

function toast(message, timeout) {
    E.toast.textContent = message;
    E.toast.classList.remove('hidden');
    clearTimeout(S.toastTimer);
    S.toastTimer = setTimeout(function () { E.toast.classList.add('hidden'); }, timeout || 2600);
}

function byId(id) {
    for (var i = 0; i < S.titles.length; i += 1) if (S.titles[i].id === id) return S.titles[i];
    return null;
}
function playable(title) { return Film2Catalog.titleIsPlayable(title); }

function movieMedia(title) {
    if (!title || title.type !== 'movie' || !title.asset) return null;
    return { titleId: title.id, title: title.title, subtitle: '', asset: title.asset, poster: title.posterUrl, backdrop: title.backdropUrl };
}
function episodeMedia(title, seasonIndex, episodeIndex) {
    if (!title || !title.seasons || !title.seasons[seasonIndex]) return null;
    var season = title.seasons[seasonIndex], ep = season.episodes && season.episodes[episodeIndex];
    if (!ep || !ep.asset) return null;
    return {
        titleId: title.id, title: title.title,
        subtitle: 'S' + season.seasonNumber + ' B' + ep.episodeNumber + ' • ' + ep.title,
        season: season.seasonNumber, episode: ep.episodeNumber, asset: ep.asset,
        poster: ep.stillUrl || title.posterUrl, backdrop: ep.stillUrl || title.backdropUrl
    };
}
function firstMedia(title) {
    if (!title) return null;
    if (title.type === 'movie') return movieMedia(title);
    for (var s = 0; s < title.seasons.length; s += 1) {
        for (var e = 0; e < title.seasons[s].episodes.length; e += 1) {
            var ep = title.seasons[s].episodes[e];
            if (ep.status === 'ready' && ep.asset && (ep.asset.videoUrl || ep.asset.masterPlaylistUrl)) return episodeMedia(title, s, e);
        }
    }
    return null;
}
function mediaFromRecord(title, record) {
    if (!title || !record) return null;
    if (record.season == null || record.episode == null) return movieMedia(title);
    for (var s = 0; s < title.seasons.length; s += 1) {
        if (Number(title.seasons[s].seasonNumber) !== Number(record.season)) continue;
        for (var e = 0; e < title.seasons[s].episodes.length; e += 1) {
            if (Number(title.seasons[s].episodes[e].episodeNumber) === Number(record.episode)) return episodeMedia(title, s, e);
        }
    }
    return null;
}
function progressForTitle(title) {
    var rec = Film2Store.latestForTitle(title.id);
    return Film2Store.progressFraction(rec);
}

function shuffled(items, seed) {
    var result = items.slice(), state = Number(seed || 1) >>> 0;
    for (var i = result.length - 1; i > 0; i -= 1) {
        state = (Math.imul ? Math.imul(state, 1664525) : (state * 1664525)) + 1013904223;
        state = state >>> 0;
        var j = state % (i + 1), tmp = result[i]; result[i] = result[j]; result[j] = tmp;
    }
    return result;
}

function card(title) {
    var poster = Film2Catalog.chooseArtwork(title, 'poster', S.seed);
    var progress = progressForTitle(title), width = Math.round(clamp(progress, 0, 1) * 100);
    return '<button class="focusable card" data-detail="' + esc(title.id) + '">' +
        (poster ? '<img src="' + esc(poster) + '" alt="">' : '<div class="card-fallback">' + esc(title.title) + '</div>') +
        '<div class="card-shade"></div><div class="card-title">' + esc(title.title) + '</div>' +
        (width > 1 && width < 95 ? '<div class="progress-strip" style="width:' + width + '%"></div>' : '') + '</button>';
}
function shelf(title, items, emphasized) {
    if (!items || !items.length) return '';
    var html = '<section class="shelf' + (emphasized ? ' shelf-emphasized' : '') + '"><h2 class="shelf-title">' + esc(title) + '</h2><div class="shelf-row">';
    for (var i = 0; i < items.length; i += 1) html += card(items[i]);
    return html + '</div></section>';
}
function heroHtml(title) {
    if (!title) return '<div class="empty-state">Henüz oynatılabilir içerik yok.</div>';
    var backdrop = Film2Catalog.chooseArtwork(title, 'backdrop', S.seed);
    var meta = [];
    if (title.releaseYear) meta.push(title.releaseYear);
    if (title.type) meta.push(title.type === 'series' ? 'Dizi' : 'Film');
    if (title.runtimeMinutes) meta.push(title.runtimeMinutes + ' dk');
    for (var i = 0; i < Math.min(3, title.genres.length); i += 1) meta.push(title.genres[i]);
    var rec = Film2Store.latestForTitle(title.id), continueMedia = mediaFromRecord(title, rec), label = Film2Store.meaningful(rec) && continueMedia ? 'Devam Et' : 'İzle';
    return '<div class="hero" style="background-image:url(\'' + esc(backdrop) + '\')"><div class="hero-copy">' +
        (title.logoUrl ? '<img class="hero-logo" src="' + esc(title.logoUrl) + '" alt="">' : '<h1 class="hero-title">' + esc(title.title) + '</h1>') +
        '<div class="hero-meta"><span>' + esc(meta.join(' • ')) + '</span></div>' +
        '<div class="hero-overview">' + esc(title.overview || '') + '</div>' +
        '<div class="hero-actions"><button class="focusable hero-button primary" data-play-title="' + esc(title.id) + '">▶ ' + label + '</button>' +
        '<button class="focusable hero-button" data-detail="' + esc(title.id) + '">Ayrıntılar</button></div></div></div>';
}

function renderHome() {
    var hero = null, ids = S.home.heroTitleIds || [], i, title;
    for (i = 0; i < ids.length && !hero; i += 1) { title = byId(ids[i]); if (title && playable(title)) hero = title; }
    if (!hero) hero = S.titles[0] || null;
    var html = heroHtml(hero), shelves = '', state = Film2Store.read(), contIds = Film2Store.continueTitleIds(), cont = [];
    for (i = 0; i < contIds.length; i += 1) { title = byId(contIds[i]); if (title) cont.push(title); }
    if (cont.length) shelves += shelf('Devam Et', cont, true);

    var editorial = S.home.shelves || [], used = {};
    for (i = 0; i < editorial.length; i += 1) {
        var sh = editorial[i]; if (sh.enabled === false) continue;
        var arr = [];
        for (var j = 0; j < (sh.titleIds || []).length; j += 1) { title = byId(sh.titleIds[j]); if (title && playable(title)) { arr.push(title); used[title.id] = 1; } }
        if (sh.shuffle) arr = shuffled(arr, S.seed + i * 97);
        if (sh.maxItems) arr = arr.slice(0, sh.maxItems);
        shelves += shelf(sh.title || 'Seçkiler', arr, false);
    }

    var myList = [];
    for (i = 0; i < state.myListTitleIds.length; i += 1) { title = byId(state.myListTitleIds[i]); if (title) myList.push(title); }
    if (myList.length) shelves += shelf('Listem', myList, false);

    var genres = Film2Catalog.groupByGenre(S.titles);
    for (i = 0; i < genres.length; i += 1) shelves += shelf(genres[i].genre, genres[i].titles, false);
    if (!shelves) shelves = shelf('Tüm İçerikler', S.titles, false);

    E['home-content'].innerHTML = html + '<div class="shelves">' + shelves + '</div>';
    E['catalog-status'].textContent = 'Güncel';
    setScreen('home');
    setTimeout(focusFirst, 40);
}

function canDownloadMedia(media) {
    if (!media || !media.asset || !window.Film2Offline || !Film2Offline.supported()) return false;
    if (media.asset.videoUrl && /^https?:\/\//i.test(media.asset.videoUrl)) return true;
    var variants = media.asset.videoVariants || [];
    for (var i = 0; i < variants.length; i += 1) if (variants[i].url && /^https?:\/\//i.test(variants[i].url)) return true;
    return false;
}
function offlineProgress(media) { return window.Film2Offline && media ? Film2Offline.progress(media) : null; }
function offlineLabel(media, compact) {
    if (!canDownloadMedia(media)) return '';
    var p = offlineProgress(media), prefix = compact ? '' : '↓ ';
    if (!p) return prefix + 'İndir';
    if (p.status === 'complete') return compact ? '✓' : '✓ İndirildi • Kaldır';
    if (p.status === 'failed') return compact ? '↻' : '↻ Tekrar İndir';
    if (p.status === 'paused') return compact ? 'Ⅱ' : 'Ⅱ İndirme Durakladı';
    var pct = p.total > 0 ? Math.round(p.fraction * 100) : 0;
    return compact ? ('↓' + (pct ? ' ' + pct + '%' : '')) : ('↓ ' + (pct ? '%' + pct + ' • ' : '') + 'İptal');
}
function preferredQualityLabel(media) {
    var rec = Film2Store.playbackFor(media);
    return rec && rec.qualityLabel ? rec.qualityLabel : null;
}
function toggleOffline(media) {
    if (!canDownloadMedia(media)) { toast('Bu içerik çevrimdışı indirmeyi desteklemiyor.'); return; }
    var p = Film2Offline.progress(media);
    try {
        if (p && p.status !== 'failed') {
            Film2Offline.remove(media); toast('İndirme kaldırıldı.');
        } else {
            Film2Offline.enqueue(media, preferredQualityLabel(media)); toast('İndirme başlatıldı.');
        }
        updateOfflineIndicators();
    } catch (e) { toast('İndirme başlatılamadı: ' + (e.message || e), 5000); }
}
function seasonMedia(title, seasonIndex) {
    var out = [], season = title && title.seasons && title.seasons[seasonIndex];
    if (!season) return out;
    for (var e = 0; e < season.episodes.length; e += 1) {
        var ep = season.episodes[e];
        if (ep.status === 'ready' && ep.asset && (ep.asset.videoUrl || (ep.asset.videoVariants && ep.asset.videoVariants.length))) {
            var media = episodeMedia(title, seasonIndex, e); if (canDownloadMedia(media)) out.push(media);
        }
    }
    return out;
}
function seasonOfflineLabel(title, seasonIndex) {
    var media = seasonMedia(title, seasonIndex); if (!media.length) return '';
    var completed = 0, active = 0, received = 0, total = 0;
    for (var i = 0; i < media.length; i += 1) {
        var p = offlineProgress(media[i]);
        if (!p) continue;
        if (p.status === 'complete') completed += 1;
        if (p.status === 'downloading' || p.status === 'queued' || p.status === 'paused') active += 1;
        received += Number(p.received || 0); total += Number(p.total || 0);
    }
    if (completed === media.length) return '✓ Sezon İndirildi • Kaldır';
    if (active) return '↓ Sezon ' + (total > 0 ? '%' + Math.round(received / total * 100) : 'indiriliyor') + ' • İptal';
    return '↓ Sezonu İndir';
}
function toggleSeasonOffline(seasonIndex) {
    if (!S.title) return;
    var list = seasonMedia(S.title, seasonIndex), i, hasAny = false;
    for (i = 0; i < list.length; i += 1) if (Film2Offline.progress(list[i])) { hasAny = true; break; }
    if (!list.length) { toast('Bu sezonda doğrudan indirilebilir bölüm yok.'); return; }
    try {
        if (hasAny) {
            for (i = 0; i < list.length; i += 1) if (Film2Offline.progress(list[i])) Film2Offline.remove(list[i]);
            toast('Sezon indirmeleri kaldırıldı.');
        } else {
            for (i = 0; i < list.length; i += 1) Film2Offline.enqueue(list[i], preferredQualityLabel(list[i]));
            toast('Sezon indirmesi başlatıldı.');
        }
        updateOfflineIndicators();
    } catch (e) { toast('Sezon indirmesi başlatılamadı: ' + (e.message || e), 5000); }
}
function updateOfflineIndicators() {
    if (S.screen !== 'detail' || !S.title || !window.Film2Offline) return;
    var current = document.querySelector('[data-download-current]');
    if (current) {
        var rec = Film2Store.latestForTitle(S.title.id), media = Film2Store.meaningful(rec) ? mediaFromRecord(S.title, rec) : firstMedia(S.title);
        current.textContent = offlineLabel(media, false) || 'İndirilemez';
    }
    var eps = document.querySelectorAll('[data-download-episode]');
    for (var i = 0; i < eps.length; i += 1) {
        var parts = eps[i].getAttribute('data-download-episode').split(':'), em = episodeMedia(S.title, Number(parts[0]), Number(parts[1]));
        eps[i].textContent = offlineLabel(em, true) || '—';
    }
    var seasons = document.querySelectorAll('[data-download-season]');
    for (i = 0; i < seasons.length; i += 1) seasons[i].textContent = seasonOfflineLabel(S.title, Number(seasons[i].getAttribute('data-download-season'))) || 'İndirilemez';
}
