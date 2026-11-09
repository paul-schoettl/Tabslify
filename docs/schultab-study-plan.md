# Schultab: 5-Tage-Lernplan aus Fotos (Feature-Plan)

Stand: 2026-11-07 · Status: **PLAN ONLY** (kein Code, kein Build, keine Migration ausgeführt)

Ziel: Der Nutzer fotografiert Klausur-/Schulaufgabenstoff (mehrere Bilder, Handschrift,
Skizzen, Text). Vision/OCR extrahiert Aufgaben, Vokabeln, Sätze und Textabschnitte inklusive
belegbarer Quellenangaben ("Text übersetzen aus Bild 2, Zeile 1-4"). Eine KI erzeugt daraus
Materialien und einen 5-Tage-Lernplan. Wiederholungen laufen nach einem Spaced-Repetition-
Schema; Bewertungen passen den Plan an (schwache Items häufiger). Der Plan lebt im Schultab.

---

## 1. Zielbild und Abgrenzung

**MVP muss können**

1. Mehrere Bilder (Kamera/Galerie) zu einem Stoffpaket hochladen.
2. Pro Bild strukturiert extrahieren: Vokabeln, Aufgaben, Textabschnitte, Sätze/Fragen.
3. Jedes extrahierte Item trägt eine menschlich lesbare Quellenangabe und eine strukturierte
   Referenz (Bildnummer, Zeilenbereich, Spalte/Abschnitt).
4. KI erzeugt Karteikarten, Quizze, Übersetzungs- und Verständnisübungen.
5. 5-Tage-Plan mit Tag 1-5, Fortschritt und "heute fällig".
6. Antworten werden bewertet; schwache Items erscheinen häufiger, starke später.
7. Plan ist offline lesbar; Antworten werden offline erfasst und später synchronisiert.
8. Tägliche Erinnerung öffnet direkt die heutige Session.

**Nicht im MVP**

- Mehrere Fächer parallel in einem Plan (Datenmodell erlaubt es, UI zunächst nicht).
- Freie Chat-Tutor-Session.
- Teilen von Plänen zwischen Nutzern.
- Automatische Notenprognose.
- FCM-Push (lokal reicht zunächst).

---

## 2. Ist-Zustand und Anknüpfungspunkte

Der "Schultab" ist heute `MenuItem.Vocabs` und wird als `VocabTab` gerendert.

| Vorhanden | Datei | Wiederverwendung |
|---|---|---|
| Tab-Registry | `app/src/main/java/com/tabslify/core/ui/PrivateTabslifyApp.kt` (`MenuItem.Vocabs`, `when (selectedMenuItem)`) | Kein neuer Tab nötig; Unterseite im Schultab |
| Schultab-Screens | `app/src/main/java/com/tabslify/tabs/school/VocabTab.kt` (`VokabelTabScreen`, `SchoolDashboard`, `UploadScreen`, `ReviewScreen`, `LearnScreen`) | Enum erweitern, Dashboard-Kachel, Muster für Upload/Review |
| Header/zuletzt genutzt | `SchoolHeader.kt` | unverändert nutzen |
| Materialien + Vision + Summary | `MaterialScreen.kt` (`school`-Bucket, `sendAiRequest`, `AiTarget.MaterialOcr`) | Muster für Bild-Upload, OCR-Prompt, Markdown-Renderer, Cache |
| Vokabel-OCR | `quiethoursnotificationhelper/WIFI_DIRECT.kt` (`callNvidiaVisionApi`, `trySendImageToLaptop`) | Nur als Fallback/Referenz; neuer Flow läuft über Edge Function |
| Zentrale AI-Prompts | `quiethoursnotificationhelper/AI.kt` (`AiTarget`, `aiSystemPrompt`) | Neue Targets `StudyExtract`, `StudyPlan`, `StudyGrade` |
| Supabase-Client | `core/objects/Config.kt` (`Config.client`, `hasAuthSession`) | unverändert |
| Edge-Function-Muster | `supabase/functions/get_suggestion/index.ts`, `supabase/migrations/*.sql` | CORS, JSON-Antworten, Service-Role-Zugriff |
| Hintergrundarbeit | `quiethoursnotificationhelper/Workers.kt`, `core/functions/ExactAlarms.kt` | WorkManager-Muster, optionaler Exact-Alarm |
| Room-Muster | `tabs/focusguard/data/FocusGuardDatabase.kt`, `tabs/tiktok/TikTokCacheDatabase.kt` | Cache-DB, DAO-Struktur |
| Notification-Muster | `Notifications.kt`, `QuietHoursNotificationService` | Kanal, Content-Intent, Permission |

**Repo-Regeln, die den Plan prägen**

- Kein Gradle (Build/Sync/Lint/Test) ohne ausdrückliche Anweisung. Verifikation über IDE/LSP,
  reine Kotlin-Logik-Tests nur, wenn ein Testlauf ausdrücklich erlaubt wird.
- Neue/geänderte Dateien ohne Kommentare; deutsche UI-Strings in `values/` und `values-en/`.
- Gefüllte Akzentflächen nutzen `AccentFill`/`OnAccentFill` (nicht `TextTertiary` auf `primary`).
- `functions.invoke` niemals mit `JSONObject`/`Map` aufrufen; `buildJsonObject` oder
  `@Serializable`-Body verwenden.

---

## 3. Architekturüberblick

```
Compose
  StudyUploadScreen / StudyPlanScreen / StudySessionScreen
        │
        ▼
StudyPlanViewModel / StudySessionViewModel
        │
        ▼
StudyRepository ────────────────► Supabase PostgREST + Storage (User-JWT)
        │                              │
        ├── Room-Cache (Plan, Items,   │
        │   Aufgaben, Review-Outbox)  │
        │                              ▼
        └── WorkManager          Edge Functions
            (Sync + Reminder)    study-extract   → Vision-Provider
                                 study-plan      → LLM + Scheduler
                                 study-grade     → LLM-Bewertung
                                        │
                                        ▼
                                 Postgres (service_role)
```

**Leitentscheidung (empfohlen):** Vision und LLM laufen serverseitig in Edge Functions.
Gründe: keine Provider-Keys auf dem Gerät, ein Ort für Prompts/Schema/Quota, reproduzierbare
Planerzeugung, Schutz vor Mehrfachkosten durch Idempotenz. Die App bleibt offline-fähig, weil
der fertige Plan lokal gespiegelt wird.

**Fallback-Option:** Client-direkte Provider-Aufrufe wie heute (`sendAiRequest`). Schnellerer
MVP, aber pro Gerät konfigurierte Keys, keine zentrale Quota und kein serverseitiges
Idempotenz-/Kostenlogging. Siehe offene Entscheidung D1.

---

## 4. Datenmodell Supabase

Neue Migration: `supabase/migrations/2026...._study_plan.sql`.
Alle Tabellen: `user_id uuid not null default auth.uid()`, `created_at timestamptz not null
default now()`, `updated_at timestamptz not null default now()`.

### 4.1 Enums

```sql
create type study_project_status as enum ('draft','extracting','ready','active','completed','archived');
create type study_source_status  as enum ('uploaded','extracting','extracted','failed');
create type study_item_kind      as enum ('vocab','term','task','text_section','sentence','question');
create type study_material_type  as enum ('flashcard','quiz_mc','quiz_short','translation','comprehension','mock_exam');
create type study_assignment_kind as enum ('learn','review','translate','comprehend','quiz','mock');
create type study_job_kind       as enum ('extract','plan','adapt','grade');
create type study_job_status     as enum ('queued','running','done','failed');
```

### 4.2 Kerntabellen

**`study_projects`** — ein Stoffpaket/Klausurvorbereitung.

| Spalte | Typ | Zweck |
|---|---|---|
| `id` | uuid PK | Projekt |
| `user_id` | uuid | RLS |
| `title` | text | z. B. "Klausur Latein Lektion 12" |
| `subject` | text | Fach |
| `exam_date` | date null | optionales Klausurdatum |
| `plan_start` | date | Tag 1 |
| `plan_days` | int default 5 | 5-Tage-Plan |
| `minutes_per_day` | int default 25 | Arbeitsbudget |
| `source_language` | text null | z. B. `la` |
| `target_language` | text default 'de' | Antwortsprache |
| `status` | study_project_status | Lebenszyklus |
| `algorithm_version` | text | z. B. `srs-1` |
| `last_opened_at` | timestamptz | Dashboard-Sortierung |

**`study_sources`** — ein Bild/PDF-Seite pro Quelle.

| Spalte | Typ | Zweck |
|---|---|---|
| `id` | uuid PK | Quelle |
| `project_id` | uuid FK → projects on delete cascade | |
| `page_index` | int | Reihenfolge/Bildnummer |
| `storage_path` | text | `study-media/{user_id}/{project_id}/{page}.jpg` |
| `mime_type` | text | |
| `sha256` | text | Dedupe/Idempotenz |
| `width`,`height` | int | |
| `status` | study_source_status | |
| `ocr_text` | text null | vollständige Extraktion |
| `ocr_meta` | jsonb | Modell, Prompt-Version, Tokens |
| `error` | text null | letzter Fehler |

Unique: `(project_id, sha256)` verhindert doppelte Bilder.

**`study_items`** — die extrahierten Einheiten, unabhängig von der Darstellung.

| Spalte | Typ | Zweck |
|---|---|---|
| `id` | uuid PK | Item |
| `project_id` | uuid FK | |
| `source_id` | uuid FK → sources | |
| `kind` | study_item_kind | Vokabel, Aufgabe, Textabschnitt, Satz, Frage, Begriff |
| `front` | text | Frage/Stimulus |
| `back` | text null | Antwort/Bedeutung (bei Aufgaben ggf. Lösung) |
| `context` | text null | umgebender Text |
| `source_page` | int | Bildnummer, 1-basiert |
| `source_line_from`,`source_line_to` | int null | Zeilenbereich |
| `source_column` | text null | z. B. "links", "Spalte 2" |
| `source_excerpt` | text null | wörtliches Zitat zur Prüfung |
| `reference_label` | text | menschenlesbar, z. B. `Bild 2, Zeile 1-4` |
| `language` | text null | `la`, `de`, ... |
| `difficulty` | smallint 1..5 | Startschätzung der KI |
| `confidence` | real 0..1 | Extraktionssicherheit |
| `tags` | text[] | Grammatik/Thema |
| `payload` | jsonb | z. B. Multiple-Choice-Optionen, Lösungsschlüssel |
| `edited_by_user` | boolean default false | schützt vor Überschreiben |

**`study_materials`** — generierte Übungen, unabhängig vom Plan.

| Spalte | Typ | Zweck |
|---|---|---|
| `id` | uuid PK | Material |
| `project_id` | uuid FK | |
| `type` | study_material_type | Karteikarten, Quiz, Übersetzung, Verständnis, Probeklausur |
| `title` | text | |
| `payload` | jsonb | konkrete Fragen/Optionen/Lösungen/Rubrik |
| `prompt_version` | text | Reproduzierbarkeit |
| `model` | text | Provider/Modell |
| `created_at` | timestamptz | |

**`study_material_items`** — Verknüpfung Material ↔ Items.

`material_id uuid`, `item_id uuid`, `role text` (`question`/`answer`/`source`), PK
`(material_id, item_id, role)`.

**`study_plans`** — der erzeugte Plan.

| Spalte | Typ | Zweck |
|---|---|---|
| `id` | uuid PK | Plan |
| `project_id` | uuid FK unique | ein aktiver Plan pro Projekt |
| `start_date` | date | Tag 1 |
| `end_date` | date | Tag 5 |
| `minutes_per_day` | int | Budget |
| `algorithm_version` | text | |
| `status` | text | `active`/`completed`/`superseded` |
| `generated_at` | timestamptz | |
| `generation_meta` | jsonb | Modell, Tokens, Dauer |

**`study_plan_days`** — Tag 1-5.

`id uuid`, `plan_id`, `day_index int 1..5`, `date date`, `focus text`, `target_minutes int`,
`status text` (`locked`/`open`/`done`), Unique `(plan_id, day_index)`.

**`study_assignments`** — konkrete fällige Einheit pro Tag.

| Spalte | Typ | Zweck |
|---|---|---|
| `id` | uuid PK | Aufgabe |
| `plan_id`,`day_id`,`item_id` | uuid | |
| `material_id` | uuid null | zugehörige Übung |
| `kind` | study_assignment_kind | lernen/wiederholen/übersetzen/verstehen/quiz/probe |
| `due_date` | date | Fälligkeit |
| `priority` | smallint | 1 = schwach/hoch |
| `planned_minutes` | int | Budgetsteuerung |
| `sort_order` | int | stabile UI-Reihenfolge |
| `status` | text | `pending`/`done`/`skipped` |
| `completed_at` | timestamptz null | |

Unique: `(plan_id, item_id, due_date, kind)` — verhindert doppelte Karten am selben Tag.

**`study_item_stats`** — kanonischer SRS-Zustand pro Item.

| Spalte | Typ | Zweck |
|---|---|---|
| `item_id` | uuid PK FK | |
| `repetitions` | int default 0 | erfolgreiche Wiederholungen |
| `interval_days` | int default 0 | aktuelles Intervall |
| `ease` | real default 2.5 | SM-2-Reserve, im MVP nur beobachtet |
| `lapses` | int default 0 | Fehlschläge |
| `streak` | int default 0 | Serie |
| `last_score` | real null | letzte Bewertung 0..1 |
| `last_reviewed_at` | timestamptz null | |
| `next_due_date` | date null | |
| `accuracy_7d` | real null | rollierend, optional per View |

**`study_reviews`** — jeder Antwortversuch, append-only.

| Spalte | Typ | Zweck |
|---|---|---|
| `id` | uuid PK | |
| `client_uuid` | uuid unique | Offline-Idempotenz |
| `assignment_id` | uuid null | |
| `item_id` | uuid | |
| `answer` | text | Nutzerantwort |
| `score` | real 0..1 | |
| `correct` | boolean | |
| `mode` | text | `typed`/`self`/`mc` |
| `latency_ms` | int null | |
| `feedback` | text null | KI-Feedback |
| `graded_by` | text | `local`/`llm`/`self` |
| `created_at` | timestamptz | Client-Zeit |
| `synced_at` | timestamptz | Server-Zeit |

**`study_jobs`** — asynchrone Extraktion/Planung.

`id`, `project_id`, `kind`, `status`, `attempts`, `input_hash`, `error`, `started_at`,
`finished_at`, `result_ref` (jsonb). Unique-Index auf `(project_id, kind, input_hash)` für
Idempotenz.

**`study_ai_usage`** — Kosten-/Quota-Ledger.

`user_id`, `usage_date date`, `operation text`, `count int`, `tokens_in bigint`,
`tokens_out bigint`, `cost_micros bigint`, PK `(user_id, usage_date, operation)`.

### 4.3 Storage

Neuer privater Bucket **`study-media`** (nicht der bestehende globale `school`-Bucket):

- Pfad: `{user_id}/{project_id}/{page_index}.jpg`
- `public = false`, `file_size_limit` z. B. 15 MiB, MIME nur `image/jpeg`, `image/png`,
  `image/webp`.
- Zugriff ausschließlich über signierte URLs; Coil cached die Bilder optional lokal.
- Originalbilder bleiben erhalten, damit Referenzen ("Bild 2") nachvollziehbar sind.

### 4.4 Retention/Cleanup

- `study-media` und `study_plans` nach 90 Tagen inaktiver Projekte löschen (pg_cron +
  Storage-API oder Edge Function).
- `study_jobs` nach 30 Tagen, `study_ai_usage` nach 180 Tagen aggregieren/löschen.
- Kein Hard-Delete von `study_reviews` während ein Plan aktiv ist.

---

## 5. Edge Functions / Backend-Flows

Gemeinsames Modul `supabase/functions/_shared/study.ts`: CORS, JWT-Prüfung, Fehler-JSON,
Quota, Provider-Client, JSON-Schema-Validierung, Logging.

### 5.1 `study-extract`

**Input (JSON, `@Serializable`/`JsonObject` vom Client):**

```json
{
  "project_id": "uuid",
  "source_ids": ["uuid"],
  "language_hint": "la",
  "prompt_version": "extract-1"
}
```

**Ablauf**

1. JWT prüfen, `user_id` aus dem Token lesen (nie aus dem Body).
2. Projekt und Quellen per Service Role laden; prüfen, dass `user_id` passt.
3. Pro Quelle: signierte URL oder Bytes aus Storage laden; Hash prüfen.
4. Bild auf max. 1600 px skalieren, JPEG 85 (Client macht das schon; Server validiert).
5. Vision-Aufruf mit **strukturiertem JSON-Schema** (kein freier Text):
   Item-Kind, `front`, `back`, `context`, `source_page`, `source_line_from/to`,
   `source_column`, `source_excerpt`, `reference_label`, `difficulty`, `confidence`, `tags`.
6. Antwort validieren; ungültige Items verwerfen und pro Quelle `error` setzen.
7. In Transaktion schreiben: `study_sources.ocr_text/status`, `study_items`,
   `study_jobs.status`.
8. Tokens/Verbrauch in `study_ai_usage` schreiben.
9. Antwort: `{ job_id, status, sources: [...], item_counts: {...} }`.

**Regeln**

- Maximal 10 Quellen pro Request, maximal 25 MB pro Bild, Timeout 60 s pro Vision-Call.
- Idempotenz über `(project_id, kind='extract', input_hash)`; gleicher Hash → vorhandenes
  Ergebnis zurückgeben.
- Teilfehler sind erlaubt: eine Quelle `failed`, andere `extracted`; Job wird `done` mit
  `partial=true`.
- Prompt-Version im Ergebnis speichern; bei Änderung bewusst neu extrahieren.

### 5.2 `study-plan`

**Input:**

```json
{
  "project_id": "uuid",
  "start_date": "2026-11-09",
  "days": 5,
  "minutes_per_day": 25,
  "focus": ["vocab", "text", "comprehension"],
  "regenerate": false
}
```

**Ablauf**

1. JWT + Projekt-Owner prüfen.
2. Items laden, nach `kind` gruppieren, Duplikate/leere Items filtern.
3. **Materialien** in Batches per LLM erzeugen:
   - Karteikarten aus `vocab`/`term`
   - Multiple-Choice + Kurzantwort aus `vocab`/`question`/`task`
   - Übersetzungsübung aus `text_section`/`sentence` (mit Quellenreferenz)
   - Verständnisfragen aus `text_section` (Antwort nur aus dem Text)
   - Eine Probeklausur für Tag 5
4. Jedes Material gegen JSON-Schema validieren; fehlende Felder reparieren oder Batch
   verwerfen.
5. **Scheduler** (deterministisch, TypeScript) erzeugt `study_plans`, `study_plan_days`,
   `study_assignments`, `study_item_stats` (siehe Abschnitt 6).
6. Transaktion: alter aktiver Plan wird `superseded`, neuer Plan `active`.
7. `study_ai_usage` fortschreiben; Antwort mit Plan-Snapshot.

**Regeln**

- LLM erzeugt **Inhalte**, nie den Zeitplan. Der Zeitplan ist Code und damit testbar.
- Maximal 300 Items pro Projekt; darüber in Batches aufteilen oder ablehnen.
- `regenerate=true` nur mit Quota und Bestätigung; bestehende Reviews bleiben erhalten.
- `algorithm_version` mitschreiben; die App darf bei unbekannter Version nicht lokal
  umplanen, sondern nur anzeigen.

### 5.3 `study-grade`

**Input:** `assignment_id`, `item_id`, `answer`, `mode`.

**Ablauf**

1. JWT + Owner prüfen.
2. Deterministische Vorprüfung:
   - Vokabel/ Begriff: Normalisierung + Diakritika-Faltung (bestehende Logik
     `vergleicheAntwort` wiederverwenden), `score` 0/1.
   - Multiple Choice: exakter Optionsvergleich.
3. Freitext (Übersetzung/Verständnis): LLM mit Rubrik und JSON-Ausgabe
   `{score: 0..1, correct: bool, feedback: string, missing: string[]}`.
4. Bei Provider-Fehler/Timeout: `score=null`, `graded_by='self'`; App fragt den Nutzer nach
   Selbsteinschätzung (richtig/falsch) und speichert das.
5. Review + Stats aktualisieren; bei Online-Verbindung direkt, offline nur Outbox.
6. Antwort: `{review_id, score, correct, feedback, next_due_date}`.

**Regeln**

- Kein LLM-Grading für reine Vokabeln (Kosten und Latenz).
- Maximal 120 Bewertungen pro Nutzer/Tag; danach `429` mit `retry_after`.
- Feedback kurz (max. 2 Sätze), keine neuen Fakten erfinden.

### 5.4 Client-Aufruf

- `Config.client.functions.invoke("study-extract", body = buildJsonObject { ... })`.
- Kein `JSONObject`/`Map` als Body (Serializer-Falle).
- Fehler als `{error, details}` behandeln und als roten Callout zeigen (bestehendes Muster).

---

## 6. Spaced Repetition und 5-Tage-Plan

### 6.1 Item-Typ → Material-Typ

| Item-Kind | Primäres Material | Wiederholungsform |
|---|---|---|
| `vocab` | Karteikarte, Quiz | typed/self |
| `term` | Karteikarte, Kurzquiz | typed |
| `task` | Quiz, Kurzantwort | typed/self |
| `text_section` | Übersetzung, Verständnisfragen | typed/self |
| `sentence` | Übersetzung | typed |
| `question` | Verständnisfrage | typed/self |

### 6.2 Intervalle (MVP: Leitner+)

Bestehende Vokabel-Logik nutzt `LEITNER_INTERVALLE = [0,1,2,4,8,16,32]`. Für den 5-Tage-Plan
wird dieselbe Skala verwendet, damit Schultab und Vokabeltraining konsistent bleiben:

| Ereignis | Neues Intervall | repetitions |
|---|---|---|
| neu | 0 (Tag 1) | 0 |
| `score >= 0.85` | 1 → 2 → 4 → 7 → 14 (je nach Reps) | +1 |
| `0.7 <= score < 0.85` | Intervall halten, mindestens 1 | +1 |
| `score < 0.7` oder Lapse | 1 | 0, `lapses++` |

Zusätzlich:

- `next_due_date = today + interval_days`, auf den Plan geklemmt:
  - vor Tag 5: normal
  - nach Tag 5 und `exam_date` vorhanden: auf `exam_date - 1` klemmen
  - sonst: nach Planende normales Leitner weiterführen
- Items mit `lapses >= 2` oder `last_score < 0.7` bekommen `priority = 1` und einen
  zusätzlichen Slot am nächsten Tag.
- Items mit `streak >= 3` und `score >= 0.9` werden aus optionalen Slots entfernt, bleiben
  aber als fällige Wiederholung erhalten.

**Warum nicht FSRS/SM-2 im MVP:** mehr Parameter, mehr Testaufwand, und die bestehende
Vokabel-Logik wäre inkonsistent. `ease` wird im Datenmodell mitgeführt, damit ein späterer
Wechsel ohne Migration möglich ist.

### 6.3 Tagesvorlage

| Tag | Fokus | Neue Items | Wiederholungen | Material |
|---|---|---|---|---|
| 1 | Kennenlernen | Vokabeln/Terms (max. 25), Textüberblick | keine | Karteikarten, Text lesen |
| 2 | Übersetzen | restliche Vokabeln, Textsätze | Tag-1-Items fällig | Übersetzung Text 1, Karteikarten |
| 3 | Verstehen | keine neuen (oder wenige) | Tag-1/2-Items | Verständnisfragen, Quiz |
| 4 | Mischen | keine neuen | fällige + schwache Items | Gemischtes Quiz, Übersetzung Text 2 |
| 5 | Probeklausur | keine neuen | nur Lapses + schwache Items | Mock Exam + kurze Nachbesprechung |

Verteilung:

- Neue Items werden auf Tag 1-3 verteilt, abhängig von `minutes_per_day` und Item-Anzahl.
- `planned_minutes` pro Assignment schätzen (Vokabel 0,5 min, Übersetzung 3 min, Quiz 1 min,
  Verständnisfrage 2 min); Summe pro Tag darf `minutes_per_day` nicht überschreiten.
- Überlauf wandert in den nächsten Tag; Tag 5 wird nie überladen.

### 6.4 Adaption

Nach jeder Session (online oder beim Sync):

1. Reviews und Stats aktualisieren.
2. Für jedes Item mit `score < 0.7` oder Lapse: zusätzlichen Slot am nächsten Tag einplanen.
3. Für jedes Item mit `streak >= 3`: optionale Slots streichen.
4. Restliche Tage neu balancieren, harte Fälligkeiten nicht verschieben.
5. Tag 5 (Mock) bleibt fix; Inhalte werden gegen die schwächsten Items ausgetauscht.
6. `study_plans.generation_meta.adaptations` inkrementieren (Nachvollziehbarkeit).

**Offline:** Die App darf die lokale Sicht optimistisch anpassen (nächste Fälligkeit, ein
Extra-Slot), aber die Serverversion gewinnt beim nächsten Sync. Bei
`algorithm_version`-Mismatch nur anzeigen, nicht lokal umplanen.

---

## 7. Compose-Screens, State und Navigation

### 7.1 Screens

`VokabelTabScreen` erweitern:

```
DASHBOARD, HOME, UPLOAD, REVIEW, LEARN, MATERIALIEN, NOTEN,
STUDY_PLAN, STUDY_UPLOAD, STUDY_REVIEW, STUDY_DAY, STUDY_SESSION,
STUDY_RESULT, STUDY_MATERIAL
```

| Screen | Datei (neu) | Inhalt |
|---|---|---|
| Dashboard-Kachel | `VocabTab.kt` (bestehend) | Kachel "Lernplan" mit "Heute: N fällig" und Fortschritt Tag x/5 |
| `StudyPlanScreen` | `tabs/school/study/StudyPlanScreen.kt` | Tagesliste Tag 1-5, Fortschrittsbalken, "Heute fällig"-CTA, Quellenchips, schwache Items |
| `StudyUploadScreen` | `.../StudyUploadScreen.kt` | Mehrbild-Auswahl (Kamera/Galerie), Thumbnails, Reihenfolge ändern, löschen, Fach/Titel/Klausurdatum/Minuten, "Analysieren" |
| `StudyReviewScreen` | `.../StudyReviewScreen.kt` | Extrahierte Items gruppiert (Vokabeln/Texte/Aufgaben), Quelle als Chip ("Bild 2, Zeile 1-4"), editieren/löschen/manuell ergänzen, "Plan erzeugen" |
| `StudyDayScreen` | `.../StudyDayScreen.kt` | Aufgaben des Tages nach Typ gruppiert, Dauer, Start-Button, Status |
| `StudySessionScreen` | `.../StudySessionScreen.kt` | Eine Frage pro Bildschirm, Fortschritt, Antwortfeld/Optionen, "Weiß nicht", Bewertung, Feedback |
| `StudyResultScreen` | `.../StudyResultScreen.kt` | Score, richtige/falsche Items, "Diese Items kommen morgen wieder", nächster Tag |
| `StudyMaterialScreen` | `.../StudyMaterialScreen.kt` | Markdown-Material (bestehender Renderer + `normalizeCallouts`), Quellenangaben |

Wiederverwendung:

- `SchoolHeader` für Titel/Back/zuletzt genutzt.
- `Composables.kt` für Buttons, Dialoge, Karten.
- `AlertDialogTabslify`-Vertrag beachten (State im Aufrufer zurücksetzen).
- Kontrast: gefüllte Flächen mit `AccentFill` + `OnAccentFill`.

### 7.2 State

**`StudyPlanViewModel`** (Activity-scoped, `viewModel()`):

```kotlin
sealed interface StudyUiState {
    data object Loading : StudyUiState
    data object Empty : StudyUiState
    data class Ready(
        val project: StudyProject,
        val plan: StudyPlan,
        val days: List<StudyDay>,
        val today: StudyDay?,
        val progress: StudyProgress,
        val offline: Boolean,
        val syncError: String? = null
    ) : StudyUiState
    data class Error(val message: String) : StudyUiState
}
```

- `load(force: Boolean)`: Room zuerst, dann Supabase-Refresh.
- `createProject(...)`, `uploadSources(...)`, `startExtraction(...)`,
  `updateItems(...)`, `generatePlan(...)`, `openDay(...)`.
- `today` wird aus lokalem Datum + `study_plan_days.date` berechnet, nie aus Server-Zeitzone.

**`StudySessionViewModel`**:

```kotlin
sealed interface SessionUiState {
    data class Question(val index: Int, val total: Int, val task: StudyTask) : SessionUiState
    data class Grading(val answer: String) : SessionUiState
    data class Feedback(val correct: Boolean, val score: Float, val text: String?) : SessionUiState
    data class Finished(val score: Float, val weak: List<StudyItem>) : SessionUiState
}
```

- Session-Reihenfolge kommt aus `study_assignments.sort_order`.
- Antwort lokal sofort speichern (Outbox), Bewertung online nachziehen.
- Kein Netzwerk im `init`; Start erst über `start(sessionId)`.

**`StudyUploadViewModel`**: Bildliste, Kompression, Upload-Fortschritt, Retry pro Bild.

### 7.3 Navigation

- Kein NavHost; `when (screen)` im `VocabTab` erweitern.
- `BackHandler` pro Unterseite auf den logischen Parent (Upload → Plan, Session → Day).
- `PrivateTabslifyApp`-`startTarget` um `"study"` erweitern, damit eine Notification direkt
  `MenuItem.Vocabs` + `STUDY_DAY` öffnet. `study_day` als Extra/SharedPreferences-Flag
  übergeben.
- Doppelte Komposition beachten: `LaunchedEffect(Unit)` nur mit
  `viewModel.loadIfNeeded()`, keine Netzwerkaufrufe direkt im Composable.

---

## 8. Offline-Cache

**Room-DB** `study_cache.db` unter `databases/` (Nutzdaten, nicht `cacheDir`; `cacheDir` ist
für die TikTok-Historie richtig, aber ein Lernplan darf nicht vom System gelöscht werden).

Entities:

| Entity | Inhalt |
|---|---|
| `StudyProjectEntity` | Projekt-Metadaten |
| `StudyDayEntity` | Tag 1-5, Datum, Fokus, Status |
| `StudyTaskEntity` | Assignment + Item-Kurzfassung + Material-ID |
| `StudyItemEntity` | `front`, `back`, `context`, `reference_label`, `kind` |
| `StudyMaterialEntity` | `payload` als JSON-String, Typ, Titel |
| `StudyStatsEntity` | SRS-Zustand pro Item |
| `StudyReviewOutboxEntity` | `client_uuid`, Payload, `attempts`, `created_at` |

DAO:

- `observePlan(projectId)`, `observeToday(date)`, `tasksForDay(dayId)`,
  `upsertAll(...)` in Transaktion.
- Outbox: `pending(limit)`, `markSynced(clientUuid)`, `incrementAttempts`.
- Pruning: abgeschlossene Pläne nach 90 Tagen lokal löschen.

**Sync**

- `StudySyncWorker` (WorkManager, `NetworkType.CONNECTED`, Backoff exponentiell).
- Pull: `updated_at > last_sync_at` für Projekt/Plan/Tage/Tasks/Stats.
- Push: Outbox in Reihenfolge; `client_uuid` macht Retries idempotent.
- Konflikt: Reviews sind append-only (Client gewinnt); Plan/Tasks sind server-authoritativ
  (Server gewinnt). Lokale optimistische Änderungen werden nach Sync verworfen/übernommen.
- Worker ohne JWT vermeiden: vor jedem Lauf `Config.hasAuthSession()` prüfen und
  `awaitInitialization()` abwarten (bestehendes Muster gegen `42501`).

**Bilder**: nicht in Room duplizieren. Coil cached signierte URLs; Originale bleiben in
`study-media`. Offline-Session funktioniert ohne Bilder, weil Aufgaben Text enthalten.

---

## 9. Notifications und Scheduling

**Kanal** `study_plan` (Wichtigkeit `DEFAULT`, nicht `SECRET`; Lernpläne sind keine
Geheimnisse). Eigene ID pro Plan/Tag, Group-Key `study_plan`.

**Tägliche Erinnerung**

- `StudyReminderWorker` (WorkManager, periodisch 1x/Tag, `initialDelay` bis zur gewählten
  Uhrzeit). Berechnet aus Room: fällige Tasks für heute.
- Notification: Titel "Lernplan: Tag 3", Text "12 Wiederholungen + 1 Text warten", Content
  Intent öffnet `MenuItem.Vocabs` + `STUDY_DAY`.
- Kein `setOngoing(true)` (sonst greift der Delete-Intent nie); kein FGS.
- `POST_NOTIFICATIONS` ist bereits im Manifest; `canNotify`/Permission-Screen beachten.

**Optional exakt**

- Nur wenn der Nutzer eine exakte Uhrzeit verlangt: `ExactAlarms.setExactOrWindow(...)` mit
  Fallback; `BootReceiver` muss `rescheduleAlarms()` erweitern.
- MVP: WorkManager mit Fenster reicht; keine neue Alarm-Permission-Kommunikation nötig.

**Sync**

- `StudySyncWorker` zusätzlich bei App-Start / nach Session-Ende enqueuen (unique work,
  `KEEP` oder `REPLACE` bewusst wählen).

**FCM (später)**

- Nur wenn Pläne im Hintergrund erzeugt werden und der Nutzer benachrichtigt werden soll.
- Data-only-Nachricht, `onMessageReceived`, PendingIntent-Codes = Zeitstempel; bestehendes
  Muster aus `ai-notify-push.md`.

---

## 10. Auth und RLS

**Grundregeln**

- Client nutzt nur den Publishable Key + User-JWT. `service_role` niemals in der App.
- Edge Functions prüfen das JWT selbst und lesen `user_id` ausschließlich daraus.
- Alle Nutzertabellen: `user_id` mit Default `auth.uid()`.
- Storage privat, Pfad beginnt mit `user_id`.

**Policy-Muster** (für jede Tabelle zu wiederholen):

```sql
alter table public.study_projects enable row level security;

create policy study_projects_select on public.study_projects
  for select to authenticated using (auth.uid() = user_id);

create policy study_projects_insert on public.study_projects
  for insert to authenticated with check (auth.uid() = user_id);

create policy study_projects_update on public.study_projects
  for update to authenticated using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create policy study_projects_delete on public.study_projects
  for delete to authenticated using (auth.uid() = user_id);
```

- Kindtabellen (`study_sources`, `study_items`, ...) prüfen zusätzlich die Zugehörigkeit über
  das Projekt, z. B. `exists (select 1 from study_projects p where p.id = project_id and
  p.user_id = auth.uid())`.
- `study_reviews`: insert/select nur eigene; kein update/delete.
- `study_jobs` und `study_ai_usage`: nur select für den Nutzer; Schreiben nur Service Role.
- `study_item_stats`/`study_assignments`: update nur eigene; Server darf alles.

**Storage-Policies**

```sql
create policy study_media_read on storage.objects
  for select to authenticated
  using (bucket_id = 'study-media'
         and (storage.foldername(name))[1] = auth.uid()::text);

create policy study_media_write on storage.objects
  for insert to authenticated
  with check (bucket_id = 'study-media'
              and (storage.foldername(name))[1] = auth.uid()::text);
```

(Delete analog; Update nur wenn nötig.)

**App-Gate**

- Kein zusätzliches `prvt()` für den Lernplan, wenn das Feature wie Vokabeln für den echten
  Nutzer sichtbar sein soll. KI-Kosten und Materialien können vorerst `prvt()`-gated bleiben,
  bis die Kosten beobachtet sind. Entscheidung D10.
- Auth-Gate der App unverändert: `Initializing`/`hadSession`-Logik nicht anfassen.

---

## 11. Kosten und Rate Limits

**Schätzung pro Projekt** (Preise ändern sich; vor Implementierung prüfen):

| Schritt | Tokens (grob) | Hinweis |
|---|---|---|
| Vision pro Bild (1600 px) | 1.000-2.000 input | 8 Bilder ≈ 8k-16k |
| Extraktion → JSON | 2.000-6.000 output | strukturiert, pro Projekt einmal |
| Materialien (5 Tage) | 10.000-25.000 output | in 3-5 Batches |
| Grading pro Freitext | 300-800 | nur Übersetzung/Verständnis |
| Plan-Adaption | 0 | deterministisch, kein LLM |

**Harte Grenzen**

- Max. 10 Bilder, 15 MB/Bild, 300 Items/Projekt.
- Max. 3 Projekte pro Nutzer/Woche (weich, konfigurierbar).
- Extraktion: 1 laufender Job pro Projekt; gleicher `input_hash` wird gecacht.
- Planerzeugung: 2 Versuche; danach manuelles Retry mit Bestätigung.
- Grading: 120/Tag/Nutzer; danach `429` und Selbstbewertung.
- Vision/LLM: `max_tokens`, Timeout 60 s, Retry nur bei 5xx/Timeout, max. 2 Versuche.
- Kein erneutes OCR, wenn `sha256` und `prompt_version` gleich sind.

**App-Budget**

- Bestehendes `reserveAiUsage` (30 Einheiten / 6 h) nicht ungeprüft für Study mitbenutzen,
  sonst blockiert ein Lernplan den KI-Chat.
- Empfehlung: separates `study_usage`-Prefs + serverseitiges `study_ai_usage`; die App zeigt
  Restkontingent im Upload-Screen.
- Serverseitig `429` mit `retry_after`; App zeigt Callout statt Crash.

**Monitoring**

- `study_ai_usage` pro Tag/Operation; wöchentliche Auswertung.
- Fehlerquote pro Provider in `study_jobs.error`; bei > 20 % auf Fallback-Provider wechseln.
- Kostenalarm bei Überschreitung eines Monatsbudgets (Edge Function + `pg_cron`).

---

## 12. UX-Flow (Stichpunkte)

1. Schultab → Dashboard-Kachel **"Lernplan"**.
2. Kein Plan: Empty State mit "Stoff fotografieren" und kurzer Erklärung (5 Tage, ca. 25 min/Tag).
3. **Fotos**: Kamera oder Galerie, mehrere Bilder, Thumbnails, Reihenfolge per Drag, löschen,
   Bild nachträglich hinzufügen.
4. **Setup**: Fach, Titel, optionale Klausur, Minuten/Tag, Fokus (Vokabeln/Text/Verstehen),
   Sprachen.
5. **Upload & Analyse**: pro Bild Status (hochgeladen → erkannt → Fehler), Retry; Hinweis
   "Handschrift kann ungenau sein, bitte prüfen".
6. **Prüfen**: Items gruppiert; Quelle als Chip "Bild 2, Zeile 1-4"; antippen zeigt den
   Originalausschnitt (Bild + Zoom); editieren, löschen, manuell ergänzen; Warnung bei
   niedriger `confidence`.
7. **Plan erzeugen**: Vorschau Tag 1-5 mit Dauer und Item-Anzahl; "Plan starten".
8. **Heute**: fällige Aufgaben, Fortschritt, "Session starten"; nicht fällige Tage sichtbar,
   aber gesperrt/ausgegraut.
9. **Session**: eine Aufgabe pro Screen; Vokabel tippen oder aufdecken; Übersetzung tippen;
   Quiz-Optionen; Verständnisfrage; jederzeit "Weiß nicht" (zählt als Lapse).
10. **Bewertung**: sofortiges Feedback, Score, Quelle; bei Offline/Selbsteinschätzung
    "Richtig/Falsch".
11. **Ergebnis**: Score, schwache Items, "kommt morgen wieder", nächster Tag.
12. **Tag 5**: Probeklausur, Ergebnis, Plan abgeschlossen; Option "Plan verlängern" oder
    "Archivieren".
13. **Erinnerung**: täglich zur gewählten Zeit; Tap öffnet die heutige Session.
14. **Fehler**: kein Netz → Plan aus Cache, Upload/Planung später; Provider-Fehler → Retry,
    keine Datenverluste; Quota → klare Meldung mit Restzeit.

---

## 13. Phasen- und Aufwandsplan (MVP zuerst)

Aufwand in Personentagen, solo, ohne Gradle-Läufe.

| Phase | Inhalt | Ergebnis | Tage |
|---|---|---|---|
| P0 | Entscheidungen D1-D4, Provider-Zugang/Secrets, Datenfluss bestätigen | festgelegte Architektur | 0,5 |
| P1 | Migrationen: Enums, Tabellen, Indizes, RLS, Bucket `study-media` | DB + Storage | 1,5 |
| P2 | `study-extract` + JSON-Schema + Provider-Client + Quota-Log | belastbare Extraktion | 2,0 |
| P3 | Upload/Review-UI (Mehrbild, Kompression, Items editieren) | Stoff erfassen | 2,0 |
| P4 | `study-plan` + Scheduler + Material-Batches + Validierung | Plan + Materialien | 2,5 |
| P5 | Plan-/Tages-/Session-UI + deterministisches Grading + Ergebnis | nutzbarer Lernloop | 3,0 |
| P6 | Room-Cache, Outbox, `StudySyncWorker` | offline-fähig | 2,0 |
| P7 | Reminder-Worker, Kanal, Deep Link | tägliche Erinnerung | 1,0 |
| P8 | Härtung: Retry, Quota, Telemetrie, leere/fehlerhafte Fälle, Texte | release-nah | 1,5 |

**Summe: ca. 15-16 Tage** (realistisch 14-18).

**MVP-Schnitt (ca. 8-9 Tage):**

- P0-P5, aber:
  - nur ein Fach/Projekt gleichzeitig
  - max. 6 Bilder
  - nur Vokabeln + Textabschnitte (Aufgaben/Fragen später)
  - Planung und Extraktion nur online
  - Cache als ein JSON-Snapshot in SharedPreferences (kein Room)
  - keine Notifications, kein FCM
  - Vokabeln deterministisch, Freitext per Selbsteinschätzung (kein LLM-Grading)
  - feste 5 Tage ab heute, kein Klausurdatum

**Phase 2 (nach MVP):** Aufgaben/Fragen, LLM-Grading, Room/Outbox, Notifications,
Probeklausur, mehrere Projekte, FCM.

---

## 14. Offene Entscheidungen

| # | Frage | Optionen | Empfehlung | Auswirkung |
|---|---|---|---|---|
| D1 | Wo laufen Vision/LLM? | A) Edge Functions mit Server-Keys, B) Client direkt mit Nutzer-Keys | **A** | A: sicher, zentral, mehr Backend; B: schneller MVP, Keys pro Gerät |
| D2 | Welcher Vision-Provider? | A) Gemini 3 Flash, B) NVIDIA Llama Vision, C) lokaler Laptop (`prvt()`) | **A mit B als Fallback** | Qualität vs. Kosten; C nur Dev |
| D3 | SRS-Algorithmus? | A) Leitner+ wie Vokabeln, B) SM-2, C) FSRS | **A für MVP**, `ease` vorbereiten | A konsistent und testbar, C besser langfristig |
| D4 | Planhorizont? | A) fix 5 Tage ab heute, B) Countdown bis Klausurdatum, C) beides | **A + optionales `exam_date`** | B braucht dynamische Tagesanzahl |
| D5 | Freitext-Bewertung? | A) LLM + Selbsteinschätzung-Fallback, B) nur Selbst, C) nur LLM | **A** | A kostet, ist aber didaktisch besser |
| D6 | Offline-Tiefe? | A) Room + Outbox, B) JSON-Snapshot, C) online-only | **A nach MVP, B im MVP** | A robust, mehr Aufwand |
| D7 | Storage? | A) neuer privater Bucket `study-media`, B) bestehender `school`-Bucket | **A** | A saubere RLS/Trennung, B kein Migrationsaufwand |
| D8 | Benachrichtigungen? | A) lokal (WorkManager), B) FCM, C) beides | **A im MVP** | B nur für Hintergrund-Erzeugung nötig |
| D9 | Item-Umfang MVP? | A) Vokabeln + Texte, B) zusätzlich Aufgaben/Fragen, C) alles inkl. Skizzen | **A**, B in Phase 2 | A hält den Plan klein |
| D10 | Sichtbarkeit? | A) wie Vokabeln für echten Nutzer, B) `prvt()`-gated | **A**, KI-Kosten beobachten | B schützt vor Kosten, versteckt Feature |
| D11 | Sprachen? | A) Deutsch only, B) Deutsch + Englisch | **A**, Strings aber in beiden Ressourcen | B mehr Übersetzungsaufwand |
| D12 | Material-Persistenz? | A) serverseitig, B) nur clientseitig | **A** | A geräteübergreifend und reproduzierbar |

---

## 15. Risiken und repo-spezifische Fallen

- **Handschrift/OCR-Fehler:** `confidence` pro Item, Quellenausschnitt, manuelles Editieren;
  niedrige Confidence sichtbar markieren. Kein stilles Übernehmen.
- **Quellenbezug:** `source_page` + `source_line_from/to` + `reference_label` immer zusammen
  speichern; nie nur "Bild 2" ohne Zeilenbereich.
- **Provider-Schema-Drift:** JSON-Schema serverseitig validieren; ungültige Batches
  verwerfen, nicht in die DB schreiben.
- **`functions.invoke`:** `JSONObject`/`Map` als Body schlägt vor dem Request fehl. Nur
  `buildJsonObject` oder `@Serializable` verwenden.
- **Storage-Fehler:** fehlendes Objekt kann als HTTP 400 mit Body `statusCode: 404`
  erscheinen. Für feste Pfade 400 und 404 als "nicht gefunden" behandeln.
- **Room-Speicherort:** Lernplandaten in `databases/`, nicht `cacheDir`; Cache-DB darf das
  System löschen, Nutzdaten nicht.
- **Notifications:** kein `setOngoing(true)` für swipe-bare Erinnerungen; Delete-Intent
  prüfen. FGS nur wenn zwingend nötig (hier nicht).
- **Doppelte Komposition:** Tabs werden zweimal komponiert; Netzwerk nur über
  `ViewModel.loadIfNeeded()`, nicht direkt im Composable.
- **Worker ohne JWT:** `42501` vermeiden; `hasAuthSession()` + `awaitInitialization()`.
- **Kontrast:** `AccentFill`/`OnAccentFill` auf gefüllten Flächen, keine
  `TextTertiary`-Texte auf `primary`.
- **Strings:** neue UI-Texte in `values/` und `values-en/`; keine Emoji-/AAPT2-Fallen.
- **Keine Kommentare** in neuen/geänderten Kotlin-Dateien.
- **Datenschutz:** Schulstoff ist persönlich; privater Bucket, keine öffentlichen URLs,
  Provider-Datenrichtlinie prüfen, optional lokale Verarbeitung (`prvt()`).

---

## 16. Abnahmekriterien und Testplan

**Funktional**

- 6-8 Bilder mit Handschrift ergeben Items mit korrekten `reference_label`s.
- Ein Textabschnitt erscheint als "Text übersetzen aus Bild 2, Zeile 1-4" und ist im
  Originalbild auffindbar.
- Plan enthält Tag 1-5, Fortschritt und "heute fällig".
- Eine Vokabel wird nach 1-2 Tagen erneut gefragt, danach später; ein Fehler erzeugt einen
  zusätzlichen Slot am Folgetag.
- Eine schwache Vokabel erscheint häufiger als eine starke.
- Offline: Session startbar, Antworten landen in der Outbox, Sync ohne Duplikate.
- Erinnerung öffnet die heutige Session.

**Technisch**

- RLS: zweiter Testnutzer sieht keine fremden Projekte/Bilder/Reviews.
- `study-extract` ist idempotent (gleicher Hash → kein zweiter Provider-Call).
- `study-plan` erzeugt bei gleichem Input + gleicher Version denselben Plan.
- Ungültiges LLM-JSON führt zu `failed`/`partial`, nicht zu kaputten Zeilen.
- Quota: 429 statt Doppelkosten; Retry mit Backoff.
- Kotlin-Logik (Intervall, Adaption, Normalisierung) ist als reine Funktion testbar.

**Nicht Teil der Abnahme**

- Gradle-Build, APK, Lint-Lauf (nur auf ausdrückliche Anweisung).

---

## 17. Nächster Schritt

1. D1-D4 entscheiden (Backend, Provider, SRS, Horizont).
2. Danach P1 (Migration + Bucket + RLS) und P2 (`study-extract`) umsetzen.
3. Erst wenn Extraktion stabil ist, UI und Planerzeugung bauen; sonst wird der teuerste
   Teil (Plan-UI) auf wackeligen Daten aufgebaut.
