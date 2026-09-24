package com.spotywoop.kt.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Appelle vraiment Spotify : lancé seulement avec SPOTIFY_LIVE=1. */
class SpotifyLiveTest {
    @Test
    fun searchReturnsResults() = runBlocking {
        assumeTrue(System.getenv("SPOTIFY_LIVE") == "1")
        val results = SpotifyClient().search("damso")
        results.tracks.take(5).forEach { println("${it.name} - ${it.artists} [${it.durationLabel}] E=${it.isExplicit} ${it.cover}") }
        println("tracks=${results.tracks.size} albums=${results.albums.size} artists=${results.artists.size} playlists=${results.playlists.size}")
        assertTrue(results.tracks.isNotEmpty())
    }
}
