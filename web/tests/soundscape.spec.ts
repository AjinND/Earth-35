import {test,expect} from "@playwright/test";
import {soundscapeAt,type SoundMix} from "../lib/soundscape";
import {COASTLINE} from "../lib/island";
import {FALLS,LAKE,LANDSCAPES} from "../lib/landscapes";
import {RIVER_COURSE} from "../lib/river-outlet";

const point=(x:number,z:number,yaw=0)=>({x,z,yaw});
const center=point(-150,0),forest=point(-550,-245);

test("water ambience follows actual shores, stream and waterfall with bounded range",()=>{
  const inland=soundscapeAt(center,720,"Clear"),coast=soundscapeAt({...COASTLINE[80],yaw:0},720,"Clear");
  expect(inland.surf.gain).toBe(0);expect(coast.surf.gain).toBeGreaterThan(.6);
  const riverPoint=RIVER_COURSE[80],byRiver=soundscapeAt(point(riverPoint.x+5,riverPoint.z),720,"Clear");
  expect(byRiver.river.gain).toBeGreaterThan(.5);expect(inland.river.gain).toBe(0);
  const byFalls=soundscapeAt(point(FALLS.x+10,FALLS.z),720,"Clear");
  expect(byFalls.waterfall.gain).toBeGreaterThan(.8);expect(inland.waterfall.gain).toBe(0);
  const lake=soundscapeAt(point(LAKE.x,LAKE.z),720,"Clear");
  expect(lake.river.gain).toBeLessThanOrEqual(.16);
  // The obsolete offshore control points do not remain audible river sources.
  const far=soundscapeAt(point(10000,10000),720,"Clear");
  expect([far.surf.gain,far.river.gain,far.waterfall.gain,far.birds.gain,far.gulls.gain,far.insects]).toEqual([0,0,0,0,0,0]);
});

test("stereo direction rotates with the listener without changing source strength",()=>{
  const eastOfFalls=point(FALLS.x+15,FALLS.z),forward=soundscapeAt(eastOfFalls,720,"Clear"),back=soundscapeAt({...eastOfFalls,yaw:Math.PI},720,"Clear");
  expect(forward.waterfall.pan).toBeCloseTo(-1);expect(back.waterfall.pan).toBeCloseTo(1);
  expect(forward.waterfall.gain).toBe(back.waterfall.gain);
  const side=soundscapeAt({...eastOfFalls,yaw:Math.PI/2},720,"Clear");
  expect(side.waterfall.pan).toBeCloseTo(0);
  const under=soundscapeAt(point(FALLS.x,FALLS.z),720,"Clear");
  expect(under.waterfall.pan).toBe(0);
});

test("rain, cloud and the simulation day determine atmospheric and wildlife layers",()=>{
  const day=soundscapeAt(forest,720,"Clear"),night=soundscapeAt(forest,0,"Clear"),rain=soundscapeAt(forest,720,"Rain"),overcast=soundscapeAt(forest,720,"Overcast");
  expect(day.birds.gain).toBeGreaterThan(.5);expect(day.insects).toBe(0);
  expect(night.birds.gain).toBe(0);expect(night.insects).toBeGreaterThan(.5);
  expect(rain.rain).toBeGreaterThan(.7);expect(day.rain).toBe(0);
  expect(rain.wind).toBeGreaterThan(overcast.wind);expect(overcast.wind).toBeGreaterThan(day.wind);
  expect(rain.birds.gain).toBe(0);expect(soundscapeAt(forest,0,"Rain").insects).toBe(0);
  for(const minute of [329,1170])expect(soundscapeAt(forest,minute,"Clear").birds.gain).toBe(0);
  expect(soundscapeAt(forest,360,"Clear").birds.gain).toBeGreaterThan(0);
  expect(soundscapeAt(forest,360,"Clear").birds.gain).toBeLessThan(day.birds.gain);
  const cove=LANDSCAPES.viewpoints.find(p=>p.id==="moonwash-cove")!;
  expect(soundscapeAt({...cove,yaw:0},720,"Clear").gulls.gain).toBeGreaterThan(0);
  expect(soundscapeAt({...cove,yaw:0},720,"Rain").gulls.gain).toBe(0);
  expect(soundscapeAt(forest,720+1440,"Clear")).toEqual(day);
  expect(soundscapeAt(forest,-1440,"Clear")).toEqual(night);
});

test("habitat calls distinguish forest, town and coast and legacy maps omit island water",()=>{
  expect(soundscapeAt(forest,720,"Clear").birds.gain).toBeGreaterThan(soundscapeAt(center,720,"Clear").birds.gain);
  const legacy=soundscapeAt(point(FALLS.x,FALLS.z),720,"Clear",false);
  expect([legacy.surf.gain,legacy.river.gain,legacy.waterfall.gain,legacy.gulls.gain]).toEqual([0,0,0,0]);
  expect(legacy.birds.gain).toBeGreaterThan(0);expect(soundscapeAt(center,0,"Clear",false).insects).toBeGreaterThan(0);
  expect(soundscapeAt(center,720,"Rain",false).rain).toBeGreaterThan(0);
});

test("all layer values remain finite and bounded across the island and malformed inputs",()=>{
  const check=(mix:SoundMix)=>{
    for(const value of Object.values(mix)) {
      if(typeof value==="number")expect(value).toBeGreaterThanOrEqual(0);
      else {expect(Number.isFinite(value.pan)).toBe(true);expect(Math.abs(value.pan)).toBeLessThanOrEqual(1);}
      const gain=typeof value==="number"?value:value.gain;
      expect(Number.isFinite(gain)).toBe(true);expect(gain).toBeGreaterThanOrEqual(0);expect(gain).toBeLessThanOrEqual(1);
    }
  };
  for(let x=-900;x<=700;x+=80)for(let z=-800;z<=700;z+=80)check(soundscapeAt(point(x,z,x*.2),x-z,"Clear"));
  for(const value of [NaN,Infinity,-Infinity,Number.MAX_VALUE])check(soundscapeAt(point(value,value,value),value,"Rain"));
});
