import { useCallback, useEffect, useRef, useState } from 'react'
import Hls, { type ErrorData, type MediaPlaylist } from 'hls.js'

import type { PlayableAsset } from '../../lib/types'
import { TrackMenu } from './TrackMenu'
import styles from './Player.module.css'

function formatTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '0:00'
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = Math.floor(seconds % 60)
  const mm = h > 0 ? String(m).padStart(2, '0') : String(m)
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

export function Player({
  asset,
  heading,
  subheading,
  onClose
}: {
  asset: PlayableAsset
  heading: string
  subheading?: string
  onClose: () => void
}) {
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const containerRef = useRef<HTMLDivElement | null>(null)
  const hlsRef = useRef<Hls | null>(null)
  const hideTimer = useRef<number | null>(null)

  const [ready, setReady] = useState(false)
  const [fatalError, setFatalError] = useState<string | null>(null)

  const [playing, setPlaying] = useState(false)
  const [currentTime, setCurrentTime] = useState(0)
  const [duration, setDuration] = useState(0)
  const [buffered, setBuffered] = useState(0)
  const [volume, setVolume] = useState(1)
  const [muted, setMuted] = useState(false)
  const [fullscreen, setFullscreen] = useState(false)

  const [audioTracks, setAudioTracks] = useState<MediaPlaylist[]>([])
  const [audioTrackId, setAudioTrackId] = useState(-1)
  const [subtitleTracks, setSubtitleTracks] = useState<MediaPlaylist[]>([])
  const [subtitleTrackId, setSubtitleTrackId] = useState(-1)

  const [controlsVisible, setControlsVisible] = useState(true)
  const [openMenu, setOpenMenu] = useState<'audio' | 'subtitle' | null>(null)

  // --- hls.js wiring -------------------------------------------------
  useEffect(() => {
    const video = videoRef.current
    if (!video) return

    setReady(false)
    setFatalError(null)

    if (Hls.isSupported()) {
      const hls = new Hls({ enableWorker: true })
      hlsRef.current = hls

      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        setReady(true)
        video.play().catch(() => {
          // Autoplay may be blocked; user can press play manually.
        })
      })
      hls.on(Hls.Events.AUDIO_TRACKS_UPDATED, (_evt, data) => {
        setAudioTracks(data.audioTracks)
      })
      hls.on(Hls.Events.AUDIO_TRACK_SWITCHED, (_evt, data) => {
        setAudioTrackId(data.id)
      })
      hls.on(Hls.Events.SUBTITLE_TRACKS_UPDATED, (_evt, data) => {
        setSubtitleTracks(data.subtitleTracks)
      })
      hls.on(Hls.Events.SUBTITLE_TRACK_SWITCH, (_evt, data) => {
        setSubtitleTrackId(data.id)
      })
      hls.on(Hls.Events.ERROR, (_evt, data: ErrorData) => {
        if (!data.fatal) return
        switch (data.type) {
          case Hls.ErrorTypes.NETWORK_ERROR:
            setFatalError('Ağ hatası: yayın alınamadı. Bağlantınızı kontrol edip tekrar deneyin.')
            hls.startLoad()
            break
          case Hls.ErrorTypes.MEDIA_ERROR:
            setFatalError('Oynatma hatası, kurtarma deneniyor…')
            hls.recoverMediaError()
            break
          default:
            setFatalError('Bu yayın oynatılamadı.')
            hls.destroy()
        }
      })

      hls.loadSource(asset.masterPlaylistUrl)
      hls.attachMedia(video)

      return () => {
        hls.destroy()
        hlsRef.current = null
      }
    }

    if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = asset.masterPlaylistUrl
      setReady(true)
      return
    }

    setFatalError('Bu ortam HLS akış oynatmayı desteklemiyor.')
    return undefined
  }, [asset.masterPlaylistUrl])

  // --- native <video> element event sync ------------------------------
  useEffect(() => {
    const video = videoRef.current
    if (!video) return

    const onPlay = (): void => setPlaying(true)
    const onPause = (): void => setPlaying(false)
    const onTimeUpdate = (): void => setCurrentTime(video.currentTime)
    const onDurationChange = (): void => setDuration(video.duration || 0)
    const onVolumeChange = (): void => {
      setVolume(video.volume)
      setMuted(video.muted)
    }
    const onProgress = (): void => {
      const ranges = video.buffered
      if (ranges.length > 0) setBuffered(ranges.end(ranges.length - 1))
    }

    video.addEventListener('play', onPlay)
    video.addEventListener('pause', onPause)
    video.addEventListener('timeupdate', onTimeUpdate)
    video.addEventListener('durationchange', onDurationChange)
    video.addEventListener('volumechange', onVolumeChange)
    video.addEventListener('progress', onProgress)

    return () => {
      video.removeEventListener('play', onPlay)
      video.removeEventListener('pause', onPause)
      video.removeEventListener('timeupdate', onTimeUpdate)
      video.removeEventListener('durationchange', onDurationChange)
      video.removeEventListener('volumechange', onVolumeChange)
      video.removeEventListener('progress', onProgress)
    }
  }, [])

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

  const toggleFullscreen = useCallback(() => {
    const el = containerRef.current
    if (!el) return
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
    else el.requestFullscreen().catch(() => {})
  }, [])

  const seek = useCallback((value: number) => {
    const video = videoRef.current
    if (!video) return
    video.currentTime = value
    setCurrentTime(value)
  }, [])

  const setVideoVolume = useCallback((value: number) => {
    const video = videoRef.current
    if (!video) return
    video.volume = value
    video.muted = value === 0
  }, [])

  const toggleMute = useCallback(() => {
    const video = videoRef.current
    if (!video) return
    video.muted = !video.muted
  }, [])

  const selectAudioTrack = useCallback((id: number) => {
    if (hlsRef.current) hlsRef.current.audioTrack = id
  }, [])

  const selectSubtitleTrack = useCallback((id: number) => {
    if (hlsRef.current) hlsRef.current.subtitleTrack = id
  }, [])

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent): void => {
      if (e.key === 'Escape' && !document.fullscreenElement) onClose()
      if (e.key === ' ' || e.code === 'Space') {
        e.preventDefault()
        togglePlay()
      }
      if (e.key === 'f') toggleFullscreen()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [onClose, togglePlay, toggleFullscreen])

  // --- auto-hide controls ---------------------------------------------
  const wakeControls = useCallback(() => {
    setControlsVisible(true)
    if (hideTimer.current) window.clearTimeout(hideTimer.current)
    hideTimer.current = window.setTimeout(() => {
      if (playing) setControlsVisible(false)
    }, 2800)
  }, [playing])

  useEffect(() => {
    wakeControls()
    return () => {
      if (hideTimer.current) window.clearTimeout(hideTimer.current)
    }
  }, [wakeControls])

  const progressPct = duration > 0 ? (currentTime / duration) * 100 : 0
  const bufferedPct = duration > 0 ? (buffered / duration) * 100 : 0

  return (
    <div
      ref={containerRef}
      className={`${styles.root} ${controlsVisible ? '' : styles.cursorHide}`}
      onMouseMove={wakeControls}
      onClick={() => setOpenMenu(null)}
    >
      <video
        ref={videoRef}
        className={styles.video}
        onClick={(e) => {
          e.stopPropagation()
          togglePlay()
        }}
        playsInline
      />

      {!ready && !fatalError ? (
        <div className={styles.centerOverlay}>
          <span className={styles.spinner} aria-hidden="true" />
        </div>
      ) : null}

      {fatalError ? (
        <div className={styles.centerOverlay}>
          <p className={styles.errorText}>{fatalError}</p>
          <button type="button" className={styles.errorClose} onClick={onClose}>
            Geri dön
          </button>
        </div>
      ) : null}

      <div className={`${styles.controls} ${controlsVisible ? styles.visible : ''}`}>
        <div className={styles.topBar} onClick={(e) => e.stopPropagation()}>
          <button type="button" className={styles.closeButton} onClick={onClose} aria-label="Kapat">
            <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true">
              <path
                d="M13.5 4L4 13.5M4 4L13.5 13.5"
                stroke="currentColor"
                strokeWidth="1.7"
                strokeLinecap="round"
              />
            </svg>
          </button>
          <div className={styles.heading}>
            <span className={styles.headingTitle}>{heading}</span>
            {subheading ? <span className={styles.headingSub}>{subheading}</span> : null}
          </div>
        </div>

        <div className={styles.bottomBar} onClick={(e) => e.stopPropagation()}>
          <div className={styles.seekRow}>
            <span className={styles.time}>{formatTime(currentTime)}</span>
            <div className={styles.seekTrack}>
              <div className={styles.seekBuffered} style={{ width: `${bufferedPct}%` }} />
              <div className={styles.seekFilled} style={{ width: `${progressPct}%` }} />
              <input
                type="range"
                className={styles.seekInput}
                min={0}
                max={duration || 0}
                step={0.1}
                value={currentTime}
                onChange={(e) => seek(Number(e.target.value))}
                aria-label="Konum"
              />
            </div>
            <span className={styles.time}>{formatTime(duration)}</span>
          </div>

          <div className={styles.controlsRow}>
            <div className={styles.controlsLeft}>
              <button
                type="button"
                className={styles.iconButton}
                onClick={togglePlay}
                aria-label={playing ? 'Duraklat' : 'Oynat'}
              >
                {playing ? (
                  <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true">
                    <rect x="5" y="4" width="3.4" height="12" rx="1" fill="currentColor" />
                    <rect x="11.6" y="4" width="3.4" height="12" rx="1" fill="currentColor" />
                  </svg>
                ) : (
                  <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true">
                    <path d="M6 4L16 10L6 16V4Z" fill="currentColor" />
                  </svg>
                )}
              </button>

              <button
                type="button"
                className={styles.iconButton}
                onClick={toggleMute}
                aria-label={muted || volume === 0 ? 'Sesi aç' : 'Sesi kapat'}
              >
                {muted || volume === 0 ? (
                  <svg width="19" height="19" viewBox="0 0 19 19" fill="none" aria-hidden="true">
                    <path d="M3 7H6L10 3.5V15.5L6 12H3V7Z" fill="currentColor" />
                    <path d="M12.5 6.5L16.5 12.5M16.5 6.5L12.5 12.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
                  </svg>
                ) : (
                  <svg width="19" height="19" viewBox="0 0 19 19" fill="none" aria-hidden="true">
                    <path d="M3 7H6L10 3.5V15.5L6 12H3V7Z" fill="currentColor" />
                    <path
                      d="M12.5 6.5C13.4 7.3 13.9 8.3 13.9 9.5C13.9 10.7 13.4 11.7 12.5 12.5"
                      stroke="currentColor"
                      strokeWidth="1.5"
                      strokeLinecap="round"
                    />
                  </svg>
                )}
              </button>
              <input
                type="range"
                className={styles.volumeInput}
                min={0}
                max={1}
                step={0.05}
                value={muted ? 0 : volume}
                onChange={(e) => setVideoVolume(Number(e.target.value))}
                aria-label="Ses düzeyi"
              />
            </div>

            <div className={styles.controlsRight}>
              <TrackMenu
                label="Ses"
                tracks={audioTracks}
                activeId={audioTrackId}
                allowOff={false}
                open={openMenu === 'audio'}
                onToggle={() => setOpenMenu((m) => (m === 'audio' ? null : 'audio'))}
                onSelect={selectAudioTrack}
              />
              <TrackMenu
                label="Altyazı"
                tracks={subtitleTracks}
                activeId={subtitleTrackId}
                allowOff
                open={openMenu === 'subtitle'}
                onToggle={() => setOpenMenu((m) => (m === 'subtitle' ? null : 'subtitle'))}
                onSelect={selectSubtitleTrack}
              />

              <button
                type="button"
                className={styles.iconButton}
                onClick={toggleFullscreen}
                aria-label={fullscreen ? 'Tam ekrandan çık' : 'Tam ekran'}
              >
                {fullscreen ? (
                  <svg width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true">
                    <path
                      d="M7 3H3V7M11 3H15V7M7 15H3V11M11 15H15V11"
                      stroke="currentColor"
                      strokeWidth="1.6"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </svg>
                ) : (
                  <svg width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true">
                    <path
                      d="M3 7V3H7M11 3H15V7M15 11V15H11M7 15H3V11"
                      stroke="currentColor"
                      strokeWidth="1.6"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </svg>
                )}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
