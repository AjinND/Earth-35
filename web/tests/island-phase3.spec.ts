import {test,expect,type Page} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import * as THREE from "three";
import {LANDSCAPES,HIGHLAND_TRAILS,coastalWeight} from "../lib/landscapes";
import {RIVER,nearestRiver} from "../lib/river";
import {dryLand} from "../lib/organic-layout";
import {COASTLINE,islandElevation,naturalElevation,walkElevation,riverLevel,onIsland} from "../lib/island";
import {createRiverSurfaces,RIVER_MOUTH,riverSample} from "../lib/river-geometry";
import {createBridgeDeckGeometry,createCoastalGeometry,createTrailGeometry} from "../lib/landscape-geometry";
import {terrainEffort,terrainStep} from "../lib/terrain-travel";
import {terrainGeometry} from "../components/IslandLandscape";
import {arrival,walkable,walkSegmentClear} from "../components/ImmersiveWorld";
import {organicCityLayout} from "../lib/city-layout";
import type {World,Building} from "../lib/types";

type Point={x:number;z:number};
const layout={...organicCityLayout,version:4};
const trails=[...HIGHLAND_TRAILS,...LANDSCAPES.trails.map(trail=>trail.points.map(([x,z])=>({x,z})))];
const expandedViewpoints=LANDSCAPES.viewpoints.filter(place=>!["cloudmere-view","silverstream-falls"].includes(place.id));
const readFixture=<T,>(name:string):T=>JSON.parse(fs.readFileSync(path.resolve("tests/fixtures",name),"utf8").replace(/^\uFEFF/,""));

function heightAt(mesh:THREE.Mesh,x:number,z:number) {
  const ray=new THREE.Raycaster(new THREE.Vector3(x,160,z),new THREE.Vector3(0,-1,0));
  return ray.intersectObject(mesh)[0]?.point.y;
}

function indexedHeight(geometry:THREE.BufferGeometry,material:THREE.Material,size=32) {
  // Keep the actual source triangles, but query only their local tile. Dense
  // coast/trail refinement otherwise makes each sample scan the whole island.
  const positions=geometry.getAttribute("position"),indices=geometry.getIndex()!,tiles=new Map<string,number[]>();
  for(let triangle=0;triangle<indices.count;triangle+=3) {
    const vertices=[indices.getX(triangle),indices.getX(triangle+1),indices.getX(triangle+2)];
    const xs=vertices.map(vertex=>positions.getX(vertex)),zs=vertices.map(vertex=>positions.getZ(vertex));
    for(let x=Math.floor(Math.min(...xs)/size);x<=Math.floor(Math.max(...xs)/size);x++)for(let z=Math.floor(Math.min(...zs)/size);z<=Math.floor(Math.max(...zs)/size);z++) {
      const key=`${x},${z}`,group=tiles.get(key)??[];group.push(...vertices);tiles.set(key,group);
    }
  }
  const meshes=new Map<string,THREE.Mesh>();
  for(const [key,indices] of tiles) {
    const tile=new THREE.BufferGeometry();tile.setAttribute("position",positions);tile.setIndex(indices);
    const box=new THREE.Box3(),point=new THREE.Vector3();
    for(const index of indices)box.expandByPoint(point.set(positions.getX(index),positions.getY(index),positions.getZ(index)));
    tile.boundingBox=box;tile.boundingSphere=box.getBoundingSphere(new THREE.Sphere());meshes.set(key,new THREE.Mesh(tile,material));
  }
  return {
    height:(x:number,z:number)=>{const mesh=meshes.get(`${Math.floor(x/size)},${Math.floor(z/size)}`);return mesh?heightAt(mesh,x,z):undefined;},
    dispose:()=>{for(const mesh of meshes.values())mesh.geometry.dispose();},
  };
}

function worldFixture():World {
  const world=readFixture<World>("organic-world.json");world.geography=layout;
  for(const place of LANDSCAPES.viewpoints)if(!world.buildings.some(building=>building.id===place.id))world.buildings.push({
    ...place,type:"wilderness",capacity:100,social:true,restAllowed:true,objects:{},outdoorRadius:0,
    entrance:{side:"open",x:place.x,z:place.z},
  } as Building);
  // Public coordinate travel permits these dry approaches; fixture markers
  // make the same coordinates selectable for a real first-person crossing.
  for(const bridge of LANDSCAPES.bridges)world.buildings.push({
    id:`inspect-${bridge.id}`,name:`Inspect ${bridge.name}`,type:"wilderness",district:"Bridge review",
    x:bridge.x-bridge.length/2-2,z:bridge.z,capacity:100,social:false,restAllowed:true,description:"Dry bridge approach",objects:{},outdoorRadius:0,
  } as Building);
  return world;
}

function samples(a:Point,b:Point,spacing=.5) {
  const steps=Math.max(1,Math.ceil(Math.hypot(b.x-a.x,b.z-a.z)/spacing));
  return Array.from({length:steps+1},(_,i)=>({x:a.x+(b.x-a.x)*i/steps,z:a.z+(b.z-a.z)*i/steps}));
}

test("the expanded coast, cliff and trail rules match the authoritative server survey",()=>{
  test.setTimeout(60000);
  const server=JSON.parse(fs.readFileSync(path.resolve("../backend/src/main/resources/city/island-landscapes-v1.json"),"utf8"));
  expect(LANDSCAPES).toEqual(server);
  const survey=readFixture<(Point&{dry:boolean})[]>("phase3-landscape-survey.json");
  expect(survey.length).toBeGreaterThan(5000);
  for(const point of survey)expect(dryLand(point),`Server/viewer boundary differs at ${point.x}, ${point.z}`).toBe(point.dry);
  for(const cliff of LANDSCAPES.protectedCliffs) {
    expect(dryLand(cliff)).toBe(false);
    expect(walkSegmentClear({x:cliff.x-cliff.radiusX-3,z:cliff.z},{x:cliff.x+cliff.radiusX+3,z:cliff.z},[],layout)).toBe(false);
  }
  for(const bridge of LANDSCAPES.bridges) {
    expect(walkSegmentClear({x:bridge.x-bridge.length/2,z:bridge.z},{x:bridge.x+bridge.length/2,z:bridge.z},[],layout)).toBe(true);
    // A nearby parallel path must still be water, rather than an invisible
    // crossing granted by a broad latitude strip.
    expect(walkSegmentClear({x:bridge.x-bridge.length/2,z:bridge.z+bridge.width/2+.6},{x:bridge.x+bridge.length/2,z:bridge.z+bridge.width/2+.6},[],layout)).toBe(false);
  }
});

test("every marked trail connects dry destinations and permits real viewer traversal",()=>{
  const world=worldFixture(),nodes:Point[]=[];
  for(const trail of trails)for(const point of trail)nodes.push(point);
  const visited=new Set<number>([0]),queue=[0];
  while(queue.length) {
    const index=queue.shift()!,point=nodes[index];
    for(let other=0;other<nodes.length;other++)if(!visited.has(other)) {
      const candidate=nodes[other];
      const neighbours=trails.some(trail=>trail.some((p,i)=>i>0&&((p===point&&trail[i-1]===candidate)||(p===candidate&&trail[i-1]===point))));
      if(neighbours||Math.hypot(point.x-candidate.x,point.z-candidate.z)<.01){visited.add(other);queue.push(other);}
    }
  }
  expect(visited.size,"A marked trail is isolated from the connected island network").toBe(nodes.length);
  for(const trail of trails)for(let segment=1;segment<trail.length;segment++) {
    const a=trail[segment-1],b=trail[segment];
    expect(walkSegmentClear(a,b,world.buildings,layout,world.objects),`Blocked trail ${a.x},${a.z} → ${b.x},${b.z}`).toBe(true);
    for(const point of samples(a,b)) {
      expect(walkable(point,world.buildings,layout,world.objects),`Blocked trail point ${point.x},${point.z}`).toBe(true);
      expect(Number.isFinite(walkElevation(point.x,point.z))).toBe(true);
    }
  }
  for(const place of LANDSCAPES.viewpoints) {
    expect(nodes.some(point=>Math.hypot(point.x-place.x,point.z-place.z)<.01),`Unconnected destination ${place.name}`).toBe(true);
    expect(arrival(place,world.buildings,layout,world.objects),`Arrival unexpectedly moves ${place.name}`).toEqual(expect.objectContaining({x:place.x,z:place.z}));
    expect(walkable(place,world.buildings,layout,world.objects)).toBe(true);
  }
});

test("the complete river remains supported by actual bed triangles through the estuary",()=>{
  const geometry=createRiverSurfaces(),material=new THREE.MeshBasicMaterial({side:THREE.DoubleSide});
  const water=indexedHeight(geometry.water,material),bed=indexedHeight(geometry.bed,material);
  try {
    for(let index=0;index<RIVER_MOUTH;index++)for(const fraction of [.13,.43,.73,.91])for(const across of [-.65,0,.65]) {
      const point=riverSample(index,fraction),x=point.x+point.sx*point.width/2*across,z=point.z+point.sz*point.width/2*across;
      const sheet=water.height(x,z),ground=bed.height(x,z);
      expect(sheet,`Missing river surface ${index}+${fraction}`).toBeDefined();expect(ground,`Missing bed ${index}+${fraction}`).toBeDefined();
      expect(sheet!-ground!,`Water below riverbed ${index}+${fraction}`).toBeGreaterThan(.02);
      expect(sheet!-ground!,`Floating river ${index}+${fraction}`).toBeLessThan(1.6);
    }
    for(let index=1;index<RIVER_MOUTH;index++)expect(riverLevel(index)).toBeLessThanOrEqual(riverLevel(index-1));
    expect(onIsland(RIVER[RIVER_MOUTH])).toBe(false);
  } finally {water.dispose();bed.dispose();Object.values(geometry).forEach(value=>value.dispose());material.dispose();}
});

test("trail and bridge triangles provide the same continuous surface as traversal",()=>{
  test.setTimeout(60000);
  const material=new THREE.MeshBasicMaterial({side:THREE.DoubleSide}),terrain=terrainGeometry(),ground=indexedHeight(terrain,material);
  const geometries:THREE.BufferGeometry[]=[terrain];
  try {
    for(const trail of trails) {
      const geometry=createTrailGeometry(trail),mesh=new THREE.Mesh(geometry,material);geometries.push(geometry);
      for(let segment=1;segment<trail.length;segment++)for(const point of samples(trail[segment-1],trail[segment],3).slice(1,-1)) {
        const surface=heightAt(mesh,point.x,point.z),underlying=ground.height(point.x,point.z),height=walkElevation(point.x,point.z);
        expect(surface,`Missing trail triangle at ${point.x},${point.z}`).toBeDefined();
        expect(surface!,`Trail/feet disagree at ${point.x},${point.z}`).toBeCloseTo(height+.10,0);
        const bridge=LANDSCAPES.bridges.some(value=>Math.abs(point.x-value.x)<value.length/2+4&&Math.abs(point.z-value.z)<=value.width/2);
        if(!bridge) {
          expect(underlying,`Missing terrain beneath trail ${point.x},${point.z}`).toBeDefined();
          expect(surface!-underlying!,`Trail floats at ${point.x},${point.z}`).toBeLessThan(1.2);
          expect(surface!-underlying!,`Trail buried at ${point.x},${point.z}`).toBeGreaterThan(-.25);
        }
      }
    }
    for(const bridge of LANDSCAPES.bridges) {
      const geometry=createBridgeDeckGeometry(bridge),mesh=new THREE.Mesh(geometry,material);geometries.push(geometry);
      let previous:number|undefined;
      for(let offset=-bridge.length/2-3.5;offset<bridge.length/2+3.5;offset+=.25) {
        const x=bridge.x+offset,height=walkElevation(x,bridge.z),surface=heightAt(mesh,x,bridge.z);
        expect(surface,`Missing deck or approach on ${bridge.id} at ${offset}`).toBeDefined();
        expect(Math.abs(surface!-height),`Bridge deck and feet differ ${bridge.id} at ${offset}`).toBeLessThan(.15);
        if(previous!==undefined)expect(Math.abs(height-previous),`Bridge has a step ${bridge.id} at ${offset}`).toBeLessThan(.25);
        previous=height;
      }
      const river=nearestRiver(bridge.x,bridge.z);
      expect(heightAt(mesh,bridge.x,bridge.z)!-riverLevel(river.index,river.fraction)).toBeGreaterThan(.4);
    }
  } finally {ground.dispose();geometries.forEach(geometry=>geometry.dispose());material.dispose();}
});

test("coastal relief has visible supported shore triangles and an elevated headland",()=>{
  const geometry=createCoastalGeometry(),material=new THREE.MeshBasicMaterial({side:THREE.DoubleSide}),mesh=new THREE.Mesh(geometry,material);
  try {
    let sampled=0;
    for(const zone of LANDSCAPES.coastalZones)for(const dx of [-.35,0,.35])for(const dz of [-.35,0,.35]) {
      const x=zone.x+dx*zone.radiusX,z=zone.z+dz*zone.radiusZ;
      if(!onIsland({x,z}))continue;
      const surface=heightAt(mesh,x,z);
      // The detailed coastal bands stop inland; terrain continues beyond them.
      if(surface!==undefined){expect(Math.abs(surface-islandElevation(x,z)),`Unsupported coast ${zone.id} at ${x},${z}`).toBeLessThan(.6);sampled++;}
    }
    expect(sampled).toBeGreaterThan(5);
    const headland=LANDSCAPES.viewpoints.find(place=>place.id==="basalt-lookout")!,cove=LANDSCAPES.viewpoints.find(place=>place.id==="moonwash-cove")!;
    expect(islandElevation(headland.x,headland.z)-islandElevation(cove.x,cove.z)).toBeGreaterThan(5);
  } finally {geometry.dispose();material.dispose();}
});

test("named coves have continuous coastal triangles immediately inside the shoreline",()=>{
  test.setTimeout(60000);
  const geometry=createCoastalGeometry(),material=new THREE.MeshBasicMaterial({side:THREE.DoubleSide}),coast=indexedHeight(geometry,material);
  try {
    for(const zone of LANDSCAPES.coastalZones.filter(zone=>zone.kind==="cove")) {
      const missing:Point[]=[],unsupported:Point[]=[];let checked=0;
      for(let segment=0;segment<COASTLINE.length;segment++) {
        const a=COASTLINE[segment],b=COASTLINE[(segment+1)%COASTLINE.length],length=Math.hypot(b.x-a.x,b.z-a.z);
        let nx=-(b.z-a.z)/length,nz=(b.x-a.x)/length;
        const middle={x:(a.x+b.x)/2,z:(a.z+b.z)/2};
        if(!onIsland({x:middle.x+nx,z:middle.z+nz})){nx=-nx;nz=-nz;}
        for(const fraction of [.01,.07,.19,.37,.53,.71,.89,.99])for(const distance of [.1,.35,2,6,12]) {
          const shore={x:a.x+(b.x-a.x)*fraction,z:a.z+(b.z-a.z)*fraction};
          // Start at a named cove shoreline, rather than another coastal
          // segment whose far inland area happens to enter the zone ellipse.
          if(coastalWeight(zone,shore.x,shore.z)<.1)continue;
          const point={x:shore.x+nx*distance,z:shore.z+nz*distance};
          if(!onIsland(point)||coastalWeight(zone,point.x,point.z)<.1)continue;
          const river=nearestRiver(point.x,point.z);
          // The river's own bed and banks intentionally replace the coast
          // overlay. Allow an adjacent triangle's footprint at that cutout.
          if(river.distance<river.width/2+2)continue;
          checked++;
          const surface=coast.height(point.x,point.z);
          if(surface===undefined)missing.push(point);
          else if(Math.abs(surface-islandElevation(point.x,point.z))>.6)unsupported.push(point);
        }
      }
      expect(checked,`No shoreline coverage samples in ${zone.id}`).toBeGreaterThan(5);
      expect.soft(missing,`${zone.id} misses ${missing.length}/${checked} dry shoreline probes; first gaps: ${JSON.stringify(missing.slice(0,5))}`).toEqual([]);
      expect(unsupported,`${zone.id} coastal triangles do not follow the shore terrain`).toEqual([]);
    }
    // A camera ray through the former green sliver encountered the flat green
    // island base after passing through this gap between two inland strips.
    for(const point of [{x:-724.845675241066,z:-115.926682187406},{x:-726.6317507033295,z:-115.56009083152117}]) {
      const surface=coast.height(point.x,point.z);
      expect(surface,`The previously exposed cove seam remains at ${point.x},${point.z}`).toBeDefined();
      expect(Math.abs(surface!-islandElevation(point.x,point.z))).toBeLessThan(.15);
    }
  } finally {coast.dispose();geometry.dispose();material.dispose();}
});

test("the estuary water remains visible above coastal triangles and from its dry viewpoint",()=>{
  test.setTimeout(60000);
  const material=new THREE.MeshBasicMaterial({side:THREE.DoubleSide}),coastGeometry=createCoastalGeometry(),terrain=terrainGeometry(),river=createRiverSurfaces();
  const coast=indexedHeight(coastGeometry,material),ground=indexedHeight(terrain,material),water=indexedHeight(river.water,material);
  const scene=[coastGeometry,terrain,river.bed].map(geometry=>new THREE.Mesh(geometry,material)),ray=new THREE.Raycaster();
  try {
    for(let index=RIVER_MOUTH-12;index<RIVER_MOUTH;index++)for(const fraction of [.2,.5,.8])for(const across of [-.65,0,.65]) {
      const point=riverSample(index,fraction),x=point.x+point.sx*point.width/2*across,z=point.z+point.sz*point.width/2*across,surface=water.height(x,z)!;
      for(const mesh of [coast,ground]) {
        const height=mesh.height(x,z);
        if(height!==undefined)expect(height,`Land triangles obscure estuary water at ${index}+${fraction}`).toBeLessThan(surface-.01);
      }
    }
    const place=LANDSCAPES.viewpoints.find(place=>place.id==="silverstream-estuary")!,eye=new THREE.Vector3(place.x,walkElevation(place.x,place.z)+1.65,place.z);
    let visible=0;
    for(let index=RIVER_MOUTH-7;index<RIVER_MOUTH;index++) {
      const point=riverSample(index,.5),target=new THREE.Vector3(point.x,point.y+.015,point.z),distance=eye.distanceTo(target);
      ray.set(eye,target.clone().sub(eye).normalize());ray.far=distance-.08;
      if(!scene.some(mesh=>ray.intersectObject(mesh).length))visible++;
    }
    expect(visible,"The estuary viewpoint cannot see its river over the intervening banks").toBeGreaterThanOrEqual(2);
  } finally {coast.dispose();ground.dispose();water.dispose();coastGeometry.dispose();terrain.dispose();Object.values(river).forEach(geometry=>geometry.dispose());material.dispose();}
});

type HeightSample=Point&{dry:boolean;naturalElevation:number;islandElevation:number;walkElevation:number};
type SegmentSample={from:Point;to:Point;walkingEffort:number;runningEnergyEffort:number;runningEnergy:number;maxGrade:number};
function independentEffort(from:Point,to:Point) {
  let walkingEffort=0,runningEnergyEffort=0,maxGrade=0;const points=samples(from,to);
  for(let index=1;index<points.length;index++) {
    const a=points[index-1],b=points[index],distance=Math.hypot(b.x-a.x,b.z-a.z);
    if(distance===0)continue;
    const grade=(walkElevation(b.x,b.z)-walkElevation(a.x,a.z))/distance,up=Math.max(grade,0),down=Math.max(-grade,0);
    const effort=distance*Math.sqrt(1+grade*grade)*(1+4*up+3*Math.max(down-.08,0))/(1+1.2*Math.min(.08,down));
    walkingEffort+=effort;runningEnergyEffort+=effort*(1+2.5*up+.5*Math.max(down-.08,0));maxGrade=Math.max(maxGrade,Math.abs(grade));
  }
  return {walkingEffort,runningEnergyEffort,runningEnergy:(.025*walkingEffort+.2*runningEnergyEffort)/layout.runningSpeed,maxGrade};
}

test("server elevations and travel costs agree with the viewer on relief, trails and bridges",()=>{
  test.setTimeout(60000);
  const survey=readFixture<{points:HeightSample[];segments:SegmentSample[]}>("phase3-terrain-survey.json");
  expect(survey.points.length).toBeGreaterThan(100);expect(survey.segments.length).toBeGreaterThanOrEqual(10);
  for(const point of survey.points) {
    expect(naturalElevation(point.x,point.z),`Natural terrain ${point.x},${point.z}`).toBeCloseTo(point.naturalElevation,7);
    expect(islandElevation(point.x,point.z),`Terrain ${point.x},${point.z}`).toBeCloseTo(point.islandElevation,7);
    expect(walkElevation(point.x,point.z),`Walkable surface ${point.x},${point.z}`).toBeCloseTo(point.walkElevation,7);
    expect(dryLand(point)).toBe(point.dry);
  }
  let uphill=0,downhill=0;
  for(const segment of survey.segments) {
    const effort=independentEffort(segment.from,segment.to);
    for(const field of ["walkingEffort","runningEnergyEffort","runningEnergy","maxGrade"] as const)expect(effort[field],`Travel ${field} ${segment.from.x},${segment.from.z} → ${segment.to.x},${segment.to.z}`).toBeCloseTo(segment[field],6);
    const movement=terrainEffort(segment.from,segment.to);
    for(const field of ["walkingEffort","runningEnergyEffort","maxGrade"] as const)expect(movement[field],`Viewer movement ${field}`).toBeCloseTo(segment[field],6);
    const budget=segment.walkingEffort*.3,step=terrainStep(segment.from,segment.to,budget);
    expect(terrainEffort(segment.from,step).walkingEffort,"Viewer movement exceeds its slope-adjusted budget").toBeLessThanOrEqual(budget+1e-8);
    expect(Math.hypot(step.x-segment.from.x,step.z-segment.from.z)).toBeGreaterThan(0);
    const delta=walkElevation(segment.to.x,segment.to.z)-walkElevation(segment.from.x,segment.from.z);
    if(delta>1)uphill++;if(delta< -1)downhill++;
  }
  expect(uphill).toBeGreaterThan(0);expect(downhill).toBeGreaterThan(0);
  const flat=independentEffort({x:-150,z:8},{x:-130,z:8});
  expect(flat.walkingEffort).toBeCloseTo(20,7);expect(flat.runningEnergyEffort).toBeCloseTo(20,7);
  for(const relief of LANDSCAPES.terrainRelief) {
    const center=islandElevation(relief.x,relief.z),flanks=[-1,1].map(side=>islandElevation(relief.x+side*relief.radiusX,relief.z));
    if(relief.height<0)expect(center,`${relief.id} needs a real depressed valley floor`).toBeLessThan((flanks[0]+flanks[1])/2);
  }
});

async function mockWorld(page:Page,world:World) {
  await page.route("**/api/v1/world",route=>route.fulfill({json:world}));
  await page.route("**/api/v1/auth/me",route=>route.fulfill({status:401,json:{message:"Landscape preview"}}));
  await page.route("**/api/config",route=>route.fulfill({json:{webSocketUrl:"ws://localhost:3002/ws/phase3-verification"}}));
  await page.routeWebSocket("**/ws/phase3-verification",socket=>socket.send(JSON.stringify(world)));
}

function viewTarget(place:typeof LANDSCAPES.viewpoints[number]):Point&{y?:number} {
  if(place.id==="silverstream-estuary") {
    const river=nearestRiver(place.x,place.z),a=RIVER[river.index],b=RIVER[river.index+1];
    return {x:a.x+(b.x-a.x)*river.fraction,z:a.z+(b.z-a.z)*river.fraction,y:riverLevel(river.index,river.fraction)};
  }
  if(place.id==="basalt-lookout") {
    const cliff=LANDSCAPES.protectedCliffs.find(zone=>zone.id==="basalt-edge")!;
    return {x:cliff.x-cliff.radiusX*.6,z:cliff.z-10,y:Math.max(0,islandElevation(cliff.x-cliff.radiusX*.6,cliff.z-10)-3)};
  }
  const coastal=LANDSCAPES.coastalZones.find(zone=>zone.id===place.id||place.id==="basalt-lookout"&&zone.id==="basalt-headland");
  if(coastal)return coastal;
  const relief=LANDSCAPES.terrainRelief.find(zone=>zone.id===place.id);
  if(relief)return {x:relief.x-relief.radiusX*.6,z:relief.z-30};
  const river=nearestRiver(place.x,place.z),a=RIVER[river.index],b=RIVER[river.index+1];
  return {x:a.x+(b.x-a.x)*river.fraction,z:a.z+(b.z-a.z)*river.fraction};
}

for(const mobile of [false,true])test(`the connected Phase 3 coast and valleys are usable on ${mobile?"mobile":"desktop"}`,async({page},info)=>{
  test.setTimeout(180000);
  await page.setViewportSize(mobile?{width:390,height:844}:{width:1536,height:960});
  await page.emulateMedia({reducedMotion:"reduce"});
  const errors:string[]=[];page.on("pageerror",error=>errors.push(error.message));
  page.on("console",message=>{if(message.type()==="error"&&/THREE|WebGL|shader/i.test(message.text()))errors.push(message.text());});
  await mockWorld(page,worldFixture());await page.goto("/");
  const canvas=page.locator(".world-stage canvas");
  await expect(canvas).toHaveAttribute("data-river-course","mountain-to-sea",{timeout:45000});
  await page.locator(".world-stage").screenshot({path:info.outputPath("phase3-island-overview.png")});
  const flow=await canvas.getAttribute("data-waterfall-flow"),currents=await canvas.getAttribute("data-river-flow");
  await page.waitForTimeout(450);expect(await canvas.getAttribute("data-waterfall-flow")).toBe(flow);expect(await canvas.getAttribute("data-river-flow")).toBe(currents);
  await page.getByRole("button",{name:"Enter the world",exact:true}).click();
  const dialog=page.getByRole("dialog",{name:"Explore New Eden in first person"});
  await expect(dialog.locator("canvas")).toHaveAttribute("data-controls-ready","true",{timeout:30000});
  let yaw=0,pitch=0;
  for(const place of expandedViewpoints) {
    await page.getByRole("button",{name:"World map",exact:true}).click();
    if(place===expandedViewpoints[0])await page.screenshot({path:info.outputPath("phase3-connected-world-map.png")});
    await page.getByRole("button",{name:new RegExp(`${place.name}.*Visit`)}).click();
    await expect(page.getByLabel("Your world position")).toHaveText(`${place.x.toFixed(1)}, ${place.z.toFixed(1)}`);
    const target=viewTarget(place),desiredYaw=Math.atan2(place.x-target.x,place.z-target.z);
    const desiredPitch=Math.max(-1.1,Math.min(1.1,Math.atan2((target.y??islandElevation(target.x,target.z))-walkElevation(place.x,place.z)-1.65,Math.hypot(target.x-place.x,target.z-place.z))));
    const box=(await dialog.locator("canvas").boundingBox())!,x=box.x+box.width*.5,y=box.y+box.height*.4;
    await page.mouse.move(x,y);await page.mouse.down();await page.mouse.move(x+(yaw-desiredYaw)/.004,y+(pitch-desiredPitch)/.004,{steps:12});await page.mouse.up();yaw=desiredYaw;pitch=desiredPitch;
    await page.waitForTimeout(180);await page.screenshot({path:info.outputPath(`${place.id}-first-person.png`)});
    const before=await page.getByLabel("Your world position").textContent();
    if(mobile) {
      const button=page.getByRole("button",{name:"Walk backward",exact:true}),bounds=(await button.boundingBox())!;
      await page.mouse.move(bounds.x+bounds.width/2,bounds.y+bounds.height/2);await page.mouse.down();
      await expect(page.getByLabel("Your world position")).not.toHaveText(before!);await page.mouse.up();
    } else {
      await page.keyboard.down("s");await expect(page.getByLabel("Your world position")).not.toHaveText(before!);await page.keyboard.up("s");
    }
  }
  for(const bridge of LANDSCAPES.bridges) {
    await page.getByRole("button",{name:"World map",exact:true}).click();
    await page.getByRole("button",{name:new RegExp(`Inspect ${bridge.name}.*Visit`)}).click();
    const box=(await dialog.locator("canvas").boundingBox())!,x=box.x+box.width*.5,y=box.y+box.height*.4;
    const desiredYaw=-Math.PI/2,desiredPitch=-.2;
    await page.mouse.move(x,y);await page.mouse.down();await page.mouse.move(x+(yaw-desiredYaw)/.004,y+(pitch-desiredPitch)/.004,{steps:12});await page.mouse.up();yaw=desiredYaw;pitch=desiredPitch;
    await page.waitForTimeout(150);await page.screenshot({path:info.outputPath(`${bridge.id}-crossing.png`)});
    await page.keyboard.down("w");
    await expect.poll(async()=>Number((await page.getByLabel("Your world position").textContent())?.split(",")[0]),{timeout:10000}).toBeGreaterThan(bridge.x+bridge.length/2+1);
    await page.keyboard.up("w");
    expect(Number((await page.getByLabel("Your world position").textContent())?.split(",")[1])).toBeCloseTo(bridge.z,1);
  }
  await page.getByRole("button",{name:"Back to dashboard",exact:true}).click();
  for(const place of expandedViewpoints) {
    await page.getByRole("button",{name:"Browse places",exact:true}).click();
    await page.locator(".places-popover").getByRole("button",{name:place.name,exact:true}).click();
    await expect(canvas).toHaveAttribute("data-target-x",place.x.toFixed(3));await expect(canvas).toHaveAttribute("data-target-z",place.z.toFixed(3));
    await page.getByRole("button",{name:"Close inspector",exact:true}).click();
    await page.waitForTimeout(250);await page.locator(".world-stage").screenshot({path:info.outputPath(`${place.id}-district.png`)});
  }
  await page.emulateMedia({reducedMotion:"no-preference"});await expect.poll(()=>canvas.getAttribute("data-river-flow")).not.toBe(currents);
  expect(errors).toEqual([]);
});
