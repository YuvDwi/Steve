# Steve Plan Dashboard (Vite + React + Three.js)

External HTML plan UI for the Steve Minecraft mod. Subscribes to
`http://127.0.0.1:8765/events` (SSE) and renders a Three.js preview of the
plan's blocks alongside the timeline / materials / approve-halt controls.

## Run

```bash
cd web
npm install
npm run dev
# Vite dev server → http://localhost:5173
```

In Minecraft (with the Steve mod installed), start the embedded dashboard
server first:

```
/steve dashboard
```

The mod prints `Plan dashboard: http://127.0.0.1:8765/`. Vite proxies
`/events` and `/command` to it, so the React app can call them as if they
were same-origin.

## Build

```bash
npm run build
# Static bundle in web/dist — drop on any static host pointed at the mod.
```

## CORS

The mod sets `Access-Control-Allow-Origin: http://localhost:5173` on
`/events` and `/command` so the Vite origin can talk to the mod directly
without the Vite proxy. The proxy in `vite.config.ts` is the fallback if
you change the port.

## Data flow

```
PlanBuildAction
   │ publish(PlanCreatedEvent / PlanDesignReadyEvent / PlanPhaseChangedEvent / PlanApprovedEvent / PlanHaltedEvent)
   ▼
SteveMod.getPlanEventBus()        (com.steve.ai.event.EventBus)
   │ subscribe + forward
   ▼
PlanDashboardServer (HTTP, 127.0.0.1:8765)
   │ GET /events  (text/event-stream)
   │ POST /command {action, projectId}
   ▼
Browser (this Vite app)
   ├── React UI: phase badge / materials / timeline / Approve / Halt
   └── Three.js: InstancedMesh per blockId, grid floor, orbit controls
```

## File map

```
web/
├── index.html               Vite entry
├── package.json
├── tsconfig.json
├── vite.config.ts           Dev-server + /events, /command proxy
├── src/
│   ├── main.tsx             ReactDOM.createRoot
│   ├── App.tsx              Layout: 3D canvas + side panel
│   ├── styles.css           Dark glass theme (matches xrblocks/demos)
│   ├── components/
│   │   └── Structure3D.tsx  Three.js InstancedMesh renderer + orbit controls
│   ├── hooks/
│   │   └── usePlanStore.ts  SSE subscription + reducer
│   └── lib/
│       └── types.ts         Wire types matching PlanEventJson output
└── README.md
```

## Block colors

`Structure3D.tsx` derives a deterministic HSL color from each block id
(`minecraft:oak_planks` → stable green-ish, etc.). Replace `colorForBlockId`
with a real Minecraft block → color table when needed.
