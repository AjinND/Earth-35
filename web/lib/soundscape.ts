import {environmentAt} from "./environment";
import {COASTLINE,onIsland} from "./island";
import {FALLS,LAKE_SHORE,LANDSCAPES} from "./landscapes";
import {habitatAt,type Habitat} from "./living-environment";
import {RIVER_COURSE} from "./river-outlet";
import {segmentIndex} from "./segment-index";

export type SoundSource={gain:number;pan:number};
export type SoundMix={wind:number;rain:number;surf:SoundSource;river:SoundSource;waterfall:SoundSource;birds:SoundSource;gulls:SoundSource;insects:number};
type Point={x:number;z:number;yaw:number};
type Position={x:number;z:number};

const coast=[...COASTLINE,COASTLINE[0]],lake=[...LAKE_SHORE,LAKE_SHORE[0]];
// Static indices reuse the rendered shoreline and stop the river at its actual
// sea outlet. No vegetation plans or scene objects are rebuilt while listening.
const coastIndex=segmentIndex([coast]),lakeIndex=segmentIndex([lake]),riverIndex=segmentIndex([RIVER_COURSE]);
const clamp=(n:number)=>Math.max(0,Math.min(1,n));
const smooth=(low:number,high:number,value:number)=>{const t=clamp((value-low)/(high-low));return t*t*(3-2*t);};
const fade=(distance:number,near:number,far:number)=>1-smooth(near,far,distance);
const silent=():SoundSource=>({gain:0,pan:0});
const birdDensity:Record<Habitat,number>={city:.22,forest:.8,mountain:.3,coast:.12,meadow:.42,riverbank:.65};
const insectDensity:Record<Habitat,number>={city:.16,forest:.65,mountain:.1,coast:.08,meadow:.52,riverbank:.65};

function nearest(paths:readonly Position[],query:ReturnType<typeof segmentIndex>,point:Point) {
  const hit=query(point.x,point.z),a=paths[hit.index],b=paths[hit.index+1];
  return {distance:hit.distance,index:hit.index,fraction:hit.fraction,
    x:a.x+(b.x-a.x)*hit.fraction,z:a.z+(b.z-a.z)*hit.fraction};
}
function source(point:Point,position:Position,gain:number):SoundSource {
  const dx=position.x-point.x,dz=position.z-point.z,distance=Math.hypot(dx,dz);
  // A Three.js camera faces -Z at yaw=0; its right vector is (cos yaw,-sin yaw).
  const pan=distance>.001?(dx*Math.cos(point.yaw)-dz*Math.sin(point.yaw))/distance:0;
  return {gain:clamp(gain),pan:gain>0?Math.max(-1,Math.min(1,pan)):0};
}

/** Relative layer gains, sampled at listener position about four times a second.
 * Birds are occasional habitat calls, rather than voices for individual models.
 * The audio engine owns timing, smoothing and master volume.
 */
export function soundscapeAt(point:Point,minute:number,weather:string,island=true):SoundMix {
  const finite=(n:number)=>Number.isFinite(n)?n:0;
  const listener={x:Math.max(-1e6,Math.min(1e6,finite(point.x))),z:Math.max(-1e6,Math.min(1e6,finite(point.z))),yaw:finite(point.yaw)%(Math.PI*2)};
  const time=((finite(minute)%1440)+1440)%1440,{daylight,cloud,rain}=environmentAt(time,weather);
  // Keep daytime calls inside the same 05:30–19:30 activity window as wildlife.
  const calls=(1-rain)*smooth(330,390,time)*(1-smooth(1110,1170,time));
  const night=(1-rain)*(1-daylight);
  const mix:SoundMix={wind:.16+cloud*.24,rain:rain*.78,surf:silent(),river:silent(),waterfall:silent(),birds:silent(),gulls:silent(),insects:0};
  if(!island) {
    mix.birds=source(listener,{x:listener.x+12,z:listener.z-9},calls*birdDensity.city);
    mix.insects=night*insectDensity.city;
    return mix;
  }
  const shore=nearest(coast,coastIndex,listener),river=nearest(RIVER_COURSE,riverIndex,listener),lakeShore=nearest(lake,lakeIndex,listener);
  const inland=onIsland(listener),habitat=inland?habitatAt(listener.x,listener.z):"coast";
  mix.wind+=habitat==="mountain"?.12:habitat==="coast"?.08:0;
  mix.surf=source(listener,shore,fade(shore.distance,6,85)*.72);
  // The lake is still water; its shore gets a gentler version of the water layer.
  // Exclude the lake plateau and cliff drop from the ordinary flowing stream.
  const riverPosition=river.index+river.fraction;
  const lakeOrFalls=riverPosition>=LANDSCAPES.lakeRiverStart&&riverPosition<LANDSCAPES.cliffRiverEnd;
  const streamGain=lakeOrFalls?0:fade(river.distance,3,42)*.58;
  const lakeGain=fade(lakeShore.distance,2,32)*.16;
  mix.river=streamGain>=lakeGain?source(listener,river,streamGain):source(listener,lakeShore,lakeGain);
  mix.waterfall=source(listener,FALLS,fade(Math.hypot(listener.x-FALLS.x,listener.z-FALLS.z),8,115)*.88);
  mix.gulls=source(listener,shore,inland?calls*fade(shore.distance,5,70)*.46:0);
  if(inland) {
    mix.birds=source(listener,{x:listener.x+12,z:listener.z-9},calls*birdDensity[habitat]);
    mix.insects=night*insectDensity[habitat];
  }
  return mix;
}
