package com.tabslify.tabs.school

import androidx.annotation.StringRes
import com.tabslify.R

enum class MaterialAiMode(
    val id: String,
    @StringRes val labelRes: Int,
    val title: String,
    val instruction: String,
    val userMessage: String
) {
    ERKLAEREN(
        id = "erklaeren",
        labelRes = R.string.material_modus_erklaeren,
        title = "Erklärung",
        userMessage = "Erkläre mir dieses Material.",
        instruction = """
            Erkläre das Quellmaterial didaktisch wertvoll nach folgendem Aufbau:

            1. **Der Anker (Einstieg & Relevanz)**
               - Starte mit einer einfachen, greifbaren Analogie oder einem praktischen Alltagsbeispiel, das das Kernthema sofort greifbar macht. Warum ist dieses Thema überhaupt spannend oder wichtig?

            2. **Die Kernbotschaft (Der rote Faden)**
               - Formuliere das Hauptziel oder die Kernaussage des Materials in einem einzigen, klaren Satz.

            3. **Schritt-für-Schritt-Erklärung (Didaktischer Aufbau)**
               - Gliedere das Thema in 2 bis 4 logische Abschnitte (gehe dabei vom Einfachen zum Komplexen über).
               - Verwende sprechende, motivierende Überschriften.
               - Erkläre jeden Fachbegriff sofort bei der ersten Nennung auf einfache und anschauliche Weise.
               - Falls Grafiken oder Diagramme im Quelltext beschrieben wurden: Erkläre deren didaktische Aussage in eigenen Worten.

            4. **Spickzettel & Aha-Momente (Kritische Punkte)**
               - Fasse die 3 bis 5 absolut kritischen Erkenntnisse prägnant zusammen.
               - Setze jeden dieser Punkte als Markdown-Callout.

            5. **Selbstcheck (Aktivierung des Gelernten)**
               - Beende den Text mit 2 kurzen, reflexiven Fragen (ohne direkt die Antwort zu verraten), mit denen die Lernenden selbst prüfen können, ob sie den Kern der Sache verstanden haben.
        """.trimIndent()
    ),
    ZUSAMMENFASSUNG(
        id = "zusammenfassung",
        labelRes = R.string.material_modus_zusammenfassung,
        title = "Zusammenfassung",
        userMessage = "Fasse dieses Material zusammen.",
        instruction = """
            Fasse das Quellmaterial kompakt zusammen:
            - Beginne mit einem Satz, der das Thema benennt.
            - Danach maximal 6 Bulletpoints mit den wichtigsten Fakten, Definitionen, Zahlen und Namen, je maximal ein Satz.
            - Verdichte statt zu wiederholen; keine Einleitung, keine Füllwörter.
            - Schließe mit einem Callout, der die eine Kernaussage des Materials festhält.
        """.trimIndent()
    ),
    KARTEIKARTEN(
        id = "karteikarten",
        labelRes = R.string.material_modus_karteikarten,
        title = "Karteikarten",
        userMessage = "Erstelle Karteikarten aus diesem Material.",
        instruction = """
            Erstelle aus dem Quellmaterial 8 bis 15 Karteikarten im Frage-Antwort-Format:
            - Jede Karte als eigener Abschnitt: **Frage:** … und darunter **Antwort:** …
            - Fragen so formulieren, dass sie zum aktiven Abrufen zwingen (keine Ja/Nein-Fragen).
            - Antworten knapp halten (maximal 2 Sätze), aber vollständig genug zum Selbstprüfen.
            - Decke Definitionen, Ursachen/Wirkungen und typische Anwendungen ab.
            - Erfinde keine Inhalte, die nicht im Material stehen.
        """.trimIndent()
    ),
    UEBUNGSAUFGABEN(
        id = "uebungsaufgaben",
        labelRes = R.string.material_modus_uebungsaufgaben,
        title = "Übungsaufgaben",
        userMessage = "Gib mir Übungsaufgaben zu diesem Material.",
        instruction = """
            Erstelle aus dem Quellmaterial 3 bis 5 Übungsaufgaben mit ansteigendem Schwierigkeitsgrad:
            - Nummeriere die Aufgaben und nenne jeweils das geforderte Niveau (leicht/mittel/schwer) und den Aufgabentyp (Reproduktion, Verständnis, Transfer).
            - Stelle die Aufgaben zuerst, die Lösungen sammelst du am Ende unter der Überschrift "## Lösungen".
            - Zu jeder Lösung gehört ein kurzer Lösungsweg, nicht nur das Ergebnis.
            - Beziehe dich ausschließlich auf das Quellmaterial; keine Aufgaben zu Inhalten, die dort nicht vorkommen.
        """.trimIndent()
    ),
    SPICKZETTEL(
        id = "spickzettel",
        labelRes = R.string.material_modus_spickzettel,
        title = "Spickzettel",
        userMessage = "Mach mir einen Spickzettel aus diesem Material.",
        instruction = """
            Destilliere das Quellmaterial zu einem Spickzettel für die letzte Minute vor der Prüfung:
            - Nur das Abrufbare: Definitionen, Formeln, Regeln, Zahlen, Merksätze.
            - Stichpunkte statt Sätze, maximal 12 Zeilen.
            - Gruppiere nach Thema mit kurzen Überschriften.
            - Hebe die 2 bis 3 häufigsten Fehlerquellen als Callout hervor.
            - Keine Erklärungen, keine Beispiele, keine Wiederholungen.
        """.trimIndent()
    ),
    GLIEDERUNG(
        id = "gliederung",
        labelRes = R.string.material_modus_gliederung,
        title = "Gliederung",
        userMessage = "Gib mir einen strukturierten Überblick über dieses Material.",
        instruction = """
            Erstelle eine hierarchische Gliederung des Quellmaterials:
            - Nutze verschachtelte Markdown-Listen (Thema → Unterthema → Detail).
            - Ergänze hinter jedem Punkt in einem Halbsatz, worum es dort konkret geht.
            - Markiere mit "→", wie die Abschnitte logisch aufeinander aufbauen.
            - Schließe mit einem kurzen Absatz "Worum es im Kern geht" (maximal 3 Sätze).
            - Übernimm nur Begriffe und Strukturen, die im Material tatsächlich vorkommen.
        """.trimIndent()
    )
}
