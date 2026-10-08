import type {Building} from "./types";

export const scenicTypes=["river","riverside_walk","mountain","hill_station","trekking_trail"];
export const isScenicPlace=(type:string)=>scenicTypes.includes(type);
export function riverBounds(buildings:readonly Building[]) {
  const stops=buildings.filter(b=>b.type==="river"||b.type==="riverside_walk");
  if(!stops.length)return null;
  return {bankX:stops[0].x,from:Math.min(...stops.map(b=>b.z))-15,to:Math.max(...stops.map(b=>b.z))+15};
}
export const riverOffset=(z:number)=>3.8+Math.sin(z*.12)*.45;
// The visitor walks on the bank and lookout paths, rather than through water or peaks.
export function scenicWalkable(point:{x:number;z:number},buildings:readonly Building[]) {
  const river=riverBounds(buildings);
  if(river&&point.z>=river.from&&point.z<=river.to&&Math.abs(point.x-river.bankX-riverOffset(point.z))<2.3)return false;
  return !buildings.some(b=>{
    const peaks=b.type==="mountain"?[[0,-13,5],[-4.3,-13.5,3.5]]:
      b.type==="hill_station"?[[0,-13,4.8],[3,-13,3.5],[-2.7,-14,3.2]]:[];
    return peaks.some(([x,z,radius])=>Math.hypot(point.x-b.x-x,point.z-b.z-z)<radius);
  });
}
