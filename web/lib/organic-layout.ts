import {onIsland,type IslandPoint} from "./island";

// A smooth, invertible street survey. Transit keeps its logical lane coordinates;
// the server and both maps use this same survey to bend the occupied streets.
export function organicPoint(x:number,z:number):IslandPoint {
  const u=x+150;
  return {x:-150+u*.82+18*Math.sin(z/76)+12*Math.sin((u+z)/110),
    z:z*.9+23*Math.sin(u/82)+12*Math.sin((u-z)/100)};
}
export function surveyPoint(x:number,z:number):IslandPoint {
  let u=(x+150)/.82,v=z/.9;
  for(let i=0;i<18;i++) {
    u=(x+150-18*Math.sin(v/76)-12*Math.sin((u+v)/110))/.82;
    v=(z-23*Math.sin(u/82)-12*Math.sin((u-v)/100))/.9;
  }
  return {x:u-150,z:v};
}
// Large shared blocks and T junctions, with continuous connectors between them.
export const streetSurveys:readonly (readonly [number,number,number,number])[]=[
  [-390,-240,90,-240],[90,-240,90,240],[90,240,-390,240],[-390,240,-390,-240],
  [-342,-240,-342,240],[-54,-240,-54,144],[42,-96,42,240],
  [-342,-192,90,-192],[-390,-96,90,-96],[-342,144,90,144],
  [-246,-192,-246,48],[-150,-96,-150,144],[-390,48,-150,48],[-150,0,42,0],
];
export const organicStreets=streetSurveys.map(([x,z,nx,nz])=>{
  const steps=Math.ceil(Math.hypot(nx-x,nz-z)/4);
  return Array.from({length:steps+1},(_,i)=>organicPoint(x+(nx-x)*i/steps,z+(nz-z)*i/steps));
});
export const svgLine=(points:readonly IslandPoint[])=>points.map((p,i)=>`${i?"L":"M"}${p.x},${p.z}`).join(" ");
export function streetDistance(x:number,z:number) {
  let squaredDistance=Infinity;
  for(const points of organicStreets)for(let i=1;i<points.length;i++) {
    const a=points[i-1],b=points[i],dx=b.x-a.x,dz=b.z-a.z;
    const t=Math.max(0,Math.min(1,((x-a.x)*dx+(z-a.z)*dz)/(dx*dx+dz*dz)));
    const px=x-a.x-t*dx,pz=z-a.z-t*dz;
    squaredDistance=Math.min(squaredDistance,px*px+pz*pz);
  }
  return Math.sqrt(squaredDistance);
}

export {RIVER,nearestRiver,bridgeZones} from "./river";
import {nearestRiver} from "./river";
import {landscapeClear,bridgeClear} from "./landscapes";
export function dryLand(point:IslandPoint) {
  if(!onIsland(point)||!landscapeClear(point.x,point.z))return false;
  const r=nearestRiver(point.x,point.z);
  return r.distance>r.width/2+.35||bridgeClear(point.x,point.z);
}
