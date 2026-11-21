import type {Building} from "./types";
import {islandElevation} from "./island";
import {outdoorBenches,outdoorBeds,outdoorCellSize,outdoorPatch,outdoorPath} from "./outdoor-layout";
import {boxFootprint,footprintsOverlap} from "./object-footprint";
import {crownRadius,habitatAt,livingSeed,plantingClear,type Plant,type PlantKind} from "./living-environment";

export const OUTDOOR_PLANTING={maxTrees:36,areaPerTree:260,crownGap:4,pathMargin:1.8,benchMargin:3.5} as const;
export const outdoorClearingRadius=(radiusX:number,radiusZ:number)=>Math.max(8,Math.min(18,Math.min(radiusX,radiusZ)*.28));

/** Small, mixed groves frame usable lawns; the surrounding habitat owns the forest. */
export function outdoorTrees(b:Building,buildings:readonly Building[]):Plant[] {
  if(b.id.includes("beach")||b.type==="plaza")return [];
  const patch=outdoorPatch(b,buildings),habitat=habitatAt(b.x,b.z);
  const palette:PlantKind[]=habitat==="mountain"?["birch","alpine-pine","pine"]:habitat==="riverbank"?["birch","willow","oak"]:["birch","oak","pine"];
  const benches=outdoorBenches(b,patch),clearing=outdoorClearingRadius(patch.radiusX,patch.radiusZ);
  const beds=outdoorBeds(b,patch).map(p=>boxFootprint(p.x,p.z,2.7,1.4));
  const otherPlaces=buildings.filter(p=>p.id!==b.id);
  const cells=new Set(patch.cells.map(p=>`${Math.round((p.x-b.x)/outdoorCellSize)}:${Math.round((p.z-b.z)/outdoorCellSize)}`));
  const inside=(x:number,z:number)=>cells.has(`${Math.round((x-b.x)/outdoorCellSize)}:${Math.round((z-b.z)/outdoorCellSize)}`);
  const candidates=patch.cells.map(p=>({...p,seed:livingSeed(p.x,p.z)})).sort((a,c)=>a.seed-c.seed);
  const trees:Plant[]=[],limit=Math.min(OUTDOOR_PLANTING.maxTrees,Math.floor(patch.area/OUTDOOR_PLANTING.areaPerTree));
  for(const p of candidates) {
    if(trees.length>=limit)break;
    const id=Math.floor(p.seed*1e7),kind=palette[trees.length%palette.length];
    const size=(.9+livingSeed(id,3)*.35)*(kind==="pine"?.85:kind==="alpine-pine"?.65:1),radius=crownRadius[kind]*size;
    const x=p.x+(livingSeed(id,4)-.5)*1.4,z=p.z+(livingSeed(id,6)-.5)*1.4,lx=x-b.x,lz=z-b.z;
    if(Math.hypot(lx,lz)<clearing+radius||outdoorPath(b,lx,lz,radius+OUTDOOR_PLANTING.pathMargin))continue;
    if(benches.some(seat=>Math.hypot(lx-seat.x,lz-seat.z)<radius+OUTDOOR_PLANTING.benchMargin))continue;
    if(beds.some(bed=>footprintsOverlap({x,z,radius},bed,.3)))continue;
    if(trees.some(tree=>Math.hypot(x-tree.x,z-tree.z)<radius+tree.radius+OUTDOOR_PLANTING.crownGap))continue;
    if(!plantingClear(x,z,radius,otherPlaces))continue;
    // Crown and a small edge verge must stay within the clipped destination.
    if(!inside(x,z)||Array.from({length:12},(_,i)=>i*Math.PI/6).some(a=>!inside(x+Math.cos(a)*(radius+1.5),z+Math.sin(a)*(radius+1.5))))continue;
    trees.push({id,x,y:islandElevation(x,z)-.04,z,size,angle:livingSeed(id,8)*Math.PI*2,radius,habitat,kind,detail:false});
  }
  return trees;
}
