# Tabslify — Gesamtbewertung

Stand: 2026-11-01
Basis: Working Tree `/home/paul/AndroidStudioProjects/Cloud1`, Backup unter `../Cloud1-backups/Cloud1-20261101-182845.*`

## Umfang

- 199 Kotlin-Dateien, ~87.500 LOC in `app/src/main/java`
- 2 Gradle-Module (`:app`, `:extern_accessibily`)
- 25 `MenuItem`-Tabs
- 2.171 Commits seit 2025-10-22, ~221k eingefügte Zeilen
- 18 externe Integrationen: Supabase (Auth, PostgREST, Storage, Realtime, Functions), Firebase (AI, FCM, AppCheck), Gemini, NVIDIA, OpenRouter, TMDB, VirusTotal, PodcastIndex, TikTok, Gmail, OSM/Nominatim, Wetter, Deutsche Bahn
- 10 Services, 14 Worker, 6 Room-Datenbanken, Autofill, TOTP, Widgets, MediaSession

## Kennzahlen

| Metrik | Wert |
|---|---|
| Dateien > 1.000 Zeilen | 21 (10,5 %), enthalten 47 % des Codes |
| Größte Datei | `MediaPlayerTab.kt` 4.189 Zeilen |
| Längste Composable | `SettingsFrame` ~1.453 Zeilen (`LandingPage.kt:1908`) |
| Max. Verschachtelung | 23 Ebenen (`PrivateTabslifyApp.kt:1647`) |
| `catch` gesamt / generisch `Exception` | 528 / 441 (83,5 %) |
| Leere Catches / nur Log / `printStackTrace` | 98 / 37 / 23 |
| `Result<…>` | 0× |
| Tests | 1 Datei, 5 Tests (0,14 %) |
| Lint | global deaktiviert (`app/build.gradle.kts:96-99`, `:178-181`) |
| SharedPreferences-Aufrufe | 207 in ~50 Dateien, 3 JSON-Stacks parallel |
| Top-Level `var` | 46, davon 42 in `WIFI_DIRECT.kt` |

## Was gut ist

- Integrationen sind echt verdrahtet, nicht gestubbt: Auth-Refresh zentral (`Config.safeCall`), Edge-Function-Proxy mit AppCheck und Signaturprüfung, echte FCM-Pfade, echte MediaSession.
- Sicherheit an den kritischen Stellen: Android-Keystore AES/GCM (`PasswordStorage.kt:19-47`), PBKDF2, keine Passwörter in SharedPreferences, App-Signaturprüfung.
- Cancellation-Disziplin: 30× `catch (CancellationException) { throw }` vor dem generischen Catch.
- Typisierte Ergebnisse statt Exception-Fluss: `SyncResult`, `BackupOutcome`, `SignatureState`.
- Vollständige Lokalisierung: 2.229/2.190 Keys, genau ein fehlender Key; 1.465 `stringResource` gegen 7 hartkodierte deutsche Texte.
- Einzelne Features sauber geschichtet: Explore (ViewModel → Repository → Room/Flows), FocusGuard, Contacts, Fitness, AI-Tab.
- Reverse Engineering dokumentiert: `docs/tiktok-im-protokoll.md` (59 KB).
- CodeQL-CI, zentrale Ports, `ExactAlarms`-Abstraktion, Error-Report nach Supabase.

## Was schlecht ist

- God-Files: `MediaPlayerTab.kt` (33 Composables + 1.268 Zeilen Nicht-UI), `WIFI_DIRECT.kt` (3.484 Zeilen, 93 Top-Level-Funktionen, ~10 Domänen), `QuietHoursNotificationService.kt` (1.432 Zeilen).
- Keine durchgesetzte Schichtung: 19 Tab-Dateien holen Daten direkt im Composable; `MainTabslifyScreen` (`PrivateTabslifyApp.kt:1072`, ~1.292 Zeilen) listet/löscht/lädt Supabase-Storage inline.
- Fehler werden verschluckt: `GmailTab.kt:159-173` kompletter Sync-Trigger in `catch (_: Exception) { }`; `PasswordStorage.kt:72-78` löscht bei jedem Entschlüsselungsfehler die Master-Passwort-Datei; alle 6 Room-DBs nutzen `fallbackToDestructiveMigration(true)`.
- Copy-Paste: Fade-in-Block 5×, `resolveSecret` 3× wortgleich, `API_PORT = 8886` 3×, DCIM-Download 3×, Deep-Link-Mapping doppelt und divergent (`LandingPage.kt:372` vs. `:451`), Retry-Schleife 4× in `HandyLampClient.kt`.
- Magic Numbers: 3.675 `dp`/`sp`-Literale mit 111 Werten, 238 verschiedene Farben, 17 Timeout-Werte, `24*60*60*1000` mehrfach neu erfunden.
- Namens-/Konsistenzchaos: `WIFI_DIRECT.kt`, `Passwordmanagerscreen.kt`, `spotifydownloader_own`, `MenuItem.Vocabs`, `Config.PLALISTS`, `GET_ACTIVE_PLALIST`, `showNetwerkInfo.kt`.
- Halbtoter Code: `inactive/ChatService.kt` wird aus `MainActivity.kt:134` gestartet; `ImageProviderActivity` ist exported; `core/functions/ImageCompression.kt` ist leer.
- Demo-Code in Produktion: `Tabslify.kt:158-162`; `fetchAndRun` führt remote aus Supabase geladenes JavaScript per Rhino aus, getriggert per FCM, Fehler verschluckt.
- Manifest: `usesCleartextTraffic="true"` global, 39 Permissions, `minSdk 35`, `versionCode 1`.
- KI-Artefakte als Arbeitsgrundlage: `AI/review_log.md` nennt 12 nicht existierende Dateien; `scan_report.json` 36/69 veraltet, 417 „Bugs“; `project_summary.md` 92 Dateien/33k LOC statt 199/87k; `memory_leak_audit/` mit falscher ChatService-Aussage; Hilfsskripte mit Windows-Pfaden.

## Vibe-Coding-Indizien

1. `.clinerules/no-validation.md`: automatische Validierung/Tests sind dem Agenten explizit verboten.
2. Ein-File-Commits: 168 von 200 Commits ändern genau eine Datei; bis zu 61 Commits an einem Tag.
3. God-Files aus inkrementellem Anbauen statt Entwurf.
4. KI-Analysen mit halluzinierten Dateinamen wurden committet und weiterbenutzt.
5. Lint komplett aus, 5 Tests, keine Instrumentation-Tests.
6. Globale mutable Zustände als Verständigungsbus (`Config.masterPassword` als Klartext-String, 42 Top-Level-`var` in einer Datei).
7. Feature-Breite vor Tiefe: Wetter, Notizen und Kalender sind funktionale Prototypen ohne Fehlerpfade.

## Gegenbeweise

- Echte Krypto, vollständige Zweisprachigkeit, Cancellation-Disziplin, typisierte Sync-Ergebnisse.
- CodeQL, zentrale Ports, dokumentiertes Reverse Engineering.
- Fehlerkultur mit Messung und Journal-Beleg in `.claude/errors.md`.

## Entscheidung

**Ja, Tabslify ist als vibe-coded einzustufen** — im Entstehungsprozess und in der Architektur. Nicht im Ergebnis: Es ist kein Wegwerf-Prototyp, sondern eine funktionierende Personal-Super-App, die an den riskanten Stellen bewusst nachgehärtet wurde. Einordnung: „AI schreibt, Besitzer debuggt und shippt“.

## Bewertung

| Bereich | Note |
|---|---|
| Nutzen für den Besitzer | 8/10 |
| Architektur | 3/10 |
| Wartbarkeit | 3/10 |
| Testbarkeit | 1/10 |
| Sicherheit | 5/10 |
| Vibe-Coded-Index | 8,5/10 |

## Sofort zu fixen

1. `PasswordStorage`-Datenverlust bei Entschlüsselungsfehlern.
2. `fallbackToDestructiveMigration` in allen Room-DBs.
3. Rhino-Remote-Ausführung aus der Supabase-Tabelle `scripts`.
