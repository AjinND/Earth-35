export const ENVIRONMENT_SECONDS_PER_MINUTE=5;
const validTick=(tickMs:number)=>Number.isFinite(tickMs)&&tickMs>0?tickMs:5000;

/** One tick of presentation between received world minutes; duplicates cannot restart it. */
export class EnvironmentClock {
  private minute:number|null=null;
  private at=0;
  private tick=5000;
  private fraction=0;
  private transportedAt:number|null=null;
  private offset={x:0,z:0};

  ingest(minute:number,tickMs:number,now:number) {
    if(!Number.isFinite(minute)||!Number.isFinite(now))return;
    const tick=validTick(tickMs);
    if(this.minute===minute) {
      // Preserve progress if the server changes its publication cadence.
      this.fraction=this.progress(now);this.at=now;this.tick=tick;return;
    }
    if(this.minute!==null&&minute<this.minute){this.transportedAt=null;this.offset={x:0,z:0};}
    this.minute=minute;this.at=now;this.tick=tick;this.fraction=0;
  }
  private progress(now:number) {
    return Math.min(1,Math.max(0,this.fraction+(Number.isFinite(now)?Math.max(0,now-this.at):0)/this.tick));
  }
  sample(now:number,reducedMotion=false) {
    const minute=this.minute===null?0:this.minute+this.progress(now);
    return {minute,seconds:reducedMotion?0:minute*ENVIRONMENT_SECONDS_PER_MINUTE};
  }
  /** Integrate transport once across both canvases; never multiply velocity by an epoch. */
  transport(now:number,velocity:{x:number;z:number},frozen=false) {
    if(!Number.isFinite(now))return frozen?{x:0,z:0}:{...this.offset};
    const seconds=this.sample(now).seconds;
    const dt=this.transportedAt===null?0:Math.max(0,Math.min(ENVIRONMENT_SECONDS_PER_MINUTE,seconds-this.transportedAt));
    this.transportedAt=seconds;
    if(!frozen) {
      this.offset.x+=(Number.isFinite(velocity.x)?velocity.x:0)*dt;
      this.offset.z+=(Number.isFinite(velocity.z)?velocity.z:0)*dt;
    }
    return frozen?{x:0,z:0}:{...this.offset};
  }
}
