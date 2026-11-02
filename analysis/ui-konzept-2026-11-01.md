# Tabslify — UI-Konzept und Landingpage-Redesign

Stand: 2026-11-01

## Ausgangslage

- Der Screenshot `Preview/LandingPage.png` ist vom 31.05. und zeigt den alten blauen Sternenhimmel.
- Seit 2026-10-28 (Commit „rework theme to a warm dark palette and redesign landing page“) ist die App warm-orange.
- `Theme.kt`: `AccentOrange = #FF7A1A`, `AccentViolet` ist ein Alias auf Orange, warme Scrims (`#1C0F06`, `#2A1608`).
- `LandingPage.kt`: `AppBackground` mit `day.jpg`/`night.jpg` plus warmem Verlauf; `TabCard` mit Blur-Glow (`BlurMaskFilter`), Orange-Gradient, linker Leuchtkante, 84 dp Höhe, Großbuchstaben, 20 dp Radius.
- Landingpage ist eine 1-Spalten-Liste mit 25 identischen Karten; alle Tabs erben den Hintergrund über `PrivateTabslifyApp.kt:556`.

Das Problem ist nicht die Farbe allein, sondern die Kombination aus Glow, Gradient, Orange, Großbuchstaben und Pixel-Font.

## Zielbild

Ruhiges dunkles Interface: neutrale Grau-/Schwarztöne, ein Blau-Violett-Akzent, flächige Karten, keine Leuchtränder. Tag/Nacht-Fotos bleiben, werden aber entsättigt und neutral überblendet.

| Token | Wert |
|---|---|
| `BgDeep` | `#08080B` |
| `BgSurface` | `#101014` |
| `BgCard` | `#16161B` |
| `Border` | `#26262E` |
| `Accent` | `#7C6CFF` |
| `AccentSoft` | `#221F3A` |
| `AccentDim` | `#4A3FA0` |
| `TextPrimary` | `#F4F4F7` |
| `TextSecondary` | `#A6A6B2` |
| `TextTertiary` | `#6E6E7C` |
| `Success` | `#4ADE80` |
| `Error` | `#F87171` |

## Entscheidungen

- Scope: Landingpage plus globale Tokens und Hintergrund; Tab-lokale Paletten folgen im zweiten Durchgang.
- Kartenlayout: 2-Spalten-Grid.
- Schrift: System-Sans statt Pixel-Font.
- Hintergrund: Tag/Nacht-Fotos behalten, entsättigen.
- Bericht: `analysis/`, nicht getrackt.

## Umsetzung

### Theme

- Neue Palette in `Theme.kt`, `AppColorScheme` komplett darauf.
- `AccentViolet = Accent` bleibt als Alias, damit die 139 Verwendungen in 14 Dateien sofort blau-violett werden.
- `AccentOrange` (26 Stellen) auf `Accent` umbenennen; `APP_BLUE` ebenfalls auf `Accent`.
- `rememberAppColor()`/`c()` und den `ACTION_TIME_TICK`-Empfänger entfernen; `primary = Accent` statisch in `MainActivity.kt:160`.
- `Typography` auf System-Sans zurücksetzen, `AppFontFamily` nicht mehr zuweisen.

### Hintergrund

- `day.jpg`/`night.jpg` mit `ColorFilter.colorMatrix(... setToSaturation(0f))` entsättigen und abdunkeln.
- Warme Scrims durch neutrale ersetzen; darüber ein dezenter Blau-Violett-Verlauf (6–8 % Alpha, oben).
- `AppBgScrim` und Aufrufer unverändert; wirkt in Shell, Landing, Onboarding und Fitness.

### Landingpage

- `LazyListState` → `LazyGridState` in `LandingPageOrApp:248` und der `LandingPage`-Signatur `:846`.
- Abschnittsüberschriften als `GridItemSpan(maxLineSpan)`.
- Header linksbündig, `statusBarsPadding()`, Titel 28 sp, Settings-Icon dezent.
- `TabCard` neu: 2-Spalten-Kachel (~112 dp), `BgCard`, 1 dp `Border`, 16 dp Radius, Icon 24 dp in 40-dp-`AccentSoft`-Badge, Label 14 sp Medium in Normalschreibung, max. 2 Zeilen, Druck-Scale 0.97.
- Raus: Blur-Paint, Glow, linker Leuchtbalken, Linear-Gradient, Großbuchstaben, 84-dp-Höhe.
- Abschnittslabel 12 sp `TextTertiary`, kein Uppercase; Divider `Border`.
- `SettingsFrame`: warme Gradienten und Pink/Violett auf neutrale Flächen plus `Accent`; Fehlerfarben bleiben.
- Sortierung, Recent-Tabs, `prvt()`-Gating und Zoom-Übergang unverändert.

### Shell

- TopAppBar-Titel/Icons auf `TextPrimary`/`TextSecondary`.
- `GoodNightScreen` (`PrivateTabslifyApp.kt:2513`) bleibt unverändert, die Funktion ist unbenutzt.
- `NeonBox`/`PloppingButton` bleiben unangetastet.

### Zusätzlich umgesetzt (globale Token-Nachzügler)

- `WelcomeOnboarding.kt`: warme Orange-/Pink-Kacheln durch `Accent`/Blau-Violett ersetzt.
- `BackHintOverlay.kt`: Orange-Verlauf und Orange-Chip durch `Accent`/`AccentDim`.
- `Composables.kt` `FeedCard`: Favoriten-Orange durch `Accent`.
- `PrivateTabslifyApp.kt` HelpFrame: warmes Braun-Orange durch `BgSurface`/`BgDeep` und `Accent`/`AccentDim`.
- `LandingPage.kt`: Setup-Screens, Dialoge, Permission-Gradienten, Live-Traffic und Settings-Neonboxen auf die neuen Tokens gezogen.

### Geänderte Dateien

- `core/ui/Theme.kt`
- `core/ui/Composables.kt`
- `core/ui/LandingPage.kt`
- `core/ui/PrivateTabslifyApp.kt`
- `core/ui/WelcomeOnboarding.kt`
- `core/ui/BackHintOverlay.kt`
- `core/activities/MainActivity.kt`
- `tabs/aitab/AITab.kt` (nur `c()` → `Accent`)

## Zweiter Durchgang

- `MediaPlayerTab` (21 Farbliterale), `WeatherTab` (22), `VocabTab` (20), `CalenderTab` (lokale Token-Kopie), Fitness-Neon, School-Gradienten.
- `NeonBox`-Glow global reduzieren, `SettingsFrame` aufteilen, Suche/Filter auf der Landingpage.

## Risiken

- Lange deutsche Titel im 2-Spalten-Grid: zwei Zeilen einplanen.
- Grid-Umstellung ändert den gespeicherten Scroll-State (`LazyListState.Saver` → `LazyGridState.Saver`).
- System-Font wirkt sofort in der ganzen App.
- `AccentViolet`-Alias verhindert Kompilierbrüche in den Feature-Dateien.
- Kein Gradle-Lauf; Verifikation nur per Diff und gezieltem Lesen.
