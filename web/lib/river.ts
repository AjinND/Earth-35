import type {IslandPoint} from "./island";
import specification from "./island-landscapes.json";

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
export function nearestRiver(x:number,z:number) {
  let squaredDistance=Infinity,index=0,fraction=0;
  for(let i=1;i<RIVER.length;i++) {
    const a=RIVER[i-1],b=RIVER[i],dx=b.x-a.x,dz=b.z-a.z;
    const t=Math.max(0,Math.min(1,((x-a.x)*dx+(z-a.z)*dz)/(dx*dx+dz*dz)));
    const px=x-a.x-t*dx,pz=z-a.z-t*dz,d=px*px+pz*pz;
    if(d<squaredDistance){squaredDistance=d;index=i-1;fraction=t;}
  }
  return {distance:Math.sqrt(squaredDistance),index,fraction,width:2.2+3.2*(index+fraction)/(RIVER.length-1)};
}
export const bridgeZones=specification.bridges.map(bridge=>bridge.z);
