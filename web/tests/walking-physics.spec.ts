import {test,expect} from "@playwright/test";
import {stepWalkingBody,type WalkingBody} from "../lib/walking-physics";

type Point={x:number;z:number};
const clear=()=>true,origin=():WalkingBody=>({x:0,z:0,vx:0,vz:0});
const simulate=(fps:number,input:Point,speed=4,seconds=1)=>{
  let body=origin();
  for(let frame=0;frame<fps*seconds;frame++)body=stepWalkingBody(body,input,1/fps,{speed,segmentClear:clear});
  return body;
};

test("walking acceleration preserves travel distance at 30, 60 and 120 frames per second",()=>{
  const expected=4*(1-(1-Math.exp(-20))/20);
  for(const fps of [30,60,120]) {
    const body=simulate(fps,{x:1,z:0});
    expect(body.x).toBeCloseTo(expected,10);
    expect(body.vx).toBeCloseTo(4*(1-Math.exp(-20)),10);
    expect(body.z).toBe(0);expect(body.vz).toBe(0);
  }
  const first=stepWalkingBody(origin(),{x:1,z:0},1/120,{speed:4,segmentClear:clear});
  expect(first.vx).toBeGreaterThan(0);expect(first.vx).toBeLessThan(1);
});

test("diagonal inputs have the same speed and sprinting scales travel",()=>{
  const straight=simulate(60,{x:1,z:0}),diagonal=simulate(60,{x:1,z:1}),run=simulate(60,{x:1,z:1},9);
  expect(Math.hypot(diagonal.x,diagonal.z)).toBeCloseTo(straight.x,10);
  expect(Math.hypot(diagonal.vx,diagonal.vz)).toBeLessThanOrEqual(4);
  expect(diagonal.x).toBeCloseTo(diagonal.z,10);
  expect(Math.hypot(run.x,run.z)/straight.x).toBeCloseTo(9/4,10);
});

test("released walking and running settle within 200ms with a short identical stopping distance",()=>{
  for(const speed of [4,9])for(const fps of [30,60,120]) {
    let body={x:0,z:0,vx:speed,vz:0};
    for(let frame=0;frame<fps*.2;frame++)body=stepWalkingBody(body,{x:0,z:0},1/fps,{speed,segmentClear:clear});
    expect(body.vx).toBe(0);expect(body.vz).toBe(0);
    expect(body.x).toBeCloseTo((speed-.01)/45,10);
    expect(body.x).toBeLessThan(.2);
    expect(stepWalkingBody(body,{x:0,z:0},.1,{speed,segmentClear:clear})).toEqual(body);
  }
});

test("wall sweeps stop at contact even when both endpoints lie outside a thin wall",()=>{
  const segmentClear=(from:Point,to:Point)=>Math.max(from.x,to.x)<.2||Math.min(from.x,to.x)>.201;
  const body=stepWalkingBody({x:0,z:0,vx:9,vz:0},{x:1,z:0},.1,{speed:9,segmentClear});
  expect(body.x).toBeLessThan(.2);expect(body.x).toBeGreaterThan(.1999);
  expect(body.vx).toBe(0);expect(body.vz).toBe(0);
});

test("diagonal motion slides along a wall without retaining blocked velocity",()=>{
  const segmentClear=(_from:Point,to:Point)=>to.x<.2;
  let body={x:0,z:0,vx:4/Math.sqrt(2),vz:4/Math.sqrt(2)};
  for(let frame=0;frame<60;frame++)body=stepWalkingBody(body,{x:1,z:1},1/60,{speed:4,segmentClear});
  expect(body.x).toBeGreaterThan(.1999);expect(body.x).toBeLessThan(.2);
  expect(body.z).toBeCloseTo(4/Math.sqrt(2),8);
  expect(body.vx).toBe(0);expect(body.vz).toBeCloseTo(4/Math.sqrt(2),8);
  const released=stepWalkingBody(body,{x:0,z:0},.1,{speed:4,segmentClear:clear});
  expect(released.x).toBe(body.x);
});

test("axis slides keep every accepted path outside a blocked inside corner",()=>{
  const accepted:{from:Point;to:Point}[]=[];
  const segmentClear=(from:Point,to:Point)=>{
    const blocked=to.x>=.2&&to.z>=.2;
    if(!blocked)accepted.push({from:{...from},to:{...to}});
    return !blocked;
  };
  const body=stepWalkingBody({x:0,z:0,vx:4,vz:4},{x:1,z:1},.1,{speed:Math.sqrt(32),segmentClear});
  expect(body.x<.2||body.z<.2).toBe(true);
  expect(Math.max(body.x,body.z)).toBeGreaterThan(.35);
  for(const {from,to} of accepted)for(let index=0;index<=10;index++) {
    const t=index/10,x=from.x+(to.x-from.x)*t,z=from.z+(to.z-from.z)*t;
    expect(x<.2||z<.2).toBe(true);
  }
});

test("terrain effort applies to the actual wall slide without double charging its movement",()=>{
  const calls:{from:Point;to:Point;budget:number}[]=[];
  const terrainStep=(from:Point,to:Point,budget:number)=>{
    calls.push({from:{...from},to:{...to},budget});
    const length=Math.hypot(to.x-from.x,to.z-from.z),effort=length+2*Math.max(0,to.z-from.z);
    const fraction=effort?Math.min(1,budget/effort):0;
    return {x:from.x+(to.x-from.x)*fraction,z:from.z+(to.z-from.z)*fraction};
  };
  let body={x:0,z:0,vx:4/Math.sqrt(2),vz:4/Math.sqrt(2)};
  const segmentClear=(_from:Point,to:Point)=>to.x<.05;
  for(let frame=0;frame<60;frame++)body=stepWalkingBody(body,{x:1,z:1},1/60,{speed:4,segmentClear,terrainStep});
  const slideCalls=calls.filter(call=>Math.abs(call.to.x-call.from.x)<1e-10&&call.to.z>call.from.z);
  expect(slideCalls.length).toBeGreaterThan(20);
  expect(body.x).toBeLessThan(.05);
  expect(body.z).toBeGreaterThan(.9);expect(body.z).toBeLessThan(1);
  expect(body.vx).toBe(0);
});

test("terrain can use its budget for a gentle descent without speeding up level ground",()=>{
  const advance=(effort:number)=>{
    let body={x:0,z:0,vx:4,vz:0};
    for(let frame=0;frame<60;frame++)body=stepWalkingBody(body,{x:1,z:0},1/60,{speed:4,segmentClear:clear,terrainStep:(from,to,budget)=>{
      const length=Math.hypot(to.x-from.x,to.z-from.z),fraction=Math.min(1.096,budget/(length*effort));
      return {x:from.x+(to.x-from.x)*fraction,z:from.z+(to.z-from.z)*fraction};
    }});
    return body.x;
  };
  expect(advance(1)).toBeCloseTo(4,10);
  expect(advance(1/1.096)).toBeCloseTo(4*1.096,10);
});

test("invalid durations do not move or mutate the body and stalled frames are bounded",()=>{
  const body=Object.freeze({x:2,z:3,vx:4,vz:0}),options={speed:4,segmentClear:clear};
  for(const dt of [0,-1,NaN,Infinity,-Infinity]) {
    const result=stepWalkingBody(body,{x:1,z:1},dt,options);
    expect(result).toEqual(body);expect(result).not.toBe(body);
  }
  expect(stepWalkingBody(body,{x:1,z:0},60,options)).toEqual(stepWalkingBody(body,{x:1,z:0},.1,options));
  expect(body).toEqual({x:2,z:3,vx:4,vz:0});
});
