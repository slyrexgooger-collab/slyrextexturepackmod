# Texture Pack Builder (Fabric 1.21.11)

Client-Mod, mit der du dir aus mehreren Resource Packs dein eigenes Pack zusammenstellst – Textur für Textur.
Die Oberfläche ist auf **Deutsch und Englisch** (richtet sich nach der Spielsprache).

## Oberfläche
Im Hauptmenü oder im Ressourcenpaket-Menü oben links auf **Pack Builder** klicken. Es gibt drei Tabs:

### Texturen
- **Links:** Kategorien (Blöcke, Items, Kreaturen, Oberfläche, Partikel, Sonstiges) und Filter
  („Mehrere Varianten“, „Nur gewählte“, Sortierung nach Relevanz/Name/Varianten).
- **Mitte:** Suche und Textur-Raster. Grün umrandet = eigene Variante gewählt, gelbe Zahl = Anzahl Varianten.
  Mausrad blättert die Seiten.
- **Rechts:** Detail-Panel mit großer Vorschau (Schachbrett zeigt Transparenz). Darunter alle Varianten –
  anklicken wählt sie aus, „V“ = Vanilla. Beim Drüberfahren siehst du Größe, Animation und aus welchen Packs sie stammt.
- **Unten:** Name für dein Pack eingeben → **Pack bauen**.

### Packs
- **+ Pack hochladen …** öffnet die Dateiauswahl deines Systems (mehrere ZIPs möglich).
- Oder einfach **ZIP-Dateien bzw. Ordner ins Spielfenster ziehen** – funktioniert auf jedem Tab.
- Packs, die in einem Unterordner verpackt sind, werden automatisch richtig umgepackt.
- Pro Pack: Vorschaubild, Herkunft (Lokal / Hochgeladen / Modrinth), Anzahl eigener Texturen,
  **Alle übernehmen**, **Abwählen** und – bei hochgeladenen und heruntergeladenen Packs – **Löschen** (mit Rückfrage).

### Modrinth
- Live-Suche beim Tippen, Sortierung (Relevanz, Downloads, Neueste, Beliebt), Filter nach Auflösung (16x, 32x, …),
  Seiten, Projekt-Icons, Download mit einem Klick (SHA-1-geprüft).

## Suche
- Findet über den **Namen in der Spielsprache** („Eichenstamm“) und den **englischen Pfad** („oak_log“).
- Mehrere Wörter müssen alle passen: `eiche tür`, `oak door`.
- `@name` filtert nach Pack, z. B. `stein @faithful`.
- Umlaute und Akzente egal: `tur` findet „Tür“.
- Sortiert nach Relevanz: exakter Name > Wortanfang > enthalten > nur im Pfad.

## Duplikate
- Gleiche Textur mit identischen **Pixeln** in mehreren Packs → nur eine Variante (Vergleich über Bildinhalt).
- Texturen, die exakt Vanilla entsprechen → werden gar nicht angezeigt.
- Selbst gebaute Packs werden nicht wieder als Quelle eingelesen.

## Ordner
| Was | Wo |
|---|---|
| Hochgeladene Packs | `.minecraft/texturepackbuilder/imported` |
| Modrinth-Downloads | `.minecraft/texturepackbuilder/downloads` |
| Fertiges Pack | `.minecraft/resourcepacks/<Name>.zip` |
| Gespeicherte Auswahl | `.minecraft/config/texturepackbuilder.json` |

## Bauen
Voraussetzungen: JDK 21, Gradle 9.

```
gradle wrapper      # einmalig, erzeugt gradlew
./gradlew build     # Windows: gradlew.bat build
```
Die fertige Mod liegt in `build/libs/texturepackbuilder-1.0.0.jar` → in den `mods`-Ordner, zusammen mit der Fabric API.
Alternativ den Ordner in IntelliJ IDEA öffnen (Gradle-Projekt) und `runClient` starten.

## Lizenzen
Die meisten Texture Packs erlauben private Nutzung, aber kein Weiterverbreiten. Das gebaute Pack ist für dich selbst
gedacht – vor dem Teilen die Lizenzen der Quell-Packs prüfen (Liste steht in `credits.txt` im Pack).
