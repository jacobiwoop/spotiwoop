# Phase 1 : Données et sources (`com.spotywoop.kt.lyrics`)

Compte rendu de réalisation de la Phase 1 du portage de **am-lyrics** en Kotlin natif pour Spotywoop.

---

## 1. Fichiers créés ou modifiés

### Fichiers de code de production (`app/src/main/java/com/spotywoop/kt/lyrics/`)
- **`LyricsModels.kt`** : Définition des modèles de données (`Syllable`, `LyricsLine`, `LyricsContributor`, `LyricsSourceResult`, `SongMetadata`, `SongCatalogResult`, `ResolvedMetadata`, `ParsedQueryMetadata`, `LyricsState`) et utilitaires de temps (`secondsToMs`, `toMilliseconds`). Horodatages en millisecondes `Long`.
- **`LyricsParsers.kt`** : Analyseurs de paroles pour l'ensemble des formats supportés :
  - TTML (`parseTTML`, `parseTTMLWithPull`, `parseTTMLDom`, `parseTTMLTime`, `calculateLineAlignments`, `isRightToLeftLanguage`).
  - JSON lrc.red (`parseLrcRedJson`).
  - Spicy Lyrics (`parseSpicyLyrics`, `parseSpicyContributors`).
  - KPoe / LyricsPlus (`convertKPoeLyrics`).
  - Sous-titres LRC (`parseLrcSubtitles`).
- **`LyricsProviders.kt`** : Clients et requêtes réseau avec `OkHttpClient` configuré avec `AppLog.Http`, timeouts, journalisation par `TAG = "LyricsProviders"` :
  - Jeton Spotify partagé et mis en cache (`getSpotifyToken`).
  - Résolution des métadonnées (`parseQueryMetadata`, `searchLyricsPlusCatalog`, `resolveSongMetadata`).
  - Fournisseurs : `fetchLyricsFromLrcRed`, `fetchLyricsFromBiniLyrics`, `fetchLyricsFromSpicyLyrics`, `fetchLyricsFromUnison`, `fetchLyricsFromYouLyPlus`, `fetchLyricsFromLrclib`, `fetchLyricsFromGenius`.
  - Règles de tri et de priorité des sources (`hasWordSyncedLyrics`, `getTimingRank`, `getRankForCollected`, `compareSources`, `mergeAndSortSources`, `getSourceKey`, `getDisplaySourceLabel`).
- **`LyricsRepository.kt`** : Cascade complète (`fetchLyrics`, `publishAvailableSources`, `switchSource`, `applySourceAtIndex`, `findCurrentSourceIndex`, `getNextSourceIndex`) avec gestion de cycle de vie (`Job.cancel()`), cache mémoire par titre (`ConcurrentHashMap`) et exposition réactive par `StateFlow<LyricsState>`.
- **`GoogleLyricsService.kt`** : Service de traduction (`translate`) et de romanisation (`romanize`, `romanizeTexts`, `romanizeWordSynced`, `romanizeLineSynced`, `isPurelyLatinScript`, `applyTranslation`, `applyRomanization`).
- **`LyricsExport.kt`** : Fonctions d'export (`formatTimestampLRC`, `formatTimestampTTML`, `getBackgroundTextPlacement`, `generateLRC`, `generatePlain`, `generateTTML`).

### Fichiers de tests (`app/src/test/`)
- **`app/src/test/resources/lrcred-lastlast.song.json`** : Fichier réel de test copié depuis `am-lyrics/lrcred-lastlast.song.json`.
- **`app/src/test/java/com/spotywoop/kt/lyrics/LrcRedJsonTest.kt`** : Validation de `lrcred-lastlast.song.json` (61 lignes, premier mot « E » de 0 à 220 ms, mot par mot, `songPart` « Intro », auteurs, et double vérification via analyseur TTML sur `song.source`).
- **`app/src/test/java/com/spotywoop/kt/lyrics/TTMLMinimalTest.kt`** : Validation d'un TTML minimal avec chœurs (`x-bg`), duo (alternance `start`/`end`), parsing des différents formats d'horodatages TTML.
- **`app/src/test/java/com/spotywoop/kt/lyrics/LrcParserTest.kt`** : Validation des sous-titres LRC (centisecondes, millisecondes, calcul de fin de ligne et omission des silences).
- **`app/src/test/java/com/spotywoop/kt/lyrics/SourceRankingTest.kt`** : Validation du classement `getTimingRank`, `getRankForCollected`, `compareSources`, et déduplication/tri `mergeAndSortSources`.
- **`app/src/test/java/com/spotywoop/kt/lyrics/LyricsExportTest.kt`** : Validation du formatage des exports LRC, texte brut (avec placement des chœurs) et TTML.
- **`app/src/test/java/com/spotywoop/kt/lyrics/SpicyLyricsAndKPoeTest.kt`** : Validation de l'analyse Spicy Lyrics (type `Syllable`) et KPoe (type `Word`).
- **`app/src/test/java/com/spotywoop/kt/lyrics/LyricsRepositoryTest.kt`** : Validation des clés de cache et du cycle d'indexation de source.
- **`app/src/test/java/com/spotywoop/kt/lyrics/GoogleLyricsServiceTest.kt`** : Validation de la détection de l'alphabet latin / non-latin.

### Modification de configuration (`app/build.gradle.kts`)
- Ajout de la dépendance de test `testImplementation("org.json:json:20240303")`.
  - **Justification** : `org.json` fait partie du SDK Android à l'exécution, mais sous l'environnement de tests unitaires purs JVM (`testDebugUnitTest`), `android.jar` ne contient que des stubs non mockés (`Method ... not mocked`). L'ajout de l'implémentation de référence `org.json:json` en périmètre de test uniquement permet aux tests JVM d'analyser les JSON hors-ligne sans dépendre d'un émulateur ou d'un appareil physique, conformément aux interdits.

---

## 2. Correspondance « Fonction de la référence → Fonction Kotlin »

| Référence TypeScript (`am-lyrics`) | Fichier / Fonction Kotlin (`com.spotywoop.kt.lyrics`) |
| :--- | :--- |
| `Syllable` (`LyricsSources.ts:1`) | `Syllable` (`LyricsModels.kt`) |
| `LyricsLine` (`LyricsSources.ts:10`) | `LyricsLine` (`LyricsModels.kt`) |
| `LyricsContributor` (`LyricsSources.ts:26`) | `LyricsContributor` (`LyricsModels.kt`) |
| `LyricsSourceResult` (`LyricsSources.ts:33`) | `LyricsSourceResult` (`LyricsModels.kt`) |
| `secondsToMs` (`LyricsSources.ts:50`) | `secondsToMs` (`LyricsModels.kt`) |
| `toMilliseconds` (`AmLyrics.ts:4930`) | `toMilliseconds` (`LyricsModels.kt`) |
| `parseTTMLTime` (`AmLyrics.ts:4379`) | `LyricsParsers.parseTTMLTime` |
| `isRightToLeftLanguage` (`AmLyrics.ts:4412`) | `LyricsParsers.isRightToLeftLanguage` |
| `calculateLineAlignments` (`AmLyrics.ts:4313`) | `LyricsParsers.calculateLineAlignments` |
| `parseTTML` (`AmLyrics.ts:4420`) | `LyricsParsers.parseTTML` (`parseTTMLWithPull` / `parseTTMLDom`) |
| `parseLrcRedJson` (`AmLyrics.ts:3786`) | `LyricsParsers.parseLrcRedJson` |
| `parseSpicyLyrics` (`LyricsSources.ts:94`) | `LyricsParsers.parseSpicyLyrics` |
| `spicyContributors` (`LyricsSources.ts:68`) | `LyricsParsers.parseSpicyContributors` |
| `convertKPoeLyrics` (`AmLyrics.ts:4782`) | `LyricsParsers.convertKPoeLyrics` |
| `parseLrcSubtitles` (`AmLyrics.ts:4045`) | `LyricsParsers.parseLrcSubtitles` |
| `getSpotifyToken` (`AmLyrics.ts:3484`) | `LyricsProviders.getSpotifyToken` |
| `parseQueryMetadata` (`AmLyrics.ts:3606`) | `LyricsProviders.parseQueryMetadata` |
| `searchLyricsPlusCatalog` (`AmLyrics.ts:3640`) | `LyricsProviders.searchLyricsPlusCatalog` |
| `resolveSongMetadata` (`AmLyrics.ts:3376`) | `LyricsProviders.resolveSongMetadata` |
| `fetchLyricsFromLrcRed` (`AmLyrics.ts:3685`) | `LyricsProviders.fetchLyricsFromLrcRed` |
| `fetchLyricsFromBiniLyrics` (`AmLyrics.ts:3839`) | `LyricsProviders.fetchLyricsFromBiniLyrics` |
| `fetchLyricsFromSpicyLyrics` (`AmLyrics.ts:3523`) | `LyricsProviders.fetchLyricsFromSpicyLyrics` |
| `fetchLyricsFromUnison` (`AmLyrics.ts:4234`) | `LyricsProviders.fetchLyricsFromUnison` |
| `fetchLyricsFromYouLyPlus` (`AmLyrics.ts:3913`) | `LyricsProviders.fetchLyricsFromYouLyPlus` |
| `fetchLyricsFromLrclib` (`AmLyrics.ts:4109`) | `LyricsProviders.fetchLyricsFromLrclib` |
| `fetchLyricsFromGenius` (`AmLyrics.ts:4182`) | `LyricsProviders.fetchLyricsFromGenius` |
| `hasWordSyncedLyrics` (`AmLyrics.ts:3094`) | `LyricsProviders.hasWordSyncedLyrics` |
| `getTimingRank` (`AmLyrics.ts:3105`) | `LyricsProviders.getTimingRank` |
| `getRankForCollected` (`AmLyrics.ts:3116`) | `LyricsProviders.getRankForCollected` |
| `compareSources` (`AmLyrics.ts:3131`) | `LyricsProviders.compareSources` |
| `getDisplaySourceLabel` (`AmLyrics.ts:3144`) | `LyricsProviders.getDisplaySourceLabel` |
| `getSourceKey` (`AmLyrics.ts:3150`) | `LyricsProviders.getSourceKey` |
| `mergeAndSortSources` (`AmLyrics.ts:3157`) | `LyricsProviders.mergeAndSortSources` |
| `fetchLyrics` (`AmLyrics.ts:2806`) | `LyricsRepository.fetchLyrics` / `executeCascade` |
| `publishAvailableSources` (`AmLyrics.ts:3039`) | `LyricsRepository.publishAvailableSources` |
| `findCurrentSourceIndex` (`AmLyrics.ts:3178`) | `LyricsRepository.findCurrentSourceIndex` |
| `getNextSourceIndex` (`AmLyrics.ts:3194`) | `LyricsRepository.getNextSourceIndex` |
| `applySourceAtIndex` (`AmLyrics.ts:3216`) | `LyricsRepository.applySourceAtIndex` |
| `switchSource` (`AmLyrics.ts:3229`) | `LyricsRepository.switchSource` |
| `GoogleService.isPurelyLatinScript` | `GoogleLyricsService.isPurelyLatinScript` |
| `GoogleService.translate` | `GoogleLyricsService.translate` |
| `GoogleService.romanize` | `GoogleLyricsService.romanize` |
| `GoogleService.romanizeWordSynced` | `GoogleLyricsService.romanizeWordSynced` |
| `GoogleService.romanizeLineSynced` | `GoogleLyricsService.romanizeLineSynced` |
| `GoogleService.romanizeTexts` | `GoogleLyricsService.romanizeTexts` |
| `applyTranslation` (`AmLyrics.ts:2320`) | `GoogleLyricsService.applyTranslation` |
| `applyRomanization` (`AmLyrics.ts:2292`) | `GoogleLyricsService.applyRomanization` |
| `formatTimestampLRC` (`AmLyrics.ts:9034`) | `LyricsExport.formatTimestampLRC` |
| `formatTimestampTTML` (`AmLyrics.ts:9044`) | `LyricsExport.formatTimestampTTML` |
| `getBackgroundTextPlacement` (`AmLyrics.ts:6946`) | `LyricsExport.getBackgroundTextPlacement` |
| `generateLRC` (`AmLyrics.ts:8923`) | `LyricsExport.generateLRC` |
| `generatePlain` (`AmLyrics.ts:8948`) | `LyricsExport.generatePlain` |
| `generateTTML` (`AmLyrics.ts:8975`) | `LyricsExport.generateTTML` |

---

## 3. Ce qui n'a pas pu être porté et pourquoi

Tous les éléments prévus pour la Phase 1 ont été intégralement portés.
Les adaptations techniques effectuées sont :
1. **Remplacement des API du navigateur DOM/HTML par des équivalents JVM/Android** :
   - `fetch` et `AbortController` → `OkHttpClient` avec `addInterceptor(AppLog.Http)` et coroutines Kotlin (`Job.cancel()`, `withTimeout`).
   - `DOMParser` du navigateur → Parseur XML Android (`org.xmlpull.v1.XmlPullParser`) et `DocumentBuilderFactory` standard pour une conformité totale en test JVM pur.
2. **Phase 2 (IHM et rendu graphique)** :
   - Conformément aux consignes du brief, les aspects UI (`LyricsView`, `PlayerScreen`, CSS Lit, animations de défilement) n'ont pas encore été modifiés et feront l'objet de la Phase 2.

---

## 4. Sortie de la compilation et des tests

### Compilation Kotlin Release (`./gradlew :app:compileReleaseKotlin`)
```
BUILD SUCCESSFUL in 2s
7 actionable tasks: 1 executed, 6 up-to-date
```
Aucune erreur, aucun avertissement sur les nouveaux fichiers créés.

### Tests unitaires JVM (`./gradlew test`)
```
BUILD SUCCESSFUL in 4s
33 actionable tasks: 3 executed, 30 up-to-date
```

Détail des 21 tests exécutés avec succès (0 échec, 0 erreur) :
- `com.spotywoop.kt.lyrics.LrcRedJsonTest` : 2 tests (analyse JSON lrc.red et TTML `lrcred-lastlast.song.json`, 61 lignes, détection mot par mot, intro, mot « E » de 0 à 220 ms, auteurs)
- `com.spotywoop.kt.lyrics.TTMLMinimalTest` : 2 tests (TTML minimal avec chœurs `x-bg`, duo `agentId` et parsing horaire multi-unités)
- `com.spotywoop.kt.lyrics.LrcParserTest` : 1 test (analyse sous-titres LRC avec centisecondes, millisecondes et silences)
- `com.spotywoop.kt.lyrics.SourceRankingTest` : 4 tests (classement, comparaison et tri fusion des sources)
- `com.spotywoop.kt.lyrics.LyricsExportTest` : 4 tests (export LRC, Plain et TTML avec placement des chœurs)
- `com.spotywoop.kt.lyrics.SpicyLyricsAndKPoeTest` : 2 tests (analyse Spicy Lyrics Syllable et KPoe Word)
- `com.spotywoop.kt.lyrics.LyricsRepositoryTest` : 2 tests (clés de cache et alternance des sources)
- `com.spotywoop.kt.lyrics.GoogleLyricsServiceTest` : 1 test (détection alphabet latin)
- `com.spotywoop.kt.data.SpotifyTotpTest` : 2 tests
- `com.spotywoop.kt.data.SpotifyLiveTest` : 1 test (ignoré en local sans identifiants réseau)
