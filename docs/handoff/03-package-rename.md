# Handoff: rename the Java package to the studio namespace

Repo root: `C:\Users\ileti\OneDrive\Desktop\Yazılım Geliştirme\geysersmod`
Mod: Minecraft Forge 1.20.1, modId `fts_geology`, JDK 17.
Build with `./gradlew build --no-daemon` from the repo root.

**Run this only after the block-texture handoff is finished and committed.** The two touch
different files, but this one moves every source file on disk and any uncommitted work elsewhere
will be painful to merge.

## The job

The mod was originally a personal project called "Hydrothermal Geysers" and still carries that
history in its package name. It is now published by Jelada Studios, so:

```
com.pandabear.geysers   ->   com.jeladastudios.ftsgeology
```

46 Java files. Move them on disk to the matching directory layout, rewrite every `package` and
`import` statement, and update the two places outside Java that name the old package.

### Directory move

```
src/main/java/com/pandabear/geysers/**   ->   src/main/java/com/jeladastudios/ftsgeology/**
```

The sub-package structure stays exactly as it is: `block`, `blockentity`, `command`, `config`,
`eruption`, `quake`, `registry`, `tectonics`, `volcano`, `worldgen`, plus `GeysersMod.java` at the
top. Delete the now-empty `com/pandabear` directories afterwards.

Note that some files reference classes by fully-qualified name mid-code, not only in imports —
for example `com.pandabear.geysers.worldgen.TerrainProbe.groundY(...)` inside
`TectonicCommands.java`. A rename that only fixes `import` lines will miss these, so replace every
occurrence of the string `com.pandabear.geysers` throughout `src/`, not just the import block.

### Outside Java

`build.gradle`, line 7 — the last remaining occurrence of the old name outside Java:
```
group = 'com.pandabear.geysers'   ->   group = 'com.jeladastudios.ftsgeology'
```

The jar manifest attributes in the same file have already been corrected to
`FT's Geology` / `Jelada Studios`. Leave them as they are.

## Two stale names to fix while you are in there

These are leftovers from the old mod name and are unrelated to the package move, but they are the
same kind of cleanup and it is cheaper to do them in one pass:

1. `geyser_config.toml.sample` in the repo root should be renamed to `fts_geology.toml.sample`.
   The mod actually registers its config as `fts_geology.toml` (see `GeysersMod.java` line 50), so
   the sample file's name is simply wrong and misleads anyone who copies it.

2. `src/main/java/.../config/GeyserConfig.java` line 6 has a Javadoc line reading
   *"backed by `geyser_config.toml`"*. That filename no longer exists. Correct it to
   `fts_geology.toml`.

Leave the `GeyserConfig` **class name** alone. Renaming it is a much larger change with no
functional benefit, and it is not part of this task.

## What must NOT change — read this carefully

The package name is invisible to Minecraft. These identifiers are not, and changing any of them
silently breaks every existing world:

- **`modId` stays `fts_geology`.** It appears in `mods.toml` line 7 and as
  `GeysersMod.MODID` in Java. Do not touch either.
- **Registry names stay identical.** Every block and item id (`fts_geology:geyser_core` and so on)
  must be byte-for-byte what it is now. A changed registry name means the block vanishes from
  saved worlds and is replaced by air.
- **The config file name stays `fts_geology.toml`** — the string in the
  `registerConfig(...)` call in `GeysersMod.java`. Only the `.sample` file on disk gets renamed.
- **Translation keys stay identical.** Every key under
  `src/main/resources/assets/fts_geology/lang/` is correct as it stands. Do not rename, reorder,
  add or remove a single key, and do not reformat the file.
- **The `assets/fts_geology/` and `data/fts_geology/` resource paths stay.** Those directories are
  named after the modId, not the package.
- **`GeysersMod.java` keeps its class name.** It is referenced by the `@Mod` annotation and
  renaming the class is not needed for a package move.

If a find-and-replace tempts you to change `fts_geology` anywhere, stop: that string is the modId
and it is already correct everywhere it appears.

## How to check your work before reporting done

Run all of these and report the actual output, not a claim that you ran them:

1. `./gradlew build --no-daemon` exits 0.
2. `grep -rn "pandabear" src/ build.gradle` returns nothing.
3. `grep -rn "com\.pandabear" .` (excluding `build/` and `.git/`) returns nothing.
4. `find src/main/java/com/pandabear -type d` reports that the path no longer exists.
5. The file count matches: `find src/main/java/com/jeladastudios/ftsgeology -name '*.java' | wc -l`
   must print **46**.
6. `grep -c "fts_geology" src/main/resources/META-INF/mods.toml` is unchanged from before your
   change, and `git diff src/main/resources/assets/` shows **no** changes at all.

Check 6 is the important one. If the diff touches anything under `assets/`, you have broken
something — revert that part and report it rather than trying to patch over it.

## Do not

- Do not change any texture, model, blockstate or lang file.
- Do not change `docs/`.
- Do not "improve" any code you pass through. This is a rename, and a rename only. If you notice a
  genuine bug, write it down in your report and leave the code alone.
