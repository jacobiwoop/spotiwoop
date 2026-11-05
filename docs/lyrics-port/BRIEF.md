# Portage Kotlin de am-lyrics : paroles mot par mot

## Objectif

Porter dans l'appli Android Spotywoop (Kotlin, Jetpack Compose, Media3) le composant web
**am-lyrics**, avec **le même comportement et les mêmes fonctionnalités**. On ne fait pas une
version simplifiée : chaque source, chaque règle de classement et chaque effet visuel du code de
référence doivent avoir leur équivalent.

Code de référence (lecture seule) : le dossier `am-lyrics/` ajouté à ton espace de travail.
- `src/AmLyrics.ts` (~9 800 lignes) : récupération, classement, analyse, rendu, animation, styles CSS (`static styles`).
- `src/LyricsSources.ts` : types et analyse de Spicy Lyrics.
- `src/GoogleService.ts` : traduction et romanisation.
- `lrcred-lastlast.song.json` : vraie réponse de `https://lrc.red/s/USAT22204901.song.json` (Last Last, Burna Boy), à utiliser dans les tests.

Lis le code de référence en entier pour la partie que tu portes ; ne devine pas un comportement.

## Projet

- Appli : `app/src/main/java/com/spotywoop/kt/`. Nouveau paquet : **`com.spotywoop.kt.lyrics`**.
- Style à suivre (regarde `data/SpotiwoopApi.kt`, `data/LyricsClient.kt`, `data/YouTubeRadioClient.kt`) :
  - objets `object` singletons pour les clients ; `OkHttpClient.Builder().addInterceptor(com.spotywoop.kt.data.AppLog.Http)` pour **chaque** client HTTP ;
  - JSON avec `org.json` ; coroutines (`withContext(Dispatchers.IO)`, `withTimeout`) ;
  - journal avec `android.util.Log` et une constante `TAG` par fichier : note chaque source essayée, son résultat (trouvé / vide / erreur + message) et sa durée ;
  - **commentaires et textes visibles en français**, courts, au même niveau de détail que le code existant.
- Vérifier la compilation : `./gradlew :app:compileReleaseKotlin` ; les tests : `./gradlew :app:testReleaseUnitTest` (JUnit 4 déjà présent, tests dans `app/src/test/java/com/spotywoop/kt/`).

## Interdits

- Pas de `git commit`, `git push`, `git tag`, ni aucune commande `gh`.
- Pas d'`adb`, pas d'installation sur un appareil.
- Ne lis pas, n'affiche pas et ne modifie pas `local.properties` (contient des secrets).
- Ne touche pas aux fichiers hors de `app/src/` et `docs/lyrics-port/`, sauf ajout d'une dépendance strictement nécessaire dans `app/build.gradle.kts` / `gradle/libs.versions.toml` (justifie-la dans le compte rendu).
- Pas de WebView : tout est natif (Kotlin + Compose).

## Phase 1 : données et sources (`com.spotywoop.kt.lyrics`)

1. **Modèle** (`LyricsModels.kt`) : équivalents exacts de `Syllable`, `LyricsLine`, `LyricsContributor`, `LyricsSourceResult` (temps en **millisecondes Long**), plus les métadonnées (`SongMetadata`) utilisées par la cascade.
2. **Analyseurs** (`LyricsParsers.kt`), mêmes règles que la référence :
   - TTML (`parseTTML`, `parseTTMLTime`, alignements `calculateLineAlignments`, langues RTL, chœurs `x-bg`, agents/duos, `songPart`, auteurs, traductions/romanisations intégrées). Utilise `org.xmlpull` / `XmlPullParser` d'Android.
   - JSON lrc.red (`parseLrcRedJson`), Spicy Lyrics (`parseSpicyLyrics`), KPoe/LyricsPlus (`convertKPoeLyrics`), LRC (`parseLrcSubtitles`), Genius, Unison, BiniLyrics : tout ce que la référence lit.
3. **Sources** (`LyricsProviders.kt`) : un fournisseur par source de la référence, avec les **mêmes adresses, paramètres, en-têtes, délais et miroirs** : lrc.red (`match.json` / `search.json` puis `/s/<ISRC>.song.json`), BiniLyrics, Spicy Lyrics (+ jeton Spotify `SPOTIFY_TOKEN_URL` partagé et mis en cache comme `getSpotifyToken`), Unison, YouLyPlus / LyricsPlus (tous les miroirs, dans le même ordre), LRCLIB, Genius, la recherche Spotify de `resolveSongMetadata`.
4. **Cascade** (`LyricsRepository.kt`) : portage exact de `fetchLyrics` + `resolveSongMetadata` + `publishAvailableSources` :
   - même ordre, mêmes conditions d'arrêt (mot par mot humain trouvé → arrêt ; `byModel` → on continue), même rattrapage LyricsPlus explicite ;
   - classement `getTimingRank` / `getRankForCollected` / `compareSources` / `mergeAndSortSources` à l'identique ;
   - expose un `Flow` (ou un rappel) qui publie les sources au fur et à mesure, comme la référence met à jour l'affichage pendant la cascade, puis l'état final (liste triée de sources, index courant, `hasFetchedAllProviders`) ;
   - changement de source (`switchSource`, `applySourceAtIndex`, `getNextSourceIndex`) ;
   - annulation propre quand le titre change (équivalent de l'`AbortController`) ;
   - cache mémoire par titre (comme `LyricsClient` actuel).
5. **Traduction / romanisation** (`GoogleLyricsService.kt`) : portage de `GoogleService.ts` et de `applyTranslation` / `applyRomanization`.
6. **Export** : `generateLRC`, `generatePlain`, `generateTTML` avec le même format de temps.
7. **Tests JVM** (`app/src/test/java/com/spotywoop/kt/lyrics/`) : analyse de `lrcred-lastlast.song.json` (copie-le dans `app/src/test/resources/`) : nombre de lignes, premier mot « E » de 0 à 220 ms, détection mot par mot, `songPart` « Intro », auteurs ; un TTML minimal avec chœur et duo ; un LRC ; le classement des sources. Ces tests ne doivent pas faire d'appel réseau.

La phase 1 ne touche pas encore l'interface : `LyricsClient`, `LyricsView` et `PlayerScreen` restent tels quels.

## Phase 2 : affichage et animation (Compose)

La phase 1 est faite et relue (`com.spotywoop.kt.lyrics` : modèle, analyseurs, sources, cascade
`LyricsRepository`, traduction, export, tests). Ne la réécris pas ; corrige-la seulement si l'affichage
en a besoin, et dis-le dans le compte rendu.

Porte maintenant **tout le rendu et toute l'animation** de `AmLyrics.ts` (de `firstUpdated` / `_onTimeChanged`
jusqu'à `render()` et `static styles`) en Jetpack Compose natif, dans `com.spotywoop.kt.lyrics.ui` :

1. **Horloge fluide** : l'appli ne connaît la position de lecture que toutes les 500 ms
   (`PlayerUiState.positionMs`, `isPlaying`). Crée `rememberPlaybackClock(positionMs, isPlaying)` qui interpole
   image par image avec `withFrameNanos`, se recale à chaque nouvelle position, et suit les sauts (seek)
   immédiatement. Tout le rendu lit cette horloge, comme `currentTime` dans la référence.
2. **Rendu mot par mot**, mêmes règles et mêmes valeurs que la référence (reprends les nombres des variables
   CSS et des constantes : tailles, espacements, durées, échelles, rayons, flou) :
   - lignes actives (`findActiveLineIndices`, chevauchements), lignes passées / futures, flou des lignes non
     actives (`noBlur`), échelle des lignes inactives ;
   - remplissage du mot par dégradé (`startWordWipe`, `getWordWipe`, `applyWipeShape`, plume de progression),
     pré-remplissage du mot suivant (`maybePreWipeNextWord`), mots longs lettre par lettre avec élévation
     (`updateCharacterMotion`, `springProgress`), lueur ;
   - lignes synchronisées seulement à la ligne, paroles non synchronisées (texte simple) ;
   - chœurs (`backgroundText`) plus petits sous la ligne, entrée / sortie animées ; duos (`oppositeTurn`)
     alignés à droite ; langues de droite à gauche ;
   - passages instrumentaux : les trois points qui respirent (`updateInstrumentalGap`) ;
   - défilement par ressort vers la ligne active (`animateScrollYouLy`, `sampleSpring`, `createLineSpringFrames`),
     position de la ligne active comme `--lyrics-scroll-padding-top` / `selected-position`, pause du défilement
     automatique quand l'utilisateur fait défiler lui-même puis reprise (`handleUserScroll`) ;
   - retour en arrière (`startLineRewind`) et saut (seek) ;
   - appui sur une ligne → `onSeek(timestampMs)` ;
   - bas de page : source affichée, bouton pour changer de source (`switchSource`), auteurs, contributeurs ;
   - boutons traduction et romanisation ; export LRC / texte / TTML proposé par le menu de partage Android
     (pas de téléchargement de fichier) ;
   - options `hidePlayedLines`, `reducedMotion`, `lineMotion` comme la référence.
   Fluidité : 60 images/s sur un téléphone moyen. Pas de recomposition de toute la liste à chaque image :
   lis l'horloge dans les phases de dessin / disposition (`drawWithContent`, `graphicsLayer`, lambdas).
3. **Branchement dans l'appli** :
   - `PlayerViewModel.loadLyrics` lance `LyricsRepository.fetchLyrics(...)` (titre, artiste, album, durée en ms ;
     `spotifyId` = l'identifiant du titre quand c'est un identifiant Spotify : 22 caractères alphanumériques,
     ou préfixe `sp:` retiré ; les identifiants `am:` et `yt:` ne sont pas Spotify). L'état de `LyricsRepository`
     remonte dans `PlayerUiState`.
   - **Affichage immédiat** : la cascade peut prendre plus de 10 s. En attendant qu'elle ait des paroles,
     affiche celles de l'actuel `LyricsClient.fetchLyrics` (API du site, rapide, ligne par ligne) ; dès que la
     cascade a publié une source, elle remplace cet affichage.
   - Remplace l'aperçu `LyricsCard` et `FullScreenLyricsDialog` (`ui/LyricsView.kt`, utilisés dans
     `ui/PlayerScreen.kt`) par le nouveau rendu, en gardant leur place et leur rôle (aperçu dans l'écran du
     lecteur, plein écran à l'appui). Le bandeau « ligne en cours » de `PlayerScreen` (`currentLyricText`)
     doit lire le nouveau modèle.
   - Polices et couleurs : celles de l'appli (`ui/theme`), le blanc pour le surlignage comme le site.
4. Vérifie avec `./gradlew :app:compileReleaseKotlin` et `./gradlew :app:testDebugUnitTest --tests 'com.spotywoop.kt.lyrics.*'`
   (la tâche `testReleaseUnitTest` n'existe pas). Ajoute des tests JVM pour la logique pure (lignes actives,
   horloge, progression d'un mot), pas pour le dessin.

Compte rendu dans `docs/lyrics-port/PHASE2.md`, même forme que pour la phase 1.

## Compte rendu attendu à la fin

Liste des fichiers créés ou modifiés, correspondance « fonction de la référence → fonction Kotlin », ce qui n'a pas pu être porté et pourquoi, sortie de la compilation et des tests.
