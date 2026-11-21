import {test,expect} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import {arrival,walkable,walkSegmentClear} from "../lib/world-collision";
import {prototypeLayout,organicCityLayout} from "../lib/city-layout";
import {LANDSCAPES,ALL_TRAILS} from "../lib/landscapes";
import {outdoorBenches,outdoorBeds,outdoorPatch} from "../lib/outdoor-layout";
import {outdoorTrees} from "../lib/outdoor-planting";
import {riverOffset} from "../lib/scenic-layout";
import type {Building,PlacedWorldObject,World} from "../lib/types";

const building=(type="house",x=0,z=0):Building=>({id:`test-${type}`,type,x,z,name:type,district:"Test",capacity:1,description:"",social:false,restAllowed:false,objects:{}});
const sign:PlacedWorldObject={id:"board",owner:"resident",kind:"advertisement",text:"Open",business:null,x:0,z:0,status:"installed"};
const worldFixture=():World=>JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/city-growth-world.json"),"utf8").replace(/^\uFEFF/,""));

test("swept building collision catches tiny corner crossings between terrain samples",()=>{
  const buildings=[building()],a={x:-4.1,z:-3.695},b={x:-3.5,z:-4.295};
  expect(walkable(a,buildings)).toBe(true);expect(walkable(b,buildings)).toBe(true);
  // None of the quarter-metre sample positions is inside this sliver.
  const steps=Math.ceil(Math.hypot(b.x-a.x,b.z-a.z)/.25);
  for(let i=0;i<=steps;i++)expect(walkable({x:a.x+(b.x-a.x)*i/steps,z:a.z+(b.z-a.z)*i/steps},buildings)).toBe(true);
  expect(walkSegmentClear(a,b,buildings)).toBe(false);
  expect(walkSegmentClear(b,a,buildings)).toBe(false);
  expect(walkSegmentClear({x:-6,z:3.9},{x:6,z:3.9},buildings)).toBe(true);
  expect(walkSegmentClear({x:-6,z:4},{x:6,z:4},buildings)).toBe(true);
});

test("installed signs block the entire swept circle, with pending signs and tangent paths clear",()=>{
  const a={x:-.8,z:.999},b={x:.6,z:.999};
  expect(walkable(a,[],prototypeLayout,[sign])).toBe(true);
  expect(walkable(b,[],prototypeLayout,[sign])).toBe(true);
  expect(walkSegmentClear(a,b,[],prototypeLayout,[sign])).toBe(false);
  expect(walkSegmentClear(b,a,[],prototypeLayout,[sign])).toBe(false);
  expect(walkSegmentClear(a,b,[],prototypeLayout,[{...sign,status:"pending"}])).toBe(true);
  expect(walkSegmentClear({x:-2,z:1},{x:2,z:1},[],prototypeLayout,[sign])).toBe(true);
  expect(walkSegmentClear(sign,sign,[],prototypeLayout,[sign])).toBe(false);
  expect(walkSegmentClear(sign,sign,[],prototypeLayout,[{...sign,status:"carried"}])).toBe(true);
});

test("wall movement permits a clear sliding axis while blocking movement into the wall",()=>{
  const buildings=[building()],start={x:-4.1,z:0};
  expect(walkSegmentClear(start,{x:-3.5,z:.8},buildings)).toBe(false);
  expect(walkSegmentClear(start,{x:-3.5,z:start.z},buildings)).toBe(false);
  expect(walkSegmentClear(start,{x:start.x,z:.8},buildings)).toBe(true);
  expect(walkSegmentClear({x:-4.1,z:4.1},{x:4.1,z:4.1},buildings)).toBe(true);
});

test("terrain sweeps include the starting point and reject nonfinite movement",()=>{
  expect(walkSegmentClear({x:71.1,z:0},{x:70.9,z:0},[])).toBe(false);
  expect(walkSegmentClear({x:0,z:0},{x:0,z:0},[])).toBe(true);
  expect(walkSegmentClear({x:NaN,z:0},{x:0,z:0},[])).toBe(false);
  expect(walkSegmentClear({x:0,z:0},{x:Infinity,z:0},[])).toBe(false);
  expect(walkable({x:NaN,z:0},[])).toBe(false);
});

test("bridges remain traversable while adjacent river crossings and protected cliffs block",()=>{
  for(const bridge of LANDSCAPES.bridges) {
    const left=bridge.x-bridge.length/2,right=bridge.x+bridge.length/2;
    expect(walkSegmentClear({x:left,z:bridge.z},{x:right,z:bridge.z},[],organicCityLayout),bridge.id).toBe(true);
    const z=bridge.z+bridge.width/2+.6;
    expect(walkSegmentClear({x:left,z},{x:right,z},[],organicCityLayout),bridge.id).toBe(false);
  }
  for(const cliff of LANDSCAPES.protectedCliffs)expect(walkSegmentClear({x:cliff.x-cliff.radiusX-3,z:cliff.z},{x:cliff.x+cliff.radiusX+3,z:cliff.z},[],organicCityLayout)).toBe(false);
  for(const trail of ALL_TRAILS)for(let i=1;i<trail.length;i++)expect(walkSegmentClear(trail[i-1],trail[i],[],organicCityLayout)).toBe(true);
  const riverside=[building("river")];
  expect(walkable({x:riverOffset(0),z:0},riverside)).toBe(false);
  expect(walkable({x:0,z:0},riverside)).toBe(true);
});

test("gathering benches use their visible orientation and fountains reserve their basin",()=>{
  const park=[building("park")];
  expect(walkable({x:-3.2,z:0},park)).toBe(false);
  expect(walkable({x:-3.2,z:1.6},park)).toBe(false);
  expect(walkable({x:-3.2,z:1.8},park)).toBe(true);
  expect(walkable({x:0,z:0},park)).toBe(true); // Only the central park has a fountain.
  const central=[{...park[0],id:"park"}],plaza=[building("plaza")];
  expect(walkable({x:0,z:0},central)).toBe(false);
  expect(walkable({x:0,z:0},plaza)).toBe(false);
  expect(walkable({x:2.2,z:0},plaza)).toBe(false);
  expect(walkable({x:2.4,z:0},plaza)).toBe(true);
  expect(walkSegmentClear({x:-5,z:0},{x:5,z:0},plaza)).toBe(false);
});

test("garden beds, tables and pergola posts collide while the open courtyard stays usable",()=>{
  const garden=[building("garden")],courtyard=[building("hangout")];
  for(const x of [-2.7,0,2.7])expect(walkable({x,z:-2.8},garden)).toBe(false);
  expect(walkable({x:0,z:2},garden)).toBe(true);
  expect(walkable({x:0,z:0},garden)).toBe(false);
  for(const x of [-3.5,3.5])for(const z of [-3,3])expect(walkable({x,z},courtyard)).toBe(false);
  expect(walkable({x:0,z:0},courtyard)).toBe(false);
  expect(walkable({x:0,z:2},courtyard)).toBe(true);
  expect(walkable({x:0,z:-3},courtyard)).toBe(true);
  expect(walkSegmentClear({x:3.5,z:-4},{x:3.5,z:-2},courtyard)).toBe(false);
});

test("expanded destinations collide with the actual clipped benches, beds and tree trunks",()=>{
  test.setTimeout(60000);
  const world=worldFixture(),layout=organicCityLayout;
  const woodland=world.buildings.find(b=>b.id==="western-woods")!,trees=outdoorTrees(woodland,world.buildings);
  expect(trees.length).toBeGreaterThan(0);
  for(const tree of trees)expect(walkable(tree,world.buildings,layout),`${tree.kind} trunk`).toBe(false);
  for(const b of world.buildings.filter(b=>(b.outdoorRadius??0)>0)) {
    const patch=outdoorPatch(b,world.buildings);
    for(const p of outdoorBenches(b,patch))expect(walkable({x:b.x+p.x,z:b.z+p.z},world.buildings,layout),b.id+" bench").toBe(false);
    for(const p of outdoorBeds(b,patch))expect(walkable(p,world.buildings,layout),b.id+" bed").toBe(false);
  }
  for(const id of ["meadow-commons","south-gardens","western-woods"])expect(walkable(world.buildings.find(b=>b.id===id)!,world.buildings,layout),id+" clearing").toBe(true);
});

test("snapshot caches preserve metadata updates and invalidate moved, retyped and resized geometry",()=>{
  const initial=[building("park")];
  expect(walkable({x:0,z:0},initial)).toBe(true);
  expect(walkable({x:0,z:0},initial.map(b=>({...b,name:"Renamed",capacity:50})))).toBe(true);
  expect(walkable({x:0,z:0},initial.map(b=>({...b,type:"plaza"})))).toBe(false);
  const moved=[building("house",20,0)];
  expect(walkable({x:0,z:0},moved)).toBe(true);
  expect(walkable({x:0,z:0},moved.map(b=>({...b,x:0})))).toBe(false);
  const world=worldFixture(),garden=world.buildings.find(b=>b.type==="garden")!,p={x:garden.x,z:garden.z-2.8};
  expect(walkable(p,world.buildings,organicCityLayout)).toBe(true);
  expect(walkable(p,world.buildings.map(b=>b.id===garden.id?{...b,outdoorRadius:0}:b),organicCityLayout)).toBe(false);
});

test("entrance changes rebuild tree clearances even when all building footprints stay fixed",()=>{
  const world=worldFixture(),marker={...building("wilderness",1000,1000),entrance:{side:"open",x:1000,z:1000}};
  const before=[...world.buildings,marker],woodland=before.find(b=>b.id==="western-woods")!;
  const tree=outdoorTrees(woodland,before)[0];expect(tree).toBeDefined();
  expect(walkable(tree,before,organicCityLayout)).toBe(false);
  const after=before.map(b=>b.id===marker.id?{...b,entrance:{side:"open",x:tree.x,z:tree.z}}:b);
  expect(walkable(tree,after,organicCityLayout)).toBe(true);
  expect(walkable(tree,before.map(b=>({...b,name:"Metadata update"})),organicCityLayout)).toBe(false);
});

test("arrivals find nearby safe ground deterministically and validate distant recovery points",()=>{
  const plaza=[building("plaza")],requested={x:0,z:0},safe=arrival(requested,plaza);
  expect(walkable(safe,plaza)).toBe(true);
  expect(Math.hypot(safe.x,safe.z)).toBeLessThanOrEqual(2.5);
  expect(arrival(requested,plaza)).toEqual(safe);
  const blockedSpawn=[building("house",0,8)];
  const distant=arrival({x:1000,z:1000},blockedSpawn);
  expect(distant).not.toEqual({x:0,z:8});expect(walkable(distant,blockedSpawn)).toBe(true);
  expect(arrival(distant,blockedSpawn)).toBe(distant);
  const impossible={...prototypeLayout,halfExtent:1};
  expect(()=>arrival(requested,[building()],impossible)).toThrow("no clear arrival position");
});

test("every world address and scenic destination has a walkable arrival after furniture collision",()=>{
  test.setTimeout(60000);
  const world=worldFixture();
  for(const b of world.buildings) {
    const safe=arrival(b,world.buildings,organicCityLayout,world.objects);
    expect(walkable(safe,world.buildings,organicCityLayout,world.objects),b.id).toBe(true);
  }
  for(const viewpoint of LANDSCAPES.viewpoints)expect(walkable(arrival(viewpoint,world.buildings,organicCityLayout,world.objects),world.buildings,organicCityLayout,world.objects),viewpoint.id).toBe(true);
});
