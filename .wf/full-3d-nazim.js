export const meta = {
  name: 'xeno-full-3d-nazim',
  description: 'Build the full animated-GLB Nazim renderer: research SceneView 2.3.3 API, then a state-driven animation player + viseme morph lip-sync + multi-asset/texture support + the export recipe doc',
  phases: [
    { title: 'Research', detail: 'SceneView/Filament 2.3.3 animation + morph-target API, from docs + the repo\'s working avatar code' },
    { title: 'Build', detail: 'renderer (clip playback + visemes), animation map, model config, NAZIM_ASSETS.md' },
    { title: 'Review', detail: 'compile-conformance + API-correctness per file' },
  ],
}

const CWD = '/Users/aadyamadankar/working/xeno-live'
const CH = `${CWD}/app/src/main/java/com/example/character`
const short = p => p.split('/').pop()

const FINDING = { type: 'object', additionalProperties: false, properties: { api: { type: 'string', description: 'the exact SceneView 2.3.3 / Filament classes + calls for: loading a GLB, listing+playing animation clips, and reading/setting morph-target (blendshape) weights' }, sources: { type: 'array', items: { type: 'string' } }, pitfalls: { type: 'array', items: { type: 'string' } } }, required: ['api'] }
const WRITTEN = { type: 'object', additionalProperties: false, properties: { writtenFiles: { type: 'array', items: { type: 'string' } }, notes: { type: 'string' } }, required: ['writtenFiles', 'notes'] }
const REVIEW = { type: 'object', additionalProperties: false, properties: { issues: { type: 'array', items: { type: 'object', additionalProperties: false, properties: { severity: { type: 'string', enum: ['high', 'medium', 'low'] }, problem: { type: 'string' }, fix: { type: 'string' } }, required: ['severity', 'problem', 'fix'] } } }, required: ['issues'] }

// ---- Phase 1: research the real SceneView/Filament animation API ------------
phase('Research')
const research = await parallel([
  () => agent(
    `Research the EXACT API for skeletal animation + morph targets (blendshapes) in io.github.sceneview:sceneview 2.3.3 (Filament-based) on Android. We need, with concrete class + method names and minimal code:
1. Load a .glb from app assets (a ModelNode / ModelLoader path) — confirm against how ${CWD}/app/src/main/java/com/example/avatar/AvatarView.kt and ${CH}/NazimRenderer.kt already load models.
2. Enumerate the animation clips in a loaded model and PLAY a named clip (looping), and cross-fade between clips. (AnimationPlayer / Animator / applyAnimation — find the real names for 2.3.3.)
3. Read + set MORPH TARGET (blendshape) weights per-frame by name or index (for viseme lip-sync) — the Filament RenderableManager morph-weights path SceneView exposes.
Use WebSearch + WebFetch on the sceneview-android GitHub (github.com/SceneView/sceneview-android), its releases/2.3.3 sources, and Filament docs. READ the repo's avatar/AvatarView.kt + LipSyncController.kt + character/NazimRenderer.kt first to match the already-working API surface. Return the concrete API, sources, and pitfalls.`,
    { schema: FINDING, phase: 'Research', label: 'research:sceneview-anim' }
  ),
  () => agent(
    `Write a concrete, accurate guide ${CWD}/NAZIM_ASSETS.md explaining how to take an Epic MetaHuman (the user has a .mhb = MetaHuman Creator DNA/parameters file) all the way to a mobile-ready animated GLB the Xeno Live app loads from assets/. Cover, step by step:
- .mhb is NOT a mesh — import it into MetaHuman Creator, then download the MetaHuman into Unreal Engine 5 (Quixel Bridge / MetaHuman plugin).
- In UE5: add the body+face animation sequences you want (idle, talking, listening, gesture). Export to glTF/GLB — via Unreal's glTF Exporter plugin OR FBX -> Blender -> glTF. MUST include: the skeletal mesh, the animation clips, the FACE morph targets / ARKit blendshapes (for visemes), and the materials/textures.
- MOBILE OPTIMIZATION (critical — a raw MetaHuman is far too heavy for a phone): pick a low LOD (LOD2/3), reduce tris, downsize textures to 1-2K, apply Draco/meshopt mesh compression + KTX2/Basis texture compression (gltf-transform / gltfpack). Target a GLB well under ~30-40 MB.
- ASSET LAYOUT in the app: a single self-contained app/src/main/assets/nazim.glb (textures + all animation clips embedded) is simplest; OR a base nazim.glb + extra clips as separate GLBs sharing the skeleton. Also note the easy interim: a portrait render at assets/nazim_portrait.png (already supported).
- HOW THE APP USES IT: NazimView -> NazimRenderer loads the GLB; NazimAnimationMap maps each NazimState (IDLE/LISTENING/THINKING/SPEAKING/ACTING) to an animation clip name; visemes are driven onto the face morph targets from the live audio amplitude. Tell the user which clip names + blendshape names to use (or to rename their clips to match) so it 'just works'.
Be accurate and practical; use WebSearch to verify the UE5 glTF export + gltf-transform steps. Return the file you wrote.`,
    { schema: WRITTEN, phase: 'Research', label: 'doc:nazim-assets' }
  )
])
const api = research[0]?.api || 'Match the SceneView API already used in avatar/AvatarView.kt and character/NazimRenderer.kt.'
log('Research done; building animated renderer against discovered API')

// ---- Phase 2: build the animated renderer + config -------------------------
const SHARED = `The app already has: NazimView.kt (fallback ladder GLB -> portrait PNG -> vector), NazimAssets.kt (MODEL_PATH=nazim.glb, hasModel/hasPortrait), NazimState (IDLE/LISTENING/THINKING/SPEAKING/ACTING/ERROR), NazimExpressionController, VisemeController, NazimPersona. DO NOT edit NazimView.kt, NazimAssets.kt, NazimPortraitAvatar.kt, NazimFallbackPortrait.kt — they are done. Match the SceneView 2.3.3 API exactly as the existing avatar/AvatarView.kt uses it. Everything must COMPILE; wrap all SceneView calls in try/catch and call the provided onFailure so NazimView falls back gracefully.

DISCOVERED SceneView/Filament animation + morph API:
${api}`

const TARGETS = [
  { f: `${CH}/NazimAnimationMap.kt`, g: 'object mapping NazimState -> ordered candidate animation clip names (first present in the GLB wins; sensible MetaHuman/Mixamo-style defaults like "idle","talking","listening","thinking","gesture"), plus the candidate viseme/jaw morph-target names ("jawOpen","mouthOpen","viseme_*","mouthFunnel"). Pure data + lookup helpers, no Android deps.' },
  { f: `${CH}/NazimModelConfig.kt`, g: 'asset paths + tunables for the 3D Nazim: the GLB path (NazimAssets.MODEL_PATH), an optional separate-animation-GLB folder name, viseme smoothing + max-jaw-open, target frame budget. Pure constants/data.' },
  { f: `${CH}/NazimRenderer.kt`, g: 'REWRITE: a @Composable NazimRenderer(state: NazimState, amplitude: Float, modifier: Modifier, onFailure: (Throwable)->Unit) that loads assets/nazim.glb via SceneView (embedded textures load automatically), plays the animation clip chosen by NazimAnimationMap.clipFor(state) (looping, cross-fade on state change), and every frame drives the face viseme morph-target weights from amplitude (via VisemeController) — all SceneView calls guarded, onFailure on any throw. Keep the exact public signature NazimView already calls: (state, amplitude, modifier, onFailure).' },
]

phase('Build')
await pipeline(
  TARGETS,
  t => agent(
    `Build/extend this file for the full animated 3D Nazim.\nTARGET: ${t.f}\nGOAL: ${t.g}\n${SHARED}\nFirst READ ${t.f} (if it exists), ${CH}/NazimRenderer.kt, ${CWD}/app/src/main/java/com/example/avatar/AvatarView.kt, ${CWD}/app/src/main/java/com/example/avatar/LipSyncController.kt, ${CH}/VisemeController.kt, ${CH}/NazimExpressionController.kt. Production-quality Kotlin/Compose, minSdk 24, must compile. Write the file.`,
    { schema: WRITTEN, phase: 'Build', label: `build:${short(t.f)}` }
  ).then(r => ({ t, written: (r && r.writtenFiles && r.writtenFiles[0]) || t.f })),
  async (built) => {
    if (!built) return null
    const rev = await agent(
      `Review ${built.written} (Xeno Live, Compose + SceneView 2.3.3). READ it + avatar/AvatarView.kt + character/NazimRenderer.kt + character/VisemeController.kt. Check: (a) it COMPILES (real SceneView/Filament 2.3.3 API, correct imports, NazimRenderer keeps the (state,amplitude,modifier,onFailure) signature NazimView calls), (b) all SceneView calls are try/catch-guarded with onFailure, (c) the animation-clip + morph-target logic is API-correct. Concrete issues + fixes; empty if good.\nDISCOVERED API:\n${api}`,
      { schema: REVIEW, phase: 'Review', label: `rev:${short(built.written)}` }
    )
    const issues = (rev?.issues || []).filter(i => i.severity !== 'low')
    if (issues.length) {
      await agent(`Fix ${built.written}: ${issues.map(i => i.problem + ' -> ' + i.fix).join('; ')}. READ it, Edit it, keep the signature + try/catch guards, keep it compiling. Don't touch other files.`, { phase: 'Review', label: `fix:${short(built.written)}` })
    }
    return built.written
  }
)
return { researched: !!research[0], doc: research[1]?.writtenFiles, built: TARGETS.map(t => short(t.f)) }
