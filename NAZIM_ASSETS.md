# Nazim Assets: From MetaHuman (`.mhb`) to a mobile-ready animated `nazim.glb`

This is the end-to-end, practical guide for turning an Epic **MetaHuman** into the asset the
Xeno Live Android app loads at `app/src/main/assets/nazim.glb` and renders as **Nazim**, the
single photoreal face of the app (SCOPE.md §2).

It is written against what the app code **actually does today** (`com.example.character.*`):

- `NazimView` probes for the GLB and falls back gracefully if it is missing.
- `NazimRenderer` loads `assets/nazim.glb` with SceneView/Filament, finds the face mesh by
  scanning renderables for a **`jawOpen`** morph target, and every frame drives a fixed set of
  **ARKit/MetaHuman blendshape names** as morph-target weights.
- Lip-sync (`VisemeController`) is driven from **live audio amplitude**; expression
  (`NazimExpressionController`) is driven from `NazimState`.

So the two things that make the GLB "just work" are:

1. The face mesh exposes glTF **morph targets** named with the ARKit blendshape names below
   (the renderer matches them **case-insensitively**, and requires at least `jawOpen`).
2. (Optional, for body/clip playback) Animation clips named to the convention in §6.

> TL;DR sizing target: a single self-contained `nazim.glb` under **~30–40 MB** (ideally
> 15–25 MB) after Draco/meshopt + KTX2 compression, low LOD, 1–2K textures.

---

## 0. What a `.mhb` is (and is NOT)

A `.mhb` is a **MetaHuman Creator preset / DNA + parameters file** — the *recipe* for a
MetaHuman (face shape, proportions, skin, hair, groom, clothing choices). **It is not a mesh,
not a rig, and not a texture set.** You cannot import a `.mhb` directly into Blender or read it
on-device. You must rehydrate it through Epic's pipeline first:

1. Bring the `.mhb` into **MetaHuman Creator** (the cloud/standalone MetaHuman app, or the
   MetaHuman plugin in UE 5.5+ where MetaHuman Creator now runs in-engine). This reconstructs
   the full character from the preset.
2. **Download / assemble** the MetaHuman into **Unreal Engine 5** via **Quixel Bridge** (older
   flow) or the **MetaHuman plugin** (current flow). This is what produces the real
   **Skeletal Meshes + skeleton + materials + textures + Rig Logic** you can actually export.

You only get a usable mesh **after** step 2. Everything below happens in UE5 (and optionally
Blender) on the downloaded MetaHuman, never on the `.mhb` directly.

---

## 1. Get the MetaHuman into UE5

1. Install **UE5** (5.3+ recommended; the in-engine glTF Exporter ships with the engine since
   5.1) and enable the **MetaHuman** plugin.
2. Open **Quixel Bridge** (Window → Quixel Bridge) or the **MetaHuman** panel, sign in, find
   your MetaHuman (the one your `.mhb` produced), pick a quality, and **Download** + **Add** it
   to the project.
3. You now have a `BP_<Name>` MetaHuman Blueprint plus the underlying assets:
   - **Face** skeletal mesh (its own skeleton; deformation driven by ~700+ **morph targets /
     blend shapes** at LOD0 via Rig Logic — this is where visemes/ARKit shapes live).
   - **Body** skeletal mesh (separate skeleton; bone-driven).
   - Materials + textures (skin, eyes, hair, clothing).

> Architecture note: in a MetaHuman the **face and body use separate skeletons** and evaluate
> together at runtime. The **face blendshapes are what the Xeno app needs** for lip-sync; the
> body skeleton is what carries idle/gesture animation clips.

---

## 2. Add the animation clips you want (idle, talking, listening, gesture)

The app distinguishes five expressive states (`NazimState`: `IDLE`, `LISTENING`, `THINKING`,
`SPEAKING`, `ACTING`, plus `ERROR`). Author or retarget one **looping body Animation Sequence**
per state-family:

| Mood / state          | Suggested clip          | Feel                                  |
|-----------------------|-------------------------|---------------------------------------|
| IDLE                  | `idle`                  | calm breathing loop                   |
| LISTENING             | `listening`             | attentive, slight lean/nod            |
| THINKING              | `thinking`              | pondering, head tilt                  |
| SPEAKING              | `talking`               | engaged, light hand gestures          |
| ACTING                | `acting` / `gesture`    | brisk, purposeful                     |

How to get these clips:
- Use Epic's MetaHuman/UE5 sample animations, Mixamo (retarget to the MetaHuman body skeleton
  via UE5 **IK Retargeter**), or your own mocap.
- Keep each clip **short and loopable** (1–4 s). Bake to an **Animation Sequence** on the
  MetaHuman body skeleton.
- For the **face**, you do **not** need to bake talking mouth animation into a clip — the app
  generates lip-sync at runtime from audio amplitude onto the face morph targets. You **do**
  need the **morph targets themselves to survive export** (§3/§4). Optionally add a subtle
  facial idle/blink clip, but the app already provides procedural blink + brow expression.

> Reality check on the current renderer: `NazimRenderer` today drives the **face morph targets**
> and a procedural **head-sway** on the root node every frame. It does **not yet** call a glTF
> animation player to play named body clips — that hook is the open task (#14, "Full animated 3D
> Nazim (GLB) support"). Embedding well-named clips now (§6) is exactly what lets that hook be a
> small, mechanical addition later, and the clips are harmless if unused. So: ship the clips,
> name them per §6, and the face/lip-sync works immediately regardless.

---

## 3. The hard part: keep the FACE morph targets / ARKit blendshapes

This is the single most common failure. The app's lip-sync and expression are **100% morph-target
driven**. If the exported GLB's face mesh has **no morph targets**, Nazim renders as a frozen
statue (the renderer logs `No 'jawOpen' morph target found ... facial animation disabled`).

Two things to know:

- MetaHuman per-vertex **shape animation (morph targets) is LOD0-only.** Lower LODs replace it
  with Rig Logic joint motion. So if you naively export a low LOD, you can **lose the visemes**.
- MetaHuman face shapes are **not** named like ARKit by default. The app expects **ARKit-style
  names** (`jawOpen`, `mouthFunnel`, `mouthPucker`, `eyeBlinkLeft`, …; full list in §7).

You have two reliable ways to ship a face that carries **ARKit-named morph targets**:

**Option A — Bake ARKit blendshapes (recommended for mobile).**
Use a MetaHuman → ARKit blendshape solution so the exported head has the **52 ARKit
blendshapes** as named morph targets:
- Epic's **Live Link Face / ARKit mapping** assets, or community tools that generate the 52
  ARKit poses on the MetaHuman head, or
- Author the 52 ARKit poses (or at least the subset in §7) as morph targets on a decimated head
  in Blender.
This gives you a **light head** (a few K–tens of K tris) that still has every viseme/expression
shape the app needs. This is the best fit for a phone.

**Option B — Export LOD0 face morphs and rename.**
Export the LOD0 face (which keeps the full morph set), then in Blender **rename** the relevant
shape keys to the ARKit names in §7. Heavier, but works. Decimate carefully so shape keys stay
valid (decimate the base mesh, then transfer/keep shape keys; Blender's plain Decimate modifier
destroys shape keys, so use a workflow that preserves them, e.g. export low-poly base + re-add
shapes, or a shape-key-aware reduction).

Whichever you pick: **at minimum you must end up with a face morph target named `jawOpen`** (the
renderer's gate), and ideally the full §7 set for good lip-sync + expression.

---

## 4. Export to glTF / GLB out of UE5

You have two routes. Either is fine; both must carry **skeletal mesh + skin weights + animation
clips + face morph targets + materials/textures**.

### Route 1 — Unreal's built-in glTF Exporter (fastest)
The glTF Exporter ships with UE5 (5.1+). Enable the **glTF Exporter** plugin if needed.

1. In the Content Browser, right-click the asset (Skeletal Mesh, or the combined character) →
   **Asset Actions → Export…**, choose **.glb** (binary). You can also export an
   **Animation Sequence** the same way.
2. In the **glTF Export Options** dialog, make sure these are ON:
   - **Mesh → Export Vertex Skin Weights** (REQUIRED — without skin weights animation/skeletal
     data won't be usable).
   - **Export Morph Targets / Blend Shapes** (REQUIRED — this is what carries the visemes).
   - **Materials** + **Textures** (so skin/eyes ship in the GLB).
   - Animation export enabled for the clips you want.
3. To get **multiple clips in one file**, the cleanest path is usually FBX → Blender (Route 2),
   because the UE glTF exporter is oriented around exporting one animation/sequence at a time and
   its Level Sequence support is limited (transform tracks, absolute space). For a **single base
   pose + face morphs**, Route 1 is great; for **several named body clips in one GLB**, prefer
   Route 2.

> Caveat: MetaHuman materials are layered/complex; the glTF exporter bakes them down to glTF PBR.
> Expect to **bake skin/eye textures to simple baseColor/normal/ORM** maps for a clean,
> mobile-friendly result (§5). Hair grooms (strand hair) do **not** export well to glTF — switch
> to **hair cards** or a simple hair mesh before exporting.

### Route 2 — FBX → Blender → glTF (most control, best for multiple clips)
1. In UE5, export the **Skeletal Mesh** as **FBX** (enable morph targets / skin weights) and
   export each **Animation Sequence** as FBX (or all into one FBX with multiple takes).
2. In **Blender** (4.x): import the FBX(s). Verify the **shape keys** (= morph targets) are
   present on the face and **rename them to the ARKit names** in §7 if needed. Combine all
   actions; push each clip down as an NLA/action so the glTF exporter writes them as separate
   **animation clips**.
3. Reduce + clean (decimate body, simplify hair to cards, drop unused bones/UVs).
4. **File → Export → glTF 2.0 (.glb)** with:
   - **Include → Selected Objects** (your character),
   - **Mesh → Apply Modifiers** OFF if it would nuke shape keys (or use shape-key-safe tools),
   - **Animation → Export** ON, **Shape Keys** ON, **Skinning** ON,
   - **Material → Export**, images packed.

After either route you have a **fat, uncompressed** `.glb` (often 80–300+ MB). That is **far too
heavy for a phone**. Do not ship it. Go to §5.

---

## 5. Mobile optimization (critical — a raw MetaHuman is too heavy for a phone)

Goal: a single `nazim.glb` **well under ~30–40 MB** (aim 15–25 MB), 60 fps on a mid-range phone.
Do these, roughly in order. Both tool families work; pick one. `gltf-transform` (the
`@gltf-transform/cli`) is the most flexible; `gltfpack` is the simplest one-shot.

Install:

```bash
npm install -g @gltf-transform/cli   # gives the `gltf-transform` CLI
npm install -g gltfpack               # or: brew install gltfpack
# KTX2/Basis needs the KTX toolkit (toktx) available for gltf-transform's ktx step
```

### 5a. Pick a LOW LOD / reduce triangles
- **Body**: use a low LOD (body has 4 LODs; LOD2/LOD3 is fine on a phone).
- **Face**: remember morphs are **LOD0-only**. So either (a) keep a **higher-LOD/light ARKit
  head** that still has the §7 shape keys (Option A in §3), or (b) decimate with a
  **shape-key-preserving** workflow. Target a **combined character around ~30–80K tris**.
- Drop strand hair → **hair cards**. Drop unseen geometry (interior mouth/teeth can stay; the
  app shows head + upper body).

### 5b. Downsize textures to 1–2K
A MetaHuman ships 4K/8K skin maps. For a phone, **1024–2048** is plenty:

```bash
gltf-transform resize in.glb step1.glb --width 2048 --height 2048
# or per-slot; baseColor 2K, normal/ORM 1K is a good split
```

### 5c. Mesh compression (Draco or meshopt) + texture compression (KTX2/Basis)

**Easiest one-shot with gltf-transform `optimize`** (does dedup/prune/weld + Draco + KTX2):

```bash
gltf-transform optimize step1.glb nazim.glb \
  --compress draco \
  --texture-compress ktx2 \
  --texture-size 2048
```

**Or build the pipeline explicitly** (more control; meshopt also compresses **morph targets** and
animation keyframes, which Draco does not):

```bash
# meshopt geometry+morph+animation compression
gltf-transform meshopt step1.glb step2.glb --level high
# KTX2 / Basis ETC1S for color, UASTC for normals
gltf-transform etc1s step2.glb step3.glb           # small, lossy color
# (or `gltf-transform uastc ... --level 4 --rdo --rdo-lambda 4 --zstd 18` for higher quality)
gltf-transform prune step3.glb nazim.glb           # drop unused data
```

> Use **meshopt** (not Draco) if you want **morph targets and animation** compressed too — Draco
> only handles base geometry. For a character with blendshapes + clips, meshopt is the better
> default. SceneView/Filament supports `KHR_draco_mesh_compression`,
> `EXT_meshopt_compression`, and `KHR_texture_basisu` (KTX2), so either path loads on-device.

**Equivalent gltfpack one-liner:**

```bash
gltfpack -i step1.glb -o nazim.glb -cc -tc -tq 8
#   -cc  meshopt geometry compression (EXT_meshopt_compression)
#   -tc  convert textures to KTX2 + Basis (ETC1S) (KHR_texture_basisu)
#   -tq  texture quality 1..100 (8 ≈ aggressive/small; raise for quality)
```

### 5d. Verify
```bash
gltf-transform inspect nazim.glb     # check tris, texture sizes, morph targets, animations
gltf-transform validate nazim.glb    # must be valid glTF 2.0
ls -lh nazim.glb                      # confirm it's under ~30–40 MB
```
In `inspect`, confirm the face mesh **lists morph targets** including `jawOpen` (and that their
names match §7), and that your **animation clips are present and named** per §6. If morph targets
vanished, you over-decimated or the exporter dropped them — go back to §3.

---

## 6. How the app uses the asset (and the names that make it "just work")

### 6a. The fallback ladder (so the screen is never blank)
`NazimView` chooses, in order:
1. **`assets/nazim.glb`** present → `NazimRenderer` (full 3D, lip-sync, expression). Preferred.
2. else **`assets/nazim_portrait.png`** present → `NazimPortraitAvatar` (animated photoreal
   portrait). **This is the easy interim**: render a single front portrait of your MetaHuman in
   UE5 and drop it at `assets/nazim_portrait.png` — already fully supported, no 3D needed.
3. else procedural vector portrait (`NazimFallbackPortrait`).

So you can ship a **portrait today** and the **GLB later** with zero code changes.

### 6b. The render path
`NazimRenderer` (SceneView 2.3.3 / Filament):
- `modelLoader.createModelInstance("nazim.glb")` → `ModelNode`.
- `resolveNazimFaceMorph(...)` scans every renderable entity for one whose morph-target name
  list contains **`jawopen`** (lowercased compare). That entity becomes the face binding.
- Every frame it merges two weight maps onto that entity's morph targets via Filament
  `setMorphWeights`:
  - **`VisemeController`** — mouth/jaw from live **audio amplitude** (`amplitude` 0..1 from the
    Gemini Live stream).
  - **`NazimExpressionController`** — brows/eyes/cheeks/blink from **`NazimState`**.
- It also applies a subtle procedural **head-sway** to the root node.

### 6c. Blendshape (morph target) names — REQUIRED, the load-bearing contract
The renderer matches these keys **case-insensitively** against your morph target names. Name your
face shape keys **exactly these** (ARKit naming). At minimum ship `jawOpen`; ship the full set
for good lip-sync + expression.

**Visemes / mouth (VisemeController):**
```
jawOpen        (REQUIRED — also the gate that enables the whole face)
mouthOpen
mouthFunnel
mouthPucker
mouthSmileLeft
mouthSmileRight
```
**Expression (NazimExpressionController):**
```
eyeBlinkLeft   eyeBlinkRight
browInnerUp
browOuterUpLeft  browOuterUpRight
browDownLeft     browDownRight
eyeWideLeft      eyeWideRight
cheekSquintLeft  cheekSquintRight
mouthSmileLeft   mouthSmileRight
mouthFrownLeft   mouthFrownRight
```
These are standard **ARKit 52** names, so if you bake ARKit blendshapes (§3 Option A) they line
up automatically. Any extra ARKit shapes you include are harmless (the renderer only reads the
ones above). Missing ones simply don't animate.

> Visemes are driven by **amplitude**, not phonemes, today: louder audio → wider `jawOpen` +
> `mouthOpen` (with `mouthFunnel` on loud vowels). `VisemeController` already has a
> phoneme→viseme hook (`setViseme(AA/OO/EE/MBP/FV)`) for a future upgrade; the same six mouth
> shapes above cover it. So you only need those six mouth blendshapes for great lip-sync.

### 6d. Animation clip names — convention for body/gesture clips
The renderer does **not yet** play named glTF animation clips (that's task #14), but author and
name them now so enabling playback is trivial and matches `NazimState` /
`NazimExpressionController.IdleAnimation`. Use these clip names in your GLB:

```
idle        → NazimState.IDLE        (IdleAnimation.BREATHE)
listening   → NazimState.LISTENING   (IdleAnimation.ALERT)
thinking    → NazimState.THINKING    (IdleAnimation.PONDER)
talking     → NazimState.SPEAKING    (IdleAnimation.ENGAGED)
acting      → NazimState.ACTING      (IdleAnimation.BUSY)
```
If your clips are named differently, just **rename them** (in Blender or with
`gltf-transform`) to these. One looping clip per state, on the body skeleton. The face is always
driven by the morph-target controllers on top of whatever body clip plays, so mouth/expression
keep working regardless of the body animation.

---

## 7. Asset layout in the app

The app loads from `app/src/main/assets/`. Two supported layouts:

**Layout A — single self-contained GLB (simplest, recommended):**
```
app/src/main/assets/
  nazim.glb            # skeletal mesh + skin + ALL named clips + face morph targets + textures (KTX2)
```
Everything embedded; one file; what `NazimAssets.MODEL_PATH = "nazim.glb"` loads. Do this.

**Layout B — base + extra clips (advanced, only if clips bloat one file):**
```
app/src/main/assets/
  nazim.glb            # base mesh + skeleton + face morphs + idle clip + textures
  nazim_talking.glb    # extra clip(s), SAME skeleton/bone names, geometry/textures stripped
  nazim_acting.glb
```
Strip mesh/texture from the clip-only GLBs (`gltf-transform` can keep just animation + skeleton)
so they're tiny and share the base skeleton. Requires extra loader code to merge clips onto the
base instance — only worth it if a single GLB gets too large.

**Interim portrait (zero 3D):**
```
app/src/main/assets/
  nazim_portrait.png   # a front portrait render of your MetaHuman from UE5
```
Already supported by `NazimPortraitAvatar` via `NazimAssets.PORTRAIT_ASSET`. Ship this first; add
`nazim.glb` whenever it's ready.

---

## 8. Quick checklist

- [ ] `.mhb` → MetaHuman Creator → **downloaded into UE5** (Quixel Bridge / MetaHuman plugin).
- [ ] Body **animation clips** authored/retargeted, named `idle/listening/thinking/talking/acting`.
- [ ] Face carries **ARKit morph targets** (§7), **including `jawOpen`** — preferably a baked
      light ARKit head so it survives mobile decimation.
- [ ] Hair → **cards** (no strand groom). Materials baked to simple PBR.
- [ ] Exported `.glb` with **skin weights + morph targets + clips + textures** (UE glTF Exporter
      or FBX→Blender→glTF).
- [ ] Optimized: low LOD, 1–2K textures, **meshopt (or Draco)** + **KTX2/Basis**; verified under
      **~30–40 MB** with `gltf-transform inspect/validate`.
- [ ] Dropped at `app/src/main/assets/nazim.glb`. (Optional interim:
      `app/src/main/assets/nazim_portrait.png`.)
- [ ] Run the app: Nazim loads, blinks/expresses by state, and lip-syncs to audio. If the face is
      frozen, the GLB lost its morph targets — recheck §3/§5d.

---

## Sources (verified)

- [Exporting Unreal Engine Content to glTF — UE5 docs](https://dev.epicgames.com/documentation/unreal-engine/exporting-unreal-engine-content-to-gltf)
- [How the glTF Exporter Handles Unreal Engine Content — UE5 docs](https://dev.epicgames.com/documentation/unreal-engine/how-the-gltf-exporter-handles-unreal-engine-content)
- [Platform Support and LOD Specifications for MetaHumans — MetaHuman docs](https://dev.epicgames.com/documentation/en-us/metahuman/platform-support-and-lod-specifications-for-metahumans)
- [Controlling MetaHuman Levels of Detail (LODs) — MetaHuman docs](https://dev.epicgames.com/documentation/metahuman/controlling-metahuman-levels-of-detail-lods)
- [glTF Transform — CLI reference](https://gltf-transform.dev/cli)
- [@gltf-transform/cli — npm](https://www.npmjs.com/package/@gltf-transform/cli)
- [gltfpack — meshoptimizer](https://meshoptimizer.org/gltf/)
- [gltfpack — npm](https://www.npmjs.com/package/gltfpack)
- [Export MetaHuman to Blender (morph target handling)](https://yelzkizi.org/export-metahuman-to-blender/)
