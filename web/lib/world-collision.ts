import type {Building,PlacedWorldObject} from "./types";
import {BUILDING_VIEWER_HALF} from "./architecture";
import {enclosedPlace,prototypeLayout,withinSettlement,type CityLayout} from "./city-layout";
import {scenicWalkable} from "./scenic-layout";
import {dryLand} from "./organic-layout";
import {ISLAND_BOUNDS} from "./island";
import {gatheringBenches,gatheringFixedObstacles,benchFootprint} from "./gathering-furniture";
import {outdoorBenches,outdoorBeds,outdoorPatch} from "./outdoor-layout";
import {outdoorTrees} from "./outdoor-planting";
import {livingSpatialKey} from "./living-environment";
import {paddedFootprint,footprintContains,type ObjectFootprint} from "./object-footprint";

export type Point={x:number;z:number};
type Box=Point&{kind:"box";halfX:number;halfZ:number;cos:number;sin:number};
type Circle=Point&{kind:"circle";radius:number};
type Solid=(Box|Circle)&{building?:boolean};
const furnitureClearance=.3;
const terrainSampleSpacing=.25;
// Keep per-frame lookups cheap, and reuse geometry when a new snapshot only changes
// residents, opening hours or other metadata. Only actual spatial changes rebuild it.
const footprints=new WeakMap<Building[],readonly Solid[]>();
const spatialFootprints=new Map<string,readonly Solid[]>();
const spatialCacheLimit=8;
const gatheringTypes=new Set(["park","garden","plaza","promenade","hangout","river","riverside_walk","mountain","hill_station","trekking_trail"]);

function box(x:number,z:number,width:number,depth:number,rotation=0,padding=furnitureClearance):Box {
  return {kind:"box",x,z,halfX:width/2+padding,halfZ:depth/2+padding,cos:Math.cos(rotation),sin:Math.sin(rotation)};
}
function physicalFootprints(buildings:Building[]):readonly Solid[] {
  const cached=footprints.get(buildings);if(cached)return cached;
  const key=livingSpatialKey(buildings),spatial=spatialFootprints.get(key);
  if(spatial) {
    footprints.set(buildings,spatial);
    // Refresh insertion order so recently used layouts survive the bounded cache.
    spatialFootprints.delete(key);spatialFootprints.set(key,spatial);
    return spatial;
  }
  const solids:Solid[]=[];
  for(const b of buildings) {
    if(enclosedPlace(b.type)) {
      solids.push({...box(b.x,b.z,BUILDING_VIEWER_HALF*2,BUILDING_VIEWER_HALF*2,0,0),building:true});
      continue;
    }
    const expanded=(b.outdoorRadius??0)>0;
    const patch=expanded?outdoorPatch(b,buildings):null;
    const benches=patch?outdoorBenches(b,patch):gatheringTypes.has(b.type)?gatheringBenches(b.type):[];
    solids.push(...benches.map(p=>benchFootprint(b,p,furnitureClearance)));
    solids.push(...gatheringFixedObstacles(b).map(p=>({...paddedFootprint(p,furnitureClearance),kind:p.kind??"circle"} as Solid)));
    if(patch)for(const tree of outdoorTrees(b,buildings)) {
      const trunk=tree.kind==="birch"?.16:tree.kind==="pine"||tree.kind==="alpine-pine"?.2:.3;
      solids.push({kind:"circle",x:tree.x,z:tree.z,radius:trunk*tree.size+furnitureClearance});
    }
    if(patch)solids.push(...outdoorBeds(b,patch).map(p=>box(p.x,p.z,2.7,1.4)));
  }
  footprints.set(buildings,solids);spatialFootprints.set(key,solids);
  while(spatialFootprints.size>spatialCacheLimit)spatialFootprints.delete(spatialFootprints.keys().next().value!);
  return solids;
}
/** Crowd offsets must respect the same furniture that blocks first-person walking. */
export function residentObstacles(buildings:Building[],objects:PlacedWorldObject[]=[]):ObjectFootprint[] {
  return [...physicalFootprints(buildings).filter(p=>!p.building).map(p=>paddedFootprint(p,.25)),
    ...objects.filter(o=>o.status==="installed").map(o=>({x:o.x,z:o.z,radius:1.25}))];
}
function finite(point:Point) {return Number.isFinite(point.x)&&Number.isFinite(point.z);}
function terrainClear(point:Point,buildings:Building[],layout:CityLayout) {
  return layout.version>=2?dryLand(point):withinSettlement(point.x,point.z,layout)&&scenicWalkable(point,buildings);
}
function local(point:Point,solid:Box):Point {
  const x=point.x-solid.x,z=point.z-solid.z;
  return {x:x*solid.cos-z*solid.sin,z:x*solid.sin+z*solid.cos};
}
function contains(point:Point,solid:Solid) {
  return footprintContains(point,solid);
}
function crosses(from:Point,to:Point,solid:Solid) {
  if(solid.kind==="circle") {
    const dx=to.x-from.x,dz=to.z-from.z,lengthSquared=dx*dx+dz*dz;
    const t=lengthSquared===0?0:Math.max(0,Math.min(1,((solid.x-from.x)*dx+(solid.z-from.z)*dz)/lengthSquared));
    return (from.x+dx*t-solid.x)**2+(from.z+dz*t-solid.z)**2<solid.radius**2;
  }
  const a=local(from,solid),b=local(to,solid);
  let enter=0,exit=1;
  for(const [start,end,half] of [[a.x,b.x,solid.halfX],[a.z,b.z,solid.halfZ]]) {
    const delta=end-start;
    if(Math.abs(delta)<1e-12){if(Math.abs(start)>=half)return false;continue;}
    const t1=(-half-start)/delta,t2=(half-start)/delta;
    enter=Math.max(enter,Math.min(t1,t2));exit=Math.min(exit,Math.max(t1,t2));
    if(enter>=exit)return false;
  }
  return enter<exit;
}

export function walkable(point:Point,buildings:Building[],layout:CityLayout=prototypeLayout,objects:PlacedWorldObject[]=[]):boolean {
  return finite(point)&&terrainClear(point,buildings,layout)&&!physicalFootprints(buildings).some(solid=>contains(point,solid))&&
    !objects.some(object=>object.status==="installed"&&contains(point,{kind:"circle",x:object.x,z:object.z,radius:1}));
}

/** Check the entire sweep against solids, with short terrain samples for banks and cliffs. */
export function walkSegmentClear(from:Point,to:Point,buildings:Building[],layout:CityLayout=prototypeLayout,objects:PlacedWorldObject[]=[]):boolean {
  if(!finite(from)||!finite(to))return false;
  if(physicalFootprints(buildings).some(solid=>crosses(from,to,solid)))return false;
  if(objects.some(object=>object.status==="installed"&&crosses(from,to,{kind:"circle",x:object.x,z:object.z,radius:1})))return false;
  const steps=Math.max(1,Math.ceil(Math.hypot(to.x-from.x,to.z-from.z)/terrainSampleSpacing));
  for(let i=0;i<=steps;i++)if(!terrainClear({x:from.x+(to.x-from.x)*i/steps,z:from.z+(to.z-from.z)*i/steps},buildings,layout))return false;
  return true;
}

/** Pick a nearby safe standing position; all distant recovery points are checked too. */
export function arrival(point:Point,buildings:Building[],layout:CityLayout=prototypeLayout,objects:PlacedWorldObject[]=[]):Point {
  const clear=(p:Point)=>walkable(p,buildings,layout,objects);
  if(clear(point))return point;
  if(finite(point))for(let radius=.5;radius<=24;radius+=.5) {
    const count=Math.ceil(2*Math.PI*radius/.5);
    for(let i=0;i<count;i++) {
      // Start on the front approach, then search each ring at equal angular spacing.
      const angle=i/count*Math.PI*2,next={x:point.x+Math.sin(angle)*radius,z:point.z+Math.cos(angle)*radius};
      if(clear(next))return next;
    }
  }
  const anchors=[{x:layout.centerX,z:layout.centerZ+8},...buildings.flatMap(b=>[b.entrance??{x:b.x,z:b.z+4},...[[0,4],[4,0],[0,-4],[-4,0]].map(([dx,dz])=>({x:b.x+dx,z:b.z+dz}))])];
  for(const p of anchors)if(clear(p))return p;
  const bounds=layout.version>=2?ISLAND_BOUNDS:{minX:layout.centerX-layout.halfExtent+1,maxX:layout.centerX+layout.halfExtent-1,minZ:layout.centerZ-layout.halfExtent+1,maxZ:layout.centerZ+layout.halfExtent-1};
  for(let z=bounds.minZ;z<=bounds.maxZ;z+=2)for(let x=bounds.minX;x<=bounds.maxX;x+=2)if(clear({x,z}))return {x,z};
  throw new Error("The world has no clear arrival position.");
}
