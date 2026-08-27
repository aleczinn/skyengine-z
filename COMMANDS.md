# Kommandos

## Allgemeine
```
/gamemode <survival|creative|spectator      | Ändert den Spielmodus des Spielers
/give <item> <amount>                       | Gibt dem Spieler das Item x mal ins Inventar
/dimension <name>                           | Teleportiert einen in die entsprechende Dimension
/tp <x> <y> <z>                             | Teleportiert einen zu den eingegebenen Koordinaten
/kill                                       | Tötet einen selbst und wiederbelebt einen am Welt-Spawn
/sethome                                    | Speichert die Stelle, welche z. B. das Zuhause sein kann
/home                                       | Teleportiert einen an die Stelle, welche man mit /sethome gesetzt hat
/setspawnpoint                              | Setzt den Welt-Spawn beim ausführen auf die Spieler-Koordinaten
```

## World Edit
```
//wand                                      | Gibt einem die Debug-Axt, welche für diverse WorldEdit Befehle benötigt wird
//pos1                                      | Setzt die Position 1 der Selektion auf die derzeitige Spielerposition
//pos2                                      | Setzt die Position 2 der Selektion auf die derzeitige Spielerposition
//hpos1                                     | Setzt die Position 1 der Selektion auf die Blockkoordinaten, auf welche man grade schaut
//hpos2                                     | Setzt die Position 2 der Selektion auf die Blockkoordinaten, auf welche man grade schaut
//set <block>                               | Füllt eine Selektion mit dem genannten Block vollständig
//replace
    //replace <block>                       | Ersetzt alle nicht Luft-Blöcke mit dem genannten Block
    //replace <from> <to>                   | Ersetzt alle Blöcke bei from mit den Blöcken bei to
    
//copy                                      | Kopiert alle Blöcke und Entitäten in der ausgewählten Selektion in ein Clipboard
//paste
    //paste -a
    //paste -s
//undo
//redo
//cut

//move
//expand <value>
//contract <value>
//stack <value>

//structure
    //structure list <?page>
    //structure load <name>
    //structure save <name>

```