import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import Hls, { type ErrorData, type MediaPlaylist } from 'hls.js'

import type { ExternalMediaTrack, PlayableAsset, VideoVariant } from '../../lib/types'
import {
  contentKey,
  playbackFor,
  readLibrary,
  savePlayback,
  setDefaultAspectMode,
  setSubtitleStyle,
  type AspectMode,
  type SubtitleBackground,
  type SubtitleEdge
} from '../../lib/userLibrary'
import { activeCueText, fetchSubtitleCues, type SubtitleCue } from '../../lib/subtitles'
import styles from './Player.module.css'

type AudioTrackLike = { id?: string; label?: string; language?: string; enabled: boolean }
type AudioTrackListLike = { length: number; [index: number]: AudioTrackLike }
type VideoWithAudioTracks = HTMLVideoElement & { audioTracks?: AudioTrackListLike }

type PlaybackIdentity = {
  titleId: string
  seasonNumber?: number
  episodeNumber?: number
}

function formatTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '0:00'
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = Math.floor(seconds % 60)
  const mm = h > 0 ? String(m).padStart(2, '0') : String(m)
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

function languageLabel(code?: string): string {
  const key = (code ?? '').toLowerCase()
  if (['tr', 'tur', 'trke', 'turkish'].includes(key)) return 'Türkçe'
  if (['en', 'eng', 'english'].includes(key)) return 'İngilizce'
  try {
    return code ? new Intl.DisplayNames(['tr'], { type: 'language' }).of(code) ?? code : 'Ses'
  } catch {
    return code || 'Ses'
  }
}

function normalizedLanguage(value?: string): string | undefined {
  const key = value?.trim().toLowerCase()
  if (!key) return undefined
  if (['tr', 'tur', 'trke', 'turkish', 'türkçe', 'turkce'].includes(key)) return 'tur'
  if (['en', 'eng', 'english', 'ingilizce'].includes(key)) return 'eng'
  return key
}

export function Player({
  asset,
  heading,
  subheading,
  identity,
  onClose
}: {
  asset: PlayableAsset
  heading: string
  subheading?: string
  identity: PlaybackIdentity
  onClose: () => void
}) {
  const videoRef = useRef<VideoWithAudioTracks | null>(null)
  const containerRef = useRef<HTMLDivElement | null>(null)
  const hlsRef = useRef<Hls | null>(null)
  const hideTimer = useRef<number | null>(null)
  const pendingSeek = useRef<number | null>(null)
  const pendingPlay = useRef(true)

  const key = contentKey(identity.titleId, identity.seasonNumber, identity.episodeNumber)
  const initialRecord = useMemo(() => playbackFor(identity.titleId, identity.seasonNumber, identity.episodeNumber), [key])
  const initialLibrary = useMemo(() => readLibrary(), [key])

  const [ready, setReady] = useState(false)
  const [fatalError, setFatalError] = useState<string | null>(null)
  const [playing, setPlaying] = useState(false)
  const [currentTime, setCurrentTime] = useState(initialRecord?.positionSeconds ?? 0)
  const [duration, setDuration] = useState(initialRecord?.durationSeconds ?? 0)
  const [buffered, setBuffered] = useState(0)
  const [volume, setVolume] = useState(1)
  const [muted, setMuted] = useState(false)
  const [fullscreen, setFullscreen] = useState(false)
  const [controlsVisible, setControlsVisible] = useState(true)
  const [settingsOpen, setSettingsOpen] = useState(false)

  const variants = asset.videoVariants ?? []
  const defaultVariant = variants.find((v) => v.height === initialRecord?.qualityHeight) ?? variants.slice().sort((a, b) => b.height - a.height)[0]
  const [qualityHeight, setQualityHeight] = useState<number | undefined>(defaultVariant?.height ?? initialRecord?.qualityHeight)
  const [sourceUrl, setSourceUrl] = useState<string | undefined>(defaultVariant?.url ?? asset.videoUrl ?? asset.masterPlaylistUrl)
  const [usingHls, setUsingHls] = useState(!defaultVariant?.url && !asset.videoUrl && Boolean(asset.masterPlaylistUrl))

  const [legacyAudioTracks, setLegacyAudioTracks] = useState<MediaPlaylist[]>([])
  const [legacyAudioTrackId, setLegacyAudioTrackId] = useState(-1)
  const [nativeAudioTracks, setNativeAudioTracks] = useState<AudioTrackLike[]>([])
  const [audioLanguage, setAudioLanguage] = useState<string | undefined>(initialRecord?.audioLanguage)

  const [subtitleTracks, setSubtitleTracks] = useState<ExternalMediaTrack[]>(asset.externalSubtitleTracks ?? [])
  const [subtitleLanguage, setSubtitleLanguage] = useState<string | undefined>(initialRecord?.subtitleLanguage)
  const [subtitlesDisabled, setSubtitlesDisabled] = useState(initialRecord?.subtitlesDisabled ?? false)
  const [subtitleCues, setSubtitleCues] = useState<SubtitleCue[]>([])
  const [subtitleText, setSubtitleText] = useState('')
  const [subtitleStyle, setSubtitleStyleState] = useState(initialLibrary.subtitleStyle)
  const [aspectMode, setAspectMode] = useState<AspectMode>(initialRecord?.aspectMode ?? initialLibrary.defaultAspectMode)

  const persist = useCallback(() => {
    const video = videoRef.current
    if (!video || !Number.isFinite(video.duration) || video.duration <= 0) return
    savePlayback({
      titleId: identity.titleId,
      seasonNumber: identity.seasonNumber,
      episodeNumber: identity.episodeNumber,
      positionSeconds: video.currentTime,
      durationSeconds: video.duration,
      audioLanguage,
      subtitleLanguage,
      subtitlesDisabled,
      qualityHeight,
      aspectMode
    })
  }, [identity.titleId, identity.seasonNumber, identity.episodeNumber, audioLanguage, subtitleLanguage, subtitlesDisabled, qualityHeight, aspectMode])

  useEffect(() => {
    let cancelled = false
    void window.film2?.offline.localPlayback(key).then((offline) => {
      if (cancelled || !offline?.videoLocalUrl) return
      setUsingHls(false)
      setSourceUrl(offline.videoLocalUrl)
      setSubtitleTracks(
        offline.subtitleRecords
          .filter((track) => track.localUrl)
          .map((track) => ({ language: track.language, label: track.label, mimeType: track.mimeType, url: track.localUrl! }))
      )
    })
    return () => { cancelled = true }
  }, [key])

  useEffect(() => {
    const video = videoRef.current
    if (!video || !sourceUrl) return
    setReady(false)
    setFatalError(null)
    hlsRef.current?.destroy()
    hlsRef.current = null

    if (usingHls && asset.masterPlaylistUrl) {
      if (Hls.isSupported()) {
        const hls = new Hls({ enableWorker: true })
        hlsRef.current = hls
        hls.on(Hls.Events.MANIFEST_PARSED, () => {
          setReady(true)
          const savedAudio = normalizedLanguage(audioLanguage)
          if (savedAudio) {
            const index = hls.audioTracks.findIndex((track) => normalizedLanguage(track.lang) === savedAudio)
            if (index >= 0) hls.audioTrack = index
          }
          video.play().catch(() => {})
        })
        hls.on(Hls.Events.AUDIO_TRACKS_UPDATED, (_evt, data) => setLegacyAudioTracks(data.audioTracks))
        hls.on(Hls.Events.AUDIO_TRACK_SWITCHED, (_evt, data) => setLegacyAudioTrackId(data.id))
        hls.on(Hls.Events.ERROR, (_evt, data: ErrorData) => {
          if (!data.fatal) return
          if (data.type === Hls.ErrorTypes.NETWORK_ERROR) hls.startLoad()
          else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) hls.recoverMediaError()
          else setFatalError('Bu yayın oynatılamadı.')
        })
        hls.loadSource(asset.masterPlaylistUrl)
        hls.attachMedia(video)
        return () => hls.destroy()
      }
      setFatalError('Bu ortam eski HLS akışını desteklemiyor.')
      return
    }

    video.src = sourceUrl
    video.load()
    return undefined
  }, [sourceUrl, usingHls, asset.masterPlaylistUrl])

  const syncNativeAudioTracks = useCallback(() => {
    const list = videoRef.current?.audioTracks
    if (!list) {
      setNativeAudioTracks([])
      return
    }
    const values: AudioTrackLike[] = []
    for (let i = 0; i < list.length; i++) values.push(list[i])
    setNativeAudioTracks(values)

    const preferred = normalizedLanguage(audioLanguage)
    if (preferred && values.length > 0) {
      let matched = false
      values.forEach((track) => {
        const enabled = normalizedLanguage(track.language) === preferred
        track.enabled = enabled
        if (enabled) matched = true
      })
      if (!matched && !values.some((track) => track.enabled)) values[0].enabled = true
    }
  }, [audioLanguage])

  useEffect(() => {
    const video = videoRef.current
    if (!video) return

    const onPlay = (): void => setPlaying(true)
    const onPause = (): void => setPlaying(false)
    const onTimeUpdate = (): void => {
      setCurrentTime(video.currentTime)
      setSubtitleText(subtitlesDisabled ? '' : activeCueText(subtitleCues, video.currentTime))
    }
    const onDurationChange = (): void => setDuration(Number.isFinite(video.duration) ? video.duration : 0)
    const onVolumeChange = (): void => { setVolume(video.volume); setMuted(video.muted) }
    const onProgress = (): void => {
      const ranges = video.buffered
      if (ranges.length > 0) setBuffered(ranges.end(ranges.length - 1))
    }
    const onLoadedMetadata = (): void => {
      setReady(true)
      syncNativeAudioTracks()
      const target = pendingSeek.current ?? initialRecord?.positionSeconds ?? 0
      if (target > 0 && target < video.duration - 5) video.currentTime = target
      pendingSeek.current = null
      if (pendingPlay.current) video.play().catch(() => {})
    }

    video.addEventListener('play', onPlay)
    video.addEventListener('pause', onPause)
    video.addEventListener('timeupdate', onTimeUpdate)
    video.addEventListener('durationchange', onDurationChange)
    video.addEventListener('volumechange', onVolumeChange)
    video.addEventListener('progress', onProgress)
    video.addEventListener('loadedmetadata', onLoadedMetadata)

    return () => {
      video.removeEventListener('play', onPlay)
      video.removeEventListener('pause', onPause)
      video.removeEventListener('timeupdate', onTimeUpdate)
      video.removeEventListener('durationchange', onDurationChange)
      video.removeEventListener('volumechange', onVolumeChange)
      video.removeEventListener('progress', onProgress)
      video.removeEventListener('loadedmetadata', onLoadedMetadata)
    }
  }, [subtitleCues, subtitlesDisabled, syncNativeAudioTracks, initialRecord?.positionSeconds])

  useEffect(() => {
    const selected = subtitleTracks.find((track) => normalizedLanguage(track.language) === normalizedLanguage(subtitleLanguage))
      ?? (!subtitlesDisabled ? subtitleTracks[0] : undefined)
    if (!selected || subtitlesDisabled) {
      setSubtitleCues([])
      setSubtitleText('')
      return
    }
    if (!subtitleLanguage) setSubtitleLanguage(selected.language)
    let cancelled = false
    void fetchSubtitleCues(selected.url)
      .then((cues) => { if (!cancelled) setSubtitleCues(cues) })
      .catch(() => { if (!cancelled) setSubtitleCues([]) })
    return () => { cancelled = true }
  }, [subtitleLanguage, subtitlesDisabled, subtitleTracks])

  useEffect(() => {
    const interval = window.setInterval(persist, 5_000)
    const beforeUnload = (): void => persist()
    window.addEventListener('beforeunload', beforeUnload)
    return () => {
      window.clearInterval(interval)
      window.removeEventListener('beforeunload', beforeUnload)
      persist()
    }
  }, [persist])

  useEffect(() => {
    const onFsChange = (): void => setFullscreen(document.fullscreenElement === containerRef.current)
    document.addEventListener('fullscreenchange', onFsChange)
    return () => document.removeEventListener('fullscreenchange', onFsChange)
  }, [])

  const togglePlay = useCallback(() => {
    const video = videoRef.current
    if (!video) return
    if (video.paused) video.play().catch(() => {})
    else video.pause()
  }, [])

  const seekBy = useCallback((delta: number) => {
    const video = videoRef.current
    if (!video) return
    video.currentTime = Math.min(Math.max(0, video.currentTime + delta), Number.isFinite(video.duration) ? video.duration : Infinity)
  }, [])

  const toggleFullscreen = useCallback(() => {
    const el = containerRef.current
    if (!el) return
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
    else el.requestFullscreen().catch(() => {})
  }, [])

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent): void => {
      if (e.key === 'Escape' && !document.fullscreenElement) { persist(); onClose() }
      if (e.key === ' ' || e.code === 'Space') { e.preventDefault(); togglePlay() }
      if (e.key === 'ArrowLeft') seekBy(-10)
      if (e.key === 'ArrowRight') seekBy(10)
      if (e.key.toLowerCase() === 'f') toggleFullscreen()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [onClose, persist, seekBy, toggleFullscreen, togglePlay])

  const wakeControls = useCallback(() => {
    setControlsVisible(true)
    if (hideTimer.current) window.clearTimeout(hideTimer.current)
    hideTimer.current = window.setTimeout(() => {
      if (playing && !settingsOpen) setControlsVisible(false)
    }, 3_500)
  }, [playing, settingsOpen])

  useEffect(() => {
    wakeControls()
    return () => { if (hideTimer.current) window.clearTimeout(hideTimer.current) }
  }, [wakeControls])

  const switchQuality = (variant: VideoVariant): void => {
    if (variant.url === sourceUrl) return
    const video = videoRef.current
    pendingSeek.current = video?.currentTime ?? currentTime
    pendingPlay.current = Boolean(video && !video.paused)
    setUsingHls(false)
    setQualityHeight(variant.height)
    setSourceUrl(variant.url)
    setSettingsOpen(false)
  }

  const selectNativeAudio = (index: number): void => {
    const list = videoRef.current?.audioTracks
    if (!list) return
    for (let i = 0; i < list.length; i++) list[i].enabled = i === index
    const selected = list[index]
    setAudioLanguage(selected.language)
    setNativeAudioTracks(Array.from({ length: list.length }, (_, i) => list[i]))
  }

  const selectLegacyAudio = (index: number): void => {
    if (!hlsRef.current) return
    hlsRef.current.audioTrack = index
    const track = legacyAudioTracks[index]
    setAudioLanguage(track?.lang)
    setLegacyAudioTrackId(index)
  }

  const selectSubtitle = (language?: string): void => {
    if (!language) {
      setSubtitlesDisabled(true)
      setSubtitleLanguage(undefined)
      setSubtitleText('')
    } else {
      setSubtitlesDisabled(false)
      setSubtitleLanguage(language)
    }
  }

  const updateSubtitleStyle = (patch: Partial<typeof subtitleStyle>): void => {
    const next = { ...subtitleStyle, ...patch }
    setSubtitleStyleState(next)
    setSubtitleStyle(patch)
  }

  const updateAspect = (mode: AspectMode): void => {
    setAspectMode(mode)
    setDefaultAspectMode(mode)
  }

  const close = (): void => { persist(); onClose() }
  const progressPct = duration > 0 ? (currentTime / duration) * 100 : 0
  const bufferedPct = duration > 0 ? (buffered / duration) * 100 : 0
  const forcedAspect = aspectMode === '16:9' ? '16 / 9' : aspectMode === '4:3' ? '4 / 3' : aspectMode === '21:9' ? '21 / 9' : undefined
  const objectFit = aspectMode === 'cover' ? 'cover' : aspectMode === 'fill' ? 'fill' : 'contain'
  const subtitleBackground = subtitleStyle.background === 'none' ? 'transparent' : subtitleStyle.background === 'dark' ? 'rgba(0,0,0,.78)' : 'rgba(0,0,0,.48)'
  const subtitleShadow = subtitleStyle.edge === 'outline'
    ? '-2px -2px 0 #000,2px -2px 0 #000,-2px 2px 0 #000,2px 2px 0 #000'
    : subtitleStyle.edge === 'shadow' ? '0 3px 6px #000,0 0 2px #000' : 'none'

  return (
    <div ref={containerRef} className={`${styles.root} ${controlsVisible ? '' : styles.cursorHide}`} onMouseMove={wakeControls}>
      <div className={styles.videoStage} style={forcedAspect ? { aspectRatio: forcedAspect } : undefined}>
        <video ref={videoRef} className={styles.video} style={{ objectFit }} onDoubleClick={toggleFullscreen} playsInline />
      </div>

      {!ready && !fatalError ? <div className={styles.centerOverlay}><span className={styles.spinner} /></div> : null}
      {fatalError ? <div className={styles.centerOverlay}><p className={styles.errorText}>{fatalError}</p><button className={styles.pillButton} onClick={close}>Geri dön</button></div> : null}

      {subtitleText && !subtitlesDisabled ? (
        <div className={styles.subtitleOverlay} style={{ bottom: `${subtitleStyle.bottomPercent}%`, color: subtitleStyle.color, fontSize: `${Math.round(30 * subtitleStyle.fontScale)}px`, textShadow: subtitleShadow }}>
          <span style={{ background: subtitleBackground }}>{subtitleText}</span>
        </div>
      ) : null}

      <div className={`${styles.controls} ${controlsVisible ? styles.visible : ''}`}>
        <div className={styles.topBar}>
          <button className={styles.circleButton} onClick={close} aria-label="Geri">←</button>
          <div className={styles.heading}><span className={styles.headingTitle}>{heading}</span>{subheading ? <span className={styles.headingSub}>{subheading}</span> : null}</div>
        </div>

        <div className={styles.centerControls}>
          <button className={styles.roundControl} onClick={() => seekBy(-10)}>↶<small>10</small></button>
          <button className={`${styles.roundControl} ${styles.primaryControl}`} onClick={togglePlay}>{playing ? 'Ⅱ' : '▶'}</button>
          <button className={styles.roundControl} onClick={() => seekBy(10)}>↷<small>10</small></button>
        </div>

        <div className={styles.bottomBar}>
          <div className={styles.seekRow}>
            <span className={styles.time}>{formatTime(currentTime)}</span>
            <div className={styles.seekTrack}>
              <div className={styles.seekBuffered} style={{ width: `${bufferedPct}%` }} />
              <div className={styles.seekFilled} style={{ width: `${progressPct}%` }} />
              <input className={styles.seekInput} type="range" min={0} max={duration || 0} step={0.1} value={currentTime} onChange={(e) => { const video = videoRef.current; if (video) video.currentTime = Number(e.target.value) }} />
            </div>
            <span className={styles.time}>{formatTime(duration)}</span>
          </div>
          <div className={styles.controlFooter}>
            <div className={styles.volumeGroup}>
              <button className={styles.iconButton} onClick={() => { const v = videoRef.current; if (v) v.muted = !v.muted }}>{muted || volume === 0 ? '🔇' : '🔊'}</button>
              <input className={styles.volumeInput} type="range" min={0} max={1} step={0.05} value={muted ? 0 : volume} onChange={(e) => { const v = videoRef.current; if (v) { v.volume = Number(e.target.value); v.muted = Number(e.target.value) === 0 } }} />
            </div>
            <div className={styles.footerActions}>
              <span className={styles.qualityBadge}>{qualityHeight ? `${qualityHeight}p` : usingHls ? 'AUTO' : 'Kaynak'}</span>
              <button className={styles.pillButton} onClick={() => setSettingsOpen((v) => !v)}>⚙ Ayarlar</button>
              <button className={styles.iconButton} onClick={toggleFullscreen}>{fullscreen ? '⤢' : '⛶'}</button>
            </div>
          </div>
        </div>
      </div>

      {settingsOpen ? (
        <div className={styles.settingsPanel} onMouseMove={wakeControls}>
          <div className={styles.settingsHeader}><strong>Oynatma Ayarları</strong><button className={styles.iconButton} onClick={() => setSettingsOpen(false)}>×</button></div>

          {variants.length > 0 ? <SettingSection title="Görüntü Kalitesi"><div className={styles.optionGrid}>{variants.slice().sort((a,b) => b.height-a.height).map((variant) => <OptionButton key={`${variant.height}-${variant.url}`} active={qualityHeight === variant.height} onClick={() => switchQuality(variant)}>{variant.label}{variant.source ? ' • Kaynak' : ''}</OptionButton>)}</div></SettingSection> : null}

          <SettingSection title="Ses Dili"><div className={styles.optionGrid}>
            {nativeAudioTracks.length > 0 ? nativeAudioTracks.map((track, i) => <OptionButton key={track.id ?? i} active={track.enabled} onClick={() => selectNativeAudio(i)}>{track.label || languageLabel(track.language) || `Ses ${i+1}`}</OptionButton>) : legacyAudioTracks.map((track, i) => <OptionButton key={track.id ?? i} active={legacyAudioTrackId === i} onClick={() => selectLegacyAudio(i)}>{track.name || languageLabel(track.lang) || `Ses ${i+1}`}</OptionButton>)}
            {nativeAudioTracks.length === 0 && legacyAudioTracks.length === 0 ? <span className={styles.hint}>MP4 varsayılan ses track'i kullanılıyor.</span> : null}
          </div></SettingSection>

          <SettingSection title="Altyazı">
            <div className={styles.optionGrid}><OptionButton active={subtitlesDisabled} onClick={() => selectSubtitle(undefined)}>Kapalı</OptionButton>{subtitleTracks.map((track) => <OptionButton key={`${track.language}-${track.url}`} active={!subtitlesDisabled && normalizedLanguage(track.language) === normalizedLanguage(subtitleLanguage)} onClick={() => selectSubtitle(track.language)}>{track.label || languageLabel(track.language)}</OptionButton>)}</div>
            <label className={styles.sliderLabel}>Boyut <b>%{Math.round(subtitleStyle.fontScale * 100)}</b><input type="range" min={0.65} max={1.75} step={0.05} value={subtitleStyle.fontScale} onChange={(e) => updateSubtitleStyle({ fontScale: Number(e.target.value) })} /></label>
            <label className={styles.sliderLabel}>Yükseklik <b>%{subtitleStyle.bottomPercent}</b><input type="range" min={4} max={32} step={1} value={subtitleStyle.bottomPercent} onChange={(e) => updateSubtitleStyle({ bottomPercent: Number(e.target.value) })} /></label>
            <div className={styles.optionGrid}>{['#ffffff','#ffd54f','#74e7ff'].map((color) => <button key={color} className={`${styles.colorDot} ${subtitleStyle.color === color ? styles.activeColor : ''}`} style={{ background: color }} onClick={() => updateSubtitleStyle({ color })} />)}</div>
            <div className={styles.optionGrid}>{(['none','soft','dark'] as SubtitleBackground[]).map((value) => <OptionButton key={value} active={subtitleStyle.background === value} onClick={() => updateSubtitleStyle({ background: value })}>{value === 'none' ? 'Arka plan yok' : value === 'soft' ? 'Yarı saydam' : 'Koyu'}</OptionButton>)}</div>
            <div className={styles.optionGrid}>{(['none','outline','shadow'] as SubtitleEdge[]).map((value) => <OptionButton key={value} active={subtitleStyle.edge === value} onClick={() => updateSubtitleStyle({ edge: value })}>{value === 'none' ? 'Kenarsız' : value === 'outline' ? 'Outline' : 'Gölge'}</OptionButton>)}</div>
          </SettingSection>

          <SettingSection title="Ekran Oranı"><div className={styles.optionGrid}>{(['fit','cover','fill','16:9','4:3','21:9'] as AspectMode[]).map((mode) => <OptionButton key={mode} active={aspectMode === mode} onClick={() => updateAspect(mode)}>{mode === 'fit' ? 'Orijinal' : mode === 'cover' ? 'Ekranı Doldur' : mode === 'fill' ? 'Esnet' : mode}</OptionButton>)}</div></SettingSection>
        </div>
      ) : null}
    </div>
  )
}

function SettingSection({ title, children }: { title: string; children: ReactNode }) {
  return <section className={styles.settingSection}><h3>{title}</h3>{children}</section>
}

function OptionButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: ReactNode }) {
  return <button className={`${styles.optionButton} ${active ? styles.optionActive : ''}`} onClick={onClick}>{children}</button>
}
