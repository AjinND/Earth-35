import {PlaybackTimeline} from "./playback-timeline";
import {ISLAND_CENTER,METERS_PER_MAP_UNIT} from "./island";

export type LightningStrike={id:number;minute:number;x:number;z:number;height:number;energy:number};
export type StormFrame={strike:LightningStrike|null;age:number;flash:number};
export type ThunderSound={delay:number;gain:number;pan:number;cutoff:number;seed:number};
export const STORM_PERIOD=8;
export const STORM_MIN_GAP=12;
const clamp=(n:number,min:number,max:number)=>Math.max(min,Math.min(max,n));
const seed=(id:number,salt:number)=>{let n=(Math.imul(id|0,1664525)+Math.imul(salt,1013904223))|0;n=Math.imul(n^(n>>>16),0x45d9f3b);return ((n^(n>>>16))>>>0)/4294967296;};
const empty=():StormFrame=>({strike:null,age:0,flash:0});

/** One shared, irregularly spaced distant strike per eight received world minutes. */
export function lightningForSlot(id:number):LightningStrike {
  const angle=seed(id,3)*Math.PI*2,radius=120+seed(id,4)*170;
  return {id,minute:id*STORM_PERIOD+2+Math.floor(seed(id,1)*4)+.2+seed(id,2)*.35,
    x:ISLAND_CENTER.x+Math.cos(angle)*radius,z:ISLAND_CENTER.z+Math.sin(angle)*radius,
    height:390+seed(id,5)*170,energy:.7+seed(id,6)*.3};
}
export function lightningAt(minute:number,weather:string):LightningStrike|null {
  if(weather!=="Rain"||!Number.isFinite(minute))return null;
  const strike=lightningForSlot(Math.floor(minute/STORM_PERIOD));
  return minute>=strike.minute?strike:null;
}
export function lightningFlash(age:number) {
  if(!Number.isFinite(age)||age<0||age>=1.25)return 0;
  if(age<.06)return age/.06;
  if(age<=.22)return 1;
  return Math.exp(-(age-.22)*5)*clamp((1.25-age)/.25,0,1);
}
export function thunderAt(strike:LightningStrike,listener:{x:number;z:number;yaw:number},age=0):ThunderSound {
  const dx=strike.x-listener.x,dz=strike.z-listener.z,distance=Math.hypot(dx,dz),meters=distance*METERS_PER_MAP_UNIT;
  return {delay:Math.max(0,clamp(meters/340,1,9)-Math.max(0,age)),gain:clamp(strike.energy*.55/(1+(meters/2500)**1.25),0,.55),
    pan:distance>.001?clamp((dx*Math.cos(listener.yaw)-dz*Math.sin(listener.yaw))/distance,-1,1):0,
    cutoff:350+1800/(1+meters/1100),seed:seed(strike.id,7)};
}

/** Wait for received clock intervals; real-time envelopes always finish, even if updates stop. */
export class StormPlayback {
  private timeline=new PlaybackTimeline();
  private latest:number|null=null;
  private previous:number|null=null;
  private frameTime:number|null=null;
  private raining=false;
  private tickMs=5000;
  private strike:LightningStrike|null=null;
  private startTime=0;
  private lastStrikeTime=-Infinity;

  ingest(minute:number,tickMs:number,weather:string,now:number) {
    if(!Number.isFinite(minute)||!Number.isFinite(now))return;
    const pace=Number.isFinite(tickMs)?Math.max(100,tickMs):5000;
    if(this.latest!==null&&minute<this.latest){this.timeline=new PlaybackTimeline();this.lastStrikeTime=-Infinity;}
    this.timeline.ingest(minute,pace,now);this.tickMs=pace;
    const raining=weather==="Rain";
    if(this.latest===null||raining!==this.raining||minute>this.latest+1||minute<this.latest) {
      this.strike=null;this.previous=this.timeline.sample(now);this.frameTime=now;
    }
    this.latest=minute;this.raining=raining;
  }

  sample(now:number,hidden=false):StormFrame {
    const cursor=this.timeline.sample(now);
    const gap=this.frameTime!==null&&now-this.frameTime>Math.max(this.tickMs*1.5,1500);
    if(!this.raining||hidden||gap) {
      this.strike=null;this.previous=cursor;this.frameTime=now;return empty();
    }
    const candidate=lightningAt(cursor,"Rain");
    if(candidate&&this.previous!==null&&this.previous<candidate.minute&&cursor>=candidate.minute&&now-this.lastStrikeTime>=STORM_MIN_GAP*1000) {
      this.strike=candidate;this.startTime=now-(cursor-candidate.minute)*this.tickMs;this.lastStrikeTime=now;
    }
    this.previous=cursor;this.frameTime=now;
    if(!this.strike)return empty();
    const age=Math.max(0,(now-this.startTime)/1000);
    if(age>15){this.strike=null;return empty();}
    return {strike:this.strike,age,flash:lightningFlash(age)*this.strike.energy};
  }
}

// One per-canvas observation joins visuals to the first-person audio listener.
// Map and first-person canvases never create or dispatch each other's thunder.
const frames=new WeakMap<HTMLCanvasElement,StormFrame>();
export const publishStormFrame=(canvas:HTMLCanvasElement,frame:StormFrame)=>frames.set(canvas,frame);
export const readStormFrame=(canvas:HTMLCanvasElement)=>frames.get(canvas);
export const clearStormFrame=(canvas:HTMLCanvasElement)=>frames.delete(canvas);

export function lightningSegments(strike:LightningStrike) {
  const points:Array<[number,number,number]>=[];
  for(let i=0;i<=12;i++) {
    const t=i/12,spread=Math.sin(t*Math.PI)*25;
    points.push([(seed(strike.id,i+20)-.5)*spread,strike.height*(1-t)+90,(seed(strike.id,i+40)-.5)*spread]);
  }
  const lines:number[]=[];
  for(let i=1;i<points.length;i++)lines.push(...points[i-1],...points[i]);
  for(const index of [4,7,9]) {
    const a=points[index],side=seed(strike.id,index+60)>.5?1:-1;
    const b:[number,number,number]=[a[0]+side*32,a[1]-28,a[2]+14];
    lines.push(...a,...b,...b,b[0]+side*18,b[1]-35,b[2]-8);
  }
  return new Float32Array(lines);
}
