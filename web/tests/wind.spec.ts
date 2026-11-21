import {test,expect} from "@playwright/test";
import {windAt} from "../lib/wind";

test("one deterministic wind field supplies normalized direction and physical velocity",()=>{
  for(const minute of [-2880,-.25,0,1,999.25,1440,14000])for(const weather of ["Clear","Overcast","Rain"]) {
    const sample=windAt(minute,weather,127,-84,10);
    expect(sample).toEqual(windAt(minute,weather,127,-84,10));
    expect(Math.hypot(sample.x,sample.z)).toBeCloseTo(sample.speed,12);
    expect(Math.hypot(sample.direction.x,sample.direction.z)).toBeCloseTo(1,12);
    expect(sample.x).toBeCloseTo(sample.direction.x*sample.speed,12);
    expect(sample.z).toBeCloseTo(sample.direction.z*sample.speed,12);
    expect(sample.strength).toBeCloseTo(sample.speed/12,12);
  }
  const changed=windAt(32,"Rain");changed.direction.x=100;
  expect(windAt(32,"Rain").direction.x).toBeLessThanOrEqual(1);
});

test("weather changes the wind envelope while preserving a shared direction and gust",()=>{
  for(let minute=-100;minute<400;minute+=1.25) {
    const clear=windAt(minute,"Clear",35,90),overcast=windAt(minute,"Overcast",35,90),rain=windAt(minute,"Rain",35,90);
    expect(overcast.speed/clear.speed).toBeCloseTo(2,12);
    expect(rain.speed/clear.speed).toBeCloseTo(3.5,12);
    expect(clear.direction).toEqual(overcast.direction);expect(rain.direction).toEqual(clear.direction);
    expect(clear.gust).toBe(rain.gust);
    expect(clear.speed).toBeGreaterThanOrEqual(1.4);expect(clear.speed).toBeLessThanOrEqual(2.6);
    expect(overcast.speed).toBeGreaterThanOrEqual(2.8);expect(overcast.speed).toBeLessThanOrEqual(5.2);
    expect(rain.speed).toBeGreaterThanOrEqual(4.9);expect(rain.speed).toBeLessThanOrEqual(9.1);
  }
  expect(windAt(13,"Unknown")).toEqual(windAt(13,"Clear"));
});

test("wind and gusts remain continuous across frames, ticks, negative time and midnight",()=>{
  for(const minute of [-1440,-1,0,1,499,1439,1440]) {
    const before=windAt(minute-.000001,"Rain",80,-45),after=windAt(minute+.000001,"Rain",80,-45);
    expect(Math.hypot(after.x-before.x,after.z-before.z)).toBeLessThan(.00001);
    expect(Math.abs(after.gust-before.gust)).toBeLessThan(.00001);
    const frame=windAt(minute+1/300,"Rain",80,-45);
    expect(Math.hypot(frame.x-after.x,frame.z-after.z)).toBeLessThan(.01);
    const nextMinute=windAt(minute+1,"Rain",80,-45);
    expect(nextMinute.direction.x*after.direction.x+nextMinute.direction.z*after.direction.z).toBeGreaterThan(.9999);
  }
});

test("nearby vegetation shares coherent gusts and distant objects retain the prevailing direction",()=>{
  for(let minute=0;minute<120;minute+=.5) {
    const center=windAt(minute,"Rain",0,0),nearby=windAt(minute,"Rain",2,-2),far=windAt(minute,"Rain",500,-500);
    expect(Math.hypot(nearby.x-center.x,nearby.z-center.z)).toBeLessThan(.12);
    expect(Math.abs(nearby.gust-center.gust)).toBeLessThan(.04);
    expect(far.direction.x*center.direction.x+far.direction.z*center.direction.z).toBeGreaterThan(.995);
  }
  const gusts=Array.from({length:400},(_,index)=>windAt(index*.5,"Rain").gust);
  expect(Math.max(...gusts)-Math.min(...gusts)).toBeGreaterThan(1.4);
});

test("surface drag slows ground wind to a quarter of the reference and increases through the canopy",()=>{
  for(const minute of [-19,0,27.5,1400])for(const weather of ["Clear","Overcast","Rain"]) {
    const ground=windAt(minute,weather,11,20,0),reference=windAt(minute,weather,11,20,10);
    const canopy=windAt(minute,weather,11,20,20),high=windAt(minute,weather,11,20,120);
    expect(ground.speed/reference.speed).toBeCloseTo(.25,12);
    expect(canopy.speed).toBeGreaterThan(reference.speed);expect(high.speed).toBeGreaterThan(canopy.speed);
    expect(high.speed/reference.speed).toBeLessThan(1.61);
    expect(ground.direction).toEqual(high.direction);expect(ground.gust).toBe(high.gust);
    expect(windAt(minute,weather,11,20,-10)).toEqual(ground);
    expect(windAt(minute,weather,11,20,100000)).toEqual(high);
  }
});

test("invalid and extreme finite parameters produce a bounded usable wind sample",()=>{
  const values=[NaN,Infinity,-Infinity,-1e308,-100,0,100,1e308];
  for(const minute of values)for(const height of values) {
    const sample=windAt(minute,"Rain",minute,-minute,height);
    for(const value of [sample.x,sample.z,sample.speed,sample.gust,sample.strength,sample.direction.x,sample.direction.z])expect(Number.isFinite(value)).toBe(true);
    expect(sample.speed).toBeGreaterThan(0);expect(sample.speed).toBeLessThan(14.7);
    expect(sample.gust).toBeGreaterThanOrEqual(-1);expect(sample.gust).toBeLessThanOrEqual(1);
    expect(sample.strength).toBeGreaterThanOrEqual(0);expect(sample.strength).toBeLessThanOrEqual(1);
  }
  expect(windAt(NaN,"Rain",Infinity,-Infinity,NaN)).toEqual(windAt(0,"Rain",0,0,10));
});
