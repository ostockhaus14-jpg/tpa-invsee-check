# TPA Invsee Check (Fabric 1.21.11, Client)

Prüft bei eingehenden `/tpa`-Anfragen automatisch per `/invsee` das Inventar des Spielers auf wertvolle Items
und blendet bei einem Fund Buttons zum Annehmen/Ablehnen ein. Einstellungen über Mod Menu.

Build lokal: `./gradlew build --no-configuration-cache` -> `build/libs/tpa-invsee-check-1.0.0.jar`
Build auf GitHub: Actions -> letzter Lauf -> Artifact `tpa-invsee-check`.
Config: `.minecraft/config/tpa-invsee-check.json`
Benötigt: Fabric Loader, Fabric API, optional Mod Menu.
