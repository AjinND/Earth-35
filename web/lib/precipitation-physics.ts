import {ATMOSPHERE_LIMITS,atmosphereSeed,type MistPatch} from "./atmosphere";

type Wind={x:number;z:number};
type Point={x:number;y:number;z:number};
const finite=(value:number,fallback=0)=>Number.isFinite(value)?value:fallback;
const fract=(value:number)=>((value%1)+1)%1;
const seed=(index:number,salt:number)=>{
  const value=atmosphereSeed(finite(index),salt);return Number.isFinite(value)?value:atmosphereSeed(0,salt);
};
const STREAK_SECONDS=.35,MIST_LIFETIME=18,MAX_MIST_DRIFT=12;

/** Map units per second: a deterministic terminal fall of 5.5–8.5 metres/second. */
export function rainVelocity(wind:Wind,index=0):Point {
  return {x:finite(wind.x)/10,y:-(5.5+3*seed(index,81))/10,z:finite(wind.z)/10};
}

// Reduce travel by its field-crossing period before multiplying. This keeps
// long-running clocks and unusual finite inputs inside the bounded rain field.
function travel(velocity:number,age:number,width:number) {
  if(!velocity)return 0;
  const period=width/Math.abs(velocity);
  return period>0&&Number.isFinite(period)?velocity*(age%period):finite(velocity*age);
}
const wrapped=(offset:number,width:number)=>((offset%width)+width)%width-width/2;

/** Stateless, seeded drops; scene geometry supplies any roof/terrain clipping. */
export function rainDrop(index:number,seconds:number,center:{x:number;z:number},bottom:number,top:number,wind:Wind) {
  const velocity=rainVelocity(wind,index),floor=finite(bottom),span=Math.max(.001,finite(finite(top,floor+28)-floor,28));
  const lifetime=finite(span/Math.abs(velocity.y),Number.MAX_VALUE),time=finite(seconds);
  const phase=fract(seed(index,7)-(time%lifetime)/lifetime),age=(1-phase)*lifetime;
  const width=ATMOSPHERE_LIMITS.rainRadius*2;
  const x=finite(center.x)+wrapped(seed(index,5)*width+travel(velocity.x,age,width),width);
  const z=finite(center.z)+wrapped(seed(index,6)*width+travel(velocity.z,age,width),width);
  const y=floor+phase*span;
  return {x,y,z,tail:{x:finite(x-velocity.x*STREAK_SECONDS,x),y:finite(y-velocity.y*STREAK_SECONDS,y),z:finite(z-velocity.z*STREAK_SECONDS,z)},velocity};
}

/** Bounded emitted vapor: recycle only when its smooth opacity envelope is zero. */
export function mistDrift(index:number,seconds:number,wind:Wind,kind:MistPatch["kind"]="river") {
  const phase=fract((finite(seconds)%MIST_LIFETIME)/MIST_LIFETIME+seed(index,91));
  const x=finite(wind.x),z=finite(wind.z),scale=Math.max(Math.abs(x),Math.abs(z),1);
  const length=Math.hypot(x/scale,z/scale),speed=scale*length;
  const distance=Math.min(MAX_MIST_DRIFT,speed/10*MIST_LIFETIME)*phase;
  return {x:length?x/scale/length*distance:0,y:kind==="falls"?phase*.8:0,z:length?z/scale/length*distance:0,opacity:Math.sin(Math.PI*phase)**2};
}
