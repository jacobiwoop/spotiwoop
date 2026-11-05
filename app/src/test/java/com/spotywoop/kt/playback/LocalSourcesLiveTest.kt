package com.spotywoop.kt.playback

import org.junit.Assume.assumeTrue
import org.junit.Test

/** Appelle vraiment les sources : lancé seulement avec SOURCES_LIVE=1. */
class LocalSourcesLiveTest {
    @Test
    fun everySource() {
        assumeTrue(System.getenv("SOURCES_LIVE") == "1")
        val songs = listOf(Triple("Stromae", "Fils de joie", 195), Triple("Burna Boy", "Last Last", 171), Triple("Stromae", "Papaoutai", 232),
            Triple("Rema, Selena Gomez", "Calm Down", 239), Triple("Fally Ipupa", "Eloko Oyo", 286))
        val sources = listOf<Pair<String, (String, String, Long) -> ResolvedStream?>>(
            "audiomack" to { a, t, d -> AudiomackSource.resolve(a, t, d) }, "netease" to NetEaseSource::resolve,
            "kuwo" to KuwoSource::resolve, "qq" to QQSource::resolve, "waptrick" to WaptrickSource::resolve,
        )
        for ((name, resolve) in sources) for ((a, t, sec) in songs) {
            val start = System.currentTimeMillis()
            val r = runCatching { resolve(a, t, sec * 1000L) }
            val u = r.getOrNull()?.url?.substringBefore("?")?.split("/")?.getOrNull(2)
            println("LIVE $name | $a $t | ${r.getOrNull()?.quality} | $u | ${System.currentTimeMillis() - start} ms | ${r.exceptionOrNull()?.message.orEmpty().take(80)}")
            Thread.sleep(2500)
        }
    }
}
