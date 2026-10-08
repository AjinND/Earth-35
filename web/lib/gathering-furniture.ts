import type {Agent,Building} from "./types";

export type BenchPlacement={x:number;z:number;rotation:number};
export type Seat={x:number;y:number;z:number;rotation:number};
export function gatheringBenches(type:string):BenchPlacement[] {
  if(["river","riverside_walk","mountain","hill_station","trekking_trail"].includes(type))return [{x:-2.2,z:2.5,rotation:0}];
  if(type==="park")return [{x:-3.2,z:0,rotation:Math.PI/2},{x:3.2,z:0,rotation:-Math.PI/2}];
  if(type==="promenade")return [{x:-2.5,z:-1.5,rotation:Math.PI/2},{x:2.5,z:1.5,rotation:-Math.PI/2}];
  if(["plaza","garden","hangout"].includes(type))return [{x:-2.2,z:2.2,rotation:0},{x:2.2,z:2.2,rotation:0}];
  return [];
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
export function fountainObstacles(buildings:readonly Building[]) {
  // Basin radius plus room for the plush body and selection ring.
  return buildings.filter(b=>b.id==="park"||b.type==="plaza").map(b=>({x:b.x,z:b.z,radius:2.55}));
}
