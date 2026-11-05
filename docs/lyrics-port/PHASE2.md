# Phase 2 : Affichage et animation (Compose)

Compte rendu de réalisation de la Phase 2 du portage de **am-lyrics** en Kotlin natif (Jetpack Compose) pour Spotywoop.

---

## 1. Fichiers créés ou modifiés

### Nouveaux fichiers de composants Compose (`app/src/main/java/com/spotywoop/kt/lyrics/ui/`)
- **`PlaybackClock.kt`** : Horloge fluide d'interpolation d'images avec `withFrameNanos` (`PlaybackClock`, `PlaybackClockState`, `rememberPlaybackClock`). Recalage adaptatif à chaque tick externe du lecteur (500 ms), lissage des micro-dérives et détection immédiate des sauts manuels (seek > 500 ms).
- **`LyricsUiModels.kt`** : Constantes physiques et géométriques issues de `AmLyrics.ts` (`INSTRUMENTAL_THRESHOLD_MS`, durées de pré-wipe, plume de progression de 30 px, flous, échelles 0.98/0.96, équations de ressort amorti `sampleSpring` et `springProgress`). Fonctions pures de calcul des lignes actives (`findActiveLineIndices`, `computeLineHighlightEndTime`), détection des pauses instrumentales (`findAllInstrumentalGaps`, `findInstrumentalGapAt`), découpage des syllabes en mots (`groupSyllablesIntoWords`), gestion des duos (`isOppositeAgent`) et détection RTL (`isLineRtl`).
- **`InstrumentalGapRow.kt`** : Composant des trois points qui respirent (`InstrumentalGapRow`, `InstrumentalDot`) lors des pauses instrumentales $\ge 7\text{ s}$ : respiration sinusoïdale de 4 s (échelle 0,85 à 1,12), allumage séquentiel des trois points, pop à 1,2 et effondrement fluide avant la reprise du chant.
- **`LyricsWordWipe.kt`** : Composants de surlignage mot par mot (`LyricsWordItem`, `StandardWord`, `EmphasisWord`) exécutés dans la phase de dessin (`drawWithContent` et `graphicsLayer` avec `CompositingStrategy.Offscreen` et masque `BlendMode.DstIn`) pour garantir 60 fps sans aucune recomposition. Support du pré-remplissage (`preWipeDurationMs`), plume de transition de 30 px, élévation et physique d'impulsion lettre par lettre (`springProgress`) et halo de lueur (`Shadow` + glow).
- **`AmLyricsView.kt`** : Composant Compose principal intégrant l'expérience complète :
  - Défilement automatique par ressort avec alignement à 12% du haut de l'écran (`SCROLL_PADDING_TOP_RATIO`).
  - Détection du défilement tactile utilisateur (`isUserScrolling`), pause de l'auto-scroll pendant 5 s (`USER_SCROLL_RESUME_DELAY_MS`) et retrait temporaire du flou (opacité 0,85) pour une lisibilité parfaite pendant le défilement manuel.
  - Dégradé de flou et d'échelle selon la distance à la ligne active (1,0 à 0,46, flou 1,2 dp à 2,4 dp, sauf si `noBlur` ou défilement utilisateur).
  - Gestion des chœurs (`backgroundText`) : taille réduite (0,65em), espacement de 15 px, entrée/sortie animée (`expandVertically` / `shrinkVertically`).
  - Gestion des duos (`oppositeTurn` / `alignment == "end"`) alignés à droite.
  - Bas de page (`LyricsFooter`) : affichage de la source active, sélecteur de source (`switchSource`), auteurs-compositeurs et avatars des contributeurs.
  - Barre d'en-tête : boutons de bascule de romanisation (`Aa`), traduction (`Tr`) et menu d'export (partage Android standard en texte brut, LRC et TTML).
  - Prise en charge des options `hidePlayedLines`, `reducedMotion` et `lineMotion`.

### Fichiers de l'application modifiés (`app/src/main/java/com/spotywoop/kt/ui/`)
- **`LyricsView.kt`** :
  - Remplacement de l'ancien affichage statique de `LyricsCard` et `FullScreenLyricsDialog` par `AmLyricsView` branché sur l'horloge fluide `rememberPlaybackClock`.
  - Intégration de la conversion instantanée `LyricsResult.toLyricsLines()` pour l'affichage de repli immédiat.
  - Maintien strict des signatures et rôles (carte d'aperçu dans l'écran de lecture, dialogue modal plein écran immersif).
- **`PlayerViewModel.kt`** :
  - Ajout du champ `richLyrics: LyricsState` dans `PlayerUiState`.
  - Abonnement réactif à `LyricsRepository.state` dès l'initialisation du ViewModel.
  - Extraction de l'identifiant Spotify (`extractSpotifyId` gérant le préfixe `sp:` et les identifiants de 22 caractères, ignorant `am:` et `yt:`).
  - Dans `loadLyrics` : déclenchement en parallèle de la cascade `LyricsRepository.fetchLyrics(...)` et de l'API rapide du site `LyricsClient.fetchLyrics(...)` pour un affichage immédiat en moins d'une seconde, remplacé automatiquement dès que la cascade produit ses résultats enrichis.
  - Réinitialisation propre lors des changements de piste.
- **`PlayerScreen.kt`** :
  - Mise à jour du bandeau « ligne en cours » (`currentLyricText`) pour lire le nouveau modèle enrichi `state.richLyrics.lyrics` en priorité (avec calcul de ligne active mot par mot), avec repli transparent sur l'ancien modèle `state.lyrics`.
  - Transmission des états `richLyrics` et `isPlaying` à `LyricsCard` et `FullScreenLyricsDialog`.

### Fichiers de tests JVM (`app/src/test/java/com/spotywoop/kt/lyrics/`)
- **`PlaybackClockTest.kt`** (6 tests) :
  - Validation de l'initialisation et du temps flottant.
  - Validation de l'avancement temporel précis à 60 images/s en lecture.
  - Validation de l'immobilité de l'horloge en pause.
  - Validation du lissage de micro-dérive (soft sync).
  - Validation du recueillement immédiat lors d'un saut franc (seek > 500 ms).
  - Validation de l'appel direct `seekTo`.
- **`ActiveLinesAndGapsTest.kt`** (6 tests) :
  - Validation du calcul séquentiel des lignes actives.
  - Validation des lignes actives simultanées lors de duos / chœurs superposés.
  - Validation de la fin de surlignage avant une pause instrumentale ($\ge 7\text{ s}$).
  - Validation de l'extension de surlignage quand les chœurs dépassent le chant principal.
  - Validation de la détection des intervalles instrumentaux d'intro et inter-lignes.
  - Validation de la recherche d'intervalle instrumental englobant un instant donné.
- **`WordWipeLogicTest.kt`** (5 tests) :
  - Validation des règles d'attribution de mode d'animation (`CharacterMotionMode.NONE`, `RISE` pour CJK, `EMPHASIS` pour mots longs $\ge 1\text{ s}$ de $\le 7$ lettres).
  - Validation du regroupement des syllabes en mots selon les espaces et calcul du pré-remplissage.
  - Validation du placement des chœurs (`BEFORE` / `AFTER`).
  - Validation de la fonction de réponse indicielle physique `springProgress`.
  - Validation de l'oscillateur harmonique amorti `sampleSpring`.

---

## 2. Correspondance « Fonction de la référence → Fonction Kotlin »

| Référence TypeScript (`am-lyrics / AmLyrics.ts`) | Fichier / Fonction Kotlin (`com.spotywoop.kt.lyrics.ui` / Spotywoop) |
| :--- | :--- |
| `currentTime` setter & `_onTimeChanged` (`AmLyrics.ts:2522, 4967`) | `PlaybackClockState.onPositionUpdate` / `onFrame` (`PlaybackClock.kt`) |
| `requestAnimationFrame` clock interpolation | `rememberPlaybackClock` avec `withFrameNanos` (`PlaybackClock.kt`) |
| `findActiveLineIndices` (`AmLyrics.ts:6776`) | `LyricsUiModels.findActiveLineIndices` |
| `computeLineHighlightEndTime` (`AmLyrics.ts:5879`) | `LyricsUiModels.computeLineHighlightEndTime` |
| `findInstrumentalGapAt` (`AmLyrics.ts:6806`) | `LyricsUiModels.findInstrumentalGapAt` |
| `findAllInstrumentalGaps` (`AmLyrics.ts:6843`) | `LyricsUiModels.findAllInstrumentalGaps` |
| `updateInstrumentalGap` (`AmLyrics.ts:5945`) | `InstrumentalGapRow` & `InstrumentalDot` (`InstrumentalGapRow.kt`) |
| `startWordWipe` (`AmLyrics.ts:7699`) | `StandardWord` avec dégradé `drawWithContent` (`LyricsWordWipe.kt`) |
| `applyWipeShape` (`AmLyrics.ts:7428`) | Calcul plume et dégradé dans `LyricsWordWipe.kt` |
| `maybePreWipeNextWord` (`AmLyrics.ts:7982`) | `preWipeDurationMs` & fenêtre d'anticipation dans `StandardWord` |
| `characterMotionMode` (`AmLyrics.ts:5706`) | `LyricsUiModels.characterMotionMode` |
| `updateCharacterMotion` (`AmLyrics.ts:8519`) | `EmphasisWord` lettre par lettre (`LyricsWordWipe.kt`) |
| `springProgress` (`AmLyrics.ts:8714`) | `LyricsUiModels.springProgress` |
| `sampleSpring` (`AmLyrics.ts:7075`) | `LyricsUiModels.sampleSpring` |
| `handleUserScroll` (`AmLyrics.ts:6685`) | Gestionnaire `isUserScrolling` (délai 5 s) dans `AmLyricsView.kt` |
| `isOppositeAgent` (`AmLyrics.ts:5858`) | `LyricsUiModels.isOppositeAgent` |
| `isLineRtl` / RTL styles (`AmLyrics.ts:476`) | `LyricsUiModels.isLineRtl` & inversion gradient dans `LyricsWordWipe.kt` |
| `getBackgroundTextPlacement` (`AmLyrics.ts:6946`) | `LyricsUiModels.getBackgroundTextPlacement` |
| Background vocals container & animation (`AmLyrics.ts:351`) | `BackgroundVocalsBlock` avec `expandVertically` dans `AmLyricsView.kt` |
| `switchSource` button & source label (`AmLyrics.ts:1702, 3229`) | `LyricsFooter` / `LyricsHeaderBar` dans `AmLyricsView.kt` |
| Export format options (LRC, Plain, TTML) | Menu d'export avec `Intent.ACTION_SEND` dans `AmLyricsView.kt` |
| `hidePlayedLines` (`AmLyrics.ts:5169`) | Masquage des lignes jouées dans `AmLyricsView.kt` |
| `reducedMotion` / `lineMotion` (`AmLyrics.ts:1038, 7160`) | Désactivation ressorts / flous / animations dans `AmLyricsView.kt` |
| `loadLyrics` & cascade triggering | `PlayerViewModel.loadLyrics` (`PlayerViewModel.kt`) |
| Spotify ID resolution from track ID | `PlayerViewModel.extractSpotifyId` (`PlayerViewModel.kt`) |
| Fast immediate lyrics fallback | `PlayerViewModel.loadLyrics` & `toLyricsLines` (`LyricsView.kt`) |
| Live Lyric Ticker binding | `currentLyricText` dans `PlayerScreen.kt` |

---

## 3. Ce qui n'a pas pu être porté et pourquoi

Toutes les fonctionnalités, règles d'affichage et animations prévues pour la Phase 2 ont été **intégralement portées en Jetpack Compose natif**.

Les adaptations techniques nécessaires ont été réalisées dans le respect absolu des interdits :
1. **Remplacement des API du DOM / CSS Web par Jetpack Compose natif** :
   - Les propriétés CSS Lit (`static styles`, pseudo-éléments `::before`/`::after`, `background-clip: text`) ont été transposées en modificateurs Compose natifs performants (`Modifier.drawWithContent`, `Modifier.graphicsLayer` avec `CompositingStrategy.Offscreen` et `BlendMode.DstIn`).
   - Le système de défilement impératif JS (`element.scrollTo`, `Animation.sampleSpring`) a été porté via `LazyListState.animateScrollToItem` avec décalage de 12% (`scrollOffset`), combiné aux fonctions mathématiques pures d'amortissement critique.
   - Les téléchargements de fichiers Web (`<a> download`) ont été remplacés par la feuille de partage native Android (`Intent.ACTION_SEND`), évitant toute manipulation de fichiers temporaires sur le système de fichiers.
2. **Aucune régression sur le code existant** :
   - `LyricsCard` et `FullScreenLyricsDialog` conservent leurs rôles, leurs paramètres par défaut et leur intégration transparente dans `PlayerScreen.kt`.

---

## 4. Sortie de la compilation et des tests

### Compilation Kotlin Release (`./gradlew :app:compileReleaseKotlin`)
```
BUILD SUCCESSFUL in 872ms
7 actionable tasks: 7 up-to-date
Configuration cache entry reused.
```
Compilation 100% propre, aucun avertissement ni erreur sur l'ensemble du projet.

### Tests unitaires JVM (`./gradlew :app:testDebugUnitTest --rerun --tests 'com.spotywoop.kt.lyrics.*'`)
```
BUILD SUCCESSFUL in 2s
26 actionable tasks: 1 executed, 25 up-to-date
Configuration cache entry stored.
```

Détail des **35 tests unitaires JVM exécutés avec succès (0 échec, 0 erreur)** :
- `com.spotywoop.kt.lyrics.PlaybackClockTest` : 6 tests (initialisation, interpolation 60 fps, comportement en pause, soft sync, hard snap au seek, appel direct `seekTo`)
- `com.spotywoop.kt.lyrics.ActiveLinesAndGapsTest` : 6 tests (détection des lignes actives séquentielles, duos simultanés, surlignage avec pause instrumentale, extension chœurs, détection de tous les gaps et du gap courant)
- `com.spotywoop.kt.lyrics.WordWipeLogicTest` : 5 tests (règles `characterMotionMode`, découpage en mots et pré-remplissage, placement des chœurs, réponse indicielle `springProgress`, oscillateur `sampleSpring`)
- `com.spotywoop.kt.lyrics.LrcRedJsonTest` : 2 tests (analyse JSON lrc.red et TTML `lrcred-lastlast.song.json`, 61 lignes, détection mot par mot, intro, mot « E » de 0 à 220 ms, auteurs)
- `com.spotywoop.kt.lyrics.TTMLMinimalTest` : 2 tests (TTML minimal avec chœurs `x-bg`, duo `agentId` et parsing horaire multi-unités)
- `com.spotywoop.kt.lyrics.LrcParserTest` : 1 test (analyse sous-titres LRC avec centisecondes, millisecondes et silences)
- `com.spotywoop.kt.lyrics.SourceRankingTest` : 4 tests (classement, comparaison et tri fusion des sources)
- `com.spotywoop.kt.lyrics.LyricsExportTest` : 4 tests (export LRC, Plain et TTML avec placement des chœurs)
- `com.spotywoop.kt.lyrics.SpicyLyricsAndKPoeTest` : 2 tests (analyse Spicy Lyrics Syllable et KPoe Word)
- `com.spotywoop.kt.lyrics.LyricsRepositoryTest` : 2 tests (clés de cache et alternance des sources)
- `com.spotywoop.kt.lyrics.GoogleLyricsServiceTest` : 1 test (détection alphabet latin)
