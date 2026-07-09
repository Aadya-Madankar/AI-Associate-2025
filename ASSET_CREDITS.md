# Asset credits

## Avatar — `app/src/main/assets/nazim.glb`

This work is based on "Gavin Reed - Detroit Become Human"
(https://sketchfab.com/3d-models/gavin-reed-detroit-become-human-0161bd8ecbaa44cb9da40ba4f2f80aaf)
by qsardor (https://sketchfab.com/qsardor57913) licensed under CC-BY-4.0
(http://creativecommons.org/licenses/by/4.0/).

Rigged humanoid (Mixamo skeleton). Framed in `NazimRenderer` via explicit scale/position
constants because the Sketchfab/FBX export reports an unreliable bind-pose bounding box.
No facial blendshapes, so mouth lip-sync is disabled for this model.

### Transplanted `Walk` / `Run` animations
The model shipped with only an `idle` clip. `Walk` and `Run` were **transplanted onto Gavin's
skeleton** from the three.js **Soldier** model (`https://threejs.org/examples/models/gltf/Soldier.glb`,
a Mixamo animation set) — both use the standard Mixamo bone hierarchy, so each animation channel was
remapped bone-for-bone (root translation and Hips rotation dropped so he animates upright, in place).
Mixamo animations are royalty-free under Adobe's license. Resolver: `idle`→idle, WALKING→`Walk`,
RUNNING→`Run` (played when XENO roams and walks to tap points).

**Caveat (read before shipping):** the *model file* is CC-BY, but the depicted character
(Gavin Reed) is IP of *Detroit: Become Human* / Quantic Dream. This is a fan asset suitable
as a development placeholder; a commercial release should use an original or properly-licensed
character. Drop any rigged humanoid `.glb` (ideally with ARKit blendshapes for lip-sync) into
`app/src/main/assets/nazim.glb` and retune the three `MODEL_*` constants in `NazimRenderer.kt`.
