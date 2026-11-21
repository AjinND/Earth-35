import {environmentAt} from "./environment";

export type SkyVector=[number,number,number];
const TAU=Math.PI*2;
const wrap=(value:number,period:number)=>((value%period)+period)%period;
const unit=(x:number,y:number,z:number):SkyVector=>{const n=Math.hypot(x,y,z);return [x/n,y/n,z/n];};

// Art-directed apparent radii and inclined prograde tracks. The epoch is world
// minute zero, never a browser date. Inclined tracks keep the bodies distinct.
export const MOONS=[
  {id:"selene",name:"Selene",periodDays:23,radius:.032,color:"#d9e4ef",offset:.27,tilt:.10},
  {id:"ember",name:"Ember",periodDays:9,radius:.020,color:"#dfb797",offset:.70,tilt:.18},
] as const;

export function skyAt(minute:number,weather:string) {
  const environment=environmentAt(minute,weather);
  const sun=unit(...environment.sun);
  const moons=MOONS.map(moon=>{
    // Phase cycle relative to the daily apparent motion of the sun.
    const orbital=wrap(minute,moon.periodDays*1440)/(moon.periodDays*1440)*TAU;
    const daily=wrap(minute,1440)/1440*TAU;
    const angle=daily+Math.PI/2+moon.offset-orbital;
    const direction=unit(Math.cos(angle),Math.sin(angle),Math.sin(angle)*40/110+moon.tilt*Math.sin(orbital+moon.offset));
    const illuminated=(1-direction.reduce((sum,v,i)=>sum+v*sun[i],0))/2;
    return {...moon,direction,illuminated,aboveHorizon:direction[1]>0};
  });
  // Daylight overwhelms the discs' contrast, just as it already does moonlight.
  // Keep the fade tied to solar elevation so cloudy days cannot reveal dark discs.
  const moonVisibility=1-environment.daylight;
  return {...environment,sunDirection:sun,moons,moonVisibility,stars:moonVisibility*(1-environment.cloud)**2};
}
