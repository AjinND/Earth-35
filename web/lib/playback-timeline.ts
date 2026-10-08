/** A shared, monotonic cursor over received world intervals. Never extrapolates. */
export class PlaybackTimeline {
  private latest:number|null=null;
  private cursor=0;
  private lastTime=0;
  private tickMs=5000;
  private playbackMs=5000;
  private cadenceMs=5000;
  private publishedAt=0;
  private holdUntil=0;
  private started=false;

  constructor(private readonly bufferMs=0) {}

  ingest(minute:number,tickMs:number,now:number) {
    this.advance(now);
    const pace=Math.max(100,tickMs);
    if(pace!==this.tickMs){this.tickMs=pace;this.playbackMs=pace;this.cadenceMs=pace;}
    if(this.latest!==null&&minute<=this.latest)return;
    if(this.latest===null) {
      this.latest=minute;this.cursor=minute;this.lastTime=now;this.publishedAt=now;return;
    }
    const missing=minute>this.latest+1;
    if(this.started&&!missing&&this.bufferMs>0) {
      // The configured tick excludes scheduling and publication overhead. Follow
      // measured consecutive intervals so a small drift cannot drain the reserve.
      const interval=Math.max(this.tickMs*.75,Math.min(this.tickMs*1.5,now-this.publishedAt));
      this.cadenceMs+=(interval-this.cadenceMs)*.5;
      // Alternating early/late arrivals bias a speed estimate alone. Gently
      // correct the remaining reserve as well, without rewinding or pausing.
      const desiredReserve=Math.min(this.bufferMs,this.tickMs*.4)/this.cadenceMs;
      const reserve=this.latest-this.cursor;
      const correction=Math.max(-.1,Math.min(.1,(desiredReserve-reserve)*.5));
      this.playbackMs=this.cadenceMs*(1+correction);
    }
    if(missing)this.cursor=minute-1;
    // Accumulate a small reserve once, and refill it only after actual starvation.
    // Fast demonstration clocks retain a proportionately short buffer.
    if(!this.started||missing||this.cursor>=this.latest) {
      this.holdUntil=now+Math.min(this.bufferMs,this.tickMs*.4);
      this.started=true;
    }
    this.latest=minute;this.publishedAt=now;
  }

  sample(now:number,reducedMotion=false) {
    this.advance(now);
    if(reducedMotion&&this.latest!==null){this.cursor=this.latest;this.holdUntil=now;}
    return this.cursor;
  }

  private advance(now:number) {
    if(this.latest!==null) {
      const elapsed=Math.max(0,now-Math.max(this.lastTime,this.holdUntil));
      this.cursor=Math.min(this.latest,this.cursor+elapsed/this.playbackMs);
    }
    this.lastTime=now;
  }
}
