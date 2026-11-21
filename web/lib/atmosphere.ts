import type {World} from "./types";
import {islandElevation,riverLevel,walkElevation} from "./island";
import {FALLS,LAKE,LANDSCAPES,lakeRadius,poolRadius} from "./landscapes";
import {RIVER,nearestRiver} from "./river";
import {enclosedPlace} from "./city-layout";
import {architecture} from "./architecture";
import {environmentAt} from "./environment";
import {renderedTerrainElevation} from "./island-terrain";

export const ATMOSPHERE_LIMITS={rainDesktop:420,rainMobile:200,rainRadius:46,rainCell:24,mist:48} as const;
export const atmosphereSeed=(index:number,salt:number)=>{const n=Math.sin(index*127.1+salt*311.7)*43758.5453;return n-Math.floor(n);};
type PrecipitationWorld=Pick<World,"buildings"|"geography">;
export type PrecipitationSampler=(x:number,z:number)=>number;
type Roof={x:number;z:number;height:number};
const roofHalf=4.4,roofCell=16;
const precipitationSamplers=new Map<string,PrecipitationSampler>();

/** Prepare once per occupied layout; queries retain exact terrain and roof heights. */
export function createPrecipitationSampler(world:PrecipitationWorld):PrecipitationSampler {
  const version=world.geography?.version??0;
  const buildings=world.buildings.filter(b=>enclosedPlace(b.type)||b.type==="construction");
  const key=JSON.stringify([version,world.geography?.centerX,world.geography?.centerZ,
    buildings.map(b=>[b.id,b.type,b.district,b.x,b.z,b.plannedType])]);
  const cached=precipitationSamplers.get(key);
  if(cached){precipitationSamplers.delete(key);precipitationSamplers.set(key,cached);return cached;}
  const columns=new Map<number,Map<number,Roof[]>>();
  for(const b of buildings) {
    if(!Number.isFinite(b.x)||!Number.isFinite(b.z))continue;
    const height=["house","apartment","office"].includes(b.type)?architecture(b.id,b.type,b.district).height+3.5:b.type==="construction"?22:b.type==="mall"?7:b.type==="studio"?9:7;
    const roof={x:b.x,z:b.z,height:(version>=2?islandElevation(b.x,b.z):0)+height};
    for(let col=Math.floor((b.x-roofHalf)/roofCell);col<=Math.floor((b.x+roofHalf)/roofCell);col++) {
      let cells=columns.get(col);if(!cells){cells=new Map();columns.set(col,cells);}
      for(let row=Math.floor((b.z-roofHalf)/roofCell);row<=Math.floor((b.z+roofHalf)/roofCell);row++) {
        const roofs=cells.get(row);if(roofs)roofs.push(roof);else cells.set(row,[roof]);
      }
    }
  }
  const sampler:PrecipitationSampler=(x,z)=>{
    let floor=version>=2?Math.max(-.6,walkElevation(x,z)):0;
    if(version>=4)floor=Math.max(floor,renderedTerrainElevation(x,z)??-Infinity);
    const r=nearestRiver(x,z);
    if(r.distance<r.width/2)floor=Math.max(floor,riverLevel(r.index,r.fraction));
    if(lakeRadius(x,z)<1)floor=Math.max(floor,LAKE.level);
    if(poolRadius(x,z)<1)floor=Math.max(floor,riverLevel(LANDSCAPES.lakeRiverEnd+1));
    const roofs=columns.get(Math.floor(x/roofCell))?.get(Math.floor(z/roofCell));
    if(roofs)for(const roof of roofs)if(Math.abs(x-roof.x)<=roofHalf&&Math.abs(z-roof.z)<=roofHalf)floor=Math.max(floor,roof.height);
    return floor+.15;
  };
  precipitationSamplers.set(key,sampler);
  if(precipitationSamplers.size>8)precipitationSamplers.delete(precipitationSamplers.keys().next().value!);
  return sampler;
}

/** Convenience query; animation loops should retain createPrecipitationSampler's result. */
export function precipitationFloor(x:number,z:number,world:PrecipitationWorld) {
  return createPrecipitationSampler(world)(x,z);
}
export type MistPatch={x:number;y:number;z:number;width:number;height:number;kind:"mountain"|"lake"|"river"|"falls"};
export function mistPatches():MistPatch[] {
  const patches:MistPatch[]=[];
  for(let i=0;i<12;i++) {
    const x=-410+atmosphereSeed(i,31)*370,z=-510+atmosphereSeed(i,32)*120;
    patches.push({x,z,y:islandElevation(x,z)+9,width:55,height:16,kind:"mountain"});
  }
  for(let i=0;i<8;i++) {
    const angle=i/8*Math.PI*2,x=LAKE.x+Math.cos(angle)*LAKE.radiusX*.72,z=LAKE.z+Math.sin(angle)*LAKE.radiusZ*.72;
    patches.push({x,z,y:LAKE.level+2,width:17,height:4,kind:"lake"});
  }
  const firstRiver=LANDSCAPES.cliffRiverEnd+2,riverStride=Math.ceil((RIVER.length-2-firstRiver)/20);
  for(let i=firstRiver;i<RIVER.length-2;i+=Math.max(1,riverStride)) {
    const p=RIVER[i];patches.push({x:p.x,z:p.z,y:riverLevel(i)+2,width:16,height:4,kind:"river"});
  }
  for(let i=0;i<6;i++)patches.push({x:FALLS.x+FALLS.dx*(2+i),z:FALLS.z+FALLS.dz*(2+i),y:riverLevel(LANDSCAPES.lakeRiverEnd+1)+2+i*.7,width:5+i,height:4+i*.3,kind:"falls"});
  return patches.slice(0,ATMOSPHERE_LIMITS.mist);
}
export function mistStrength(kind:MistPatch["kind"],minute:number,weather:string) {
  const {cloud}=environmentAt(minute,weather),time=((minute%1440)+1440)%1440;
  const morning=Math.max(0,1-Math.abs(time-360)/180);
  return kind==="falls"?.28:kind==="mountain"?.04+cloud*.24:.015+morning*.24+cloud*.09;
}
