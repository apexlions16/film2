package com.apexlions.film2.studio.work

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import com.apexlions.film2.studio.Film2StudioApplication
import com.apexlions.film2.studio.catalog.AssetStatus
import com.apexlions.film2.studio.catalog.Episode
import com.apexlions.film2.studio.catalog.ExternalMediaTrack
import com.apexlions.film2.studio.catalog.PlayableAsset
import com.apexlions.film2.studio.catalog.Season
import com.apexlions.film2.studio.hf.HfAccountEntry
import com.apexlions.film2.studio.hf.HfUploadProgress
import com.apexlions.film2.studio.hf.HfUploadStage
import com.apexlions.film2.studio.hf.uploadFileWithFailover
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.time.Instant
import java.util.Locale

/** Result returned when the upload was published without GitHub Actions remuxing. */
data class FastPublishResult(
    val message: String,
)

/**
 * Fast path for the common Film2 upload case.
 *
 * Separate MP4 video + AAC/M4A tracks are muxed on-device with Android's MediaExtractor /
 * MediaMuxer. Samples stay encoded: no video or audio transcoding happens. The resulting
 * single MP4 is uploaded to Hugging Face exactly once and the catalog is made ready directly.
 *
 * If the device/container/codec cannot use this path, null is returned BEFORE source files
 * are uploaded and UploadWorker falls back to the existing GitHub Actions workflow.
 */
class FastPublishCoordinator(private val context: Context) {

    suspend fun markProcessing(app: Film2StudioApplication, spec: UploadJobSpec) {
        val title = app.githubClient.getTitle(spec.titleId)
            ?: throw IllegalStateException("Katalogda ${spec.titleId} bulunamadi")
        val now = Instant.now().toString()

        if (spec.kind != "episode") {
            val keepReady = title.status == AssetStatus.READY && title.asset != null
            app.githubClient.putTitle(
                title.copy(
                    status = if (keepReady) AssetStatus.READY else AssetStatus.PROCESSING,
                    updatedAt = now,
                ),
            )
            return
        }

        val seasonNo = requireNotNull(spec.seasonNumber)
        val episodeNo = requireNotNull(spec.episodeNumber)
        val seasons = title.seasons.orEmpty().toMutableList()
        val seasonIndex = seasons.indexOfFirst { it.seasonNumber == seasonNo }
        val season = if (seasonIndex >= 0) {
            seasons[seasonIndex]
        } else {
            Season(
                seasonNumber = seasonNo,
                name = "Sezon $seasonNo",
                overview = "",
                episodes = emptyList(),
            )
        }

        val episodes = season.episodes.toMutableList()
        val episodeIndex = episodes.indexOfFirst { it.episodeNumber == episodeNo }
        val existing = episodeIndex.takeIf { it >= 0 }?.let(episodes::get)
        val processingEpisode = if (existing != null) {
            existing.copy(
                status = if (existing.status == AssetStatus.READY && existing.asset != null) {
                    AssetStatus.READY
                } else {
                    AssetStatus.PROCESSING
                },
            )
        } else {
            Episode(
                episodeNumber = episodeNo,
                title = "$episodeNo. Bolum",
                overview = "",
                status = AssetStatus.PROCESSING,
            )
        }
        if (episodeIndex >= 0) episodes[episodeIndex] = processingEpisode else episodes += processingEpisode
        val updatedSeason = season.copy(episodes = episodes.sortedBy { it.episodeNumber })
        if (seasonIndex >= 0) seasons[seasonIndex] = updatedSeason else seasons += updatedSeason

        val hasReadyMedia = title.status == AssetStatus.READY ||
            seasons.any { s -> s.episodes.any { it.status == AssetStatus.READY && it.asset != null } }
        app.githubClient.putTitle(
            title.copy(
                status = if (hasReadyMedia) AssetStatus.READY else AssetStatus.PROCESSING,
                seasons = seasons.sortedBy { it.seasonNumber },
                updatedAt = now,
            ),
        )
    }

    suspend fun tryPublish(
        app: Film2StudioApplication,
        spec: UploadJobSpec,
        accounts: List<HfAccountEntry>,
        onProgress: (percent: Int, message: String, bytes: Long, total: Long) -> Unit,
    ): FastPublishResult? = when (spec.mode) {
        "separate" -> tryPublishSeparate(app, spec, accounts, onProgress)
        "combined" -> tryPublishCombinedMp4(app, spec, accounts, onProgress)
        else -> null
    }

    private suspend fun tryPublishSeparate(
        app: Film2StudioApplication,
        spec: UploadJobSpec,
        accounts: List<HfAccountEntry>,
        onProgress: (Int, String, Long, Long) -> Unit,
    ): FastPublishResult? {
        val video = spec.files.firstOrNull { it.role == "video" } ?: return null
        val audios = spec.files.filter { it.role == "audio" }
        if (audios.isEmpty()) return null

        val cacheRoot = context.externalCacheDir ?: context.cacheDir
        val estimatedOutput = (querySize(Uri.parse(video.uri)).coerceAtLeast(0L) +
            audios.sumOf { querySize(Uri.parse(it.uri)).coerceAtLeast(0L) })
        if (estimatedOutput > 0L) {
            // Current HF uploader prepares a seekable copy. Leave space for mux output +
            // that upload preparation copy. This guard makes fallback safe on low storage.
            val required = estimatedOutput * 2L + FAST_PATH_SAFETY_BYTES
            if (cacheRoot.usableSpace < required) {
                onProgress(
                    4,
                    "Hizli yerel mux icin gecici alan yetersiz; sunucu yolu kullanilacak",
                    0,
                    required,
                )
                return null
            }
        }

        val outputDir = File(cacheRoot, "film2_fast_mux").apply { mkdirs() }
        val output = File(outputDir, "${safeName(spec.titleId)}_${System.currentTimeMillis()}.mp4")
        val muxResult = try {
            FastLocalMuxer(context).mux(
                videoUri = Uri.parse(video.uri),
                audioInputs = audios.map { Uri.parse(it.uri) to normalizedLanguage(it.language) },
                output = output,
                onProgress = { fraction ->
                    onProgress(
                        5 + (fraction.coerceIn(0f, 1f) * 23f).toInt(),
                        "Video ve sesler cihazda hizli MP4'e birlestiriliyor (encode yok)",
                        (fraction * estimatedOutput.coerceAtLeast(1L)).toLong(),
                        estimatedOutput,
                    )
                },
            )
        } catch (t: Throwable) {
            output.delete()
            if (t is CancellationException) throw t
            onProgress(5, "Yerel hizli mux uygun degil; GitHub fallback kullanilacak", 0, 0)
            return null
        }

        try {
            return publishPreparedVideo(
                app = app,
                spec = spec,
                accounts = accounts,
                videoUri = Uri.fromFile(output),
                audioLanguages = muxResult.audioLanguages,
                durationSeconds = muxResult.durationUs.takeIf { it > 0L }?.div(1_000_000.0),
                subtitles = spec.files.filter { it.role == "subtitle" },
                uploadStartPercent = 29,
                onProgress = onProgress,
            )
        } finally {
            output.delete()
        }
    }

    private suspend fun tryPublishCombinedMp4(
        app: Film2StudioApplication,
        spec: UploadJobSpec,
        accounts: List<HfAccountEntry>,
        onProgress: (Int, String, Long, Long) -> Unit,
    ): FastPublishResult? {
        val combined = spec.files.firstOrNull { it.role == "combined" } ?: return null
        val ext = combined.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext !in setOf("mp4", "m4v")) return null

        val info = try {
            FastLocalMuxer(context).inspectMp4(Uri.parse(combined.uri))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return null
        }

        onProgress(8, "Birlesik MP4 zaten hazir; sunucu remux'u atlaniyor", 0, 0)
        return publishPreparedVideo(
            app = app,
            spec = spec,
            accounts = accounts,
            videoUri = Uri.parse(combined.uri),
            audioLanguages = info.audioLanguages,
            durationSeconds = info.durationUs.takeIf { it > 0L }?.div(1_000_000.0),
            subtitles = emptyList(),
            uploadStartPercent = 10,
            onProgress = onProgress,
        )
    }

    private suspend fun publishPreparedVideo(
        app: Film2StudioApplication,
        spec: UploadJobSpec,
        accounts: List<HfAccountEntry>,
        videoUri: Uri,
        audioLanguages: List<String>,
        durationSeconds: Double?,
        subtitles: List<UploadJobFile>,
        uploadStartPercent: Int,
        onProgress: (Int, String, Long, Long) -> Unit,
    ): FastPublishResult {
        var registry = app.shardRegistryManager.ensureCapacity(
            app.githubClient.getShardRegistry(),
            accounts,
        ).registry
        val prefix = mediaPrefix(spec)
        val version = System.currentTimeMillis()
        val videoPath = "$prefix/video_$version.mp4"
        val uploader = app.newHfUploader()

        val videoUpload = uploadFileWithFailover(
            uploader = uploader,
            shardRegistryManager = app.shardRegistryManager,
            localUri = videoUri,
            repoPath = videoPath,
            registry = registry,
            accounts = accounts,
            onProgress = { progress ->
                mapUploadProgress(
                    progress = progress,
                    base = uploadStartPercent,
                    span = 58,
                    label = "Final MP4 tek seferde Hugging Face'e yukleniyor",
                    onProgress = onProgress,
                )
            },
        )
        registry = app.shardRegistryManager.recordUsage(
            videoUpload.registry,
            videoUpload.shard.id,
            videoUpload.bytes,
        )

        val subtitleTracks = mutableListOf<ExternalMediaTrack>()
        subtitles.forEachIndexed { index, subtitle ->
            val lang = normalizedLanguage(subtitle.language)
            val ext = subtitle.fileName.substringAfterLast('.', "vtt")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]"), "")
                .ifBlank { "vtt" }
            val path = "$prefix/subs_${lang}_${version}_${index + 1}.$ext"
            val result = uploadFileWithFailover(
                uploader = uploader,
                shardRegistryManager = app.shardRegistryManager,
                localUri = Uri.parse(subtitle.uri),
                repoPath = path,
                registry = registry,
                accounts = accounts,
                onProgress = { progress ->
                    mapUploadProgress(
                        progress = progress,
                        base = 88,
                        span = 6,
                        label = "Altyazilar yukleniyor",
                        onProgress = onProgress,
                    )
                },
            )
            registry = app.shardRegistryManager.recordUsage(
                result.registry,
                result.shard.id,
                result.bytes,
            )
            subtitleTracks += ExternalMediaTrack(
                language = lang,
                label = languageLabel(lang),
                url = result.url,
                mimeType = when (ext) {
                    "srt" -> "application/x-subrip"
                    else -> "text/vtt"
                },
            )
        }

        onProgress(95, "Katalog aninda hazir duruma getiriliyor", 0, 0)
        app.githubClient.putShardRegistry(registry)
        publishReadyTitle(
            app = app,
            spec = spec,
            shardId = videoUpload.shard.id,
            asset = PlayableAsset(
                videoUrl = videoUpload.url,
                masterPlaylistUrl = null,
                durationSeconds = durationSeconds,
                audioLanguages = audioLanguages.distinct(),
                subtitleLanguages = subtitleTracks.map { it.language }.distinct(),
                externalAudioTracks = emptyList(),
                externalSubtitleTracks = subtitleTracks,
                videoVariants = emptyList(),
            ),
        )
        onProgress(100, "Hazir: GitHub Actions remux'u atlandi", 0, 0)
        return FastPublishResult(
            message = "Hazir: tek MP4 dogrudan yayinlandi; GitHub Actions remux'u atlandi",
        )
    }

    private suspend fun publishReadyTitle(
        app: Film2StudioApplication,
        spec: UploadJobSpec,
        shardId: String,
        asset: PlayableAsset,
    ) {
        val title = app.githubClient.getTitle(spec.titleId)
            ?: throw IllegalStateException("Katalogda ${spec.titleId} bulunamadi")
        val now = Instant.now().toString()
        if (spec.kind != "episode") {
            app.githubClient.putTitle(
                title.copy(
                    status = AssetStatus.READY,
                    shardId = shardId,
                    asset = asset,
                    updatedAt = now,
                ),
            )
            return
        }

        val seasonNo = requireNotNull(spec.seasonNumber)
        val episodeNo = requireNotNull(spec.episodeNumber)
        val seasons = title.seasons.orEmpty().toMutableList()
        val seasonIndex = seasons.indexOfFirst { it.seasonNumber == seasonNo }
        val season = if (seasonIndex >= 0) seasons[seasonIndex] else Season(
            seasonNumber = seasonNo,
            name = "Sezon $seasonNo",
            overview = "",
            episodes = emptyList(),
        )
        val episodes = season.episodes.toMutableList()
        val episodeIndex = episodes.indexOfFirst { it.episodeNumber == episodeNo }
        val existing = episodeIndex.takeIf { it >= 0 }?.let(episodes::get)
        val readyEpisode = (existing ?: Episode(
            episodeNumber = episodeNo,
            title = "$episodeNo. Bolum",
            overview = "",
            status = AssetStatus.PENDING,
        )).copy(
            status = AssetStatus.READY,
            shardId = shardId,
            asset = asset,
        )
        if (episodeIndex >= 0) episodes[episodeIndex] = readyEpisode else episodes += readyEpisode
        val updatedSeason = season.copy(episodes = episodes.sortedBy { it.episodeNumber })
        if (seasonIndex >= 0) seasons[seasonIndex] = updatedSeason else seasons += updatedSeason
        app.githubClient.putTitle(
            title.copy(
                status = AssetStatus.READY,
                seasons = seasons.sortedBy { it.seasonNumber },
                updatedAt = now,
            ),
        )
    }

    private fun mapUploadProgress(
        progress: HfUploadProgress,
        base: Int,
        span: Int,
        label: String,
        onProgress: (Int, String, Long, Long) -> Unit,
    ) {
        val ratio = if (progress.totalBytes > 0L) {
            (progress.bytesProcessed.toDouble() / progress.totalBytes.toDouble()).coerceIn(0.0, 1.0)
        } else 0.0
        val phase = when (progress.stage) {
            HfUploadStage.PREPARING -> ratio * 0.24
            HfUploadStage.CHECKING -> 0.25
            HfUploadStage.UPLOADING -> 0.25 + ratio * 0.70
            HfUploadStage.FINALIZING -> 0.99
        }
        onProgress(
            (base + phase * span).toInt().coerceIn(0, 99),
            label,
            progress.bytesProcessed,
            progress.totalBytes,
        )
    }

    private fun mediaPrefix(spec: UploadJobSpec): String = if (spec.kind == "episode") {
        "media/${spec.titleId}/s${spec.seasonNumber}e${spec.episodeNumber}"
    } else {
        "media/${spec.titleId}"
    }

    private fun normalizedLanguage(value: String?): String {
        val raw = value?.trim().orEmpty()
        val key = raw
            .lowercase(Locale("tr", "TR"))
            .replace('ı', 'i')
            .replace('ğ', 'g')
            .replace('ü', 'u')
            .replace('ş', 's')
            .replace('ö', 'o')
            .replace('ç', 'c')
            .replace(Regex("[^a-z0-9]"), "")
        return when (key) {
            "en", "eng", "english", "ingilizce" -> "eng"
            "tr", "tur", "turkish", "turkce", "trke" -> "tur"
            "de", "deu", "ger", "german", "almanca" -> "deu"
            "fr", "fra", "fre", "french", "fransizca" -> "fra"
            "es", "spa", "spanish", "ispanyolca" -> "spa"
            else -> key.takeIf { it.length == 3 } ?: "und"
        }
    }

    private fun languageLabel(language: String): String = when (language) {
        "eng" -> "Ingilizce"
        "tur" -> "Turkce"
        "deu" -> "Almanca"
        "fra" -> "Fransizca"
        "spa" -> "Ispanyolca"
        else -> language.uppercase(Locale.ROOT)
    }

    private fun querySize(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                return cursor.getLong(index).coerceAtLeast(0L)
            }
        }
        return -1L
    }

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        private const val FAST_PATH_SAFETY_BYTES = 256L * 1024 * 1024
    }
}

private data class FastMuxResult(
    val audioLanguages: List<String>,
    val durationUs: Long,
)

private data class Mp4Inspection(
    val audioLanguages: List<String>,
    val durationUs: Long,
)

/** Platform muxer: copies already encoded samples, it never invokes a codec/encoder. */
private class FastLocalMuxer(private val context: Context) {

    private data class Source(
        val extractor: MediaExtractor,
        val inputTrack: Int,
        val outputTrack: Int,
    )

    fun inspectMp4(uri: Uri): Mp4Inspection {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var hasVideo = false
            var durationUs = 0L
            val languages = mutableListOf<String>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    hasVideo = true
                    durationUs = maxOf(durationUs, format.durationUsOrZero())
                } else if (mime.startsWith("audio/")) {
                    languages += normalizeEmbeddedLanguage(format.getString(MediaFormat.KEY_LANGUAGE))
                    durationUs = maxOf(durationUs, format.durationUsOrZero())
                }
            }
            if (!hasVideo) throw IllegalArgumentException("MP4 icinde video track'i bulunamadi")
            Mp4Inspection(languages.distinct(), durationUs)
        } finally {
            extractor.release()
        }
    }

    fun mux(
        videoUri: Uri,
        audioInputs: List<Pair<Uri, String>>,
        output: File,
        onProgress: (Float) -> Unit,
    ): FastMuxResult = runCatching {
        output.parentFile?.mkdirs()
        output.delete()

        val extractors = mutableListOf<MediaExtractor>()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val sources = mutableListOf<Source>()
            var durationUs = 0L
            var maxInputSize = DEFAULT_SAMPLE_BUFFER_BYTES

            val videoExtractor = MediaExtractor().also { it.setDataSource(context, videoUri, null) }
            extractors += videoExtractor
            val videoTrack = findFirstTrack(videoExtractor, "video/")
                ?: throw IllegalArgumentException("Video track'i bulunamadi")
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            durationUs = maxOf(durationUs, videoFormat.durationUsOrZero())
            maxInputSize = maxOf(maxInputSize, videoFormat.maxInputSizeOrZero())
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                val rotation = videoFormat.getInteger(MediaFormat.KEY_ROTATION)
                if (rotation in setOf(90, 180, 270)) muxer.setOrientationHint(rotation)
            }
            val videoOut = muxer.addTrack(videoFormat)
            videoExtractor.selectTrack(videoTrack)
            sources += Source(videoExtractor, videoTrack, videoOut)

            val languages = mutableListOf<String>()
            audioInputs.forEach { (uri, requestedLanguage) ->
                val extractor = MediaExtractor().also { it.setDataSource(context, uri, null) }
                extractors += extractor
                val audioTrack = findFirstTrack(extractor, "audio/")
                    ?: throw IllegalArgumentException("Ses dosyasinda audio track bulunamadi")
                val format = extractor.getTrackFormat(audioTrack)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime != MediaFormat.MIMETYPE_AUDIO_AAC) {
                    throw IllegalArgumentException("Hizli MP4 yolu AAC ses bekliyor; gelen: $mime")
                }
                val lang = requestedLanguage.ifBlank { "und" }
                if (lang != "und") format.setString(MediaFormat.KEY_LANGUAGE, lang)
                durationUs = maxOf(durationUs, format.durationUsOrZero())
                maxInputSize = maxOf(maxInputSize, format.maxInputSizeOrZero())
                val outTrack = muxer.addTrack(format)
                extractor.selectTrack(audioTrack)
                sources += Source(extractor, audioTrack, outTrack)
                languages += lang
            }

            muxer.start()
            muxerStarted = true
            val capacity = maxInputSize
                .coerceAtLeast(DEFAULT_SAMPLE_BUFFER_BYTES)
                .coerceAtMost(MAX_SAMPLE_BUFFER_BYTES)
            val buffer = ByteBuffer.allocateDirect(capacity)
            val info = MediaCodec.BufferInfo()
            var lastReported = -1

            while (true) {
                val source = sources
                    .filter { it.extractor.sampleTime >= 0L }
                    .minByOrNull { it.extractor.sampleTime }
                    ?: break

                buffer.clear()
                val sampleSize = source.extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    source.extractor.advance()
                    continue
                }
                if (sampleSize > buffer.capacity()) {
                    throw IllegalArgumentException("Medya ornegi cihaz buffer sinirini asti: $sampleSize bayt")
                }
                val sampleTrack = source.extractor.sampleTrackIndex
                if (sampleTrack != source.inputTrack) {
                    throw IllegalStateException("Beklenmeyen track sirasi: $sampleTrack")
                }
                val sampleTime = source.extractor.sampleTime.coerceAtLeast(0L)
                info.set(
                    0,
                    sampleSize,
                    sampleTime,
                    source.extractor.sampleFlags,
                )
                buffer.position(0)
                buffer.limit(sampleSize)
                muxer.writeSampleData(source.outputTrack, buffer, info)
                source.extractor.advance()

                if (durationUs > 0L) {
                    val percent = ((sampleTime.toDouble() / durationUs.toDouble()) * 100.0)
                        .toInt()
                        .coerceIn(0, 100)
                    if (percent != lastReported) {
                        lastReported = percent
                        onProgress(percent / 100f)
                    }
                }
            }

            muxer.stop()
            muxerStarted = false
            onProgress(1f)
            FastMuxResult(languages.distinct(), durationUs)
        } finally {
            extractors.forEach { runCatching { it.release() } }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }.getOrElse { t ->
        output.delete()
        throw t
    }

    private fun findFirstTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(prefix)) return i
        }
        return null
    }

    private fun MediaFormat.durationUsOrZero(): Long =
        if (containsKey(MediaFormat.KEY_DURATION)) getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0L) else 0L

    private fun MediaFormat.maxInputSizeOrZero(): Int =
        if (containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(0) else 0

    private fun normalizeEmbeddedLanguage(value: String?): String {
        val raw = value?.lowercase(Locale.ROOT).orEmpty()
        return when (raw) {
            "en", "eng" -> "eng"
            "tr", "tur" -> "tur"
            "de", "deu", "ger" -> "deu"
            "fr", "fra", "fre" -> "fra"
            "es", "spa" -> "spa"
            else -> raw.takeIf { it.length == 3 } ?: "und"
        }
    }

    companion object {
        private const val DEFAULT_SAMPLE_BUFFER_BYTES = 16 * 1024 * 1024
        private const val MAX_SAMPLE_BUFFER_BYTES = 64 * 1024 * 1024
    }
}
