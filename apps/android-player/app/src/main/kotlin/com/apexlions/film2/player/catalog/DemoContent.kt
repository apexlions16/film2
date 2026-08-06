package com.apexlions.film2.player.catalog

/**
 * One hardcoded, always-present demo row item pointing at a public multi-audio-track
 * HLS test stream. Lets the playback pipeline (HlsMediaSource + track selection) be
 * verified end-to-end before any real content exists in the catalog. Clearly labeled
 * in the UI as "Demo Stream (test)" — see ui/browse/BrowseScreen.kt.
 */
object DemoContent {
    const val DEMO_TITLE_ID = "demo-stream-test"

    val demoTitle = Title(
        id = DEMO_TITLE_ID,
        type = TitleType.MOVIE,
        imdbId = "tt0000000",
        title = "Demo Stream (test)",
        overview = "Public multi-audio-track HLS test stream, used to verify playback and " +
            "track switching before real content is published.",
        genres = listOf("Test"),
        posterUrl = null,
        backdropUrl = null,
        cast = emptyList(),
        crew = emptyList(),
        status = AssetStatus.READY,
        createdAt = "2026-01-01T00:00:00.000Z",
        updatedAt = "2026-01-01T00:00:00.000Z",
        asset = PlayableAsset(
            masterPlaylistUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            audioLanguages = listOf("eng"),
            subtitleLanguages = emptyList(),
        ),
    )
}
