import {test,expect} from "@playwright/test";
import {FALLS} from "../lib/landscapes";
import {sprayParticle,mistParticle,advectWaterfallParticle,atInlet,INLET_IMPACT,SPRAY_COUNT,MIST_COUNT} from "../lib/waterfall";
import {windAt} from "../lib/wind";

test("zero wind preserves waterfall ballistics, particle sizing and opacity",()=>{
  for(const time of [0,.15,1.3,100,3601])for(let i=0;i<SPRAY_COUNT;i++) {
    expect(sprayParticle(i,time,{x:0,z:0})).toEqual(sprayParticle(i,time));
    expect(mistParticle(i,time,{x:0,z:0})).toEqual(mistParticle(i,time));
  }
});

test("air drag bends spray and mist downwind while preserving height and fade",()=>{
  const wind=windAt(720,"Rain");
  let moved=0;
  for(const [sample,count] of [[sprayParticle,SPRAY_COUNT],[mistParticle,MIST_COUNT]] as const)for(let i=0;i<count;i++)for(const time of [.17,.65,1.1,2.7]) {
    const still=sample(i,time),blown=sample(i,time,wind),dx=blown.x-still.x,dz=blown.z-still.z;
    expect(blown.y).toBe(still.y);expect(blown.size).toBe(still.size);expect(blown.opacity).toBe(still.opacity);
    expect(dx*wind.x+dz*wind.z).toBeGreaterThanOrEqual(-1e-10);
    if(Math.hypot(dx,dz)>.001)moved++;
  }
  expect(moved).toBeGreaterThan(100);
});

test("strong wind stays bounded and cannot blow either impact behind its cliff",()=>{
  for(const inlet of [false,true])for(const kind of ["spray","mist"] as const) {
    const sample=kind==="spray"?sprayParticle:mistParticle,limit=kind==="spray"?.8:2.5;
    const impact=inlet?INLET_IMPACT:FALLS;
    for(let i=0;i<16;i++)for(const time of [0,.17,.65,1.3,2.7,4.9])for(const wind of [{x:1000,z:1000},{x:-1000,z:-1000},{x:-impact.dx*1000,z:-impact.dz*1000}]) {
      const source=sample(i,time),still=inlet?atInlet(source):source,blown=advectWaterfallParticle(still,i,time,kind,wind,inlet);
      expect(Math.hypot(blown.x-still.x,blown.z-still.z)).toBeLessThanOrEqual(limit+1e-10);
      expect((blown.x-impact.x)*impact.dx+(blown.z-impact.z)*impact.dz).toBeGreaterThanOrEqual(.06-1e-10);
      expect(blown.y).toBe(still.y);
    }
  }
});

test("inlet spray follows the same world wind direction after its impact transform",()=>{
  const wind={x:4,z:-2};
  for(let i=0;i<32;i++) {
    const source=atInlet(sprayParticle(i,1.2)),blown=advectWaterfallParticle(source,i,1.2,"spray",wind,true);
    const dx=blown.x-source.x,dz=blown.z-source.z;
    expect(dx*wind.z-dz*wind.x).toBeCloseTo(0,9);
    expect(dx*wind.x+dz*wind.z).toBeGreaterThanOrEqual(-1e-10);
  }
});

test("particle cycles remain finite and fade correctly for negative and large shared times",()=>{
  for(const time of [-1e8,-7200,-1.3,-.01,0,1e8])for(let i=0;i<SPRAY_COUNT;i++)for(const sample of [sprayParticle,mistParticle]) {
    const point=sample(i,time,windAt(time/5,"Rain"));
    expect(Object.values(point).every(Number.isFinite)).toBe(true);
    expect(point.opacity).toBeGreaterThanOrEqual(0);expect(point.opacity).toBeLessThanOrEqual(1);
    expect(point.size).toBeGreaterThan(0);
  }
});
