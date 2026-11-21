import {test,expect} from "@playwright/test";
import {EnvironmentClock,ENVIRONMENT_SECONDS_PER_MINUTE} from "../lib/environment-motion";

test("an environment without a received world clock holds a zero phase",()=>{
  const clock=new EnvironmentClock();
  for(const now of [-1000,0,5000,1e9,NaN])expect(clock.sample(now)).toEqual({minute:0,seconds:0});
});

test("environment motion uses five presentation seconds per received world minute at either cadence",()=>{
  expect(ENVIRONMENT_SECONDS_PER_MINUTE).toBe(5);
  for(const tickMs of [1000,5000]) {
    const clock=new EnvironmentClock();clock.ingest(60,tickMs,200);
    expect(clock.sample(200)).toEqual({minute:60,seconds:300});
    for(let tick=0;tick<4;tick++) {
      const start=200+tick*tickMs,minute=60+tick;
      expect(clock.sample(start+tickMs/4)).toEqual({minute:minute+.25,seconds:(minute+.25)*5});
      expect(clock.sample(start+tickMs/2)).toEqual({minute:minute+.5,seconds:(minute+.5)*5});
      const before=clock.sample(start+tickMs-.001),boundary=clock.sample(start+tickMs);
      expect(boundary.minute-before.minute).toBeCloseTo(.001/tickMs,10);
      clock.ingest(minute+1,tickMs,start+tickMs);
      expect(clock.sample(start+tickMs)).toEqual(boundary);
    }
  }
});

test("duplicate snapshots preserve fractional progress without prolonging extrapolation",()=>{
  const clock=new EnvironmentClock();clock.ingest(120,5000,0);
  for(let now=250;now<=10000;now+=250) {
    const before=clock.sample(now);clock.ingest(120,5000,now);
    expect(clock.sample(now)).toEqual(before);
    expect(clock.sample(now).minute).toBeCloseTo(120+Math.min(now/5000,1),10);
  }
  expect(clock.sample(60000)).toEqual({minute:121,seconds:605});
});

test("a stopped server holds after one presentation interval and resumes from a new received minute",()=>{
  const clock=new EnvironmentClock();clock.ingest(400,5000,1000);
  expect(clock.sample(3500)).toEqual({minute:400.5,seconds:2002.5});
  for(const now of [6000,60000,3600000])expect(clock.sample(now)).toEqual({minute:401,seconds:2005});
  clock.ingest(450,5000,3600000);
  expect(clock.sample(3600000)).toEqual({minute:450,seconds:2250});
  expect(clock.sample(3602500)).toEqual({minute:450.5,seconds:2252.5});
});

test("reduced motion returns a zero animation phase without changing the shared clock",()=>{
  const clock=new EnvironmentClock();clock.ingest(100,5000,0);
  expect(clock.sample(1250)).toEqual({minute:100.25,seconds:501.25});
  expect(clock.sample(2500,true)).toEqual({minute:100.5,seconds:0});
  expect(clock.sample(2500)).toEqual({minute:100.5,seconds:502.5});
  expect(clock.sample(3750)).toEqual({minute:100.75,seconds:503.75});
  expect(clock.sample(10000,true)).toEqual({minute:101,seconds:0});
  expect(clock.sample(10000)).toEqual({minute:101,seconds:505});
});

test("changing publication cadence preserves progress and remains monotone until the next snapshot",()=>{
  const clock=new EnvironmentClock();clock.ingest(10,5000,100);
  expect(clock.sample(2100).minute).toBeCloseTo(10.4,10);
  clock.ingest(10,1000,2100);
  expect(clock.sample(2100).minute).toBeCloseTo(10.4,10);
  expect(clock.sample(2400).minute).toBeCloseTo(10.7,10);
  clock.ingest(10,5000,2400);
  const samples=[2400,2900,3400,3900,10000].map(now=>clock.sample(now).minute);
  expect(samples[0]).toBeCloseTo(10.7,10);expect(samples[2]).toBeCloseTo(10.9,10);
  expect(samples[3]).toBe(11);expect(samples[4]).toBe(11);
  for(let index=1;index<samples.length;index++)expect(samples[index]).toBeGreaterThanOrEqual(samples[index-1]);
  clock.ingest(11,5000,10000);
  expect(clock.sample(10000).minute).toBe(11);expect(clock.sample(12500).minute).toBe(11.5);
});

test("new and reversed world minutes establish explicit anchors, including negative clocks",()=>{
  const clock=new EnvironmentClock();clock.ingest(20,1000,-2000);
  expect(clock.sample(-1500)).toEqual({minute:20.5,seconds:102.5});
  clock.ingest(80,1000,-1400);
  expect(clock.sample(-1400)).toEqual({minute:80,seconds:400});
  clock.ingest(-30,1000,-1000);
  expect(clock.sample(-1000)).toEqual({minute:-30,seconds:-150});
  expect(clock.sample(-500)).toEqual({minute:-29.5,seconds:-147.5});
  const boundary=clock.sample(0);clock.ingest(-29,1000,0);
  expect(clock.sample(0)).toEqual(boundary);
  expect(clock.sample(500)).toEqual({minute:-28.5,seconds:-142.5});
});

test("invalid received minutes and timestamps leave the existing anchor and cadence intact",()=>{
  const clock=new EnvironmentClock();clock.ingest(10,1000,0);
  expect(clock.sample(500).minute).toBe(10.5);
  for(const invalid of [NaN,Infinity,-Infinity]) {
    clock.ingest(invalid,5000,500);
    clock.ingest(50,5000,invalid);
  }
  expect(clock.sample(750)).toEqual({minute:10.75,seconds:53.75});
  expect(clock.sample(1000)).toEqual({minute:11,seconds:55});
});

test("invalid publication intervals fall back to five seconds for new and duplicate snapshots",()=>{
  for(const tickMs of [NaN,Infinity,-Infinity,0,-100]) {
    const clock=new EnvironmentClock();clock.ingest(-2,tickMs,0);
    expect(clock.sample(2500)).toEqual({minute:-1.5,seconds:-7.5});
    expect(clock.sample(5000)).toEqual({minute:-1,seconds:-5});
    clock.ingest(4,1000,6000);
    clock.ingest(4,tickMs,6500);
    expect(clock.sample(6500)).toEqual({minute:4.5,seconds:22.5});
    expect(clock.sample(7750)).toEqual({minute:4.75,seconds:23.75});
    expect(clock.sample(9000)).toEqual({minute:5,seconds:25});
  }
});

test("transport integrates the shared clock once across repeated canvas samples and a stopped server",()=>{
  const clock=new EnvironmentClock();clock.ingest(100,1000,0);
  const velocity={x:2,z:-1};
  expect(clock.transport(0,velocity)).toEqual({x:0,z:0});
  expect(clock.transport(250,velocity)).toEqual({x:2.5,z:-1.25});
  expect(clock.transport(250,velocity)).toEqual({x:2.5,z:-1.25});
  expect(clock.transport(500,velocity)).toEqual({x:5,z:-2.5});
  expect(clock.transport(1000,velocity)).toEqual({x:10,z:-5});
  expect(clock.transport(1000,velocity)).toEqual({x:10,z:-5});
  expect(clock.transport(60000,velocity)).toEqual({x:10,z:-5});
});

test("constant wind transport covers the same distance at 30, 60 and 120 frames per second",()=>{
  for(const fps of [30,60,120])for(const tickMs of [1000,5000]) {
    const clock=new EnvironmentClock(),velocity={x:2,z:-1};
    clock.ingest(100,tickMs,0);clock.transport(0,velocity);
    let offset={x:0,z:0};
    for(let frame=1;frame<=fps*4;frame++) {
      const now=frame/fps*tickMs;
      if(frame%fps===0)clock.ingest(100+frame/fps,tickMs,now);
      offset=clock.transport(now,velocity);
    }
    expect(offset.x).toBeCloseTo(40,10);expect(offset.z).toBeCloseTo(-20,10);
  }
});

test("changing wind integrates only new travel instead of rotating the accumulated cloud offset",()=>{
  const clock=new EnvironmentClock();clock.ingest(10000,1000,0);
  expect(clock.transport(0,{x:1,z:0})).toEqual({x:0,z:0});
  expect(clock.transport(250,{x:1,z:0})).toEqual({x:1.25,z:0});
  expect(clock.transport(500,{x:0,z:1})).toEqual({x:1.25,z:1.25});
  expect(clock.transport(750,{x:-1,z:0})).toEqual({x:0,z:1.25});
});

test("freezing transport does not bank elapsed wind and returns an immutable zero offset",()=>{
  const clock=new EnvironmentClock(),velocity={x:2,z:-1};clock.ingest(0,1000,0);clock.transport(0,velocity);
  expect(clock.transport(250,velocity)).toEqual({x:2.5,z:-1.25});
  const frozen=clock.transport(500,velocity,true);expect(frozen).toEqual({x:0,z:0});
  frozen.x=999;
  expect(clock.transport(750,velocity,true)).toEqual({x:0,z:0});
  const resumed=clock.transport(1000,velocity);expect(resumed).toEqual({x:5,z:-2.5});
  resumed.z=999;
  expect(clock.transport(1000,velocity)).toEqual({x:5,z:-2.5});
});

test("reconnections cap skipped transport and a reversed world minute clears accumulated displacement",()=>{
  const clock=new EnvironmentClock(),velocity={x:2,z:-1};clock.ingest(0,1000,0);clock.transport(0,velocity);
  clock.ingest(100,1000,1000);
  expect(clock.transport(1000,velocity)).toEqual({x:10,z:-5});
  expect(clock.transport(1500,velocity)).toEqual({x:15,z:-7.5});
  clock.ingest(-40,1000,2000);
  expect(clock.transport(2000,velocity)).toEqual({x:0,z:0});
  expect(clock.transport(2250,velocity)).toEqual({x:2.5,z:-1.25});
});

test("invalid velocity components cannot poison subsequent transport",()=>{
  const clock=new EnvironmentClock();clock.ingest(-10,1000,0);clock.transport(0,{x:0,z:0});
  expect(clock.transport(250,{x:NaN,z:Infinity})).toEqual({x:0,z:0});
  expect(clock.transport(500,{x:2,z:-Infinity})).toEqual({x:2.5,z:0});
  expect(clock.transport(750,{x:NaN,z:-2})).toEqual({x:2.5,z:-2.5});
});
