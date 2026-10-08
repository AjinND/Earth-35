import specification from "./island-landscapes.json";
import type {IslandPoint} from "./island";
import {nearestRiver,RIVER} from "./river";

// The same versioned specification is loaded by IslandLandscapes on the server.
export const LANDSCAPES=specification;
export const LAKE=specification.lake;
export const HIGHLAND_TRAIL:readonly IslandPoint[]=specification.trail.map(([x,z])=>({x,z}));
export const HIGHLAND_TRAILS:readonly (readonly IslandPoint[])[]=[HIGHLAND_TRAIL,specification.fallsTrail.map(([x,z])=>({x,z}))];
export const CONNECTED_TRAILS=specification.trails.map(trail=>({...trail,points:trail.points.map(([x,z])=>({x,z}))}));
export const ALL_TRAILS:readonly (readonly IslandPoint[])[]=[...HIGHLAND_TRAILS,...CONNECTED_TRAILS.map(trail=>trail.points)];
export type LandscapeBridge=typeof specification.bridges[number];
export const BRIDGE_RAMP_LENGTH=4;
export function bridgeClear(x:number,z:number) {
  return specification.bridges.some(bridge=>Math.abs(x-bridge.x)<=bridge.length/2+1e-9&&Math.abs(z-bridge.z)<=bridge.width/2+1e-9);
}
export function coastalWeight(zone:typeof specification.coastalZones[number],x:number,z:number) {
  return Math.max(0,Math.min(1,(1.3-Math.hypot((x-zone.x)/zone.radiusX,(z-zone.z)/zone.radiusZ))/.3));
}
// Keep the plunge pool inside the existing inaccessible waterfall corridor.
const lip=RIVER[LANDSCAPES.lakeRiverEnd],foot=RIVER[LANDSCAPES.lakeRiverEnd+1];
const fallLength=Math.hypot(foot.x-lip.x,foot.z-lip.z);
export const FALLS={
  x:lip.x+(foot.x-lip.x)*.52,z:lip.z+(foot.z-lip.z)*.52,
  dx:(foot.x-lip.x)/fallLength,dz:(foot.z-lip.z)/fallLength,
  poolOffset:1.25,poolLength:2,poolWidth:4.2,
};
export function poolPoint(angle:number,radius=1) {
  const edge=1+.055*Math.sin(angle*3)+.035*Math.cos(angle*5);
  const along=FALLS.poolOffset+Math.cos(angle)*FALLS.poolLength*edge*radius;
  const across=Math.sin(angle)*FALLS.poolWidth*edge*radius;
  return {x:FALLS.x+FALLS.dx*along+FALLS.dz*across,z:FALLS.z+FALLS.dz*along-FALLS.dx*across};
}
export function poolRadius(x:number,z:number) {
  const dx=x-FALLS.x,dz=z-FALLS.z;
  const along=(dx*FALLS.dx+dz*FALLS.dz-FALLS.poolOffset)/FALLS.poolLength;
  const across=(dx*FALLS.dz-dz*FALLS.dx)/FALLS.poolWidth,angle=Math.atan2(across,along);
  return Math.hypot(along,across)/(1+.055*Math.sin(angle*3)+.035*Math.cos(angle*5));
}
export function lakeRadius(x:number,z:number) {
  const dx=(x-LAKE.x)/LAKE.radiusX,dz=(z-LAKE.z)/LAKE.radiusZ,angle=Math.atan2(dz,dx);
  return Math.hypot(dx,dz)/(1+.09*Math.sin(angle*3)+.045*Math.cos(angle*5));
}
export const LAKE_SHORE:readonly IslandPoint[]=Array.from({length:96},(_,i)=>{
  const angle=i/96*Math.PI*2,radius=1+.09*Math.sin(angle*3)+.045*Math.cos(angle*5);
  return {x:LAKE.x+Math.cos(angle)*LAKE.radiusX*radius,z:LAKE.z+Math.sin(angle)*LAKE.radiusZ*radius};
});
export function landscapeClear(x:number,z:number) {
  if(lakeRadius(x,z)<1.035)return false;
  if(specification.protectedCliffs.some(cliff=>Math.hypot((x-cliff.x)/cliff.radiusX,(z-cliff.z)/cliff.radiusZ)<1))return false;
  const river=nearestRiver(x,z),position=river.index+river.fraction;
  return !(position>=LANDSCAPES.lakeRiverEnd&&position<LANDSCAPES.cliffRiverEnd&&river.distance<river.width/2+4.5);
}
export function trailDistance(x:number,z:number) {
  let distance=Infinity;
  for(const trail of ALL_TRAILS)for(let i=1;i<trail.length;i++) {
    const a=trail[i-1],b=trail[i],dx=b.x-a.x,dz=b.z-a.z;
    const t=Math.max(0,Math.min(1,((x-a.x)*dx+(z-a.z)*dz)/(dx*dx+dz*dz)));
    distance=Math.min(distance,Math.hypot(x-a.x-t*dx,z-a.z-t*dz));
  }
  return distance;
}
