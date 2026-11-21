import type {Agent} from "./types";
import type {Seat} from "./gathering-furniture";
import {PlaybackTimeline} from "./playback-timeline";

export type ResidentPose={x:number;z:number;heading:number;visible:boolean;activity:string;moving:boolean;routeAnchored:boolean;seat?:Seat;activityProgress?:Agent["activityProgress"];performance?:Agent["performance"]};
type Snapshot=Pick<Agent,"x"|"z"|"activity"|"motion"|"activityProgress"|"performance">&{seat?:Seat};
type Segment={from:Snapshot;to:Snapshot};
const keepsWalkingRoute=(snapshot:Snapshot)=>snapshot.activity==="DO_ACTIVITY"&&snapshot.activityProgress?.mode==="walk";
const pose=(snapshot:Snapshot):ResidentPose=>({...snapshot,heading:0,visible:snapshot.activity!=="RIDE_BUS",moving:false,routeAnchored:keepsWalkingRoute(snapshot)||!!snapshot.performance?.anchored});
function timedPath(path:NonNullable<Snapshot["motion"]>) {
  return path.length>1&&path[0].fraction===0&&path[path.length-1].fraction===1&&path.every((point,index)=>
    typeof point.fraction==="number"&&Number.isFinite(point.fraction)&&point.fraction>=0&&point.fraction<=1&&
    (index===0||point.fraction>=path[index-1].fraction!));
}

/** A conversation's lingering gaze must not turn a walking body off its path. */
export function residentFacingHeading(pose:Pick<ResidentPose,"moving"|"activity">,navigationHeading:number,standingHeading:number,attentionHeading?:number) {
  if(pose.moving||pose.activity==="BOARD_BUS")return navigationHeading;
  return attentionHeading!==undefined&&Number.isFinite(attentionHeading)?attentionHeading:pose.activity==="Idle"?standingHeading:navigationHeading;
}

/** Play the actual walking path before hiding a resident inside a bus. */
export class ResidentPlayback {
  private latest:number|null=null;
  private initial:Snapshot={x:0,z:0,activity:"Idle"};
  private previous=this.initial;
  private segments=new Map<number,Segment>();

  constructor(private readonly timeline=new PlaybackTimeline()) {}

  ingest(minute:number,snapshot:Snapshot,tickMs:number,now:number) {
    this.timeline.ingest(minute,tickMs,now);
    if(this.latest!==null&&minute<this.latest)return;
    if(minute===this.latest) {
      // Actions may start or finish within a tick. Refresh their final state without
      // resetting the cursor or discarding the already received walking trace.
      if(snapshot.x===this.previous.x&&snapshot.z===this.previous.z&&!snapshot.motion?.length)
        snapshot={...snapshot,motion:this.previous.motion};
      const segment=this.segments.get(minute);
      if(segment)segment.to=snapshot;else this.initial=snapshot;
      this.previous=snapshot;return;
    }
    if(this.latest===null) {
      this.latest=minute;this.initial=snapshot;this.previous=snapshot;return;
    }
    if(minute>this.latest+1) {
      this.segments.clear();
      // A reconnect supplies this interval's actual starting point when available.
      const start=snapshot.motion?.[0];
      this.previous=start?{...snapshot,...start,activity:snapshot.activity==="RIDE_BUS"?"BOARD_BUS":snapshot.activity}:snapshot;
      this.initial=this.previous;
    }
    this.segments.set(minute,{from:this.previous,to:snapshot});
    this.previous=snapshot;this.latest=minute;
  }

  sample(now:number,reducedMotion=false):ResidentPose {
    const cursor=this.timeline.sample(now,reducedMotion);
    for(const minute of this.segments.keys())if(minute<cursor-1)this.segments.delete(minute);
    const minute=Math.ceil(cursor),segment=this.segments.get(minute);
    if(!segment) {
      // A reconnect can hold exactly at the first point of a queued final path.
      const queued=this.segments.get(minute+1);
      return {...pose(this.initial),routeAnchored:keepsWalkingRoute(this.initial)||!!this.initial.performance?.anchored||!!queued?.to.motion?.length};
    }
    const fraction=Math.max(0,Math.min(1,cursor-(minute-1)));
    const {from,to}=segment,path=to.motion?.length?to.motion:undefined;
    const progress=fraction*((path?.length??2)-1),step=Math.floor(progress);
    let a=path?.[step]??from,b=path?.[Math.min(step+1,path.length-1)]??to,t=path?progress-step:fraction;
    if(path&&timedPath(path)) {
      let index=0;
      while(index<path.length-2&&path[index+1].fraction!<=fraction)index++;
      a=path[index];b=path[index+1];
      const elapsed=path[index+1].fraction!-path[index].fraction!;
      t=elapsed>0?Math.max(0,Math.min(1,(fraction-path[index].fraction!)/elapsed)):1;
    }
    const headingA="heading" in a?a.heading:Math.atan2(to.x-from.x,to.z-from.z);
    const headingB="heading" in b?b.heading:headingA;
    const boarding=to.activity==="RIDE_BUS"&&from.activity!=="RIDE_BUS";
    return {x:fraction>=1?to.x:a.x+(b.x-a.x)*t,z:fraction>=1?to.z:a.z+(b.z-a.z)*t,
      heading:headingA+Math.atan2(Math.sin(headingB-headingA),Math.cos(headingB-headingA))*t,
      visible:to.activity!=="RIDE_BUS"||(boarding&&fraction<1),
      activity:boarding&&fraction<1?"BOARD_BUS":to.activity,
      moving:fraction<1&&Math.hypot(b.x-a.x,b.z-a.z)>.0001,
      routeAnchored:!!to.performance?.anchored||keepsWalkingRoute(to)||(fraction<1&&(!!from.performance?.anchored||keepsWalkingRoute(from)||!!path)),
      activityProgress:to.activityProgress,
      performance:to.performance,
      seat:fraction>=1?to.seat:from.seat&&Math.hypot(to.x-from.x,to.z-from.z)<.0001?from.seat:undefined};
  }
}
