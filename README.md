# Coloso Ancestral - Boss para Fabric 1.21.4

## Compilar con GitHub (sin instalar nada)
1. Crea un repositorio en GitHub y sube TODO el contenido de esta carpeta.
2. Pestana **Actions** -> workflow **build** -> espera a que termine.
3. Abajo en **Artifacts** descarga `bossmod-jar`, saca el `.jar` (el que NO dice `-sources`) y ponlo en `mods/` junto con **Fabric API**.

## Compilar en tu Mac
```
brew install gradle openjdk@21
gradle build
```
El jar queda en `build/libs/`.

## Probarlo
Modo Supervivencia, dificultad Normal o mas. Huevo en la pestana "Huevos generadores" o:
`/summon bossmod:boss`

## Balance
Todo esta al inicio de `BossEntity.java` (seccion CONFIGURACION): vida por fase, dano, velocidad, cooldowns,
% de inmunidad a flechas y ticks de escudo desactivado.

## Modelo con animaciones propias
Ahora usa el modelo del zombi x2 con textura propia (`textures/entity/boss.png`). Para un modelo de Blockbench
con animaciones de verdad, anade GeckoLib (compatible con 1.21.4) y cambia solo `BossRenderer`; la logica
de ataques no cambia.

## Modelos de Blockbench (carpeta `models/`)
Archivos fuente `.bbmodel` (no se empaquetan en el jar, son para editarlos en Blockbench):
- `esqueleto_oscuro`, `esqueleto_consumido`, `esqueleto_fantasma` (64x32)
- `mini_boss_oscuro` (256x128, 39 cubos)
`mini_boss_oscuro` aun no se usa en el juego.

## Esbirros (fase 2 y 3)
Los 3 esqueletos usan tus texturas (ya extraidas a `textures/entity/`) sobre el modelo de esqueleto vanilla,
porque tus .bbmodel tienen exactamente esa geometria.
- `bossmod:esqueleto_oscuro`: 40 de vida, golpe fuerte con empuje
- `bossmod:esqueleto_consumido`: 24 de vida, rapido, su golpe aplica Wither
- `bossmod:esqueleto_fantasma`: 16 de vida, parpadea junto al objetivo y lo ralentiza (solo fase 3 en el boss)
El boss los invoca con el ataque "Invocar esbirros": 3 por vez en fase 2 (max 6 vivos), 5 en fase 3 (max 10).
Siempre invoca al empezar una fase nueva. Desaparecen si el boss muere.
Para probarlos solos: `/summon bossmod:esqueleto_oscuro`.
Vida, dano y velocidad: `BossMod.java` (onInitialize). Cantidades: `MINION_CAP` y `MINION_BATCH` en `BossEntity.java`.
