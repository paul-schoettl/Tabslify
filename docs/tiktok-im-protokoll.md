# TikTok-IM-Protokoll (reverse engineered)

Grundlage ist die Web-SDK-Implementierung von TikTok (`webmssdk.js` + die App-Chunks im
Minified-Code). Diese Datei hält fest, was nachgemessen wurde, damit das TikTok-Tab nach
einem Gerät- oder Cache-Wechsel wieder funktioniert.

Code: `app/src/main/java/com/tabslify/tabs/tiktok/` (`TikTokProto.kt` = Wire-Format,
`TikTokDmClient.kt` = HTTP/State, `TikTokCookieRepository.kt` = Session vom PC).

## 1. Zugang

Die App nutzt **keinen App-Login**, sondern den Browser-Cookie des PCs. `Handy.py`
(`POST :8886/tiktok/cookie`) liefert `cookie` + `device_id`; die App cached beides in
SharedPreferences `tiktok` (Keys `cookie`, `device_id`, `cookie_from_pc`).

Gerätekennungen im Request (Feld 9 = `device_id`, Feld 11 = `"web"`), Parameter-Map in
Feld 15 (`aid=1988`, `app_name=tiktok_web`, `browser_*`, `verifyFp`, `Web-Sdk-Ms-Token`, …).

**Der private Medien-CDN verlangt zwingend das `sessionid`-Cookie** — ohne Cookie 403,
mit 200. Sticker-CDNs (`…dm-sign-no…`) brauchen es nicht.

## 2. Frame-Aufbau (gilt für alle Requests)

| Feld | Inhalt |
|---|---|
| 1 | `cmd` (Endpoint-Nummer) |
| 2 | **Sub-Command** — endpoint-abhängig, wird teils validiert (siehe 3) |
| 3 | `sdk_version` = `"1.8.2"` |
| 4 | `token` = `""` |
| 5 | `refer` = 3 |
| 6 | `inbox_type` = 0 |
| 7 | `build_number` = `"1132b10:master"` |
| 8 | Body (ggf. in ein Feld des `RequestBody` eingepackt) |
| 9 | `device_id` |
| 11 | `device_platform` = `"web"` |
| 15 | Browser-Parameter-Map (Eintrag: f1 Key, f2 Value) |
| 18 | `auth_type` = 1 |

Antwort: feld 1 cmd, **3 `status_code`** (0 = ok), 4 `error_desc`, **6 Body**; im Body
trägt das Feld mit der cmd-Nummer die Antwort.

> **Sub-Command-Falle:** Bei `cmd 301` muss Feld 2 = **10012** sein. Mit 10005 antwortet
> der Server `200001`, obwohl der Rest identisch ist. Bei `mark_read` wird das Feld
> dagegen nicht validiert. Also: pro Endpoint nachmessen, nie übertragen.

## 3. Endpunkte (aus der SDK-URL-Map)

| cmd | Pfad | Sub-Cmd | Body-Feld | Status |
|---|---|---|---|---|
| 203 | `v2/message/get_by_user_init` | 10001 | direkt (Feld 8) | fertig, benutzt |
| — | Reaktionen | – | Nachrichten-`f15` | fertig, kein WS nötig |
| 204 | `v1/message/get_by_user_combo` | 10011 | direkt | nicht benutzt |
| 301 | `v1/message/get_by_conversation` | **10012** | 301 | fertig, benutzt |
| 100 | `v1/message/send` | Zeitstempel | 100 | fertig, benutzt |
| 2002 | `v3/conversation/mark_read` | 10000 | **604** | fertig, benutzt |
| 3 | `v3/conversation/get_read_index` | – | – | offen |
| 2001 | `v3/conversation/get_min_index` | – | – | offen |
| 2004 | `v1/media/get_urls` | – | 2004 | für Bild/Sticker unnötig (Chatseite liefert frische URLs); **Audio: `200001`, siehe 21.2** |
| 701 / 702 | `v1/message/delete` / `recall` | – | – | fertig, live verifiziert |
| 705 | `v1/message/set_property` | – | – | fertig, live verifiziert |
| 206 | `…/get_by_conversation_search` | – | – | offen (Suche) |
| 2003 | `tiktok/v1/im/upload_config` | – | – | offen (Bild senden) |
| 300 / 2006 | `v1/conversation/get_list` / `list` | 10000 | – | 2006/10000 antwortet mit `status_code=0`; Einträge enthalten `f50 conversation_core_info`, `f51 conversation_setting_info`, `f100 biz_ext` — **kein** Streak-Zähler |
| 400/401/411 | online / offline / input_status | – | – | offen (Präsenz/Typing) |

## 4. Body-Schemata

**`get_by_conversation` (f301 im Feld 8)**
`f1 conversation_id`, `f2 conversation_type`(1), `f3 conversation_short_id`,
`f4 direction` (1 = OLDER), `f5 anchor_index`, `f6 limit`.
`anchor_index` ist ein **µs-Zeitstempel**; Nachrichten-Zeitstempel (MessageBody f10) sind
**ms**. „Neueste Seite" = `anchor = jetzt*1000`, `direction = 1`. Serverdeckel: **50** Nachrichten.
Antwort (f301): f1 Messages, f2 `next_cursor`, f3 `has_more`.

**`send` (f100 im Feld 8)**
`SendMessageRequestBody`: f1 conversation_id, f2 conversation_type, f3 conversation_short_id,
f4 content (**JSON-String** `{"aweType":0,"text":"…"}`), f6 message_type (7 = Text),
f7 ticket, f8 client_message_id.
Antwort: top-level `status_code`; im Body (f100): f1 server_message_id, f3 status, f9 new_ticket.

**`mark_read` (f604 im Feld 8)**
`MarkConversationReadRequestBody`: f1 conversation_id, **f2 conversation_short_id**,
**f3 conversation_type**, f4 read_message_index. Achtung: Hier ist die Reihenfolge
kurz_id **vor** type — anders als in `get_by_conversation`.

**`get_by_user_init` (Feld 8 direkt)**
`MessagesPerUserInitResponseBody`: f1 Messages, **f2 ConversationInfo** (nur im Init!).
ConversationInfo (Schema **V2**): f1 conversation_id, **f2 conversation_short_id**,
f3 conversation_type, **f4 ticket**, f6 Participants, f11 badge_count (ungelesen), f13 Zeit.
`ticket` kommt aktuell als Zeichenkette `"deprecated"` — exakt so senden.

**MessageBody**: f1 conv_id, f3 id, f5 conv_hash, f7 sender, **f8 content (JSON)**,
f10 timestamp (ms), f20 content_pb, f21 scene, f24 media_info_list.

## 5. content_pb (Medien)

MessageContent: f2 image_card, f4 picture_card, f5 video_card, f7 sticker_card,
f14 audio_card, **f200 streak_cmd**.

- **Sticker**: `f7` → f1 (BaseImage) → **f2 = fertige signierte URL** (animiertes WebP,
  `~tplv-…-sticker-set-frame.awebp?rk3s=…&x-signature=…`). Fallback: f4.f3.f2 (statisches JPEG).
- **DM-Bild**: `f4` → f1 → f2 = URL-Liste [0] Vollbild, f6 = decrypt_key.
  Die Bytes sind **AES-256-GCM-verschlüsselt**:
  IV = Datei[0..12], Chiffretext = ab 12, Key = `decrypt_key` als Hex → Bytes.
  Ergebnis ist ein WebP. Cookie nötig (privater CDN).
- **Video**: `f5` → f1 (video) → Cover über f1.

JSON-Check der Reihenfolge: `{"hack":"1"}` + Karte = Sticker/Bild. `{"hack":"1"}` ohne
Karte = System-Nudge („Angestupst“). `aweType` 810/800 = Foto-/Videopost, 703 = Antwort,
700/0 = Text.

## 6. Streaks

Es gibt **zwei** Quellen für Streak-Informationen:

**a) `MessageBody.f21 = scene`** (Zustandsereignis, pro Nachricht)

| scene-Wert | Bedeutung |
|---|---|
| `streak_new_recover_with_pet` | Streak neu gestartet/wiederhergenommen → aktiv |
| `streak_end_with_restore_with_pet` | Streak beendet, wiederherstellbar |
| `streak_end_no_restore` | Streak endgültig beendet |

Diese Nachrichten haben **leeren Content** (`contentField` liefert nichts) und tragen nur
das Szenen-Flag. Sie dürfen deshalb nicht als sichtbare Blase gerendert, aber auch nicht
früh verworfen werden — `parseMessage` gibt sie als `RawMessage(silent = true)` zurück.

**b) `content_pb.f200` = `StreakCommandMessage`**: f1 `cmd_type`, f2 `streak_value`
(`StreakValue`, **Submessage**), f3 `streak_action`.

`StreakCommandType` (aus dem Web-SDK-Deskripter): Unknown 0, **Start 1, End 2,
Update 3, Remind 4**.

`StreakValue`: f1 `users` (repeated), f2 `streak_start` (int64, ms), **f3
`streak_counter` (int32 = der Tageszähler)**, f4 `streak_timezone`, f5
`restorable_before`, f6 `remain_restore_cnt`, f7 `streak_level`, f8
`streak_start_original`, f9 `streak_timezone_offset`, f10
`total_restore_cnt_this_window`, f11 `conversation_type`, f12 `conversation_id`,
f13 `restore_refresh_window_start_ts`, f14 `restore_refresh_day_cnt`.

Ableitung im Code (`TikTokProto.parse` / `parseConversationPage`): die Payload kommt
**neueste-zuerst**, deshalb werden die Nachrichten vor der Auswertung mit `sortedBy {
it.timestamp }` aufsteigend sortiert — erst dann gewinnt das **neueste** Ereignis.
`end` bzw. `end_no_restore` setzt den Zähler auf 0. Streak- und Szenen-Nachrichten werden
**nicht** als Chat-Nachricht angezeigt, sondern nur in `TikTokFeed.streaks` bzw.
`TikTokPage.streaks` geführt und in `TikTokConversation.streak` (`TikTokStreak`) gespiegelt.

**Zahl der Streak-Tage:** `TikTokStreak.value` wird ausschließlich aus dem echten
`streak_counter` eines Live-Pushes übernommen. Solange TikTok keinen Counter liefert,
zeigt die Oberfläche nur den bestätigten Zustand und berechnet keine lokale Schätzung.
Ein Chat mit `streak_end_no_restore` zeigt bewusst kein Badge.

**Vollständiges Streak-Feldinventar** (aus allen 446 Klassen des Web-SDK-Deskripters
extrahiert, `/tmp/opencode/ttsend/fieldmap.json`):

| Nachricht | Streak-Feld |
|---|---|
| `MessageContent` | f200 `streak_cmd` → `StreakCommandMessage` |
| `StreakCommandMessage` | f1 `cmd_type`, f2 `streak_value` [StreakValue], f3 `streak_action` |
| `StreakValue` | f2 `streak_start`, **f3 `streak_counter`**, f4 `streak_timezone`, f5 `restorable_before`, f6 `remain_restore_cnt`, f7 `streak_level`, f8 `streak_start_original`, f9 `streak_timezone_offset`, f10 `total_restore_cnt_this_window`, f100 `conversation_type`, f101 `conversation_id`, f200 `restore_refresh_window_start_ts`, f201 `restore_refresh_day_cnt` |
| `PersistentExtra` (= `MessageBody.f26 biz_persistent_extra`) | f3 `streak_info` [StreakInfo] — **enthält nur `invite_source`, keinen Zähler** |
| `StreakStatusValue` | f1 `streak_message_content`, f2 `scene`, f3 `create_time`, f4 `disable_in_app_notification`, f5 `client_message_id` |

`BizStatusMessageType`: NotUsed 0, **Streak 2000**, StreakPet 2001,
B2CActionBarRefresh 2002, StickerCreationResult 2003.

**Warum der Zähler fehlt:** `streak_cmd` existiert in zwei Trägern — als
`content_pb.f200` einer normalen Nachricht und als **Status-Nachricht** (`status=2000`).
Beide kommen ausschließlich **als Live-Push**, nicht in der Historie. Belege:
`MessageBody.f12 status` ist in allen erfassten Nachrichten nur 0 oder 1; in 200 echten
Nachrichten (Init + 3 Verlaufsseiten) kein einziges `content_pb.f200`; der
Frontier-WebSocket lieferte in 45 s nur Auth (Cmd 10001) und BSync-Index-Sync.

Die Frontier-Frames **sind** lesbares Protobuf (gleicher Feld-Walker), ein WS-Client
wäre also prinzipiell machbar — er bringt den echten Zähler aber erst dann, wenn sich ein
Streak tatsächlich ändert (Update = neuer Streak-Tag).

**Der echte Tageszähler kommt aus der Init-Antwort, nicht aus dem WebSocket.**

Die Systemnachrichten der Serie tragen ein JSON-Blob, ausgerechnet in denselben
Nachrichten, die früher als „leer und störend" aussortiert wurden:

```json
{"tips":"Serienhaustier und 230-tägige Serie wiederhergestellt {{1}}",
 "template":[{"key":1,"action":200002,
   "extra":{"streak_badge_streak_num":"230","streak_badge_style":"orange",
            "streak_badge_streak_level":"200"}},
  {"key":2,"action":200001,"extra":{"panel_type":"streak_restore",
            "streak_badge_restorable_before":"1792274400"}}],
 "fallback_tips":"…"}
```

| Feld | Bedeutung |
|---|---|
| `streak_badge_streak_num` | **die Tageszahl**, als String |
| `streak_badge_style` | `orange` = aktiv, `grey` = beendet |
| `streak_badge_streak_level` | Stufen-Nummer (100 / 200 bei 191 / 230) |
| `action: 200001` | im Template vorhanden → „Wiederherstellen"-Button, also `restorable` |
| `streak_badge_restorable_before` | Ablaufzeitpunkt des Restore-Angebots |

Gegen echte Daten verifiziert (Init-Antwort des Kontos):

| Chat | Tage | Szene | Anzeige |
|---|---|---|---|
| …727089… | **230** | `streak_new_recover_with_pet` | 🔥 230 |
| …738699… | **191** | `streak_new_recover_with_pet` | 🔥 191 |
| …741496… | 28 | `streak_end_no_restore` | nichts (beendet) |
| …744453… | 14 | `streak_end_no_restore` | nichts (beendet) |

Wichtig: In **einem** Chat liegen mehrere solcher Nachrichten (unterbrochen →
wiederhergestellt). Pro Chat gewinnt die **neueste**. Das Parsen läuft deshalb über
`applyStreakBadges()` über alle Nachrichten absteigend sortiert, nicht zeilenweise.
`style: grey` **ohne** `action: 200001` heißt „beendet, nicht wiederherstellbar" →
kein Badge. Fehlt die Zahl ganz (z. B. Chat mit `programmi`), wird nichts angezeigt —
eine Flamme ohne Tage ist schlechter als gar nichts.

Damit ist die Nachrichtenzählung aus der Historie endgültig überflüssig: sie
lieferte 261/270/225/271 Tage gegenüber echten 230/191 und traf nie.

**Frontier-Push (implementiert, `TikTokFrontier.kt`)** — nur noch Ergänzung:

```
wss://im-ws.tiktok.com/ws/v2?fpid=32&service=33554513&method=2&aid=1988
    &device_id=<eigene UID>&access_key=<md5>&device_platform=web
access_key = MD5(fpId + "e252eea4204adef812c32f35a4b80f4b" + deviceId + "f8a69f1719916z")
```

`appKey`/Salt aus dem Web-SDK (`42024917.js`), gegen einen Live-Capture verifiziert.
Handshake antwortet mit `101 Switching Protocols` und `Handshake-Status: 0`.

**Auth-Frame** (Cmd **10001**, Web-SDK-Capture, byteweise nachgebaut und verifiziert):
`f1=10001`, `f2=Sequenz-ms`, `f3=33554513`, `f4=2`, `f7="2"`,
`f8={ f1={f1=2, f2=uid, f3=uid, f4=seq}, f2*={f1=3, f3=0|1|2} }`.
**Cmd 10015 — Geräte-Registrierung** (im Webclient 2328 B): gleiche Hülle, aber
`f3=5`, `f4=1` und `f5*` = Key/Value-Paare (aid, app_name, channel, device_platform,
device_id, region, verifyFp, user_agent …), aus denselben Parametern wie der
HTTP-Aufruf plus `X-Bogus`.

**Cmd 10002 — Index-Registrierung** (274 B, 11 Einträge), live aus dem Browser-Mitschnitt
entschlüsselt:

```
f1  = { f1=2, f2=<eigene UID>, f3=<eigene UID>, f4=<Zeitstempel ms> }   Identität
f2* = { f1=4, f2=0|1, f3=0|1, f4=<Index> }                            × 11
```

Das ist der fehlende Teil: erst dadurch weiß der Server, welchen Nachrichtenstand der
Client hat, und schickt **nur neuere Nachrichten** statt den BSync-Index zu wiederholen.

**Offen und ausdrücklich nicht geraten:** der Wert `f4` in den Index-Einträgen. Die
gegriffenen Werte (z.B. `7595256756528218657`, `6983896500996660010`) stehen **nirgends**
in der Init-Antwort, im Chatverlauf oder in den Chat-Datensätzen — sie sind auch keine
`conv_short_id`. Aktuell werden die bekannten `conv_short_id` der Chats gesendet, weil
sie dieselbe Größenordnung haben. **Ob das stimmt, ist unbewiesen** und nur am Gerät
prüfbar: kommen neue Nachrichten an, war es richtig.

**Und die echte URL des Webclients** weicht ab: `fpid=9`, `aid=1459`,
`version_code=fws_1.0.0`, dazu `ttwid`, `xsack`, `xaack`, `xsqos` — und **kein**
`service=` in der URL. Der `access_key` dafür wird offenbar aus der `ttwid` abgeleitet,
nicht aus der UID (mit fpid 9 nachgerechnet, kein Treffer). Für die App bleibt `fpid=32`:
die Handshake-Antwort `Handshake-Status: 0` ist damit belegt.

**Antwort-Frames:** `f5` = Header-Paare (`X-Method: PayloadRelatedMethod`,
`X-PSM: bytedance.bsync.cache_svr`, Trace-IDs), `f6` = Kompressionshinweis (`gzip`),
**`f8` = gzip-komprimierter Payload** — ohne Entpacken findet der Parser nichts.

Push-Auswertung: rekursiv durch den Frontier-Payload, `content_pb.f200` bzw.
`status == 2000` → `StreakValue.f3 streak_counter`. Das Ergebnis wird pro Konto und
Chat in `SharedPreferences` (`tiktok_streaks`) gespeichert und überlebt Neustarts. Der
Badge zeigt eine Zahl nur bei einem bestätigten Serverwert.

Anzeige: `🔥` bei aktivem Zustand, `⚪ ↺` bei einem beendeten aber wiederherstellbaren
Streak — in der Chatliste und in der Chat-Kopfzeile. Der bestätigte Serverzähler steht
dahinter, falls vorhanden.

**Befund zum Zähler:** Der Zahlenwert (z. B. 230/191) steckt in **keinem** der
erreichbaren Endpunkte — nicht in `get_by_user_init`, nicht in `conversation/get_list`
(`f50 conversation_core_info`/`f51 conversation_setting_info` samt Ext-Maps wurden
vollständig geprüft), nicht in `conversation/get_by_conversation` und nicht im
Frontier-WebSocket (`im-ws.tiktok.com/ws/v2`, 45 s mitgehört: nur Auth/Heartbeat).
Auch TikToks **eigene** Web-Oberfläche zeigt im Messenger keine Flammen; die Zahl gehört
zur Mobile-App. Verifiziert sind deshalb Zustand und Szenenwechsel, nicht der Tageswert.

Gegenprobe mit echten Daten (`init_resp.bin`, 50 Nachrichten): vier Chats tragen
Streak-Szenen — zwei `end_no_restore` (ergeben korrekt *kein* Badge) und zwei
`new_recover_with_pet` (ergeben `🔥` ohne geschätzte Tageszahl).

Gegenprobe mit dem **echten Kotlin-Parser** auf einer synthetischen Init-Antwort
(JVM-Harness, kein Gradle): 11 Nachrichten, davon eine `streak_cmd` (Update 3,
`streak_counter=191`) und zwei Szenen-Nachrichten mit leerem Content →
8 sichtbare Nachrichten, `TikTokStreak(active=true, value=191)`;
der `end_no_restore`-Chat bleibt inaktiv mit Wert 0.

## 7. Push-Kette (Supabase → FCM)

Die App sendet **nie selbst** FCM — es gibt keine Token-Registrierung, Identität ist ein
FCM-**Topic**. Sämtliche Pushes entstehen in Edge Functions. Muster aus `gmail-sync`:

```
TikTok-Server  --(Cloud-Cron alle 2 min)-->  tiktok-sync  --FCM data-only--> Topic "tiktok"
                                                                    |
                                                                    v
                                            MyFirebaseMessagingService: handleTikTokPush()
                                                                    |
                                                                    v
                                            Deep-Link target=tiktok + tiktok_conv
                                                                    |
                                                                    v
                                            SharedViewModel.pendingTikTokOpen → Chat öffnet
```

| Baustein | Ort |
|---|---|
| Tabelle | `tiktok_pushes` (RLS wie `lamp_state`) |
| Zustand je Chat | `tiktok_sync_state (conv_id, last_ts, sender_id)` |
| Erkennung | `tiktok-sync` (Cron `*/2 * * * *`) — Init-Abruf, Zeitstempelvergleich |
| Push-Auslösung | `tiktok-sync` bzw. `tiktok-push` bei Meldung aus der App |
| FCM-Zustellung | Topic `tiktok`, **data-only**, `android.priority: high` |
| Empfang | `handleTikTokPush()`, Kanal `tiktok_channel` innerhalb `if (prvt())` |
| Secrets | `TIKTOK_COOKIE`, `TIKTOK_DEVICE_ID`, `FIREBASE_SERVICE_ACCOUNT` |

**Zwei Fehler, die in Produktion passiert sind und nicht wiederholen dürfen:**

1. **Feld 7 (Sender) ist eine Zahl, kein String.** Als String gelesen bleibt die
   eigene UID leer, die Prüfung „ist das meine Nachricht?" greift nie, und die App
   benachrichtigt sich selbst. Deshalb gibt es inzwischen die Bremse: findet
   `tiktok-sync` die eigene UID nicht, pusht es **gar nichts**.

2. **UIDs brauchen BigInt.** `7469075901474161686` liegt über 2^53; als JavaScript-`Number`
   gerundet ergibt das `7469075901474162000`. Protobuf-Varints in Deno deshalb als `bigint`
   lesen, erst beim Formatieren zu String.

**Akku-Verhalten:** Im Hintergrund pollt **nichts**. Polling läuft ausschließlich, solange
der TikTok-Tab sichtbar ist (`DisposableEffect` → `startPolling`/`stopPolling`), 30 s
Intervall. Bildschirm aus ⇒ null Requests. Ohne diese Begrenzung wären es 102 KB × 120/h =
**12 MB pro Stunde** gewesen, weil `QuietHoursNotificationService` den Prozess am Leben hält.
Datenbank- und Streak-Schreiben laufen nur bei tatsächlicher Änderung (Fingerprint aus
Anzahl, letzter Zeitstempel und letzter Nachrichten-ID).

## 8. Reaktionen (Cmd 705, live verifiziert)

Reaktionen gehen **nicht** über 703 — im Browser-Mitschnitt ist 703 gar kein Pfad, sondern
ein JS-Chunk-Hash. Der echte Befehl steckt in den Metadaten der Sync-Antwort:

```
s:property_modify = {"UserId":…, "BizID":1180, "Inbox":0,
                     "ConversationShortId":…, "ServerMessageId":…,
                     "Modifys":[{"Op":0,"Key":"e:❤️"}]}
```

und im Server-Log derselben Antwort: `"cmd":705, "source_method":"SET_MESSAGE_PROPERTY"`.

```
POST https://im-api.tiktok.com/v1/message/set_property     (Cmd 705, Sub 0)

f705 = ModifyMessagePropertyRequestBody
  f1* = ModifyPropertyBody
          f1 conv_id, f2 conv_type, f3 conv_short_id, f4 server_message_id,
          f6 modify [ModifyPropertyContent]
            f1 operation   → 0 = setzen, 1 = entfernen
            f2 key         → "e:" + Emoji
```

**Die Emoji-Liste ist eine feste TikTok-Auswahl, keine Unicode-Klassifikation.**
Über den echten Server abgefragt (`Op=1` als nahezu副作用-freie Sonde):

| erlaubt | abgelehnt |
|---|---|
| ❤️ 👍 👎 😂 🥰 🤔 😮 😢 😭 😡 🙏 🎉 🔥 ✌️ 🤗 🕶️ 🥳 ❣️ ☺️ | 🤣 😍 😘 😎 👏 ⭐ 🌟 ✨ 💪 🤝 👌 😴 😇 🙌 💃 🤯 😤 👀 💔 😌 |

Ein nicht erlaubtes Emoji liefert `200001` — deshalb ist `REACTION_EMOJI` in `TikTokTab.kt`
nur mit geprüften Werten gefüllt. Merkwürdig dabei: 😎 fällt raus, 🕶️ nicht; 👏 auch,
🙏 dagegen schon.

## 9. Reaktionen in der Oberfläche

**Anzeige:** Die Chips sitzen **pro Nachricht** unterhalb der Blase im Chatfeed
(`MessageBubble` → `ReactionChips(message.reactions)`), auf der Seite der Nachricht ausgerichtet
(eigene rechts, fremde links). Eine erste Fassung hatte sie in der Chatlisten-Vorschau —
das war die falsche Stelle, siehe unten.

`TikTokConversation.reactions` wird in `TikTokProto.parse()` über
`list.flatMap { it.reactions }.distinct()` aus **allen** Nachrichten des Chats aggregiert und
in `setReaction()` mitgepflegt, wird aber **aktuell nirgends angezeigt**. Das Feld ist damit
reserviert: eine Anzeige in der Chatlisten-Vorschau wäre eine Zeile
(`ReactionChips(conversation.reactions)`), wenn sie gewollt ist.

**Persistenz:** eigene Reaktionen liegen als kommaseparierte Liste in
`CachedMessage.reactions`. `TikTokMessage.toCached()` schreibt mit `joinToString(",")`,
`CachedMessage.toMessage()` liest mit `split(',')` — beide Richtungen leicht zu verwechseln,
das Gate hat es beim ersten Versuch gefangen.

**Aktualisierung ohne Neuladen:** `TikTokDmClient.setReaction()` setzt nach dem Anpassen der
Nachrichtenliste zusätzlich `conversations` neu zusammen, sonst bliebe die Chatliste stale,
bis der nächste Poll kommt.

**Kontextmenü:** eigenes `ModalBottomSheet` (`MessageActionSheet`) statt `AlertDialog`,
Muster aus `VocabTab.kt` (`rememberModalBottomSheetState(skipPartiallyExpanded = true)`),
`ModalBottomSheet` wird im Repo bereits in `VocabTab` und `MediaPlayerTab` genutzt. Emoji-Raster
3 × 7, aktive Reaktion mit `primary`-Hintergrund. Löschen in `Color(0xFFB71C1C)`, dieselbe
Farbe wie in `AlertDialogTabslify` (`Composables.kt`).

**Fremde Reaktionen kommen über den WebSocket-Sync** — verifiziert am 17.10. in
`capture/ws_messages.txt`. Der Push ist ein normaler Protobuf-Map-Eintrag:

```
0a 11 "s:property_modify"     <- Key, 17 B
12 f1 13                      <- Value-Tag, Laenge 2545
7b 22 55 ...                  <- JSON
```

Der JSON-Inhalt:

```json
{"UserId":…, "BizID":1180, "Inbox":0,
 "ConversationShortId":7696878946750939424,
 "ServerMessageId":7697125754840155670,
 "Modifys":[{"Op":0,"Key":"e:😂"}], …}
```

`ServerMessageId` = `TikTokMessage.id`, `ConversationShortId` = `meta.convShortId`.
`Op` 0 = setzen, 1 = entfernen; **ein Eintrag kann mehrere `Modifys` tragen**, im Capture
`[{"Op":0,"Key":"e:❤️"},{"Op":1,"Key":"e:🤪"}]` in einem Push. `Key` ist immer `e:<Emoji>`.

`TikTokProto.parseFrontierReactions()` liest das ohne JSON-Bibliothek: die beiden IDs per
Regex aus dem Rohstring, `Modifys` per Regex `Op`/`Key`. Grund: die IDs liegen bei 7,7 × 10¹⁸,
wo `org.json` je nach Implementierung über `Double` gehen kann und die ID still verfälscht.
Gegen die 10 aufgezeichneten Frames geprüft: 12 Operationen, alle IDs und Ops korrekt.

**Was beim 705-Sync auffällt:** `UpdateMessagePropertyEntity = {server_message_id, version}`
und `ModifyMessagePropertyResponseBody = {status, version}` — der Server versioniert
Properties. Ein vom Gerät im Offline veralteter Property-Stand wird also nicht durch einen
HTTP-Abruf korrigiert, weil es keinen gibt.

**Noch ungeprüft:** ob der WS des Geräts die Pushes überhaupt ausliefert. Die
Index-Anmeldung (Cmd `10002`) in `TikTokFrontier.kt` nutzt weiterhin Annahmen als Werte, das
ist dokumentiert und nicht bestätigt. Der Parser ist gegen echte Bytes geprüft, der
Zustellweg ist es nicht — erst ein Test auf dem Gerät mit einer Reaktion der Gegenseite sagt
das endgültig.

## 10. Stolperfallen bei der Push-Zustellung

1. **Der Frontier darf nicht am HTTP-Polling hängen.** `startPolling()` rief ursprünglich
   `TikTokFrontier.start()` nur in `load().onSuccess` auf, und `load()`-Fehler wurden
   verschluckt. Folge auf dem Gerät: ein HTTP-Fehler ⇒ kein WebSocket ⇒ keine Reaktionen,
   kommentarlos, während die Oberfläche scheinbar normal aus dem Room-Cache versorgt wird.
   Startet jetzt aus `startPolling()` heraus direkt, Fehler werden mit
   `Laden fehlgeschlagen: …` geloggt.
2. **„Alles geladen“ heißt nicht „Netzwerk ok“.** Die Bildanzeige kommt aus `quelle=DISK`
   bzw. `MEMORY_CACHE`; im Log stand kein einziges `quelle=NETZ`, während der Tab voll
   funktionierte. Cache und Netz sind unabhängig zu prüfen.
3. **`adb`-Diagnose braucht ein debuggbares Paket.** Installiert war ein Release-Build ohne
   `DEBUGGABLE`, damit scheitert `run-as` und die Cookie-Datei ist nicht lesbar.

## 11. Cache-Bereinigung bei gelöschten Nachrichten

`writeCache()` nutzt `@Insert(IGNORE)` und löscht nur per Zeit-Cutoff (90 Tage). Eine serverseitig
gelöschte Nachricht blieb damit bis zum Zeit-Ablauf im Cache und tauchte beim nächsten Start
über `readCache()` wieder auf. Drei Ursachen, alle behoben:

1. **`deleteMessage()` / `recall()`** entfernten nur aus `_feed.value`, nicht aus Room. Jetzt
   zusätzlich `cacheLoeschung()` → `messageDao().deleteByKey(messageId)`.
2. **`load()` mischt additiv** — `byId` bekommt erst den alten Bestand, dann die Serverdaten.
   Eine Nachricht, die der Server nicht mehr kennt, fällt dadurch nie heraus.
3. **`deleteStale(convId, keep)` lag tot im DAO.**

**Der Cache liegt in `cacheDir`, nicht in den App-Daten.** Die Datenbankdatei wird als
`cacheDir/tiktok/tiktok_cache_db` angelegt, nicht über den Standardpfad in `databases/`.
Damit gehört der Chatverlauf zu den Daten, die Android jederzeit räumen darf — gewollt. Wird der
Cache gelöscht, lädt der Tab beim Öffnen Chats und Nachrichten neu, weil die Init-Antwort
(Chats in Feld 2, Nachrichten in Feld 1, `TikTokProto.parse()`) beides liefert. Seitengröße der
Chat-Anfrage ist `CONVERSATION_PAGE_SIZE = 50`.

Beim ersten `get()` wird die alte Datei aus `databases/` samt `-wal` und `-shm` gelöscht
(`aufraeumen()`), sonst kämen die veralteten Zeilen zurück.

Nur Streaks (`PREFS_STREAK`) und Cookie (`TikTokCookieRepository`) liegen in
SharedPreferences — Nachrichten werden **nie** in die Prefs geschrieben.

**Regel für veraltete Zeilen:** gelöscht wird nur, was im geladenen Fenster liegt und dort fehlt.
Fenstergrenze ist die jeweils älteste serverseitige Nachricht des Chats; alles mit kleinerem
Zeitstempel gilt als „noch nicht geladen" und bleibt stehen. Grund: `openConversation()` lädt nur
die *neueste* Seite, ältere Nachrichten kommen lazy über `loadMore()` — ein globales Aufräumen
würde gültige Historie löschen.

Zwei Umsetzungsfallen, die den ersten Versuch wirkungslos machten:

1. **Die schützende Menge darf nicht aus dem Speicher kommen.** `merge(replace = true)` wirft
   die älteren Nachrichten bereits aus `_feed.value`. Eine daraus gebildete „zu alt"-Liste ist
   immer leer, und ein Wächter darauf kehrt im Normalfall sofort zurück — die Bereinigung lief
   nie. Die Grenzen kommen jetzt aus dem Serverergebnis (`fensterGrenzen`), für Speicher
   (`fensterBereinigen`) und Datenbank (`cacheFensterRaeumen`) aus derselben Quelle.
2. **Speicher und Datenbank getrennt bereinigen.** Wird nur Room gelöscht, schreibt der nächste
   `persistIfChanged` die alten Zeilen aus dem Speicher sofort wieder zurück.

## 12. Drei Fehler, die alte Nachrichten am Leben hielten

1. **Polling starte nicht wieder.** `startPolling()` kehrte bei `pollJob?.isActive == true` früh
   zurück. Wird der Tab zweimal komponiert (bekannte Eigenheit der Zielkomposition), startet der
   zweite Aufruf, **während der alte Job noch lebt**, und der neue wird sofort von `stopPolling()`
   abgebrochen. Danach pollte die Sitzung nie wieder — alles Angezeigte war eingefrorener
   Cache-Stand. Jetzt wird der alte Job unter `synchronized` bedingungslos beendet und ein
   frischer angelegt. Logbild des Fehlers: einmal `Frontier-Client gestartet`, dann
   `StandaloneCoroutine was cancelled`, danach kein weiterer Start.

2. **`CancellationException` war als Netzwerkfehler getarnt.** `runCatching { load(app) }
   .onFailure { … }` fängt auch `CancellationException` und meldete
   `Laden fehlgeschlagen: StandaloneCoroutine was cancelled`, statt die echte Ursache zu zeigen.
   Jetzt wird sie vor dem generischen `catch` weitergereicht.

3. **`readCache()` vergiftete die Chat-Metadaten.** Aus dem Cache wurde
   `TikTokConvMeta(convId, 1, 0L, "", unread)` gebaut, also `convShortId = 0` und leeres
   `ticket`. `convMeta()` liefert einen vorhandenen Eintrag ungeprüft zurück und holt deshalb
   **nie** frisch vom Server — Reaktion, Löschen, Zurückrufen und Senden scheitern aus dem
   Cache heraus alle mit „Chat-Metadaten fehlen". Jetzt bleibt `convMeta` leer, der Nachbezug
   greift.

**Chat-Vorschau:** `writeCache()` leitete `preview` aus dem gespeicherten Feld ab. Nach dem
Pruning zeigte die Liste dadurch eine Nachricht an, die gar nicht mehr existierte. Die Vorschau
wird jetzt aus den tatsächlich geschriebenen Nachrichten berechnet.

## 13. Tages-Trenner und Ungelesen-Marke im Chatfeed

`ordered` ist absteigend nach Zeitstempel sortiert und wird in einer `LazyColumn` mit
`reverseLayout = true` gezeigt: Index 0 (neueste) sitzt unten, der höchste Index (älteste) oben.

**Tages-Trenner:** `previous = ordered[index - 1]` ist der **neuere** Nachbar. Der Trenner wurde
damit ans untere Ende einer Tagesgruppe gehängt — sichtbar als „Gestern" unter den gestrigen
Nachrichten und „Heute" zwischen zwei heutigen. Richtig ist der **ältere** Nachbar
`ordered[index + 1]`; die letzte Position (`getOrNull` == null) bekommt den Trenner immer.

**Ungelesen-Marke:** `ordered.getOrNull(ordered.size - conversation.unread)` zeigte in die
Historie statt auf die älteste ungelesene Nachricht. Da `ordered[0]` die neueste ist, liegt die
älteste ungelesene bei `conversation.unread - 1`.

Beides wurde nebenbei auf `itemsIndexed` umgestellt: `ordered.indexOf(message)` suchte per
Datenklasse-Gleichheit und lieferte bei zwei inhaltlich gleichen Nachrichten den **ersten** Index
statt des tatsächlichen.

## 14. Gelöschte Nachrichten: zwei getrennte Marker

Der Server entfernt weder zurückgerufene noch für einen User gelöschte Nachrichten. Beide
kommen als Grabsteine zurück, nur mit unterschiedlichen Feldern in der `f9`-Map:

| Zustand | Marker | `f8` | `f21` scene | `f12` |
|---|---|---|---|---|
| Zurückgerufen | `s:is_recalled = 1`, `s:recall_uid` | leer | leer | 3 |
| Für mich gelöscht | `s:invisible = <UID>` | `"{"hack":"1"}"` | `sticker` / `video_sticker` / `private_image` | 0 oder 1 |

`s:invisible` trägt die **UID der Person, für die die Nachricht unsichtbar ist**. Deshalb wird
gegen die eigene UID geprüft und nicht gegen „Marker vorhanden".

Umsetzung: `mapWerte(fields, schluessel)` liest die `f9`-Map (Schlüssel `f1`, Wert `f2`).
`erledigt()` verwirft `s:is_recalled = 1` direkt in `parseMessage()`. `s:invisible` wandert nach
`RawMessage.invisibleFor` und wird im Sichtfilter entfernt, **nachdem** `self` ermittelt wurde —
die eigene UID entsteht ja erst aus den Nachrichten selbst:

```kotlin
val ohneSystem = raw.filter { …Streak-/Systemfilter… }
val self = mostFrequentUid(ohneSystem.map { it.convId })
val visible = ohneSystem.filter { it.invisibleFor.isEmpty() || it.invisibleFor == self }
```

**Gegen die echten Daten geprüft:** Chatseite @programmi (16 Nachrichten) → 3 `s:is_recalled`,
4 `s:invisible`; Init-Antwort (50 Nachrichten) → 3 und 1. In dieser Chatseite tragen die beiden
15:10-Platzhalter sowie `video_sticker` und `private_image` `s:invisible`; der einfache
`sticker` um 15:43:34 und der `nudge` um 15:43:25 tragen **keinen** Lösch-Marker und bleiben
sichtbar — der Server führt sie weiterhin als vorhanden.

**Nicht abgedeckt:** Nachrichten, die auf Serverseite ohne jedes Feld verschwinden, gibt es nicht;
jede Löschung ist über eines der beiden Felder sichtbar. Ob der Simple-Sticker auf TikTok selbst
noch da ist, lässt sich von hier aus nicht entscheiden — wir sehen nur, dass der API-Server ihn
unverändert liefert.

**Grenze des JVM-Gates:** `org.json` stammt aus `android.jar` und wirft dort
`RuntimeException("Stub!")`; `runCatching { JSONObject(...) }` fängt das still. Im Testaufbau
liefert `parse()` deshalb 0 Nachrichten, **auf dem Gerät aber 21**. Aussagen über `parse()` aus
einer JVM-Probe ohne echtes `org.json` sind wertlos — Reflektion auf einzelne Felder
funktioniert dagegen zuverlässig.

**Fallstrick beim Einfügen:** `if (!convId.contains(':')) return null` kommt dreimal vor, in
`parseMessage()`, `parseConversation()` und weiteren Stellen. Eine Ersetzung auf den ersten
Treffer landet in `parseConversation()` und verwirft dann gültige Chats.

## 15. Doppelte Komposition hat das Polling abgeschossen

`TikTokDmClient` ist ein `object`, `TikTokTabContent` wird aber **zweimal komponiert**. Ablauf:

1. Instanz A steigt ein → `startPolling()` startet Job und Frontier.
2. Instanz B steigt auf → `startPolling()` wirft A weg und startet neu. `TikTokFrontier.start()`
   kehrt aber per `if (loop?.isActive == true) return` **früh** — deshalb steht in genau einem
   Prozess nur **ein** `Frontier-Client gestartet`.
3. Instanz A geht → `onDispose` → `stopPolling()` + `TikTokFrontier.stop()` → räumt **Bs** Job
   und Bs WebSocket ab.
4. B bleibt komponiert und rendert weiter — aus dem Cache. Es pollt nie wieder.

Logbild: ein Start, kein `Frontier verbunden`, `… was cancelled`, danach weiter `bild geladen
quelle=DISK`.

Lösung: Referenzzählung im Singleton. `startPolling()` zählt hoch und legt nur beim ersten
Aufruf einen Job an, `stopPolling()` zählt runter und beendet erst bei Null. `TikTokFrontier.stop()`
wurde aus dem `onDispose` entfernt und liegt jetzt im Singleton, damit beide Seiten denselben
Lebenszyklus teilen.

Das war die eigentliche Ursache hinter allen „Anzeige aktualisiert sich nicht"-Symptomen: `load()`
wurde jedes Mal abgebrochen, also lief auch das Aufräumen nie durch.

## 16. Drei Filter, drei verschiedene Orte

Gelöschte Nachrichten zu erkennen reicht nicht — jeder Filter muss an **allen** Parse-Wegen sitzen,
sonst holt ein anderer Weg sie wieder zurück:

| Ort | `s:is_recalled` | `s:invisible` |
|---|---|---|
| `parseMessage()` | verwirft sofort | an `RawMessage.invisibleFor` |
| `parse()` (Init) | – | im `visible`-Filter |
| `parseConversationPage()` (Chatseite) | – | **im `visible`-Filter** |

`parseConversationPage()` hat einen **eigenen** `visible`-Filter und bekommt `self` als Parameter.
Fehlte der `invisibleFor`-Check dort, lieferte die Chatseite die Nachrichten weiter, `merge()`
legte sie zurück in den Feed — der Chat sah unverändert aus, obwohl der Filter in `parse()` sauber
arbeitete.

**Die Fensterregel gehört nur in `openConversation()`.** `load()` benutzt die Init-Antwort, und die
liefert je Chat nur die **neuesten** Nachrichten. Die dort berechnete „älteste serverseitige
Nachricht" ist damit die Grenze des **neuesten** Ausschnitts, nicht der Historie — alles Ältere gilt
fälschlich als „noch nicht geladen" und bleibt geschützt. Genau dort lagen die gelöschten Nachrichten:
zwei Placeholder vom 15.10. waren zwei Sekunden älter als die neue Fenstergrenze und überlebten.
Die Regel ist aus `load()` entfernt; allein `cacheFensterRaeumen()` nach `openConversation()` nutzt
sie, wo die Seite wirklich 50 Nachrichten abdeckt.

## 17. Die Lösch-Bedingung war invertiert

```kotlin
// FALSCH — behält genau die Nachrichten, die weg sollen
item.invisibleFor.isEmpty() || item.invisibleFor == self
// RICHTIG
item.invisibleFor != self
```

Bei `s:invisible == eigeneUID` ergibt die erste Fassung `false || true` = **true**, die Nachricht
bleibt also stehen. Sie fiel nur deshalb überhaupt auf, weil `openConversation()` die Seite neu
holt — der Chat sah unverändert aus, und das Logfile blieb leer.

**Zwei Lehren daraus:**

1. Ein Filter, dessen Ergebnis man nicht vor dem Gerät geprüft hat, ist eine Vermutung.
2. Die JVM-Prüfung war nutzlos, weil `org.json` aus `android.jar` `Stub!` wirft. Erst ein
   **eigener minimaler `org.json`-Ersatz im Test-Classpath** (nicht ins Repo) machte den echten
   Parse-Weg offline lauffähig. Damit war der Fehler in Sekunden sichtbar statt nach einem
   Build-Durchlauf: `parseConversationPage()` lieferte 11 statt der erwarteten 7 Nachrichten.

**Stand gegen die echten Binaries:** Chatseite @programmi → 16 roh, 3 `is_recalled` in
`parseMessage` verworfen, 2 Platzhalter mangels Inhalt verworfen, **4 `s:invisible` im
Sichtfilter** → **7 sichtbar**. Bleiben genau die ohne Lösch-Marker:

```
15.10. 15:03:29 Sticker   15.10. 21:36:24 "hi"
16.10. 06:59:11 "."       16.10. 14:40:00 "."
16.10. 15:43:25 Sticker   16.10. 15:43:34 Sticker
17.10. 14:11:41 "Hi"
```

`Sticker 15:43:34` und der `nudge 15:43:25` tragen keinen Marker und bleiben zu Recht — der Server
führt sie als vorhanden.

## 18. Reaktionen kommen über HTTP in Feld f15 — kein WebSocket nötig

Reaktionen stehen **direkt in der Nachricht**, nicht nur als WS-Push. `f15` ist eine wiederholte
Map, `f1` = Schlüssel (`e:😂`), `f2` = Payload (darin `f4` = reagierende UID):

```
f15  f1="e:😂"    f2={ … f4="7469075901474161686" … }
f15  f1="e:love"  f2={ … }
f15  f1="e:❤️"    f2={ … }
```

`TikTokProto.reaktionenAusF15(fields)` sammelt alle `f15`-Schlüssel mit Präfix `e:` und
verwirft **reine ASCII-Schlüssel** — `e:love` ist eine Systemmarkierung, kein Emoji. Damit bleiben
nur echte Emojis übrig. Das Ergebnis wandert über `RawMessage.reaktionen` nach
`TikTokMessage.reactions`.

**Gemessen an den echten Antworten:** Chatseite @programmi → 7 sichtbare Nachrichten, davon 4 mit
Reaktionen (❤️, ❤️, 😂, 😂). Init-Antwort → 40 Nachrichten, 5 mit Reaktionen, eine davon mit
10 Emojis. Damit braucht das Laden von Reaktionen **keinen** WebSocket.

Der WS-Pfad (`parseFrontierReactions`) bleibt für Live-Updates sinnvoll, ist aber nicht
Voraussetzung für die Anzeige.

### Diagnose

Das Debug-Logging wurde nach dem erfolgreichen Test wieder entfernt. Für den nächsten Fehlerfall
gibt es zwei Ansatzpunkte:

- **Reaktionen kommen nicht an:** In `reaktionenAusF15()` die rohen `f15`-Schlüssel einer Nachricht
  ausgeben (Feldnummer, Unterfeld 1). Erwartet `e:<Emoji>`.
- **WS-Pushes kommen nicht an:** In `parseFrontierReactions()` protokollieren, ob der Marker
  `s:property_modify` im Frame vorkommt und wie viele Treffer es gab.

**Merken:** Jede JVM-Prüfung von `TikTokProto` braucht einen echten `org.json`-Ersatz im
Test-Classpath (`android.jar` wirft `Stub!` und verschluckt alle JSON-Auswertung). Ein Shim liegt
unter `/tmp/opencode/kcheck/shim/`, dazu ein passendes `android.util.Log`. Nur damit sind Aussagen
über `parse()` und `parseConversationPage()` belastbar.

## 19. Medien: Container, und was W2 noch braucht

### Struktur im Nachrichten-Container `f20`

| Feld | Inhalt |
|---|---|
| `f4` | Bild mit mehreren Auflösungen (3672 B bei einem Foto) |
| `f7` | Medien allgemein: `f1 = { f1 = Speicher-Key, f2 = URL }` — Sticker, Video-Sticker, Nudge |

`parseContentPb()` wertet `f7` (Sticker, mit Fallback auf `f7.f4`) und `f4` (Bild, mit
Thumbnail-Fallback) aus. Das entschlüsselte Ergebnis landet in `CachedMessage.mediaUrl`,
`mediaKind` (`sticker` / `photo`) und `mediaKey`.

### Abgelaufene URLs ohne Cmd 2004

Die URLs tragen Signaturen und laufen ab; Cmd `2004` liefert `200001` und hilft nicht. Der
Umweg: **die Chatseite neu abrufen** — sie liefert bei jedem Aufruf frische URLs.

`TikTokDmClient.erneuereMedienUrl(context, convId)` ruft `openConversation()` auf. In
`TikTokMedia` greift das, wenn `encryptedMediaFile()` `null` liefert; danach fließt die neue URL
über den Feed in den Composable, der Effekt läuft erneut. Geschützt gegen Request-Sturm durch
`URL_ERNEUERUNG_SPERRE_MS = 5000` (ein Abruf pro 5 s für die ganze App) und durch
`urlErneuert`, das pro Medien-URL nur einmal auslöst.

Bisher griff bei abgelaufenen URLs nur ein Cookie-Retry — und nur bei Medien **ohne**
`mediaKey`. Verschlüsselte Medien blieben deshalb dauerhaft kaputt.

### Video und Sprache (aus echten Binaries, 17.10.)

Neue Container in `f20`:

| scene | Feld | Inhalt |
|---|---|---|
| `private_video` | **`f5`** | Cover und Stream |
| `voice` | **`f14`** | `f1` = Wellenform, `f102` = Beschriftung mit Dauer |

**Der eigentliche Medien-Verweis ist `f24` auf Nachrichtenebene**, nicht `f20`. `f2` ist der Typ
(`2` = Video, `3` = Sprache), `f20` ist eine Liste von Einträgen mit `f1` = `"play"` bzw.
`"cover"`:

```
f24  f1 = "v2643agc…"            Transmissions-ID
     f2 = 2                      Typ: Video
     f20 { f1="play",  f2=<kmoat-URL>, f22=1367, f20=1080, f21=1920 }
     f20 { f1="cover", f2=<Bild-URL>, f20=1080, f21=1920 }
f24  f1 = "v266afgc…",  f2 = 3    Typ: Sprache — **kein f20, also keine Audio-URL**
```

**Video: `video/fplay` ist eine Sackgasse.** Die URL
`vas-*.byteoversea.com/video/fplay/…/v2643agc…` liefert **JSON**, kein Video:

```json
{"video_info":{"data":{"status":2002,"message":"未授权, 禁止访问",
                       "video_duration":1.394,"video_list":{}}}}
```

`status 2002` bleibt mit und ohne Cookie und bei `device_platform` web_pc/web/android/ios/linux.
Der echte Stream ist der **`kmoat`**-Eintrag aus `f24` — HTTP 200, `video/mp4`, 477 KB,
H.264 1080×1920 mit AAC, 1,37 s, **unverschlüsselt** (Header beginnt direkt mit `ftyp`).

**Der `kmoat`-Stream braucht zwingend den Cookie.** Gemessen:

| Header | HTTP |
|---|---|
| keine | 403 |
| nur Referer | 403 |
| nur User-Agent | 403 |
| UA + Referer | 403 |
| **nur Cookie** | **200** |
| Cookie + beliebiger UA | 200 |

Deshalb bekommt ExoPlayer eine `DefaultHttpDataSource.Factory()` mit
`setDefaultRequestProperties(mapOf("Cookie" to cookie))`; der User-Agent ist nebensächlich.

**Sprache: der Server liefert in Nachrichtenantworten keine Audio-URL.** Die Nachricht enthält
nur Wellenform, ein nicht lesbares `f101` und die Beschriftung. Die Dauer steht ausschließlich im
Text (`f102` → `"[Sprachnachricht] 0:03"`), die per Regex ausgelesen wird (`dauerAusText`,
`dauerTextIn`). Die Wellenform ist **keine** Varint-Paare (Summe der ersten Werte: 537 Millionen)
und wird bewusst nicht angezeigt, statt sie zu raten.

Das ist am 17.10. gegen einen frischen Abruf geprüft: Cmd 301 **und** Cmd 203 liefern für das
Video desselben Chats bei jedem Abruf neu signierte URLs, für die Sprachnachricht aber
weiterhin nichts — auch nicht nach Stunden. Es ist also kein Abruf- oder Zeitproblem, der
Server erzeugt für `MediaType.AUDIO` (f24.f2 = 3) schlicht keine `url_info_list`. Zusätzlich
gemessen: `AudioCard.f201` (resp_base) enthält `f3` = 390300 (TTL in Sekunden) und `f1.f1` = ein
laufender Ablaufzeitpunkt — die Ressource existiert serverseitig, ihre URL wird aber nicht
mitgeliefert. Details und der erfolglose Auflösungsversuch: Abschnitt 21.

Angezeigt wird sie als „🎤 Sprachnachricht · 3 s · Ton nicht abrufbar" — ehrlich statt mit einem
Player, der ins Leere läuft.

**Album:** in den Daten nicht vorhanden. Die drei letzten Nachrichten sind drei einzelne Fotos,
zwei davon in derselben Sekunde.

## 20. Bekannte Stolperfallen

1. Sub-Command pro Endpoint. **200001** erscheint bei Cmds, die der Webclient offenbar
   per WebSocket statt HTTP schickt (`get_media_urls` 2004, `input_status` 411) — er steht
   nicht im SDK-Fehler-Enum und ist parameterunabhängig. Nicht als Parameterfehler
   behandeln.
2. `ticket`/short_id/type je Body unterschiedlich nummeriert (siehe 4).
3. Zeitstempel-Einheiten: Nachrichten ms, `anchor_index` µs.
4. Medien-URLs tragen Signaturen (`x-orig-expires`) und laufen ab → Cache-Key ist der URL-Hash,
   neue URL ⇒ neue Datei; `encryptedMediaFile()` löscht bei Fehlschlag.
5. Bild-Dekodierung schlägt ohne Entschlüsselung mit „BitmapFactory returned a null bitmap" fehl.
6. OkHttp lässt sich auf der JVM nicht starten (Android-Log-Abhängigkeit) → Entschlüsselung
   nur mit echtem Key/Payload gegenprüfen, nicht mit curl allein.

## 21. Sprachnachricht: Auflösung der Audio-URL (Stand 17.10., 22 Uhr)

Ziel wäre `GetMediaUrls` (Cmd `2004`, Pfad `v1/media/get_urls`) mit dem Schema aus dem
Web-SDK. Gemessen wurde mit **echtem** Body (gebaut mit `TikTokProto.buildRequest`), nicht
geraten.

### 21.1 Das Schema steht im Web-SDK (`/tmp/opencode/ttjs/0bcbd36f.js`, `im_proto`)

```
RequestBody.get_media_urls_body            = Feld 2004
GetMediaUrlsRequestBody
  f1  source_appid    int32   (1988, = ctx.option.appId)
  f2  uri             string
  f3  media_type      int32   (1 IMG, 2 VIDEO, 3 AUDIO, 4 FILE)
  f4  encrypted       bool
  f5  img_options     repeated ImgOption
  f6  video_options   repeated VideoOption   {1 vid, 2 cover_uri, 100 urls}
  f7  audio_options   repeated AudioOption   {1 vid, 100 urls}
  f8  file_options    repeated FileOption    {100 urls}
  f9  encrypted_uri   string
  f10 read_url_from   int32   (1 URL_VSDK, 2 URL_IMAGEX; IMG ⇒ 2, sonst 1)
MediaInfo (in der Nachricht, f24)
  f1 id, f2 type, f3 decrypt_key, f4 url_info_list
UrlInfo: f1 display_type, f2 url_list, f3 video_model, f20 width, f21 height,
         f22 duration, f23 size
```

Der Aufrufer im SDK ist `__internal_getFileUrl` → `api.GetMediaUrls`. Wichtig: **das SDK ruft
das nur im Upload-Pfad auf** (`__internal_getFileUrlWithUpload`), nie für empfangene Medien.
Empfangene Medien kommen ausschließlich mit den URLs aus der Nachricht; bei Ablauf
(`get needRefresh(){return "0"!==this.ttl&&Date.now()>1e3*Number(this.ttl)}`) holt der Client
die **Nachricht** neu (`triggerRefresh` → `getMessages`), nicht die URLs.

### 21.2 Cmd 2004 ist für den Webclient gesperrt — belegt, nicht vermutet

| Kanal | Anfrage | Antwort |
|---|---|---|
| HTTP `POST im-api.tiktok.com/v1/media/get_urls` | Cmd 2004, echtes Schema | `200001` |
| HTTP, gleiche URL | **leerer** Body | `200001` (parameterunabhängig) |
| HTTP | `ttoth/v1/media/get_urls` | `200001` |
| WS `wss://im-ws.tiktok.com/ws/v2` (aid 1459, Envelope eines funktionierenden 705) | Cmd 2004, echtes Schema | `200001` |
| WS, **Kontrolle** | Cmd 301, gleiches Envelope | `status 0`, 25 KB Body |
| HTTP + WS | Cmd 211 (`get_by_id`) | `200001` |
| HTTP | Cmd 2200 (`v1/message/get`, Message-ID + Index) | `200001` |

`200001` steht nicht im SDK-Fehler-Enum (`InvalidCommand` = 5, `InvalidToken` = 200005) und
erscheint bei **jedem** Body, auch leerem. Damit ist es ein Cmd-Gate des Gateways für diesen
Client, kein Parameter- oder Transportproblem: 301 und 705 laufen über denselben Kanal.

### 21.3 Der Webclient kann Sprachnachrichten nicht abspielen

Im Chat-Renderer (`/tmp/opencode/ttjs/2475dd00.js`) gibt es Fälle für PictureCard, VideoCard,
Sticker, Text, StartChatTip — **keinen** für `MsgTypeTemplateAudioCard` (1813). Unbekannte
Typen landen im Zweig `l()` → `"[webdm_message_not_supported]"`. Ein Mitschnitt im Browser kann
also keinen Audio-Abruf zeigen, weil keiner stattfindet. `audio_wave`/`waveform` kommen nur in
der Proto-Definition vor, nie in UI-Code.

### 21.4 `audio_get` ist im Basis-APK ein toter String

Im `classes9.dex` (Basis-APK, 160 MB, 15 DEX) existieren

```
/tiktok/v1/im/public/resource/audio_get      (String-Index 2437)
im/public/resource/refresh                    (String-Index 51069)
```

Beide haben **null** Code-Referenzen: kein `const-string` (0x1a) und kein
`const-string/jumbo` (0x1b) im gesamten DEX, keine Annotation, kein `VALUE_STRING` (0x1e) in
einem `encoded_array`/`static_values`. Die 17 rohen uleb-Treffer für 2437 liegen alle in
Code-Operanden (Umgebungsbytes zeigen Register/Index-Kontexte). Das Basis-APK enthält
überhaupt **keine** `lib/*.so` — die liegen im ABI-Split. Der Aufrufer steckt also in einem
Split-DEX oder nativ. Ohne Gerät nicht auffindbar.

### 21.5 Offen (braucht Gerät oder eine Gegenseite)

1. **Peer-Nachricht**: dieselbe Sprachnachricht von UID `7469077275818066977` senden und `f24`
   prüfen. Die bisherige Messung kann nur Self-Sends (die Sprachnachricht kam vom eigenen
   Konto). In allen gespeicherten Antworten (Init 107 KB, Chatseite 43 KB, Historie A–H)
   existiert genau **eine** Sprachnachricht, und die ist die eigene.
2. **`adb` + Splits**: `split_config.arm64_v8a.apk` (`.so`) und alle `split_df_*.apk` ziehen,
   nach `audio_get` bzw. `im/public/resource` greppen. Pro DEX den String-Index bestimmen und
   dann nach `0x1a`+uleb bzw. `0x1e`+uleb suchen.
3. Erst wenn die Parameter bekannt sind, gegen `req_*.bin`-Vorlagen testen (Artefakte siehe
   21.6). Bis dahin wird nichts geraten — zwei Fehler dieser Art haben heute Zeit gekostet.

### 21.6 Artefakte der Messung (in `/tmp/opencode/kcheck/t4/`)

| Datei | Inhalt |
|---|---|
| `req_video_kontrolle.bin`, `req_audio_a.bin`, `req_audio_b_uri.bin` | Cmd-2004-Bodies, echtes Schema (1173/1139/1173 B) |
| `req_211_voice.bin`, `req_211_video.bin` | Cmd 211, Schema `f1 conv_id, f2 type, f3 short_id, f4 msg_id` |
| `req_2200_voice.bin`, `req_2200_video.bin` | Cmd 2200, `f4 entries[{f1 msg_id, f2 index}]` |
| `ws2005.py` | WS-Sender: Handshake, `hi`, Envelope, Antwortauswertung |
| `frame705.hex` | Envelope eines echten, funktionierenden 705-Frames als Vorlage |
| `conv_fresh2.bin` (43286 B), `init_fresh2.bin` (107721 B) | frische Antworten vom 17.10., 21:15 Uhr |

### 21.7 Nebenbefund: der echte IM-WebSocket

Der Webclient nutzt **zwei** WebSockets; nur der zweite trägt IM-Cmds:

```
Frontier: wss://im-ws.tiktok.com/ws/v2?fpid=32&service=33554513&method=2&aid=1988
          &device_id=<uid>&access_key=e3f1768ea5a71a86163ec1faef712074&device_platform=web
IM:       wss://im-ws.tiktok.com/ws/v2?device_platform=web&version_code=fws_1.0.0
          &access_key=d549b8dd45cc1629aecbad47e3289eac&fpid=9&aid=1459&ttwid=<Cookie-Wert, bereits
          URL-kodiert>&xsack=1&xaack=1&xsqos=0[&Web-Sdk-Ms-Token=<msToken>]
```

Ablauf: HTTP-Upgrade (101), danach binär `hi` (2 B) — der Server antwortet mit `hi` — dann
Protobuf-Frames. **Doppelkodierung von `ttwid` ergibt 400**; der Cookie-Wert muss 1:1
übernommen werden (er enthält schon `%7C`).

Envelope: `f1` = Sequenz, `f2` = Zeitstempel ms, `f3` = 5, `f4` = 1, `f5` = Attr-Map
(Parameter + `X-Bogus`), `f7` = `"pb"`, `f8` = Payload. Der Payload ist dasselbe Request-Proto
wie beim HTTP-Aufruf; `f2` dort ist die **sequence_id** und entspricht dem `f1` der Hülle —
deshalb ist der Wert frei wählbar (die „Sub-Command-Falle" aus Abschnitt 3 ist damit erklärt;
`10012` bei Cmd 301 war nur die Sequenz des Webclients). Antwort: `f8` = Response
(`f1` cmd, `f2` seq, `f3` status, `f4` error_desc, `f6` Body).

## 22. Die Audio-URL einer Sprachnachricht (22.10.2026)

### 22.1 Fundort

Die vollständige, signierte URL steht **nicht in der Nachricht**, sondern in der
Download-Manager-DB der App:

```
/data/data/com.zhiliaoapp.musically/databases/downloader.db
  Tabelle downloader, Spalte url
```

Beispielzeile (Sprachnachricht `v266afgc000cdbaikrvog65o83c0ip60`):

```
https://video-useastred.tiktokv.eu/f276424af6f3ff2b117eac7245236a99/6afdcf00/
tos-useast2a-v-50863-euttp/oIgV3ZQafxE9wUDBABe1NmBMKiDmiqAJQpAEz4
?a=1180&bti=…&bt=203&ft=…&mime_type=video_mp4&rc=…&l=…&ply_type=3
&policy={"vm":3,"uid":"7469075901474161686"}&btag=200070000
```

Begleitend: `savePath` = `…/cache/voicemessage/download`, `name` = `<vid>.m4a`.

### 22.2 Proto-Struktur der Medien-URL

Aus dem Disassembly der App (`com.bytedance.im.core.proto`, un-obfuskiert benannt):

| Klasse | Feld-Nr | Feld |
|---|---|---|
| `UrlStruct` | **1** | `uri` — die fertige URL inkl. `x-expires`/`x-signature` |
| | 2 | `url_list` |
| | 3–5 | `width`, `height`, `data_size` |
| `MediaURLStruct` | **2** | `url_list` (Liste `UrlStruct`) |
| | 1, 3, 20–25 | `display_type`, `video_model`, `width`…`codec` |
| `MediaInfo` | **20** | `url_info_list` (Liste `MediaURLStruct`) |
| | 1–3 | `id`, `type`, `decrypt_key` |

`content_pb` (Feld 20 der Message-Proto) trägt je Nachrichtentyp ein eigenes Template-Feld;
Feld **14** = Voice-Template, darin die `MediaInfo`.

Wichtig: `repeated message` ist in Proto3 **gepackt** — die `UrlStruct`-Bytes liegen
konkateniert in einem Blob. Der Parser muss den Blob entweder als String (mit
eingebetteten Sub-Tags) oder als ByteArray auswerten.

### 22.3 Abruf der Datei

Die URL ist an den **signierten Request-Kontext** der App gebunden (TTNet-Header,
`X-Bogus`, TLB-Tag). Mit Session-Cookies allein antwortet der CDN **HTTP 403 „TLB"** —
auch vom Gerät selbst und mit korrektem User-Agent. Der Abruf muss also über den
App-eigenen Netzwerkstack erfolgen.

### 22.4 Zugriff auf /data/data ohne Root

`android:debuggable="true"` in das Manifest der installierten APK patchen →

```
adb shell run-as com.zhiliaoapp.musically ls cache/voicemessage/download/
```

Damit sind Cache, `downloader.db` und `shared_prefs/ttnetCookieStore.xml` (Session-Cookies)
lesbar. Der Test-Key liegt in `~/samsung-root/keys/mitm2.keystore` (Passwort `android`),
die gepatchten APKs in `~/samsung-root/apks/` (base + 57 Splits).

Die IM-Nachrichten-DB selbst (`encrypted_<uid>_im.db`, `biz_2_*_im.db`) ist mit
ByteDance-eigener Verschlüsselung gespeichert und ohne die laufende App nicht lesbar.

## 23. Woher die Voice-URL wirklich kommt (21.–22.10.2026)

### 23.1 Die Nachricht enthält KEINE URL

Vollständige Proto-Struktur der Voice-Nachricht (aus `conversation-antwort-*.bin`,
Nachricht `v266afgc000cdb9q9vnog65gi9rrr010`):

| Feld | Inhalt |
|---|---|
| f1 | `conversation_id` = `0:1:7469075901474161686:7469077275818066977` |
| f2 | `conversation_type` = 1 |
| f3 | `server_message_id` = `7697677595378271777` |
| f4 | create_time |
| f5 | `conversation_short_id` = `7696878946750939424` |
| f7 | `sender_uid` |
| f9 | Property-Items (`s:mode`, `s:client_message_id`, `source_aid`, `s:from_mt_sync`, `a:moderation_result`, `im_callback_status_code`) |
| f20 | Message-Template (`1Sf0`): f1 `audio_wave`, f101 `preview_hint`, f102 Text, f200 Fallback, f201 `resp_base` |
| f21 | `voice` (Template-Typ) |
| f24 | `MediaInfo` = { f1 vid, f2 = 3 (Audio), f3 `decrypt_key` (leer) } |
| f26 | { f7 { f1 = 7606252248728867329 } } |
| f29 | `US-EastRed` (CDN-Region) |

`MediaInfo.f20` (`url_info_list`) ist bei Voice **nicht vorhanden** — anders als bei
Bildern, die darüber fertige URLs mitbringen. Die URL entsteht serverseitig in einem
**zweiten Abruf**.

### 23.2 Die 403 „TLB" ist ein abgelaufenes Token, kein Signaturproblem

`IMVoiceMessageUtil` (Klassen `com.ss.android.ugc.aweme`) enthält die Log-Strings
`isResourceExpired: msgId=`, `parseExpirationTimeFromUrl: hex=`,
`Message resource expired by ttl: msgId=` und `URL expiration check: msgId=`.
Die App prüft die Gültigkeit der CDN-URL selbst; das Pfadsegment `6afdcf02` der URL ist
ein Hex-Formatter für Minuten:Sekunden (`IMVoiceMessageUtil.LJFF(long)`).

Beide in `downloader.db` gefundenen Voice-URLs tragen **dasselbe** `ft=`/`l=`
(`l=202610191614544CB96C80948828711E0F`, 19.10. 16:14) — ein serverseitig signiertes
Batch-Token mit TTL. Der Abruf am 20.10. liefert deshalb HTTP 403 „TLB".

### 23.3 Der richtige Endpunkt: `im/media_message/unmask`

Aus `TikTokImApi` (Basis-APK, `classes9.dex`):

```java
@POST("im/media_message/unmask")
void unmask(@Query("inbox_type") int inboxType,
            @Query("conversation_type") int conversationType,
            @Query("conversation_id") String conversationId,
            @Query("conversation_short_id") long conversationShortId,
            @Query("selected_server_message_id") long serverMessageId,
            @Query("scene") int scene,
            Callback<BaseResponse> cb);
```

Alle Werte stehen in der Nachricht selbst (siehe 23.1).

**Host:** `https://api-va.tiktokv.com/tiktok/v1/im/media_message/unmask`
(bestätigt: POST → HTTP 200 JSON; ein erfundener Pfad → `status_msg: "url doesn't match"`).

**Methode:** nur POST. GET auf denselben Pfad liefert `url doesn't match`.
Antwort ist **JSON**: `{"log_pb":{...},"status_code":8,"status_msg":"Anmeldung abgelaufen"}`
(bei gültiger Session `status_code:0`).

`im/public/resource/refresh` (POST + Protobuf) existiert ebenfalls, ist aber ausschließlich
für Sticker/Video/AiMoji: die Scene-Enum kennt nur `UNKNOWN=0`, `STICKERSET=1`,
`VIDEO2STICKER=2`, `SHAREVIDEO=3`, `AIMOJI=4`, `OTHERSTICKERS=5` — **keine Voice-Scene**.
Die Resource-Ids kommen dort aus Property-Keys mit Präfix `3:`.

### 23.4 Client-Umsetzung

`TikTokDmClient.mediaUrl(...)`: POST `im/media_message/unmask` mit
`inbox_type`, `conversation_type`, `conversation_id`, `conversation_short_id`,
`selected_server_message_id`, `scene` + den üblichen Web-Parametern; die Antwort wird
per Regex auf eine URL geprüft (`TikTokProto.parseAntwortUrl`).

Offen: die gültigen Werte für `inbox_type` und `scene` sowie die Frage, ob die
**Web-Session** (Cookie aus dem Tabslify-Login) auf `api-va.tiktokv.com` akzeptiert wird.
Beides klärt der Request-Mitschnitt der App (`retry_request`-Tabelle der IM-DB:
`req_cmd` + `req_body`).

### 23.5 Werkzeuge

- `~/samsung-root/phase1.sh` — zieht die Live-IM-DB (`biz_2_*_im.db` + WAL) und die
  Cookies vom A14, wertet `attchment`, `msg_property_new`, `message_kv`, `retry_request`,
  `msg_metadata`, `region_name` aus und testet die gefundenen CDN-URLs
- `~/samsung-root/phase1_analyse.py`, `~/samsung-root/phase1_unmask.py`

### 23.6 Mitschnitt 21./22.10.2026 (A14, PCAPdroid + TLS)

- Der CDN akzeptiert die Voice-URL **ohne App-Signatur**: `GET` auf die frische
  `video-useastred.tiktokv.eu`-URL liefert mit dem **Web-Cookie** `sessionid` (allein;
  auch `sessionid_ss`/`sid_tt`) `HTTP 200` und die m4a-Daten. Ohne Cookie, mit
  App-Cookie oder mit `ttwid`/`uid_tt`/`msToken` allein: `403` (TLB).
- Die URL selbst ist **nicht ableitbar**: Pfadsegment 1 ist ein server-issued Token
  (kein md5 der vid), `l`/`ft` sind batch- und zeitgebunden (`l=20261021194753…`).
  Der App-Download um 22:11 nutzte noch die 21:47er URL erfolgreich — die Sperre ist
  die Signatur/der Kontext, nicht die Ablaufzeit.
- Hosts der App (SNI im Mitschnitt): `p0-tiktok-dm-private-useastred.tiktokv.eu`
  (DM, 5×), `api16-normal-useastred.tiktokv.eu` (12×), `video-useastred.tiktokv.eu`
  (CDN), `frontier16-normal-useastred.tiktokv.eu` (WS). Der Web-Host `im-api.tiktok.com`
  hat keinen unmask-Pfad (`/v1/...` alle 404); `www.tiktok.com/api/im/*` liefert nur
  den Catch-all `{"status_msg":"url doesn't match"}`.
- Browser-Weg offen: `/messages` lädt sein DM-Bundle als eigene Chunks
  (`data-chunk="messages"`, 14 Dateien) — dort steht der Web-Aufruf für die Voice-URL.

### 23.7 Signierter unmask-Request funktioniert (01.11.2026, ohne Gerät)

Der DM-Call lässt sich mit dem **vorhandenen Client-Signer** (`TikTokSignatur`, rein JVM)
nachbauen — Stichprobe mit Web-Cookie, App-Params (aid 1233, `musical_ly`, googleplay) und
`x-gorgon`/`x-ladon`/`x-argus`:

| Host | Ergebnis |
|---|---|
| `api16-normal-useastred.tiktokv.eu` | **HTTP 200**, `status_code:0` (Pfad + Signatur gültig) |
| `api16-normal-c-useast1a.tiktokv.com` | HTTP 403 (anderer Edge/Region) |
| `p0-tiktok-dm-private-useastred.tiktokv.eu` | HTTP 403 TLB (auch signiert) |

Param-Matrix: `inbox_type=0` gültig; `inbox_type` 1/2 → `status_code:5 "Ungültige Parameter"`.
`scene` 0–3 gültig, ab 4 `Ungültige Parameter`. Eine **erfundene** `selected_server_message_id`
ergibt `status_code:5`, eine echte `status_code:0` — der Request ist damit inhaltlich korrekt.

**Die URL steht nicht in der Antwort.** Der App-Code (`MessageExtKt$updateUnmask$1` →
`X/C57070Yg9`, jadx) ruft
`unmask(conversation.inboxType, conversationType, conversationId, conversationShortId, msgId, 3, cb)`
auf, **ignoriert** das `BaseResponse` und setzt nur die Property `a:unmasked=1`. Der Response-Body
ist `{"log_pb":…,"status_code":0,"status_msg":""}` — ohne `data`. Auch ein anschließender
Conversation-Re-Fetch (`im-api/v1/message/get_by_conversation`) liefert für `f24` nur
`vid + type=3 + leer` — keine URL.

→ Die Medien-URL kommt **nachgelagert über den IM-Push** (Frontier-WS `im-ws.tiktok.com/ws/v2`)
als Message-Update mit `f24.f20`-Rollen-Einträgen. Der Client muss also: signiertes `unmask`
(scene=3, inbox_type der Conversation) senden, den Frontier-Push auswerten
(`medienAusF24` existiert) und die Datei mit dem Web-Cookie (`sessionid`, siehe 23.6) laden.

Werkzeuge (nicht im Repo): `/tmp/opencode/probe/run.sh`, `Probe.kt` (Signer+Matrix),
`conv.py` (Web-Conversation inkl. Voice-`server_message_id`/`vid`).

### 23.8 Client-Umsetzung (01.11.2026)

`TikTokDmClient.mediaUrl()` fragt jetzt **signiert** an:

- Host `https://api16-normal-useastred.tiktokv.eu/tiktok/v1/im/media_message/unmask`
- App-Params (`appParams`: aid 1233, `musical_ly`, `channel=googleplay`, `version 39.8.2`,
  `device_id`/`install_id`/`iid` aus der Web-Session) + `conversation_type`,
  `conversation_id`, `conversation_short_id`, `inbox_type=0`, `selected_server_message_id`,
  **`scene=3`** (Wert aus dem App-Code).
- Header aus `TikTokSignatur.signiere()` (`x-gorgon`, `x-ladon`, `x-argus`, `x-khronos`,
  `x-ss-req-ticket`, `x-tt-trace-id`), App-UA, `Content-Type: application/x-www-form-urlencoded`.
- Antwort wird geloggt (`Unmask msg=… status=… url=…`); `TikTokProto.parseAntwortUrl()`
  greift weiterhin, falls doch eine URL im Body steht.
- `TikTokProto.parseMessage()` übernimmt eine später gelieferte `f24.f20`-Play-URL jetzt auch
  für `voice` (`mediaUrl`/`mediaStream`/`mediaDuration`), nicht nur für Video.

Offen: Der Push, der die URL nach dem `unmask` liefert, ist noch nicht verifiziert (A14 ohne
App-Session, Web spielt keine Voice). Verifikation auf dem echten Gerät: Logcat-Zeile
`Unmask msg=… status=0` + `TikTokDump`-Frame `frontier/antwort` bzw. `frontier`-Log
„Frontier-Frame ohne bekannte Nutzlast".
