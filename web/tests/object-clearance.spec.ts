import {test,expect} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import {boxFootprint,footprintsOverlap,footprintContains,type ObjectFootprint} from "../lib/object-footprint";
import {gatheringBenches,gatheringFixedObstacles,gatheringSeats,gatheringDecorationClear,benchFootprint} from "../lib/gathering-furniture";
import {outdoorBeds,outdoorBenches,outdoorPatch} from "../lib/outdoor-layout";
import {outdoorTrees} from "../lib/outdoor-planting";
import {residentObstacles} from "../lib/world-collision";
import {separateResidents,residentSpacing} from "../lib/resident-spacing";
import type {Building,World} from "../lib/types";

const world:World=JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/city-growth-world.json"),"utf8").replace(/^\uFEFF/,""));
function assertNoOverlap(shapes:readonly ObjectFootprint[],place:string) {
  for(let i=0;i<shapes.length;i++)for(let j=i+1;j<shapes.length;j++)expect(footprintsOverlap(shapes[i],shapes[j]),`${place}: objects ${i} / ${j}`).toBe(false);
}
test("footprints catch edges intersecting even when both centres are outside",()=>{
  expect(footprintsOverlap(boxFootprint(0,2,3.5,1.8),benchFootprint({x:0,z:0},{x:-2.2,z:2.2,rotation:0}))).toBe(true);
  expect(footprintsOverlap({x:0,z:0,radius:2},benchFootprint({x:0,z:0},{x:2.2,z:2.2,rotation:0}))).toBe(true);
  expect(footprintsOverlap(boxFootprint(0,0,4,.4,Math.PI/4),boxFootprint(0,0,4,.4,-Math.PI/4))).toBe(true);
  expect(footprintsOverlap(boxFootprint(0,0,2,2),{x:2,z:2,radius:1})).toBe(false);
  expect(footprintsOverlap(boxFootprint(0,0,2,2),{x:2,z:2,radius:1.5})).toBe(true);
});
test("compact venue furniture and decorative trees do not intersect",()=>{
  for(const type of ["park","promenade","plaza","garden","hangout","trekking_trail"]) {
    const b={id:type,type,x:0,z:0} as Building;
    assertNoOverlap([...gatheringFixedObstacles(b),...gatheringBenches(type).map(p=>benchFootprint(b,p))],type);
  }
  expect(gatheringDecorationClear({id:"garden",type:"garden",x:0,z:0} as Building,-2.7,-2.7,1.05)).toBe(false);
  expect(gatheringDecorationClear({id:"trail",type:"trekking_trail",x:0,z:0} as Building,-2.8,2.8,.945)).toBe(false);
});
test("all expanded destinations reserve full footprints for benches, beds, posts and trees",()=>{
  test.setTimeout(60000);
  for(const b of world.buildings.filter(b=>(b.outdoorRadius??0)>0)) {
    const patch=outdoorPatch(b,world.buildings);
    const benches=outdoorBenches(b,patch),beds=outdoorBeds(b,patch);
    const shapes=[...gatheringFixedObstacles(b),...benches.map(p=>benchFootprint(b,p)),...beds.map(p=>boxFootprint(p.x,p.z,2.7,1.4)),...outdoorTrees(b,world.buildings).map(p=>({x:p.x,z:p.z,radius:p.radius}))];
    assertNoOverlap(shapes,b.id);
    // Seat slots must still correspond to a rendered bench after clipping.
    for(const p of gatheringSeats(b))expect(benches.some(bench=>footprintContains(p,benchFootprint(b,bench))),b.id+" seat").toBe(true);
    expect(outdoorBeds(b,patch)).toEqual(beds);
  }
});
test("standing residents clear every furniture footprint while reserved seats stay fixed",()=>{
  for(const type of ["park","plaza","garden","hangout","promenade"]) {
    const b={id:type,type,x:18,z:6} as Building,obstacles=residentObstacles([b]);
    const seats=gatheringSeats(b).map((p,i)=>({id:`seat-${i}`,x:p.x,z:p.z,y:p.y,anchored:true}));
    const original=[...seats,...Array.from({length:32},(_,i)=>({id:`visitor-${i}`,x:b.x,z:b.z,y:.22}))];
    for(const projection of [undefined,{right:{x:Math.SQRT1_2,y:0,z:-Math.SQRT1_2},up:{x:-.35,y:Math.sqrt(.755),z:-.35},elevation:()=>0}]) {
      const points=separateResidents(original,obstacles,projection);
      for(const p of points) {
        if(p.anchored){expect(p).toEqual(seats.find(s=>s.id===p.id));continue;}
        expect(obstacles.some(o=>footprintContains(p,o)),type+" "+p.id).toBe(false);
        for(const other of points.filter(o=>o.id!==p.id))expect(Math.hypot(p.x-other.x,p.z-other.z)).toBeGreaterThanOrEqual(residentSpacing-.0001);
      }
      expect(separateResidents([...original].reverse(),obstacles,projection)).toEqual(points);
    }
  }
});
test("one idle resident inside a rotated bench is moved clear without moving its sitter",()=>{
  const b={id:"park",type:"park",x:0,z:0} as Building,seat=gatheringSeats(b)[0];
  const sitter={id:"sitter",x:seat.x,z:seat.z,y:seat.y,anchored:true};
  const obstacles=residentObstacles([b]),points=separateResidents([sitter,{id:"standing",x:-3.2,z:0,y:.22}],obstacles);
  expect(points.find(p=>p.id==="sitter")).toEqual(sitter);
  expect(obstacles.some(o=>footprintContains(points.find(p=>p.id==="standing")!,o))).toBe(false);
});
test("a standing task at an old seat coordinate clears furniture without losing its terrain height",()=>{
  const obstacle=boxFootprint(0,0,2.8,.825,Math.PI/2,.55);
  const elevation=(x:number,z:number)=>x*.2+z*.1;
  const p={id:"talking-after-rest",x:0,z:0,y:.22,anchored:true,obstacleAnchored:false};
  const result=separateResidents([p],[obstacle],{right:{x:1,y:0,z:0},up:{x:0,y:1,z:-.5},elevation})[0];
  expect(footprintContains(result,obstacle)).toBe(false);
  expect(result.y).toBeCloseTo(elevation(result.x,result.z)+.22);
});
