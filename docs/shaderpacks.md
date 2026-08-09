# Native shader packs

SkyEngine shader packs are engine-native render-pipeline packages. They deliberately do not
pretend to be Iris packs: the manifest names semantic engine stages, while GLSL files may use
relative `#include "..."` directives. This keeps the hot path free of runtime discovery and
allows later terrain, water and shadow stages to be added without changing the pack loader.

External packs live in `%APPDATA%/.skyengine/shaderpacks/<id>/`. The selected pack is stored in
`%APPDATA%/.skyengine/config/shaders.json`. Pressing **F10** parses all declared files, compiles
every participating stage and loads its data textures. Only after every step succeeds are all
live programs swapped at the next frame boundary; otherwise the previous pack remains active.

Version 1 of `pack.json` supports:

```json
{
  "schema": 1,
  "id": "example",
  "name": "Example",
  "programs": {
    "sky_vertex": "shaders/sky.vert",
    "sky_fragment": "shaders/sky.frag",
    "bloom_downsample": "shaders/bloom_downsample.frag",
    "bloom_blur": "shaders/bloom_blur.frag",
    "bloom_upsample": "shaders/bloom_upsample.frag",
    "bloom_composite": "shaders/bloom_composite.frag",
    "color_grading": "shaders/color_grading.frag"
  },
  "textures": {
    "atmosphere_scattering": "textures/scattering.dat",
    "moon_noise": "textures/noise.png"
  },
  "post": ["bloom", "color_grading"]
}
```

Paths are confined to the pack directory. The current atmosphere volume contract is raw
`RGB16F`, `32 x 64 x 32`; it is uploaded once per activation. Scene and bloom targets remain
`RGBA16F`, and the final pack stage performs the HDR-to-display transform before the GUI.

The built-in `photon` pack adapts Photon Shaders by SixthSurge at source revision
`15458c0937f8647c37eb6a501bef5eb3bf3da31b`. See
`THIRD_PARTY_LICENSES/PHOTON_SHADERS_LICENSE.txt`.
