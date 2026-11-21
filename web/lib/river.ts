import type {IslandPoint} from "./island";
import specification from "./island-landscapes.json";
import {segmentIndex} from "./segment-index";

const riverControls:readonly (readonly [number,number])[]=[[-205,-470],[-170,-423],[-80,-350],[-100,-295],[40,-242],[130,-165],[145,-76],[185,-5],[166,75],[233,145],[294,193],[380,230]];
export const RIVER:readonly IslandPoint[]=riverControls.flatMap((p,i)=>{
  if(i===riverControls.length-1)return [{x:p[0],z:p[1]}];
  const a=riverControls[Math.max(0,i-1)],b=riverControls[i+1],c=riverControls[Math.min(i+2,riverControls.length-1)];
  return Array.from({length:16},(_,j)=>{
    const t=j/16,t2=t*t,t3=t2*t;
    const axis=(k:number)=>.5*(2*p[k]+(-a[k]+b[k])*t+(2*a[k]-5*p[k]+4*b[k]-c[k])*t2+(-a[k]+3*p[k]-3*b[k]+c[k])*t3);
    return {x:axis(0),z:axis(1)};
  });
});
const riverIndex=segmentIndex([RIVER]);
export function nearestRiver(x:number,z:number) {
  const {distance,index,fraction}=riverIndex(x,z);
  return {distance,index,fraction,width:2.2+3.2*(index+fraction)/(RIVER.length-1)};
}
export const bridgeZones=specification.bridges.map(bridge=>bridge.z);
