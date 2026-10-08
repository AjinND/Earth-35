// Deterministic island geometry; the server owns the occupied city layout.
import {RIVER,nearestRiver} from "./river";
import {LAKE,LANDSCAPES,lakeRadius,FALLS,poolRadius,coastalWeight,BRIDGE_RAMP_LENGTH,type LandscapeBridge} from "./landscapes";
export type IslandPoint = {x:number;z:number};
export const METERS_PER_MAP_UNIT = 10;
export const ISLAND_AREA_KM2 = 100;
export const ISLAND_CENTER = {x:-170,z:-100};

const outline: readonly [number,number][] = [
  [-590,-150],[-555,-310],[-455,-390],[-430,-525],[-285,-580],[-145,-545],
  [-50,-610],[90,-570],[155,-455],[285,-415],[330,-300],[440,-240],
  [475,-90],[415,15],[460,130],[350,230],[320,370],[175,440],
  [60,395],[-45,460],[-170,425],[-245,320],[-380,310],[-415,195],
  [-535,130],[-500,5],
];
export function polygonArea(points:readonly IslandPoint[]) {
  return Math.abs(points.reduce((area,p,i)=>{
    const next=points[(i+1)%points.length];return area+p.x*next.z-next.x*p.z;
  },0))/2;
}
// A closed Catmull–Rom outline, sampled once and normalized by area.
function smoothOutline() {
  const points:IslandPoint[]=[];
  for(let i=0;i<outline.length;i++) {
    const p0=outline[(i-1+outline.length)%outline.length],p1=outline[i];
    const p2=outline[(i+1)%outline.length],p3=outline[(i+2)%outline.length];
    for(let j=0;j<8;j++) {
      const t=j/8,t2=t*t,t3=t2*t;
      const axis=(a:number)=>.5*((2*p1[a])+(-p0[a]+p2[a])*t+(2*p0[a]-5*p1[a]+4*p2[a]-p3[a])*t2+(-p0[a]+3*p1[a]-3*p2[a]+p3[a])*t3);
      points.push({x:axis(0),z:axis(1)});
    }
  }
  const scale=Math.sqrt(ISLAND_AREA_KM2*1e6/(METERS_PER_MAP_UNIT**2)/polygonArea(points));
  return points.map(p=>({x:p.x*scale+ISLAND_CENTER.x,z:p.z*scale+ISLAND_CENTER.z}));
}
export const COASTLINE:readonly IslandPoint[]=smoothOutline();
export const ISLAND_BOUNDS={
  minX:Math.min(...COASTLINE.map(p=>p.x)),maxX:Math.max(...COASTLINE.map(p=>p.x)),
  minZ:Math.min(...COASTLINE.map(p=>p.z)),maxZ:Math.max(...COASTLINE.map(p=>p.z)),
};
export function onIsland({x,z}:IslandPoint) {
  let inside=false;
  for(let i=0,j=COASTLINE.length-1;i<COASTLINE.length;j=i++) {
    const a=COASTLINE[i],b=COASTLINE[j];
    if((a.z>z)!==(b.z>z)&&x<(b.x-a.x)*(z-a.z)/(b.z-a.z)+a.x)inside=!inside;
  }
  return inside;
}
export function coastDistance({x,z}:IslandPoint) {
  let nearest=Infinity;
  for(let i=0;i<COASTLINE.length;i++) {
    const a=COASTLINE[i],b=COASTLINE[(i+1)%COASTLINE.length],dx=b.x-a.x,dz=b.z-a.z;
    const t=Math.max(0,Math.min(1,((x-a.x)*dx+(z-a.z)*dz)/(dx*dx+dz*dz)));
    nearest=Math.min(nearest,Math.hypot(x-a.x-dx*t,z-a.z-dz*t));
  }
  return nearest;
}
export function naturalElevation(x:number,z:number) {
  // Keep every existing street, entrance and scenic approach on its original ground.
  const settlement=Math.max(Math.abs(x+150)-288,Math.abs(z)-288,0);
  const fade=Math.min(1,settlement/75);
  if(fade===0)return 0;
  const peak=(cx:number,cz:number,height:number,rx:number,rz:number)=>height*Math.exp(-(((x-cx)/rx)**2)-(((z-cz)/rz)**2));
  const relief=peak(-370,-390,76,130,120)+peak(-205,-465,96,105,120)+peak(-50,-420,55,120,85)
    +peak(-475,-180,30,150,190)+peak(-260,140,18,190,140)
    +LANDSCAPES.terrainRelief.reduce((sum,r)=>sum+peak(r.x,r.z,r.height,r.radiusX,r.radiusZ),0);
  const ridges=1-Math.min(1,Math.max(0,(relief-32)/28))*.22*(1-Math.abs(Math.sin((x+z*.45)*.04)));
  const coast=coastDistance({x,z});let coastFade=Math.min(1,coast/55);
  for(const zone of LANDSCAPES.coastalZones)if(zone.kind!=="cove") {
    const target=Math.min(1,coast/(zone.kind==="cliff"?6:18));
    coastFade+=(target-coastFade)*coastalWeight(zone,x,z);
  }
  let ground=fade*Math.max(0,relief)*ridges*coastFade;
  for(const zone of LANDSCAPES.coastalZones)if(zone.kind==="cove")ground+=(coast*.045-ground)*coastalWeight(zone,x,z)*(1-Math.min(1,coast/45));
  return ground;
}
// Water always descends. Terrain and the detailed river bed share these levels.
const riverLevels=RIVER.reduce<number[]>((levels,p)=>{
  const index=levels.length;
  const natural=onIsland(p)?Math.max(.14,naturalElevation(p.x,p.z)-.4):-.65;
  const level=index>=LANDSCAPES.lakeRiverStart&&index<=LANDSCAPES.lakeRiverEnd?LAKE.level:natural;
  levels.push(Math.min(levels.at(-1)??Infinity,level));return levels;
},[]);
export const riverLevel=(index:number,fraction=0)=>{
  // The lake outlet crosses a short rock lip, then drops across an actual cliff.
  const t=index===LANDSCAPES.lakeRiverEnd?Math.min(1,Math.max(0,(fraction-.48)/.04)):fraction;
  return riverLevels[index]+((riverLevels[index+1]??riverLevels[index])-riverLevels[index])*t;
};
export function islandElevation(x:number,z:number) {
  let ground=naturalElevation(x,z);
  const radius=lakeRadius(x,z);
  if(radius<1.55) {
    // A submerged basin and continuous raised rim meet the existing hillside.
    const bed=LAKE.level-1.8;
    const rim=LAKE.level+.7;
    ground=radius<1?bed+(rim-bed)*Math.max(0,(radius-.88)/.12):rim+(ground-rim)*Math.min(1,(radius-1)/.55);
  }
  const r=nearestRiver(x,z),position=r.index+r.fraction;
  const level=riverLevel(r.index,r.fraction),inLake=radius<1&&level<=LAKE.level+.1;
  // The lake's rim used to lower the inlet terrain beneath its incoming water.
  // Support the whole highland channel, including that transition, with rock.
  if(position<LANDSCAPES.cliffRiverEnd+2&&!inLake&&r.distance<r.width/2+7) {
    const bank=level+.7;
    const blend=Math.min(1,Math.max(0,(r.distance-r.width/2)/7));
    ground=bank+(ground-bank)*blend;
  }
  const pool=poolRadius(x,z),downstream=(x-FALLS.x)*FALLS.dx+(z-FALLS.z)*FALLS.dz;
  if(pool<1.35&&downstream>=0) {
    const level=riverLevel(LANDSCAPES.lakeRiverEnd+1),bed=level-1.25,rim=level+.3;
    ground=pool<1?bed+(rim-bed)*Math.max(0,(pool-.72)/.28):rim+(ground-rim)*Math.min(1,(pool-1)/.35);
  }
  if(r.distance>r.width/2+4)return ground;
  const channel=level-.6;
  if(position<LANDSCAPES.cliffRiverEnd+2&&!inLake&&pool>=1)return channel+(ground-channel)*Math.min(1,Math.max(0,(r.distance-r.width/2)/4));
  return Math.min(ground,channel+(ground-channel)*Math.min(1,Math.max(0,(r.distance-r.width/2)/4)));
}
export function walkElevation(x:number,z:number) {
  for(const bridge of LANDSCAPES.bridges)if(Math.abs(z-bridge.z)<=bridge.width/2+1e-9) {
    const end=Math.max(0,Math.abs(x-bridge.x)-bridge.length/2);
    if(end<BRIDGE_RAMP_LENGTH) {
      const deck=bridgeElevation(bridge);
      return end===0?deck:deck+(islandElevation(x,z)-deck)*end/BRIDGE_RAMP_LENGTH;
    }
  }
  return islandElevation(x,z);
}
export function bridgeElevation(bridge:LandscapeBridge) {
  const r=nearestRiver(bridge.x,bridge.z);return riverLevel(r.index,r.fraction)+.55;
}
export const islandLandmarks=[
  {id:"northern-ridge",name:"Northern ridge",x:-225,z:-450},
  {id:"western-woods",name:"Western woods",x:-595,z:-100},
  {id:"south-coast",name:"South coast",x:-150,z:350},
  {id:"cloudmere",name:"Cloudmere Lake",x:LAKE.x,z:LAKE.z},
  {id:"silverstream-falls",name:"Silverstream Falls",x:-96,z:-367},
  ...LANDSCAPES.coastalZones.map(zone=>({id:zone.id,name:zone.name,x:zone.x,z:zone.z})),
  {id:"fern-valley",name:"Fern Valley",x:-550,z:-245},
] as const;
export const islandPath=COASTLINE.map((p,i)=>`${i?"L":"M"}${p.x.toFixed(2)},${p.z.toFixed(2)}`).join(" ")+" Z";
