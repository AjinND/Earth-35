import {test,expect} from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import {createPrecipitationSampler,precipitationFloor} from "../lib/atmosphere";
import {architecture} from "../lib/architecture";
import {enclosedPlace,organicCityLayout,prototypeLayout} from "../lib/city-layout";
import {islandElevation,riverLevel,walkElevation} from "../lib/island";
import {renderedTerrainElevation} from "../lib/island-terrain";
import {FALLS,LAKE,LANDSCAPES,lakeRadius,poolRadius} from "../lib/landscapes";
import {nearestRiver,RIVER} from "../lib/river";
import type {Building,World} from "../lib/types";

type FloorWorld=Pick<World,"buildings"|"geography">;
const fixture=()=>JSON.parse(fs.readFileSync(path.resolve("tests/fixtures/city-growth-world.json"),"utf8").replace(/^\uFEFF/,"")) as World;
const building=(id:string,x:number,z:number,type="house"):Building=>({id,x,z,type,name:id,district:"Housing",capacity:4,description:"",objects:{},social:false,restAllowed:false,entrance:{side:"front",x,z:z+4}});

// An independent all-building reference retains the existing roof, bridge and
// water semantics. Version four additionally follows the actual terrain mesh.
function referenceFloor(x:number,z:number,world:FloorWorld) {
  const version=world.geography?.version??0;
  let floor=version>=2?Math.max(-.6,walkElevation(x,z)):0;
  if(version>=4)floor=Math.max(floor,renderedTerrainElevation(x,z)??-Infinity);
  const river=nearestRiver(x,z);
  if(river.distance<river.width/2)floor=Math.max(floor,riverLevel(river.index,river.fraction));
  if(lakeRadius(x,z)<1)floor=Math.max(floor,LAKE.level);
  if(poolRadius(x,z)<1)floor=Math.max(floor,riverLevel(LANDSCAPES.lakeRiverEnd+1));
  for(const b of world.buildings)if((enclosedPlace(b.type)||b.type==="construction")&&Math.abs(x-b.x)<=4.4&&Math.abs(z-b.z)<=4.4) {
    const height=["house","apartment","office"].includes(b.type)?architecture(b.id,b.type,b.district).height+3.5:b.type==="construction"?22:b.type==="mall"?7:b.type==="studio"?9:7;
    floor=Math.max(floor,(version>=2?islandElevation(b.x,b.z):0)+height);
  }
  return floor+.15;
}

test("prepared precipitation floors preserve exact roofs, water and terrain across layouts",()=>{
  test.setTimeout(60000);
  const world=fixture();
  const points=[...Array.from({length:100},(_,i)=>({x:-620+(i*83.7)%1110,z:-590+(i*137.9)%1050})),
    ...world.buildings.flatMap(b=>[{x:b.x,z:b.z},{x:b.x+4.4,z:b.z},{x:b.x-4.40001,z:b.z}]),
    {x:LAKE.x,z:LAKE.z},{x:FALLS.x+FALLS.dx*3,z:FALLS.z+FALLS.dz*3},
    ...RIVER.filter((_,i)=>i%13===0),...LANDSCAPES.bridges.map(b=>({x:b.x,z:b.z}))];
  for(const version of [0,2,4]) {
    const input={...world,geography:{...organicCityLayout,version}},sample=createPrecipitationSampler(input);
    for(const point of points)expect(sample(point.x,point.z)).toBeCloseTo(referenceFloor(point.x,point.z,input),10);
  }
});

test("rain clips above a rendered cliff triangle that rises higher than analytic walking ground",()=>{
  const world:FloorWorld={buildings:[],geography:{...organicCityLayout,version:4}},x=-102,z=-362;
  const rendered=renderedTerrainElevation(x,z)!;
  expect(rendered-walkElevation(x,z)).toBeGreaterThan(.8);
  expect(createPrecipitationSampler(world)(x,z)).toBeGreaterThanOrEqual(rendered+.15);
  expect(precipitationFloor(x,z,world)).toBeCloseTo(rendered+.15,10);
});

test("roof indexing handles negative cells, shared boundaries and overlapping roofs exactly",()=>{
  const world:FloorWorld={geography:prototypeLayout,buildings:[building("edge",-16,16),building("site",-14,16,"construction"),building("other",16,-16,"studio")]};
  const sample=createPrecipitationSampler(world);
  for(const b of world.buildings)for(const dx of [-4.400001,-4.4,0,4.4,4.400001])for(const dz of [-4.4,0,4.4]) {
    expect(sample(b.x+dx,b.z+dz)).toBe(referenceFloor(b.x+dx,b.z+dz,world));
  }
  expect(sample(-16,16)).toBe(22.15);
});

test("semantic layout caching refreshes new, moved and changed buildings without altering old samplers",()=>{
  const world:FloorWorld={buildings:[],geography:prototypeLayout},open=createPrecipitationSampler(world);
  world.buildings.push(building("new-home",16,16));
  const house=createPrecipitationSampler(world);
  expect(house).not.toBe(open);expect(house(16,16)).toBeGreaterThan(open(16,16)+5);
  world.buildings[0]={...world.buildings[0],x:48,z:48};
  const moved=createPrecipitationSampler(world);
  expect(moved(16,16)).toBe(open(16,16));expect(house(16,16)).toBeGreaterThan(moved(16,16)+5);
  world.buildings[0]={...world.buildings[0],type:"construction",plannedType:"office"};
  const site=createPrecipitationSampler(world);expect(site(48,48)).toBe(22.15);
  const copy={...world,buildings:world.buildings.map(b=>({...b}))};
  expect(createPrecipitationSampler(copy)).toBe(site);
  copy.buildings[0].plannedType="house";
  expect(createPrecipitationSampler(copy)).not.toBe(site);
});

test("terrain version participates in the sampler cache and legacy queries remain compatible",()=>{
  const base:FloorWorld={buildings:[building("mountain-roof",-200,-430)],geography:prototypeLayout};
  const flat=createPrecipitationSampler(base),mountain={...base,geography:{...organicCityLayout,version:4}},raised=createPrecipitationSampler(mountain);
  expect(raised).not.toBe(flat);expect(raised(-200,-430)).toBeGreaterThan(flat(-200,-430)+20);
  for(const point of [{x:0,z:0},{x:-200,z:-430},{x:LAKE.x,z:LAKE.z}])expect(precipitationFloor(point.x,point.z,mountain)).toBe(raised(point.x,point.z));
});
