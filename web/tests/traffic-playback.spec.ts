import {test,expect} from "@playwright/test";
import {TrafficPlayback} from "../lib/traffic-playback";
import type {Vehicle} from "../lib/types";

function car(start:number,end:number):Vehicle {
  return {id:"car-0",type:"car",route:"",name:"Car",x:end,z:0,heading:Math.PI/2,stop:"",seatsAvailable:0,
    motion:Array.from({length:13},(_,i)=>({x:start+(end-start)*i/12,z:0,heading:Math.PI/2}))};
}
const x=(player:TrafficPlayback,time:number)=>player.sample(time).get("car-0")!.x;

test("same-minute broadcasts never replay motion or snap a vehicle to the endpoint",()=>{
  const player=new TrafficPlayback();player.ingest(600,[car(-6,0)],1000,0);
  expect(x(player,0)).toBe(0);player.ingest(601,[car(0,6)],1000,0);
  let previous=0;
  for(let time=10;time<=1000;time+=10) {
    player.ingest(601,[car(0,6)],1000,time);
    const position=x(player,time);expect(position).toBeGreaterThanOrEqual(previous-1e-8);
    expect(position).toBeCloseTo(time*6/1000,6);previous=position;
  }
  expect(x(player,1500)).toBe(6);
});

test("early successive ticks queue without restarting an unfinished path",()=>{
  const player=new TrafficPlayback();player.ingest(600,[car(-6,0)],1000,0);player.ingest(601,[car(0,6)],1000,0);
  expect(x(player,500)).toBeCloseTo(3);player.ingest(602,[car(6,12)],1000,500);
  expect(x(player,500)).toBeCloseTo(3);expect(x(player,1000)).toBeCloseTo(6);
  expect(x(player,1250)).toBeCloseTo(7.5);expect(x(player,2000)).toBeCloseTo(12);
});

test("substeps interpolate continuously and delayed or stale broadcasts never rewind",()=>{
  const player=new TrafficPlayback();player.ingest(600,[car(-6,0)],1000,0);player.ingest(601,[car(0,6)],1000,0);
  expect(x(player,42)).toBeCloseTo(.252);expect(x(player,1000)).toBe(6);
  player.ingest(600,[car(-6,0)],1000,1100);expect(x(player,1100)).toBe(6);
  player.ingest(602,[car(6,12)],1000,1500);expect(x(player,1500)).toBe(6);expect(x(player,1750)).toBeCloseTo(7.5);
});

test("turns follow the sampled corner and take the shortest rotation",()=>{
  const player=new TrafficPlayback();const vehicle=car(0,0);
  vehicle.motion=Array.from({length:13},(_,i)=>({x:i<=6?i*.5:3,z:i<=6?0:(i-6)*.5,heading:i<=6?Math.PI/2:0}));
  vehicle.x=3;vehicle.z=3;vehicle.heading=0;
  player.ingest(600,[car(-6,0)],1000,0);player.ingest(601,[vehicle],1000,0);
  const before=player.sample(400).get(vehicle.id)!;expect(before.z).toBe(0);expect(before.x).toBeCloseTo(2.4);
  const turn=player.sample(525).get(vehicle.id)!;expect(turn.x).toBe(3);expect(turn.z).toBeCloseTo(.15);
  expect(turn.heading).toBeGreaterThan(0);expect(turn.heading).toBeLessThan(Math.PI/2);
  expect(player.sample(1000).get(vehicle.id)!.z).toBe(3);
});

test("new vehicles appear and departed vehicles leave on the shared timeline",()=>{
  const player=new TrafficPlayback();player.ingest(600,[car(-6,0)],1000,0);
  const bus={...car(10,16),id:"bus:R1:0:600",type:"bus"};
  player.ingest(601,[car(0,6),bus],1000,0);expect(player.sample(100).size).toBe(2);
  player.ingest(602,[car(6,12)],1000,500);
  expect(player.sample(750).has(bus.id)).toBe(true);expect(player.sample(1100).has(bus.id)).toBe(false);
});

test("a turn that would sweep into a waiting car keeps authoritative clearance",()=>{
  const player=new TrafficPlayback();const bus={...car(0,0),id:"bus:R1:0:600",type:"bus"};
  const waiting={...car(2.5,2.5),z:2.5,motion:undefined};
  player.ingest(600,[bus,waiting],1000,0);
  bus.motion=Array.from({length:13},(_,i)=>({x:0,z:0,heading:i===0?Math.PI/2:0}));bus.heading=0;
  player.ingest(601,[bus,waiting],1000,0);
  // The endpoints clear the car; a 45-degree bus body would sweep into it.
  // Unsafe rotation uses the already validated next pose for the whole substep.
  for(let time=1;time<84;time++)expect(player.sample(time).get(bus.id)!.heading).toBe(0);
});

test("bus doors open after arrival and remain open through queued stationary intervals",()=>{
  const player=new TrafficPlayback(),id="bus:R1:0:600";
  const bus=(start:number,end:number,stop:string):Vehicle=>({...car(start,end),id,type:"bus",stop});
  player.ingest(600,[bus(-6,0,"")],1000,0);
  player.ingest(601,[bus(0,6,"stop-market")],1000,0);
  expect(player.sample(999).get(id)!.stop).toBe("");
  expect(player.sample(1000).get(id)!.stop).toBe("stop-market");
  player.ingest(602,[bus(6,6,"stop-market")],1000,1000);
  player.ingest(603,[bus(6,12,"")],1000,1500);
  expect(player.sample(1750).get(id)!.stop).toBe("stop-market");
  expect(player.sample(2250).get(id)!.stop).toBe("");
});
