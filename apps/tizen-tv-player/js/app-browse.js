'use strict';
function detail(titleId) {
    var title = byId(titleId); if (!title) return;
    S.title = title;
    var backdrop = Film2Catalog.chooseArtwork(title, 'backdrop', S.seed);
    E['detail-backdrop'].style.backgroundImage = backdrop ? 'url("' + String(backdrop).replace(/"/g, '') + '")' : '';
    var rec = Film2Store.latestForTitle(title.id), resumeMedia = mediaFromRecord(title, rec), first = firstMedia(title);
    var canResume = Film2Store.meaningful(rec) && resumeMedia;
    var currentMedia = canResume ? resumeMedia : first;
    var playLabel = canResume ? ('▶ Devam Et' + (rec.season != null ? ' • S' + rec.season + ' B' + rec.episode : ' • ' + Math.floor(rec.positionMs / 60000) + ' dk')) : (title.type === 'series' ? '▶ İlk Bölüm' : '▶ Oynat');
    var inList = Film2Store.inMyList(title.id), meta = [];
    if (title.releaseYear) meta.push(title.releaseYear);
    meta.push(title.type === 'series' ? 'Dizi' : 'Film');
    if (title.runtimeMinutes) meta.push(title.runtimeMinutes + ' dk');
    meta = meta.concat((title.genres || []).slice(0, 3));

    var html = '<div class="detail-main">' +
        (title.logoUrl ? '<img class="detail-logo" src="' + esc(title.logoUrl) + '" alt="">' : '<h1 class="detail-title">' + esc(title.title) + '</h1>') +
        '<div class="detail-meta"><span>' + esc(meta.join(' • ')) + '</span></div>' +
        '<div class="detail-overview">' + esc(title.overview || '') + '</div>' +
        '<div class="detail-actions">' +
        '<button class="focusable detail-button primary" data-play-title="' + esc(title.id) + '">' + playLabel + '</button>' +
        '<button class="focusable detail-button" data-my-list="' + esc(title.id) + '">' + (inList ? '✓ Listemde' : '+ Listem') + '</button>' +
        '<button class="focusable detail-button" data-custom-lists="' + esc(title.id) + '">☰ Listeler</button>' +
        (canDownloadMedia(currentMedia) ? '<button class="focusable detail-button" data-download-current="1">' + esc(offlineLabel(currentMedia, false)) + '</button>' : '') +
        (title.trailerUrl ? '<button class="focusable detail-button" data-trailer-mute="1">🔇 Trailer</button>' : '') +
        '<button class="focusable detail-button" data-nav-home="1">Geri</button></div>';
    var progress = Film2Store.progressFraction(rec);
    if (progress > 0.005) html += '<div class="detail-progress"><span style="width:' + Math.round(progress * 100) + '%"></span></div>';
    if (title.cast.length) html += '<p class="detail-people"><b>Başroldekiler:</b> ' + esc(title.cast.slice(0, 8).map(function (p) { return p.name; }).join(', ')) + '</p>';
    if (title.crew.length) html += '<p class="detail-people"><b>Yapım:</b> ' + esc(title.crew.slice(0, 6).map(function (p) { return p.name + ' (' + p.job + ')'; }).join(', ')) + '</p>';
    html += '</div>';

    if (title.type === 'series') {
        for (var s = 0; s < title.seasons.length; s += 1) {
            var season = title.seasons[s], episodes = '', seasonLabel = seasonOfflineLabel(title, s);
            for (var e = 0; e < season.episodes.length; e += 1) {
                var ep = season.episodes[e];
                if (ep.status !== 'ready' || !ep.asset || !(ep.asset.videoUrl || ep.asset.masterPlaylistUrl)) continue;
                var media = episodeMedia(title, s, e), epRecord = Film2Store.playbackFor(media), epProgress = Film2Store.progressFraction(epRecord);
                episodes += '<div class="episode-item"><button class="focusable episode-card" data-episode="' + s + ':' + e + '">' +
                    '<div class="ep-no">Bölüm ' + esc(ep.episodeNumber) + (ep.runtimeMinutes ? ' • ' + esc(ep.runtimeMinutes) + ' dk' : '') + '</div>' +
                    '<div class="ep-title">' + esc(ep.title) + '</div><div class="ep-overview">' + esc(ep.overview || '') + '</div>' +
                    (epProgress > 0.005 ? '<div class="episode-progress"><span style="width:' + Math.round(epProgress * 100) + '%"></span></div>' : '') + '</button>' +
                    (canDownloadMedia(media) ? '<button class="focusable episode-download" data-download-episode="' + s + ':' + e + '">' + esc(offlineLabel(media, true)) + '</button>' : '') + '</div>';
            }
            if (episodes) html += '<section class="detail-section"><div class="season-heading"><h2>' + esc(season.name || ('Sezon ' + season.seasonNumber)) + '</h2>' +
                (seasonLabel ? '<button class="focusable season-download" data-download-season="' + s + '">' + esc(seasonLabel) + '</button>' : '') + '</div><div class="episode-list">' + episodes + '</div></section>';
        }
    }

    E['detail-content'].innerHTML = html;
    setScreen('detail');
    startTrailer(title);
    updateOfflineIndicators();
    setTimeout(focusFirst, 60);
}

function playTitle(titleId) {
    var title = byId(titleId); if (!title) return;
    S.title = title;
    var rec = Film2Store.latestForTitle(title.id), media = Film2Store.meaningful(rec) ? mediaFromRecord(title, rec) : null;
    start(media || firstMedia(title));
}

function renderSearch() {
    setScreen('search');
    E['search-input'].value = S.searchQuery || '';
    renderSearchResults();
    setTimeout(function () { focus(E['search-input']); }, 50);
}
function renderSearchResults() {
    S.searchQuery = E['search-input'].value || '';
    var results = Film2Catalog.searchTitles(S.titles, S.searchQuery), html = '';
    if (!results.length) html = '<div class="empty-state compact">Sonuç bulunamadı.</div>';
    else for (var i = 0; i < results.length; i += 1) html += card(results[i]);
    E['search-results'].innerHTML = html;
}

function renderLibrary() {
    var state = Film2Store.read(), html = '<div class="page-heading"><h1>Listelerim</h1><button class="focusable page-button" data-new-list="1">+ Yeni Liste</button><button class="focusable page-button" data-nav-home="1">Geri</button></div>';
    var items = [], title, i;
    if (window.Film2Offline) {
        var offline = Film2Offline.read(), offlineIds = [], seen = {}, k;
        for (k in offline.records) if (Object.prototype.hasOwnProperty.call(offline.records, k) && offline.records[k].status === 'complete' && !seen[offline.records[k].titleId]) { seen[offline.records[k].titleId] = 1; offlineIds.push(offline.records[k].titleId); }
        var offlineTitles = [];
        for (i = 0; i < offlineIds.length; i += 1) { title = byId(offlineIds[i]); if (title) offlineTitles.push(title); }
        if (offlineTitles.length) html += '<section class="library-section"><h2>İndirilenler</h2><div class="library-grid">' + offlineTitles.map(card).join('') + '</div></section>';
    }
    for (i = 0; i < state.myListTitleIds.length; i += 1) { title = byId(state.myListTitleIds[i]); if (title) items.push(title); }
    html += '<section class="library-section"><h2>Listem</h2>' + (items.length ? '<div class="library-grid">' + items.map(card).join('') + '</div>' : '<div class="library-empty">Henüz içerik eklemedin.</div>') + '</section>';
    for (i = 0; i < state.customLists.length; i += 1) {
        var list = state.customLists[i], listTitles = [];
        for (var j = 0; j < list.titleIds.length; j += 1) { title = byId(list.titleIds[j]); if (title) listTitles.push(title); }
        html += '<section class="library-section"><h2>' + esc(list.name) + '</h2>' + (listTitles.length ? '<div class="library-grid">' + listTitles.map(card).join('') + '</div>' : '<div class="library-empty">Bu liste boş.</div>') + '</section>';
    }
    E['library-content'].innerHTML = html;
    setScreen('library');
    setTimeout(focusFirst, 40);
}

function openTitleLists(titleId) {
    var state = Film2Store.read(), opts = [], i;
    for (i = 0; i < state.customLists.length; i += 1) {
        opts.push({ label: state.customLists[i].name, meta: state.customLists[i].titleIds.indexOf(titleId) >= 0 ? '✓ Bu listede' : 'Listeye ekle', listId: state.customLists[i].id, titleId: titleId, selected: state.customLists[i].titleIds.indexOf(titleId) >= 0 });
    }
    opts.push({ label: '+ Yeni liste oluştur', special: 'new-list', titleId: titleId });
    openModal('Listeler', opts, function (item) {
        if (item.special === 'new-list') {
            openTextInput('Yeni Liste', 'Liste adı', function (name) {
                var list = Film2Store.createCustomList(name);
                if (list) { Film2Store.toggleCustom(list.id, titleId); toast('Liste oluşturuldu.'); if (S.screen === 'detail') detail(titleId); }
            });
        } else {
            var enabled = Film2Store.toggleCustom(item.listId, titleId);
            toast(enabled ? 'Listeye eklendi.' : 'Listeden çıkarıldı.');
        }
    });
}
