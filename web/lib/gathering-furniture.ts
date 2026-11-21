import type {Agent,Building} from "./types";
import {boxFootprint,footprintsOverlap,type ObjectFootprint} from "./object-footprint";

export type BenchPlacement={x:number;z:number;rotation:number};
export type Seat={x:number;y:number;z:number;rotation:number};
export function gatheringBenches(type:string):BenchPlacement[] {
  if(["river","riverside_walk","mountain","hill_station","trekking_trail"].includes(type))return [{x:-2.2,z:2.5,rotation:0}];
  if(type==="park")return [{x:-3.2,z:0,rotation:Math.PI/2},{x:3.2,z:0,rotation:-Math.PI/2}];
  if(type==="promenade")return [{x:-2.5,z:-1.5,rotation:Math.PI/2},{x:2.5,z:1.5,rotation:-Math.PI/2}];
  if(["plaza","garden","hangout"].includes(type))return [{x:-2.2,z:type==="plaza"?2.8:2.2,rotation:0},{x:2.2,z:type==="plaza"?2.8:2.2,rotation:0}];
  return [];
}
export const gatheringTable={x:0,z:0,width:3.5,depth:1.8};
export function benchFootprint(origin:{x:number;z:number},bench:BenchPlacement,padding=0) {
  // Seat and back together occupy local z [-.425,.4].
  return boxFootprint(origin.x+bench.x-Math.sin(bench.rotation)*.0125,origin.z+bench.z-Math.cos(bench.rotation)*.0125,2.8,.825,bench.rotation,padding);
}
export function gatheringFixedObstacles(b:Building):ObjectFootprint[] {
  const expanded=(b.outdoorRadius??0)>0,shapes:ObjectFootprint[]=[];
  if(b.id==="park"||b.type==="plaza")shapes.push({kind:"circle",x:b.x,z:b.z,radius:2});
  if(!expanded&&b.type==="garden")for(const x of [-2.7,0,2.7])shapes.push(boxFootprint(b.x+x,b.z-2.8,2,2.3));
  if(!expanded&&(b.type==="garden"||b.type==="hangout"))shapes.push(boxFootprint(b.x+gatheringTable.x,b.z+gatheringTable.z,gatheringTable.width,gatheringTable.depth));
  if(b.type==="hangout")for(const x of expanded?[-4,4]:[-3.5,3.5])for(const z of [-3,3])shapes.push(boxFootprint(b.x+x,b.z+z,expanded?.25:.3,expanded?.25:.3));
  return shapes;
}
export function gatheringDecorationClear(b:Building,x:number,z:number,radius:number):boolean {
  const decoration={x:b.x+x,z:b.z+z,radius};
  return [...gatheringFixedObstacles(b),...gatheringBenches(b.type).map(p=>benchFootprint(b,p))].every(shape=>!footprintsOverlap(decoration,shape,.25));
}
// Both the furniture meshes and resident placement use the same local layout.
export function gatheringSeats(building:Building):Seat[] {
  return gatheringBenches(building.type).flatMap(b=>[-.7,.7].map(offset=>({
    x:building.x+b.x+offset*Math.cos(b.rotation),z:building.z+b.z-offset*Math.sin(b.rotation),
    y:.83,rotation:b.rotation,
  })));
}
/** Render reserved seats; older snapshots retain the finite visual fallback. */
export function restingSeats(agents:readonly Agent[],buildings:readonly Building[]):Map<string,Seat> {
  const assigned=new Map<string,Seat>();
  for(const building of buildings) {
    const seats=gatheringSeats(building);
    const resting=agents.filter(a=>a.location===building.id&&(a.activity==="REST"||a.activity==="READ_BOOK"))
      .sort((a,b)=>a.id<b.id?-1:a.id>b.id?1:0);
    const occupied=new Set<number>();
    for(const a of resting)if(a.seatIndex!=null&&seats[a.seatIndex]&&!occupied.has(a.seatIndex)) {
      assigned.set(a.id,seats[a.seatIndex]);occupied.add(a.seatIndex);
    }
    for(const a of resting)if(a.seatIndex===undefined) {
      const index=seats.findIndex((_,i)=>!occupied.has(i));
      if(index>=0){assigned.set(a.id,seats[index]);occupied.add(index);}
    }
  }
  return assigned;
}
/** A desk chair is occupied only after the server places this resident at its stance. */
export function workspaceSeats(agents:readonly Agent[],buildings:readonly Building[]):Map<string,Seat> {
  const assigned=new Map<string,Seat>();
  for(const resident of agents) {
    if(resident.performance?.posture!=="seated"||resident.performance.phase==="approach")continue;
    const building=buildings.find(place=>place.id===resident.location);
    const station=building?.workspaces?.find(workspace=>workspace.kind==="desk"&&Math.hypot(resident.x-workspace.x,resident.z-workspace.z)<.05);
    if(station)assigned.set(resident.id,{x:resident.x,z:resident.z,y:.83,rotation:station.heading});
  }
  return assigned;
}
export function fountainObstacles(buildings:readonly Building[]) {
  // Basin radius plus room for the plush body and selection ring.
  return buildings.filter(b=>b.id==="park"||b.type==="plaza").map(b=>({x:b.x,z:b.z,radius:2.55}));
}
