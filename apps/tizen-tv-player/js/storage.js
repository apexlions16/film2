(function (g) {
'use strict';

var KEY = 'film2.tv.user-library.v2';
var defaults = {
    playback: {},
    myListTitleIds: [],
    customLists: [],
    subtitleStyle: { fontScale: 1.08, bottomPercent: 12, color: 'white', background: 'soft', edge: 'outline' },
    defaultDisplayMode: 'fit'
};
function clone(v){return JSON.parse(JSON.stringify(v));}
function safeJson(raw,fallback){try{return JSON.parse(raw);}catch(e){return fallback;}}
function clamp(n,min,max){return Math.max(min,Math.min(max,Number(n)||0));}
function normalizeState(raw){var s=raw&&typeof raw==='object'?raw:{},o=clone(defaults),k;o.playback=s.playback&&typeof s.playback==='object'?s.playback:{};o.myListTitleIds=Array.isArray(s.myListTitleIds)?s.myListTitleIds:[];o.customLists=Array.isArray(s.customLists)?s.customLists:[];if(s.subtitleStyle&&typeof s.subtitleStyle==='object')for(k in o.subtitleStyle)if(Object.prototype.hasOwnProperty.call(s.subtitleStyle,k))o.subtitleStyle[k]=s.subtitleStyle[k];o.subtitleStyle.fontScale=clamp(o.subtitleStyle.fontScale||1.08,.65,1.75);o.subtitleStyle.bottomPercent=clamp(o.subtitleStyle.bottomPercent||12,4,32);o.defaultDisplayMode=s.defaultDisplayMode||'fit';return o;}
function read(){try{return normalizeState(safeJson(localStorage.getItem(KEY),null));}catch(e){return clone(defaults);}}
function write(next){var c=normalizeState(next);try{localStorage.setItem(KEY,JSON.stringify(c));var ev;try{ev=new CustomEvent('film2:library');}catch(e){ev=document.createEvent('Event');ev.initEvent('film2:library',true,true);}g.dispatchEvent(ev);}catch(e2){}return c;}
function mediaKey(m){return m?String(m.titleId||'')+'|'+(m.season!=null?m.season:-1)+'|'+(m.episode!=null?m.episode:-1):'';}
function oldMediaKey(m){return String(m.titleId||'')+(m.season!=null?'.s'+m.season+'.e'+m.episode:'.movie');}
function migrateLegacy(m){var st=read(),k=mediaKey(m);if(st.playback[k])return st.playback[k];try{var legacy=safeJson(localStorage.getItem('film2.tv.progress.'+oldMediaKey(m)),null);if(!legacy)return null;var q=safeJson(localStorage.getItem('film2.tv.pref.'+oldMediaKey(m)+'.quality'),null),a=safeJson(localStorage.getItem('film2.tv.pref.'+oldMediaKey(m)+'.audio'),null),s=safeJson(localStorage.getItem('film2.tv.pref.'+oldMediaKey(m)+'.subtitle'),null);var r={key:k,titleId:m.titleId,season:m.season,episode:m.episode,positionMs:Number(legacy.position||0),durationMs:Number(legacy.duration||0),finished:!!legacy.finished,updatedAt:Number(legacy.updatedAt||Date.now()),qualityLabel:typeof q==='string'?q:null,audio:a||null,subtitle:s||null,subtitlesDisabled:s&&s.type==='off',displayMode:st.defaultDisplayMode};st.playback[k]=r;write(st);return r;}catch(e){return null;}}
function playbackFor(m){var st=read(),k=mediaKey(m);return st.playback[k]||migrateLegacy(m)||null;}
function savePlayback(m,snap){if(!m)return null;var st=read(),k=mediaKey(m),old=st.playback[k]||{},dur=Math.max(Number(snap.durationMs||0),Number(old.durationMs||0)),pos=Math.max(0,Number(snap.positionMs||0));if(dur>0)pos=Math.min(pos,dur);var r={key:k,titleId:m.titleId,season:m.season,episode:m.episode,positionMs:pos,durationMs:dur,finished:dur>0&&pos/dur>=.95,updatedAt:Date.now(),qualityLabel:snap.qualityLabel!=null?snap.qualityLabel:old.qualityLabel||null,audio:snap.audio!=null?snap.audio:old.audio||null,subtitle:snap.subtitle!=null?snap.subtitle:old.subtitle||null,subtitlesDisabled:snap.subtitlesDisabled!=null?!!snap.subtitlesDisabled:!!old.subtitlesDisabled,displayMode:snap.displayMode||old.displayMode||st.defaultDisplayMode};st.playback[k]=r;write(st);return r;}
function progressFraction(r){return !r||Number(r.durationMs||0)<=0?0:clamp(Number(r.positionMs||0)/Number(r.durationMs),0,1);}
function meaningful(r){var p=progressFraction(r);return !!r&&Number(r.positionMs||0)>=30000&&p<.95;}
function latestForTitle(id){var st=read(),best=null,k,r;for(k in st.playback)if(Object.prototype.hasOwnProperty.call(st.playback,k)){r=st.playback[k];if(r.titleId===id&&(!best||Number(r.updatedAt||0)>Number(best.updatedAt||0)))best=r;}return best;}
function continueTitleIds(){var st=read(),rows=[],seen={},k,r,ids=[];for(k in st.playback)if(Object.prototype.hasOwnProperty.call(st.playback,k)){r=st.playback[k];if(meaningful(r))rows.push(r);}rows.sort(function(a,b){return Number(b.updatedAt||0)-Number(a.updatedAt||0);});for(var i=0;i<rows.length;i++)if(!seen[rows[i].titleId]){seen[rows[i].titleId]=true;ids.push(rows[i].titleId);}return ids;}
function toggleMyList(id){var st=read(),found=false,next=[];for(var i=0;i<st.myListTitleIds.length;i++){if(st.myListTitleIds[i]===id)found=true;else next.push(st.myListTitleIds[i]);}if(!found)next.push(id);st.myListTitleIds=next;write(st);return !found;}
function inMyList(id){return read().myListTitleIds.indexOf(id)>=0;}
function uid(){return 'list-'+Date.now().toString(36)+'-'+Math.floor(Math.random()*0xffff).toString(36);}
function createCustomList(name){var clean=String(name||'').replace(/^\s+|\s+$/g,'').slice(0,64);if(!clean)return null;var st=read(),list={id:uid(),name:clean,titleIds:[]};st.customLists.push(list);write(st);return list;}
function toggleCustom(listId,titleId){var st=read(),enabled=false;for(var i=0;i<st.customLists.length;i++){var list=st.customLists[i];if(list.id!==listId)continue;var idx=list.titleIds.indexOf(titleId);if(idx>=0)list.titleIds.splice(idx,1);else{list.titleIds.push(titleId);enabled=true;}}write(st);return enabled;}
function setSubtitleStyle(patch){var st=read(),k;for(k in patch)if(Object.prototype.hasOwnProperty.call(patch,k))st.subtitleStyle[k]=patch[k];st.subtitleStyle.fontScale=clamp(st.subtitleStyle.fontScale,.65,1.75);st.subtitleStyle.bottomPercent=clamp(st.subtitleStyle.bottomPercent,4,32);write(st);return st.subtitleStyle;}
function resetSubtitleStyle(){var st=read();st.subtitleStyle=clone(defaults.subtitleStyle);write(st);return st.subtitleStyle;}
function setDefaultDisplayMode(mode){var st=read();st.defaultDisplayMode=mode||'fit';write(st);return st.defaultDisplayMode;}
g.Film2Store={read:read,write:write,mediaKey:mediaKey,playbackFor:playbackFor,savePlayback:savePlayback,progressFraction:progressFraction,meaningful:meaningful,latestForTitle:latestForTitle,continueTitleIds:continueTitleIds,toggleMyList:toggleMyList,inMyList:inMyList,createCustomList:createCustomList,toggleCustom:toggleCustom,setSubtitleStyle:setSubtitleStyle,resetSubtitleStyle:resetSubtitleStyle,setDefaultDisplayMode:setDefaultDisplayMode,defaults:clone(defaults)};
}(window));
