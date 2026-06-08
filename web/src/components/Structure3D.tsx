import {useEffect, useMemo, useRef} from 'react';
import * as THREE from 'three';
import type {BlockEntry} from '../lib/types';

interface Props {
  blocks: BlockEntry[];
}

/** Deterministic, readable color from a block-id string. Used as a placeholder
 *  until we have a real block→color registry. */
function colorForBlockId(id: string): THREE.Color {
  // hash → HSL → RGB. Avoid black for visibility.
  let h = 0;
  for (let i = 0; i < id.length; i++) {
    h = (h * 31 + id.charCodeAt(i)) >>> 0;
  }
  const hue = (h % 360) / 360;
  const sat = 0.55;
  const lig = 0.55;
  return new THREE.Color().setHSL(hue, sat, lig);
}

/** Render the build design as a Three.js InstancedMesh per blockId group.
 *  Re-builds the scene whenever the block list changes (cheap, ~O(N)). */
export function Structure3D({blocks}: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const sceneRef = useRef<{
    scene: THREE.Scene;
    camera: THREE.PerspectiveCamera;
    renderer: THREE.WebGLRenderer;
    controls: ReturnType<typeof makeOrbitControls>;
    cleanup: () => void;
  } | null>(null);

  // Group blocks by blockId so each material/geometry is shared via InstancedMesh.
  const groups = useMemo(() => {
    const map = new Map<string, BlockEntry[]>();
    for (const b of blocks) {
      let arr = map.get(b.blockId);
      if (!arr) { arr = []; map.set(b.blockId, arr); }
      arr.push(b);
    }
    return Array.from(map.entries()).map(([blockId, list]) => ({blockId, list}));
  }, [blocks]);

  // One-time scene setup.
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const scene = new THREE.Scene();
    scene.background = new THREE.Color(0x101418);

    const camera = new THREE.PerspectiveCamera(45, 1, 0.1, 5000);
    camera.position.set(40, 40, 40);
    camera.lookAt(0, 0, 0);

    const renderer = new THREE.WebGLRenderer({antialias: true});
    renderer.setPixelRatio(window.devicePixelRatio);
    container.appendChild(renderer.domElement);

    // Lights.
    scene.add(new THREE.AmbientLight(0xffffff, 0.6));
    const sun = new THREE.DirectionalLight(0xffffff, 0.8);
    sun.position.set(50, 80, 30);
    scene.add(sun);

    // Grid + axes for orientation.
    const grid = new THREE.GridHelper(200, 40, 0x444444, 0x222222);
    (grid.material as THREE.Material).transparent = true;
    (grid.material as THREE.Material).opacity = 0.4;
    scene.add(grid);

    // Minimal orbit controls (drag to rotate, wheel to zoom). Avoids pulling
    // in three/addons via dynamic import for a single component.
    const controls = makeOrbitControls(camera, renderer.domElement);

    const resize = () => {
      const w = container.clientWidth;
      const h = container.clientHeight;
      renderer.setSize(w, h, false);
      camera.aspect = w / h;
      camera.updateProjectionMatrix();
    };
    resize();
    const ro = new ResizeObserver(resize);
    ro.observe(container);

    let raf = 0;
    const tick = () => {
      raf = requestAnimationFrame(tick);
      controls.update();
      renderer.render(scene, camera);
    };
    tick();

    sceneRef.current = {
      scene, camera, renderer, controls,
      cleanup: () => {
        cancelAnimationFrame(raf);
        ro.disconnect();
        controls.dispose();
        renderer.dispose();
        renderer.domElement.remove();
      },
    };
    return () => {
      sceneRef.current?.cleanup();
      sceneRef.current = null;
    };
  }, []);

  // Rebuild instanced meshes whenever the block list changes.
  useEffect(() => {
    const ctx = sceneRef.current;
    if (!ctx) return;
    const {scene} = ctx;

    // Drop any previous block meshes.
    for (const child of [...scene.children]) {
      if ((child as THREE.Object3D & {__steveBlock?: boolean}).__steveBlock) {
        scene.remove(child);
        (child as THREE.Mesh).geometry?.dispose();
        const mat = (child as THREE.Mesh).material as THREE.Material | THREE.Material[];
        if (Array.isArray(mat)) mat.forEach((m) => m.dispose());
        else mat?.dispose();
      }
    }

    if (blocks.length === 0) return;

    // Diag: print the computed center + a few sample blocks so we can
    // verify recentering actually aligns the structure on (0, 0, 0).
    console.log('[Structure3D] blocks.length=', blocks.length, 'sample=', blocks.slice(0, 3));

    // Bounding box used to size the camera fit. The world origin (0, 0, 0)
    // is the screen-center anchor — the camera always looks at it, and the
    // structure is offset by its bounding-box center so its midpoint sits
    // exactly at (0, 0, 0).
    let minX = Infinity, minY = Infinity, minZ = Infinity;
    let maxX = -Infinity, maxY = -Infinity, maxZ = -Infinity;
    for (const b of blocks) {
      if (b.x < minX) minX = b.x;
      if (b.y < minY) minY = b.y;
      if (b.z < minZ) minZ = b.z;
      if (b.x > maxX) maxX = b.x;
      if (b.y > maxY) maxY = b.y;
      if (b.z > maxZ) maxZ = b.z;
    }
    const cx = (minX + maxX) / 2;
    const cy = (minY + maxY) / 2;
    const cz = (minZ + maxZ) / 2;
    console.log('[Structure3D] bbox=', {minX, maxX, minY, maxY, minZ, maxZ},
      'center=', {cx, cy, cz},
      'container=', {w: containerRef.current?.clientWidth, h: containerRef.current?.clientHeight});
    const dx = (maxX - minX) / 2;
    const dy = (maxY - minY) / 2;
    const dz = (maxZ - minZ) / 2;
    const radius = Math.sqrt(dx * dx + dy * dy + dz * dz);
    const halfFov = (ctx.camera.fov * Math.PI) / 180 / 2;
    const fill = 0.55;
    const distance = radius / (Math.tan(halfFov) * fill) * 1.4;

    // Camera sits on the +X/+Y/+Z diagonal and always aims at (0, 0, 0).
    const k = distance / Math.sqrt(3);
    ctx.camera.position.set(k, k, k);
    ctx.camera.lookAt(0, 0, 0);
    ctx.controls.setTarget(new THREE.Vector3(0, 0, 0));
    ctx.controls.setDistance(distance);

    const geometry = new THREE.BoxGeometry(1, 1, 1);

    for (const {blockId, list} of groups) {
      const material = new THREE.MeshLambertMaterial({color: colorForBlockId(blockId)});
      const mesh = new THREE.InstancedMesh(geometry, material, list.length);
      const m = new THREE.Matrix4();
      for (let i = 0; i < list.length; i++) {
        const b = list[i];
        m.makeTranslation(b.x - cx, b.y - cy, b.z - cz);
        mesh.setMatrixAt(i, m);
      }
      mesh.instanceMatrix.needsUpdate = true;
      (mesh as THREE.Object3D & {__steveBlock: boolean}).__steveBlock = true;
      scene.add(mesh);
    }
  }, [groups, blocks]);

  return (
    <div
      ref={containerRef}
      style={{
        width: '100%',
        height: '100%',
        position: 'relative',
        overflow: 'hidden',
        // Center the WebGL canvas on the container's visual center, so the
        // scene origin (0,0,0) — which holds the structure midpoint — sits
        // at the geometric middle of the component regardless of padding or
        // box-sizing on the host element.
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
      }}
    />
  );
}

// --- Minimal orbit controls (no three/examples dependency) ----------------

function makeOrbitControls(
  camera: THREE.PerspectiveCamera,
  dom: HTMLElement,
) {
  let azimuth = Math.atan2(camera.position.x, camera.position.z);
  let polar = Math.acos(camera.position.y / camera.position.length());
  let radius = camera.position.length();
  const target = new THREE.Vector3();

  let dragging = false;
  let lastX = 0, lastY = 0;

  const onDown = (e: PointerEvent) => {
    dragging = true;
    lastX = e.clientX;
    lastY = e.clientY;
    dom.setPointerCapture(e.pointerId);
  };
  const onMove = (e: PointerEvent) => {
    if (!dragging) return;
    const dx = e.clientX - lastX;
    const dy = e.clientY - lastY;
    lastX = e.clientX;
    lastY = e.clientY;
    azimuth -= dx * 0.005;
    polar = Math.max(0.05, Math.min(Math.PI - 0.05, polar - dy * 0.005));
    update();
  };
  const onUp = (e: PointerEvent) => {
    dragging = false;
    try { dom.releasePointerCapture(e.pointerId); } catch {/*ignore*/}
  };
  const onWheel = (e: WheelEvent) => {
    e.preventDefault();
    const factor = e.deltaY > 0 ? 1.1 : 1 / 1.1;
    radius = Math.max(2, Math.min(2000, radius * factor));
    update();
  };

  const update = () => {
    const sinP = Math.sin(polar);
    camera.position.set(
      target.x + radius * sinP * Math.sin(azimuth),
      target.y + radius * Math.cos(polar),
      target.z + radius * sinP * Math.cos(azimuth),
    );
    camera.lookAt(target);
  };
  update();

  dom.style.touchAction = 'none';
  dom.addEventListener('pointerdown', onDown);
  dom.addEventListener('pointermove', onMove);
  dom.addEventListener('pointerup', onUp);
  dom.addEventListener('pointercancel', onUp);
  dom.addEventListener('wheel', onWheel, {passive: false});

  return {
    update,
    setTarget(v: THREE.Vector3) { target.copy(v); update(); },
    setDistance(d: number) {
      radius = Math.max(2, Math.min(2000, d));
      update();
    },
    dispose() {
      dom.removeEventListener('pointerdown', onDown);
      dom.removeEventListener('pointermove', onMove);
      dom.removeEventListener('pointerup', onUp);
      dom.removeEventListener('pointercancel', onUp);
      dom.removeEventListener('wheel', onWheel);
    },
  };
}
