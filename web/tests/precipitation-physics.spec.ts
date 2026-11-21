import {test,expect} from "@playwright/test";
import {ATMOSPHERE_LIMITS,atmosphereSeed} from "../lib/atmosphere";
import {rainDrop,rainVelocity,mistDrift} from "../lib/precipitation-physics";

const wind={x:6,z:-8},center={x:-150,z:20};

test("rain uses seeded terminal velocity and wind in map units independently of column height",()=>{
  for(let index=0;index<100;index++) {
    const velocity=rainVelocity(wind,index);
    expect(velocity.x).toBe(.6);expect(velocity.z).toBe(-.8);
    expect(velocity.y).toBeCloseTo(-(5.5+3*atmosphereSeed(index,81))/10,12);
    expect(velocity.y).toBeLessThanOrEqual(-.55);expect(velocity.y).toBeGreaterThanOrEqual(-.85);
    for(const [bottom,top] of [[0,10],[0,100],[240,300]]) {
      const before=rainDrop(index,0,center,bottom,top,wind),after=rainDrop(index,.001,center,bottom,top,wind);
      expect(before.velocity).toEqual(velocity);
      expect(after.y-before.y).toBeCloseTo(velocity.y*.001,9);
      expect(after.x-before.x).toBeCloseTo(velocity.x*.001,9);
      expect(after.z-before.z).toBeCloseTo(velocity.z*.001,9);
      expect(before.tail.x-before.x).toBeCloseTo(-velocity.x*.35,10);
      expect(before.tail.y-before.y).toBeCloseTo(-velocity.y*.35,10);
      expect(before.tail.z-before.z).toBeCloseTo(-velocity.z*.35,10);
    }
  }
});

test("rain stays deterministic and bounded across recycling, negative time and long clocks",()=>{
  for(const seconds of [-1e9,-500,-1,0,1,500,1e9])for(let index=0;index<80;index++) {
    const drop=rainDrop(index,seconds,center,3,53,wind);
    expect(drop).toEqual(rainDrop(index,seconds,center,3,53,wind));
    expect(Math.abs(drop.x-center.x)).toBeLessThanOrEqual(ATMOSPHERE_LIMITS.rainRadius);
    expect(Math.abs(drop.z-center.z)).toBeLessThanOrEqual(ATMOSPHERE_LIMITS.rainRadius);
    expect(drop.y).toBeGreaterThanOrEqual(3);expect(drop.y).toBeLessThan(53);
    expect([drop.x,drop.y,drop.z,...Object.values(drop.tail),...Object.values(drop.velocity)].every(Number.isFinite)).toBe(true);
  }
  const calm=rainDrop(7,123,center,3,53,{x:0,z:0});
  expect(calm.x).toBeCloseTo(center.x+(atmosphereSeed(7,5)-.5)*ATMOSPHERE_LIMITS.rainRadius*2,10);
  expect(calm.z).toBeCloseTo(center.z+(atmosphereSeed(7,6)-.5)*ATMOSPHERE_LIMITS.rainRadius*2,10);
  const velocity=rainVelocity(wind,7),lifetime=50/Math.abs(velocity.y);
  expect(rainDrop(7,123+lifetime,center,3,53,wind).y).toBeCloseTo(rainDrop(7,123,center,3,53,wind).y,9);
});

test("invalid numeric inputs have deterministic finite rain and vapor fallbacks",()=>{
  for(const invalid of [NaN,Infinity,-Infinity]) {
    const velocity=rainVelocity({x:invalid,z:invalid},invalid);
    expect(Object.values(velocity).every(Number.isFinite)).toBe(true);
    const drop=rainDrop(invalid,invalid,{x:invalid,z:invalid},invalid,invalid,{x:invalid,z:invalid});
    expect([drop.x,drop.y,drop.z,...Object.values(drop.tail),...Object.values(drop.velocity)].every(Number.isFinite)).toBe(true);
    expect(drop).toEqual(rainDrop(invalid,invalid,{x:invalid,z:invalid},invalid,invalid,{x:invalid,z:invalid}));
    expect(Object.values(mistDrift(invalid,invalid,{x:invalid,z:invalid})).every(Number.isFinite)).toBe(true);
  }
  expect(Object.values(rainDrop(0,1,center,20,10,wind).velocity).every(Number.isFinite)).toBe(true);
  for(const value of [1e-308,1e300,Number.MAX_VALUE]) {
    const drop=rainDrop(value,value,{x:value,z:value},0,value,{x:-value,z:value});
    expect([drop.x,drop.y,drop.z,...Object.values(drop.tail),...Object.values(drop.velocity)].every(Number.isFinite)).toBe(true);
    expect(Object.values(mistDrift(value,value,{x:value,z:-value},"falls")).every(Number.isFinite)).toBe(true);
  }
});

test("mist advects downwind within its emission bounds and only falls mist rises",()=>{
  for(const kind of ["mountain","lake","river","falls"] as const)for(let index=0;index<20;index++)for(const seconds of [-100,0,1,9,18,100,1e9]) {
    const drift=mistDrift(index,seconds,wind,kind),distance=Math.hypot(drift.x,drift.z);
    expect(drift).toEqual(mistDrift(index,seconds,wind,kind));
    expect(Object.values(drift).every(Number.isFinite)).toBe(true);
    expect(distance).toBeLessThanOrEqual(12);expect(drift.x*wind.x+drift.z*wind.z).toBeGreaterThanOrEqual(0);
    expect(drift.x*wind.z-drift.z*wind.x).toBeCloseTo(0,10);
    expect(drift.opacity).toBeGreaterThanOrEqual(0);expect(drift.opacity).toBeLessThanOrEqual(1);
    if(kind==="falls") {expect(drift.y).toBeGreaterThanOrEqual(0);expect(drift.y).toBeLessThan(.8);}
    else expect(drift.y).toBe(0);
  }
  for(const seconds of [0,1,20]) {
    const still=mistDrift(1,seconds,{x:0,z:0});expect(still.x).toBe(0);expect(still.z).toBe(0);
  }
  expect(Math.hypot(...["x","z"].map(axis=>mistDrift(3,5,{x:1e9,z:-1e9})[axis as "x"|"z"]))).toBeLessThanOrEqual(12);
});

test("mist opacity and visible displacement remain continuous through the 18-second recycle",()=>{
  for(let index=0;index<20;index++) {
    const wrap=(1-atmosphereSeed(index,91))*18;
    const before=mistDrift(index,wrap-1e-5,wind,"falls"),at=mistDrift(index,wrap,wind,"falls"),after=mistDrift(index,wrap+1e-5,wind,"falls");
    expect(before.opacity).toBeLessThan(1e-10);expect(at.opacity).toBeLessThan(1e-10);expect(after.opacity).toBeLessThan(1e-10);
    for(const axis of ["x","y","z"] as const)expect(Math.abs(before[axis]*before.opacity-after[axis]*after.opacity)).toBeLessThan(1e-8);
    const first=mistDrift(index,3.25,wind),next=mistDrift(index,21.25,wind);
    for(const key of ["x","y","z","opacity"] as const)expect(next[key]).toBeCloseTo(first[key],10);
  }
});
