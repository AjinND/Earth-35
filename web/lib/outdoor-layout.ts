import type {Building} from "./types";
import {streetDistance,dryLand} from "./organic-layout";
import {onIsland,islandElevation} from "./island";
import {enclosedPlace} from "./city-layout";
import {nearestRiver} from "./river";
import {gatheringBenches,gatheringFixedObstacles,benchFootprint,type BenchPlacement} from "./gathering-furniture";
import {boxFootprint,footprintCorners,footprintsOverlap,type BoxFootprint} from "./object-footprint";

export const outdoorCellSize=2;
export type OutdoorCell={x:number;z:number};
export type OutdoorPatch={cells:OutdoorCell[];area:number;radiusX:number;radiusZ:number};
// Same diagonal and vertex heights as OutdoorDestination's two cell triangles.
export function outdoorSurfaceElevation(b:Building,x:number,z:number) {
  const size=outdoorCellSize,half=size/2;
  const left=b.x+Math.floor((x-b.x+half)/size)*size-half;
  const top=b.z+Math.floor((z-b.z+half)/size)*size-half;
  const u=(x-left)/size,v=(z-top)/size;
  const a=islandElevation(left,top),c=islandElevation(left,top+size),d=islandElevation(left+size,top);
  return (u+v<=1?a+(d-a)*u+(c-a)*v:islandElevation(left+size,top+size)*(u+v-1)+c*(1-u)+d*(1-v))+.055;
}
const patches=new Map<string,OutdoorPatch>();
export function outdoorDimensions(b:Building) {
  const radius=b.outdoorRadius??0;
  return {radiusX:radius*(b.type==="promenade"?.38:b.type==="trekking_trail"?.48:b.type==="river"||b.type==="riverside_walk"?.4:1),
    radiusZ:radius*(b.type==="promenade"||b.type==="trekking_trail"?1.25:b.type==="plaza"?.85:1)};
}
// Clearance includes the full crown, plus space beside the painted path. The
// curve's maximum slope bounds the distance even around its bends.
export function outdoorPath(b:Building,x:number,z:number,clearance=0) {
  if(["promenade","trekking_trail","river","riverside_walk"].includes(b.type))return Math.abs(x-Math.sin(z/9)*2.8)<1.4+clearance*Math.hypot(1,2.8/9);
  if(b.type==="plaza")return true;
  if(b.type==="garden")return Math.abs(z)<1.5+clearance||Math.abs(x-Math.round(x/8)*8)<1.2+clearance;
  return Math.abs(x-Math.sin(z/9)*2.5)<1.35+clearance*Math.hypot(1,2.5/9)||Math.abs(z-Math.sin(x/12)*2)<1.15+clearance*Math.hypot(1,2/12);
}
export function outdoorBenches(b:Building,patch:OutdoorPatch) {
  const inside=patchContains(b,patch),occupied=gatheringFixedObstacles(b),benches:BenchPlacement[]=[];
  for(const p of [...gatheringBenches(b.type),...[-1,1].flatMap(side=>[-1,1].map(end=>({x:side*patch.radiusX*.55,z:end*patch.radiusZ*.56,rotation:end<0?Math.PI:0})))]) {
    const shape=benchFootprint(b,p);
    if(!inside(shape)||occupied.some(other=>footprintsOverlap(shape,other,.25)))continue;
    benches.push(p);occupied.push(shape);
  }
  return benches;
}
function patchContains(b:Building,patch:OutdoorPatch) {
  const cells=new Set(patch.cells.map(p=>`${Math.round((p.x-b.x)/outdoorCellSize)}:${Math.round((p.z-b.z)/outdoorCellSize)}`));
  return (shape:BoxFootprint)=>[shape,...footprintCorners(shape)].every(p=>cells.has(`${Math.round((p.x-b.x)/outdoorCellSize)}:${Math.round((p.z-b.z)/outdoorCellSize)}`));
}
/** One accepted bed layout for rendering, planting and walking collision. */
export function outdoorBeds(b:Building,patch:OutdoorPatch):OutdoorCell[] {
  if(b.type!=="garden")return [];
  const inside=patchContains(b,patch),occupied=[...gatheringFixedObstacles(b),...outdoorBenches(b,patch).map(p=>benchFootprint(b,p))],beds:OutdoorCell[]=[];
  for(const [i,p] of patch.cells.entries()) {
    if(i%33!==0||outdoorPath(b,p.x-b.x,p.z-b.z)||Math.hypot(p.x-b.x,p.z-b.z)<=5)continue;
    const shape=boxFootprint(p.x,p.z,2.7,1.4);
    if(!inside(shape)||occupied.some(other=>footprintsOverlap(shape,other,.3)))continue;
    beds.push(p);occupied.push(shape);if(beds.length===16)break;
  }
  return beds;
}
/** Connected destination ground, clipped around streets, water and occupied properties. */
export function outdoorPatch(b:Building,buildings:readonly Building[]):OutdoorPatch {
  const key=[b.id,b.type,b.x,b.z,b.outdoorRadius,...buildings.filter(p=>enclosedPlace(p.type)).map(p=>`${p.id}:${p.x}:${p.z}`)].join("|");
  const cached=patches.get(key);if(cached)return cached;
  const {radiusX,radiusZ}=outdoorDimensions(b),step=outdoorCellSize;
  const obstacles=buildings.filter(p=>enclosedPlace(p.type)&&Math.abs(p.x-b.x)<radiusX+6&&Math.abs(p.z-b.z)<radiusZ+6);
  const clear=(x:number,z:number)=>{
    const dx=x-b.x,dz=z-b.z,theta=Math.atan2(dz/radiusZ,dx/radiusX);
    if(Math.hypot(dx/radiusX,dz/radiusZ)>1+.06*Math.sin(theta*3)+.035*Math.cos(theta*5))return false;
    const river=nearestRiver(x,z);
    return onIsland({x,z})&&dryLand({x,z})&&river.distance>river.width/2+.5&&streetDistance(x,z)>3.4&&
      !obstacles.some(p=>Math.abs(p.x-x)<4.8&&Math.abs(p.z-z)<4.8);
  };
  const cells=new Map<string,OutdoorCell>();
  for(let row=-Math.ceil(radiusZ/step);row<=Math.ceil(radiusZ/step);row++)for(let col=-Math.ceil(radiusX/step);col<=Math.ceil(radiusX/step);col++) {
    const x=b.x+col*step,z=b.z+row*step;
    if([[-1,-1],[-1,1],[1,-1],[1,1]].every(([dx,dz])=>clear(x+dx,z+dz)))cells.set(`${col}:${row}`,{x,z});
  }
  const seed=[...cells.entries()].sort((a,c)=>Math.hypot(a[1].x-b.x,a[1].z-b.z)-Math.hypot(c[1].x-b.x,c[1].z-b.z))[0];
  const connected:OutdoorCell[]=[],seen=new Set<string>(),queue=seed?[seed[0]]:[];
  for(let i=0;i<queue.length;i++) {
    const id=queue[i];if(seen.has(id)||!cells.has(id))continue;seen.add(id);connected.push(cells.get(id)!);
    const [x,z]=id.split(":").map(Number);for(const [dx,dz] of [[1,0],[-1,0],[0,1],[0,-1]])queue.push(`${x+dx}:${z+dz}`);
  }
  const patch={cells:connected,area:connected.length*step*step,radiusX,radiusZ};
  patches.set(key,patch);while(patches.size>48)patches.delete(patches.keys().next().value!);return patch;
}
