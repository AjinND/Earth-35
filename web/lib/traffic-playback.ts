import type {Vehicle} from "./types";
import {PlaybackTimeline} from "./playback-timeline";

export type TrafficPose={x:number;z:number;heading:number;stop?:string};
type Segment={vehicles:Vehicle[];smooth:boolean[]};
const pose=(v:Vehicle,step:number):TrafficPose=>v.motion?.[step]??v;
const blend=(a:TrafficPose,b:TrafficPose,t:number):TrafficPose=>({
  x:a.x+(b.x-a.x)*t,z:a.z+(b.z-a.z)*t,
  heading:a.heading+Math.atan2(Math.sin(b.heading-a.heading),Math.cos(b.heading-a.heading))*t,
});

function overlap(a:TrafficPose,av:Vehicle,b:TrafficPose,bv:Vehicle) {
  const al=av.type==="bus"?2.55:1.425,bl=bv.type==="bus"?2.55:1.425;
  const af=[Math.sin(a.heading),Math.cos(a.heading)],ar=[af[1],-af[0]];
  const bf=[Math.sin(b.heading),Math.cos(b.heading)],br=[bf[1],-bf[0]];
  const dot=(u:number[],v:number[])=>u[0]*v[0]+u[1]*v[1];
  return [af,ar,bf,br].every(axis=>Math.abs((a.x-b.x)*axis[0]+(a.z-b.z)*axis[1])<
    al*Math.abs(dot(af,axis))+.76*Math.abs(dot(ar,axis))+bl*Math.abs(dot(bf,axis))+.76*Math.abs(dot(br,axis)));
}

/** One monotonic timeline for all vehicles, independent of React/world broadcasts. */
export class TrafficPlayback {
  private latest:number|null=null;
  private initial:Vehicle[]=[];
  private segments=new Map<number,Segment>();

  constructor(private readonly timeline=new PlaybackTimeline()) {}

  ingest(minute:number,vehicles:Vehicle[],tickMs:number,now:number) {
    this.timeline.ingest(minute,tickMs,now);
    // Agent actions and reconnects can broadcast the same traffic snapshot repeatedly.
    if(this.latest!==null&&minute<=this.latest)return;
    if(this.latest===null) {
      this.latest=minute;this.initial=vehicles;return;
    }
    if(minute>this.latest+1) {
      // Missing history cannot be reconstructed safely. Resume from the available path.
      this.segments.clear();
    }
    const smooth=Array.from({length:12},(_,step)=>{
      // Turning rotates the entire body: only smooth a turn if its swept footprint clears traffic.
      for(let i=0;i<vehicles.length;i++)for(let j=i+1;j<vehicles.length;j++) {
        const a=vehicles[i],b=vehicles[j];
        for(let fraction=0;fraction<=1;fraction+=.1)
          if(overlap(blend(pose(a,step),pose(a,step+1),fraction),a,blend(pose(b,step),pose(b,step+1),fraction),b))return false;
      }
      return true;
    });
    this.initial=vehicles.map(v=>({...v,...pose(v,0)}));
    this.segments.set(minute,{vehicles,smooth});this.latest=minute;
  }
  vehicles() {
    const all=new Map(this.initial.map(v=>[v.id,v]));
    for(const segment of this.segments.values())for(const v of segment.vehicles)all.set(v.id,v);
    return [...all.values()];
  }
  sample(now:number,reducedMotion=false):Map<string,TrafficPose> {
    const cursor=this.timeline.sample(now,reducedMotion);
    for(const minute of this.segments.keys())if(minute<cursor-1)this.segments.delete(minute);
    const minute=Math.ceil(cursor),segment=this.segments.get(minute);
    if(!segment)return new Map(this.initial.map(v=>[v.id,{x:v.x,z:v.z,heading:v.heading,stop:v.stop}]));
    const progress=Math.max(0,Math.min(12,(cursor-(minute-1))*12));
    const step=Math.min(11,Math.floor(progress)),fraction=progress-step;
    return new Map(segment.vehicles.map(v=>{
      const a=pose(v,step),b=pose(v,step+1);
      const sampled=segment.smooth[step]?blend(a,b,fraction):b;
      return [v.id,{...sampled,stop:fraction===1||Math.hypot(a.x-b.x,a.z-b.z)<.001?v.stop:""}];
    }));
  }
}
