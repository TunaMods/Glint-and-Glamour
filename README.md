# Glint & Glamour

NBT-driven per-item animated enchantment glints. Source lives on a branch per game version.

Formerly Custom Glints. The name changed in 1.7.0; the mod id is still `customglint`, so saves and NBT carry over untouched.

| MC | Loader | Branch | Maven (`mcmodsrepo`) | Latest api | Changelog |
|---|---|---|---|---|---|
| 1.20.1 | Forge 47.x | [`1.20.1`](https://github.com/TunaMods/Glint-and-Glamour/tree/1.20.1) | `.../Glint-and-Glamour/1.20.1/mcmodsrepo` | `1.9.0` | [changelog-1.20.1.txt](changelog-1.20.1.txt) |
| 1.21.1 | NeoForge 21.1.233 | [`1.21.1`](https://github.com/TunaMods/Glint-and-Glamour/tree/1.21.1) | `.../Glint-and-Glamour/1.21.1/mcmodsrepo` | `1.9.0` | [changelog-1.21.1.txt](changelog-1.21.1.txt) |
| 26.1.2 | NeoForge 26.1.2.76 | [`26.1.2`](https://github.com/TunaMods/Glint-and-Glamour/tree/26.1.2) | `.../Glint-and-Glamour/26.1.2/mcmodsrepo` | `1.9.0` | [changelog-26.1.2.txt](changelog-26.1.2.txt) |
| 26.2 | NeoForge 26.2.0.93 | [`26.2`](https://github.com/TunaMods/Glint-and-Glamour/tree/26.2) | `.../Glint-and-Glamour/26.2/mcmodsrepo` | `1.9.0` | [changelog-26.2.txt](changelog-26.2.txt) |
| 26.3 | NeoForge 26.3.0.99 | [`26.3`](https://github.com/TunaMods/Glint-and-Glamour/tree/26.3) | `.../Glint-and-Glamour/26.3/mcmodsrepo` | `1.9.0` | [changelog-26.3.txt](changelog-26.3.txt) |

Maven base: `https://raw.githubusercontent.com/TunaMods/Glint-and-Glamour/<branch>/mcmodsrepo`

The repo used to be `TunaMods/custom_glint`. GitHub redirects the old links, but point new builds at the url above.

## Artifacts

- `glint-and-glamour-api-<ver>.jar` (`customglint_api`) — rendering pipeline + Java API. Bundle this via jarJar.
- `Glint-and-Glamour-<ver>.jar` (`customglint` + `customglint_api`) — full standalone, api nested in `META-INF/jarjar/`.

Versions through 1.6.0 were published as `custom-glint-api`; 1.7.0 and later are `glint-and-glamour-api`. Old pins keep resolving.

## Bundling (jarJar)

Forge (1.20.1):

```gradle
repositories { maven { url = "https://raw.githubusercontent.com/TunaMods/Glint-and-Glamour/1.20.1/mcmodsrepo" } }
dependencies {
    compileOnly fg.deobf("net.tunamods.customglint:glint-and-glamour-api:1.9.0")
    runtimeOnly fg.deobf("net.tunamods.customglint:glint-and-glamour-api:1.9.0")
    jarJar(group: 'net.tunamods.customglint', name: 'glint-and-glamour-api', version: '[1.9.0,2.0)')
}
```

NeoForge (1.21.1 and 26.x). Swap `<branch>` for the branch in the table and `<api>` for its latest api:

```gradle
repositories { maven { url = "https://raw.githubusercontent.com/TunaMods/Glint-and-Glamour/<branch>/mcmodsrepo" } }
dependencies {
    jarJar(implementation("net.tunamods.customglint:glint-and-glamour-api")) {
        version {
            strictly "[<api>,2.0)"
            prefer "<api>"
        }
    }
}
```

Pin embedders to the latest version. Per-mod compat mixins (Ice and Fire, Epic Knights, Sophisticated Backpacks, ElytraSlot, Epic Fight, GeckoLib, Immersive Armors, Mekanism, Artifacts, First-person Model) ship only in the full jar, not the api.

Full dev docs (API surface, render hooks, auto-apply registries) are in each branch's `README.txt`.

MIT, attribution required.
