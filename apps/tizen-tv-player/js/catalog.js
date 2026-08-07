(function (global) {
    'use strict';

    var REPO = 'apexlions16/film2';
    var BRANCH = 'main';
    var API = 'https://api.github.com/repos/' + REPO;
    var RAW = 'https://raw.githubusercontent.com/' + REPO + '/' + BRANCH + '/';

    function xhrText(url) {
        return new Promise(function (resolve, reject) {
            var xhr = new XMLHttpRequest();
            xhr.open('GET', url, true);
            xhr.timeout = 20000;
            xhr.onreadystatechange = function () {
                if (xhr.readyState !== 4) { return; }
                if (xhr.status >= 200 && xhr.status < 300) {
                    resolve(xhr.responseText);
                } else {
                    reject(new Error('HTTP ' + xhr.status + ' — ' + url));
                }
            };
            xhr.onerror = function () { reject(new Error('Ağ hatası — ' + url)); };
            xhr.ontimeout = function () { reject(new Error('İstek zaman aşımına uğradı — ' + url)); };
            xhr.send();
        });
    }

    function xhrJson(url) {
        return xhrText(url).then(function (text) { return JSON.parse(text); });
    }

    function normalizeAsset(asset) {
        if (!asset) { return null; }
        return {
            videoUrl: asset.videoUrl || null,
            masterPlaylistUrl: asset.masterPlaylistUrl || null,
            durationSeconds: Number(asset.durationSeconds || 0),
            audioLanguages: asset.audioLanguages || [],
            subtitleLanguages: asset.subtitleLanguages || [],
            externalAudioTracks: asset.externalAudioTracks || [],
            externalSubtitleTracks: asset.externalSubtitleTracks || [],
            videoVariants: asset.videoVariants || []
        };
    }

    function normalizeTitle(raw) {
        var title = raw || {};
        title.asset = normalizeAsset(title.asset);
        title.genres = title.genres || [];
        title.cast = title.cast || [];
        title.crew = title.crew || [];
        title.seasons = title.seasons || [];
        for (var s = 0; s < title.seasons.length; s += 1) {
            var season = title.seasons[s];
            season.episodes = season.episodes || [];
            for (var e = 0; e < season.episodes.length; e += 1) {
                season.episodes[e].asset = normalizeAsset(season.episodes[e].asset);
            }
        }
        return title;
    }

    function titleIsPlayable(title) {
        if (!title) { return false; }
        if (title.type === 'movie') {
            return title.status === 'ready' && !!(title.asset && (title.asset.videoUrl || title.asset.masterPlaylistUrl));
        }
        for (var s = 0; s < title.seasons.length; s += 1) {
            var episodes = title.seasons[s].episodes || [];
            for (var e = 0; e < episodes.length; e += 1) {
                var ep = episodes[e];
                if (ep.status === 'ready' && ep.asset && (ep.asset.videoUrl || ep.asset.masterPlaylistUrl)) {
                    return true;
                }
            }
        }
        return false;
    }

    function listTitleIds() {
        return xhrJson(API + '/contents/catalog/titles?ref=' + encodeURIComponent(BRANCH)).then(function (entries) {
            var ids = [];
            for (var i = 0; i < entries.length; i += 1) {
                var item = entries[i];
                if (item.type === 'file' && /\.json$/i.test(item.name) && item.name.charAt(0) !== '_') {
                    ids.push(item.name.replace(/\.json$/i, ''));
                }
            }
            return ids;
        });
    }

    function getTitle(id) {
        return xhrJson(RAW + 'catalog/titles/' + encodeURIComponent(id) + '.json').then(normalizeTitle);
    }

    function loadTitles() {
        return listTitleIds().then(function (ids) {
            var jobs = [];
            for (var i = 0; i < ids.length; i += 1) { jobs.push(getTitle(ids[i])); }
            return Promise.all(jobs).then(function (titles) {
                return titles.filter(titleIsPlayable).sort(function (a, b) {
                    return String(a.title || '').localeCompare(String(b.title || ''), 'tr');
                });
            });
        });
    }

    function loadHome() {
        return xhrJson(RAW + 'catalog/home.json').catch(function () {
            return { heroTitleIds: [], shelves: [] };
        });
    }

    function loadRevision() {
        return xhrJson(RAW + 'catalog/version.json?_=' + Date.now()).then(function (data) {
            return data && data.revision ? String(data.revision) : '';
        }).catch(function () { return ''; });
    }

    global.Film2Catalog = {
        repo: REPO,
        branch: BRANCH,
        rawBase: RAW,
        loadTitles: loadTitles,
        loadHome: loadHome,
        loadRevision: loadRevision,
        getTitle: getTitle,
        normalizeAsset: normalizeAsset,
        titleIsPlayable: titleIsPlayable,
        xhrText: xhrText
    };
}(window));
