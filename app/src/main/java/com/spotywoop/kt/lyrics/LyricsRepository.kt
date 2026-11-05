package com.spotywoop.kt.lyrics

import com.spotywoop.kt.data.AppLog
import com.spotywoop.kt.data.OfflineCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Gestionnaire et cascade de recherche des paroles.
 * Portage fidèle de la logique de cascade (fetchLyrics, publishAvailableSources, switchSource) de AmLyrics.ts.
 *
 * Maintient le cache mémoire, expose l'état réactif via StateFlow et annule proprement
 * toute requête en cours lors d'un changement de piste.
 */
object LyricsRepository {

    private const val TAG = "LyricsRepository"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeFetchJob: Job? = null

    private val _state = MutableStateFlow(LyricsState())
    val state: StateFlow<LyricsState> = _state.asStateFlow()

    // Cache mémoire par clé "$artist - $title"
    private val cache = ConcurrentHashMap<String, LyricsState>()

    private fun logI(msg: String) {
        try {
            android.util.Log.i(TAG, msg)
        } catch (_: Throwable) {
            println("[$TAG] $msg")
        }
    }

    private fun logW(msg: String) {
        try {
            android.util.Log.w(TAG, msg)
        } catch (_: Throwable) {
            println("[$TAG] WARN: $msg")
        }
    }

    /**
     * Clé de cache pour mémoriser les paroles d'un morceau.
     */
    fun getCacheKey(artist: String, title: String): String = "${artist.trim()} - ${title.trim()}".lowercase()

    private fun diskKey(cacheKey: String) = "lyrics-rich:$cacheKey"

    /**
     * Lance la cascade complète de recherche de paroles pour un morceau.
     * Annule immédiatement toute recherche antérieure.
     */
    fun fetchLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationMs: Long? = null,
        isrc: String? = null,
        query: String? = null,
        spotifyId: String? = null,
        musicId: String? = null,
        ttml: String? = null,
    ) {
        activeFetchJob?.cancel()

        val cacheKey = getCacheKey(artist, title)
        val cached = cache[cacheKey]
        if (cached != null && ttml.isNullOrBlank()) {
            logI("Cascade '$artist - $title' : servi depuis le cache mémoire")
            _state.value = cached
            return
        }

        activeFetchJob = scope.launch {
            try {
                executeCascade(
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    isrc = isrc,
                    query = query,
                    spotifyId = spotifyId,
                    musicId = musicId,
                    ttml = ttml,
                    cacheKey = cacheKey,
                )
            } catch (_: CancellationException) {
                logI("Cascade '$artist - $title' annulée pour un nouveau morceau")
            } catch (e: Exception) {
                logW("Cascade '$artist - $title' : erreur inattendue ${e.message}")
                _state.value = _state.value.copy(isLoading = false, isFetchingAlternatives = false)
            }
        }
    }

    /**
     * Exécution séquentielle de la cascade, conforme à am-lyrics.
     */
    private suspend fun executeCascade(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        isrc: String?,
        query: String?,
        spotifyId: String?,
        musicId: String?,
        ttml: String?,
        cacheKey: String,
    ) = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        logI("Cascade démarrée pour '$artist - $title' (${durationMs ?: 0} ms)")

        _state.value = LyricsState(
            isLoading = true,
            lyrics = null,
            currentSource = null,
            availableSources = emptyList(),
            currentSourceIndex = 0,
            isFetchingAlternatives = false,
            hasFetchedAllProviders = false,
            songwriters = null,
        )

        // Déjà trouvées lors d'une écoute précédente : affichées telles quelles, même hors connexion.
        if (ttml.isNullOrBlank()) {
            OfflineCache.read(diskKey(cacheKey))?.let(LyricsDiskFormat::decode)?.let { saved ->
                cache[cacheKey] = saved
                _state.value = saved
                logI("Cascade '$artist - $title' : servi depuis le disque")
                return@withContext
            }
        }

        // 1. TTML local fourni en paramètre direct
        if (!ttml.isNullOrBlank()) {
            val parseResult = LyricsParsers.parseTTML(ttml)
            if (parseResult != null && parseResult.lines.isNotEmpty()) {
                val localResult = LyricsSourceResult(
                    lines = parseResult.lines,
                    source = "Local",
                    originalTTML = ttml,
                    songwriters = parseResult.songwriters,
                )
                val finalState = LyricsState(
                    lyrics = localResult.lines,
                    currentSource = localResult,
                    availableSources = listOf(localResult),
                    currentSourceIndex = 0,
                    isLoading = false,
                    isFetchingAlternatives = false,
                    hasFetchedAllProviders = true,
                    songwriters = localResult.songwriters,
                )
                _state.value = finalState
                logI("Cascade : TTML local utilisé en ${System.currentTimeMillis() - started} ms")
                return@withContext
            }
        }

        // 2. lrc.red
        val baseMeta = if (title.isNotBlank() && artist.isNotBlank()) {
            SongMetadata(title = title, artist = artist, album = album, durationMs = durationMs)
        } else null

        val lrcRedResult = LyricsProviders.fetchLyricsFromLrcRed(
            title = title,
            artist = artist,
            isrc = isrc,
            metadata = baseMeta,
            searchQuery = query.orEmpty(),
        )

        // Si lrc.red renvoie du mot par mot fait par un humain (non IA), on s'arrête immédiatement
        if (lrcRedResult != null && !lrcRedResult.byModel && LyricsProviders.hasWordSyncedLyrics(lrcRedResult.lines)) {
            val finalState = LyricsState(
                lyrics = lrcRedResult.lines,
                currentSource = lrcRedResult,
                availableSources = listOf(lrcRedResult),
                currentSourceIndex = 0,
                isLoading = false,
                isFetchingAlternatives = false,
                hasFetchedAllProviders = true,
                songwriters = lrcRedResult.songwriters,
            )
            _state.value = finalState
            cache[cacheKey] = finalState
            OfflineCache.write(diskKey(cacheKey), LyricsDiskFormat.encode(finalState))
            logI("Cascade terminée immédiatement : lrc.red mot par mot trouvé en ${System.currentTimeMillis() - started} ms")
            return@withContext
        }

        // Publie lrc.red comme premier résultat intermédiaire pendant qu'on cherche les alternatives
        if (lrcRedResult != null) {
            publishAvailableSources(listOf(lrcRedResult), isFetchingAlternatives = true)
        }

        // 3. Résolution des métadonnées étendues (catalogue LyricsPlus / requête brute)
        val resolved = LyricsProviders.resolveSongMetadata(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            isrc = isrc,
            query = query,
            spotifyId = spotifyId,
            musicId = musicId,
            fallback = lrcRedResult?.metadata,
        )

        val isMusicIdOnly = !musicId.isNullOrBlank() && title.isBlank() && artist.isBlank() && query.isNullOrBlank() && isrc.isNullOrBlank()
        val collectedSources = mutableListOf<LyricsSourceResult>()
        if (lrcRedResult != null) collectedSources.add(lrcRedResult)

        fun hasWordSync(): Boolean = collectedSources.any { !it.byModel && LyricsProviders.hasWordSyncedLyrics(it.lines) }

        // 4. BiniLyrics
        if (resolved.metadata != null && !isMusicIdOnly && lrcRedResult?.byModel != true) {
            val bini = LyricsProviders.fetchLyricsFromBiniLyrics(
                title = resolved.metadata.title,
                artist = resolved.metadata.artist,
                isrc = resolved.catalogIsrc,
                metadata = resolved.metadata,
            )
            if (bini != null && bini.lines.isNotEmpty()) {
                collectedSources.add(bini)
                publishAvailableSources(collectedSources, isFetchingAlternatives = true)
            }
        }

        // 5. Spicy Lyrics
        if (lrcRedResult?.byModel == true || !hasWordSync()) {
            val spicy = LyricsProviders.fetchLyricsFromSpicyLyrics(
                spotifyId = resolved.spotifyId,
                isrc = lrcRedResult?.isrc ?: resolved.catalogIsrc,
                metadata = resolved.metadata,
            )
            if (spicy != null) {
                collectedSources.add(spicy)
                publishAvailableSources(collectedSources, isFetchingAlternatives = true)
            }
        }

        // 6. Unison
        if (resolved.metadata != null && !isMusicIdOnly) {
            if (collectedSources.isEmpty() || !hasWordSync()) {
                val unison = LyricsProviders.fetchLyricsFromUnison(resolved.metadata)
                if (unison != null && unison.lines.isNotEmpty()) {
                    collectedSources.add(unison)
                    publishAvailableSources(collectedSources, isFetchingAlternatives = true)
                }
            }

            // 7. LyricsPlus / YouLyPlus
            if (!hasWordSync()) {
                val youLyResults = LyricsProviders.fetchLyricsFromYouLyPlus(
                    title = resolved.metadata.title,
                    artist = resolved.metadata.artist,
                    isrc = resolved.catalogIsrc,
                    metadata = resolved.metadata,
                    skipBiniCache = true,
                )
                if (youLyResults.isNotEmpty()) {
                    collectedSources.addAll(youLyResults)
                    publishAvailableSources(collectedSources, isFetchingAlternatives = true)
                }
            }
        }

        // 8. LRCLIB (repli synchronisé ligne ou brut)
        fun hasLineSync(): Boolean = collectedSources.any {
            LyricsProviders.hasWordSyncedLyrics(it.lines) || (!it.byModel && LyricsProviders.getTimingRank(it.lines) < 2)
        }

        if ((collectedSources.isEmpty() || !hasLineSync()) && resolved.metadata != null) {
            val lrclib = LyricsProviders.fetchLyricsFromLrclib(resolved.metadata)
            if (lrclib != null && lrclib.lines.isNotEmpty()) {
                collectedSources.add(lrclib)
                publishAvailableSources(collectedSources, isFetchingAlternatives = true)
            }
        }

        // 9. Genius (dernier recours pour paroles brutes)
        if (collectedSources.isEmpty() && resolved.metadata != null) {
            val genius = LyricsProviders.fetchLyricsFromGenius(resolved.metadata)
            if (genius != null && genius.lines.isNotEmpty()) {
                collectedSources.add(genius)
                publishAvailableSources(collectedSources, isFetchingAlternatives = true)
            }
        }

        val allFetched = collectedSources.isEmpty() || collectedSources.any { it.source == "LRCLIB" || it.source == "Genius" }
        publishAvailableSources(
            sources = collectedSources,
            isFetchingAlternatives = false,
            isLoading = false,
            hasFetchedAll = allFetched,
        )

        if (_state.value.lyrics != null) {
            cache[cacheKey] = _state.value
            OfflineCache.write(diskKey(cacheKey), LyricsDiskFormat.encode(_state.value))
        }
        logI("Cascade terminée : ${_state.value.availableSources.size} sources en ${System.currentTimeMillis() - started} ms")
    }

    /**
     * Publie la liste des sources triées et sélectionne la meilleure.
     */
    private fun publishAvailableSources(
        sources: List<LyricsSourceResult>,
        isFetchingAlternatives: Boolean = false,
        isLoading: Boolean = false,
        hasFetchedAll: Boolean = false,
    ) {
        val sorted = LyricsProviders.mergeAndSortSources(sources)
        val best = sorted.firstOrNull()

        _state.value = _state.value.copy(
            lyrics = best?.lines,
            currentSource = best,
            availableSources = sorted,
            currentSourceIndex = if (best != null) 0 else _state.value.currentSourceIndex,
            isLoading = isLoading,
            isFetchingAlternatives = isFetchingAlternatives,
            hasFetchedAllProviders = hasFetchedAll,
            songwriters = best?.songwriters ?: _state.value.songwriters,
        )
    }

    /**
     * Trouve l'index de la source courante dans la liste fournie.
     */
    fun findCurrentSourceIndex(
        sources: List<LyricsSourceResult>,
        currentLines: List<LyricsLine>?,
        currentSourceLabel: String?,
    ): Int {
        val identityIndex = sources.indexOfFirst { it.lines === currentLines }
        if (identityIndex != -1) return identityIndex

        val sourceKey = LyricsProviders.getSourceKey(currentSourceLabel)
        if (sourceKey.isEmpty()) return -1

        return sources.indexOfFirst { LyricsProviders.getSourceKey(it.source) == sourceKey }
    }

    /**
     * Calcule le prochain index de source pour la bascule utilisateur (switchSource).
     */
    fun getNextSourceIndex(
        sources: List<LyricsSourceResult>,
        currentIndex: Int,
        currentSourceLabel: String?,
        currentLines: List<LyricsLine>?,
    ): Int {
        if (sources.size <= 1) return -1
        if (currentIndex != -1) {
            return (currentIndex + 1) % sources.size
        }
        val currentKey = LyricsProviders.getSourceKey(currentSourceLabel)
        val fallbackIndex = sources.indexOfFirst {
            it.lines !== currentLines && LyricsProviders.getSourceKey(it.source) != currentKey
        }
        return if (fallbackIndex == -1) 0 else fallbackIndex
    }

    /**
     * Applique manuellement la source située à l'index donné.
     */
    fun applySourceAtIndex(index: Int) {
        val sources = _state.value.availableSources
        val source = sources.getOrNull(index) ?: return
        _state.value = _state.value.copy(
            lyrics = source.lines,
            currentSource = source,
            currentSourceIndex = index,
            songwriters = source.songwriters ?: _state.value.songwriters,
        )
        logI("Source changée vers '${source.source}' (index $index)")
    }

    /**
     * Bascule vers la source suivante disponible.
     * Si tous les fournisseurs n'avaient pas encore été consultés, complète la recherche avant de basculer.
     */
    suspend fun switchSource() = withContext(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState.isFetchingAlternatives) return@withContext

        val currentSources = currentState.availableSources
        val currentLabel = currentState.currentSource?.source
        val currentLines = currentState.lyrics

        if (!currentState.hasFetchedAllProviders) {
            _state.value = currentState.copy(isFetchingAlternatives = true)
            try {
                val metadata = currentSources.firstOrNull { it.source == "lrc.red" }?.metadata
                val resolved = LyricsProviders.resolveSongMetadata(fallback = metadata)
                val newSources = mutableListOf<LyricsSourceResult>()

                if (resolved.metadata != null &&
                    !currentSources.any { it.byModel } &&
                    !currentSources.any { it.source == "BiniLyrics" }
                ) {
                    val bini = LyricsProviders.fetchLyricsFromBiniLyrics(
                        title = resolved.metadata.title,
                        artist = resolved.metadata.artist,
                        isrc = resolved.catalogIsrc,
                        metadata = resolved.metadata,
                    )
                    if (bini != null) newSources.add(bini)
                }

                if (!currentSources.any { it.source.startsWith("Spicy Lyrics", ignoreCase = true) }) {
                    val spicy = LyricsProviders.fetchLyricsFromSpicyLyrics(
                        spotifyId = resolved.spotifyId,
                        isrc = currentSources.firstOrNull { it.source == "lrc.red" }?.isrc ?: resolved.catalogIsrc,
                        metadata = resolved.metadata,
                    )
                    if (spicy != null) newSources.add(spicy)
                }

                if (resolved.metadata != null) {
                    if (!currentSources.any { it.source.contains("unison", ignoreCase = true) }) {
                        val unison = LyricsProviders.fetchLyricsFromUnison(resolved.metadata)
                        if (unison != null && unison.lines.isNotEmpty()) newSources.add(unison)
                    }

                    if (!currentSources.any {
                            !it.byModel && LyricsProviders.getRankForCollected(it.source) == 1.0 && LyricsProviders.hasWordSyncedLyrics(it.lines)
                        }
                    ) {
                        val youLy = LyricsProviders.fetchLyricsFromYouLyPlus(
                            title = resolved.metadata.title,
                            artist = resolved.metadata.artist,
                            isrc = resolved.catalogIsrc,
                            metadata = resolved.metadata,
                            skipBiniCache = true,
                        )
                        if (youLy.isNotEmpty()) newSources.addAll(youLy)
                    }

                    if (!currentSources.any { it.source.contains("lrclib", ignoreCase = true) }) {
                        val lrclib = LyricsProviders.fetchLyricsFromLrclib(resolved.metadata)
                        if (lrclib != null && lrclib.lines.isNotEmpty()) newSources.add(lrclib)
                    }

                    if (!currentSources.any { it.source.contains("genius", ignoreCase = true) }) {
                        val genius = LyricsProviders.fetchLyricsFromGenius(resolved.metadata)
                        if (genius != null && genius.lines.isNotEmpty()) newSources.add(genius)
                    }
                }

                if (newSources.isNotEmpty()) {
                    val merged = LyricsProviders.mergeAndSortSources(currentSources + newSources)
                    val newIndex = findCurrentSourceIndex(merged, currentLines, currentLabel)
                    _state.value = _state.value.copy(
                        availableSources = merged,
                        currentSourceIndex = newIndex,
                        hasFetchedAllProviders = true,
                        isFetchingAlternatives = false,
                    )
                } else {
                    _state.value = _state.value.copy(
                        hasFetchedAllProviders = true,
                        isFetchingAlternatives = false,
                    )
                }
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    hasFetchedAllProviders = true,
                    isFetchingAlternatives = false,
                )
            }
        }

        val updatedSources = _state.value.availableSources
        if (updatedSources.size > 1) {
            val currIdx = findCurrentSourceIndex(updatedSources, currentLines, currentLabel)
            val nextIdx = getNextSourceIndex(updatedSources, currIdx, currentLabel, currentLines)
            if (nextIdx != -1) {
                applySourceAtIndex(nextIdx)
            }
        }
    }
}
