# LiveMap – Paper 26.2 Live-Webmap über GitHub Pages

Ein Paper-Plugin, das eine Draufsicht-Karte deiner Welten rendert (Vanilla-Kartenfarben) und automatisch
in den `gh-pages`-Branch dieses Repos pusht. GitHub Pages liefert die Karte (Leaflet) aus, inkl. Live-Spielerpositionen.

**Karte:** https://bastiankretschmann.github.io/minecraft-livemap/

## Installation
1. `LiveMap.jar` aus den [Releases](../../releases) in den `plugins/`-Ordner legen.
2. Auf GitHub ein **Fine-grained Personal Access Token** erstellen (nur dieses Repo, Permission *Contents: Read and write*).
3. Token in `plugins/LiveMap/config.yml` unter `github.token` eintragen (oder Umgebungsvariable `LIVEMAP_TOKEN`). Server neu starten.
4. Karte füllt sich rund um Spieler (`render-radius-chunks`) und wird alle `publish-interval-seconds` hochgeladen.

Befehle: `/livemap` (Status), `/livemap publish` (sofort hochladen) – Permission `livemap.admin`.

## Hinweise
- GitHub Pages braucht nach jedem Push ca. 1 Minute zum Deployen; die Karte ist also nahezu live, nicht in Echtzeit.
- Es werden nur Chunks gerendert, die geladen sind und in der Nähe von Spielern liegen.
- Jeder Upload ist ein einzelner Commit ohne Historie (Force-Push auf `gh-pages`), das Repo wächst nicht an.
- Das Repo muss öffentlich sein (GitHub Pages im Free-Plan).
- Den Token niemals committen.

## Build
GitHub Actions baut das JAR (Java 25, Gradle). Lokal: `gradle build` mit JDK 25.
