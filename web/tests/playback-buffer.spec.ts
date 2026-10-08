import {test,expect} from "@playwright/test";
import {PlaybackTimeline} from "../lib/playback-timeline";
import {TrafficPlayback} from "../lib/traffic-playback";
import {ResidentPlayback} from "../lib/resident-playback";
import type {Vehicle} from "../lib/types";

const vehicle=(minute:number):Vehicle=>({id:"car-0",type:"car",route:"",name:"Car",x:minute*6,z:0,heading:Math.PI/2,stop:"",seatsAvailable:0,
  motion:Array.from({length:13},(_,i)=>({x:(minute-1)*6+i*.5,z:0,heading:Math.PI/2}))});
const walker=(minute:number)=>({x:minute*6,z:0,activity:"MOVE"});

function players() {
  const timeline=new PlaybackTimeline(2000),traffic=new TrafficPlayback(timeline),resident=new ResidentPlayback(timeline);
  const publish=(minute:number,now:number,trace=false)=>{timeline.ingest(minute,5000,now);traffic.ingest(minute,[vehicle(minute)],5000,now);resident.ingest(minute,{...walker(minute),motion:trace?vehicle(minute).motion:undefined},5000,now);};
  const sample=(now:number)=>{
    const car=traffic.sample(now).get("car-0")!,person=resident.sample(now);
    expect(person.x).toBeCloseTo(car.x,6);return car.x;
  };
  return {timeline,traffic,resident,publish,sample};
}

test("a shared buffer keeps walkers and traffic moving through publication jitter",()=>{
  const p=players();p.publish(0,0);p.publish(1,5000);
  expect(p.sample(6000)).toBe(0);
  let previous=p.sample(8000);
  // A late interval followed by a shorter interval leaves the known path continuous.
  const updates=[{minute:2,time:11500},{minute:3,time:16000},{minute:4,time:21000}];
  for(let now=8100;now<=22000;now+=100) {
    const update=updates.find(update=>update.time===now);if(update)p.publish(update.minute,now);
    const position=p.sample(now);expect(position).toBeGreaterThan(previous);previous=position;
  }
});

test("measured cadence preserves the reserve across forty slightly slower ticks",()=>{
  const p=players();p.publish(0,0);p.publish(1,5500);
  let previous=p.sample(8000);
  for(let now=8100;now<=220000;now+=100) {
    if(now%5500===0)p.publish(now/5500,now);
    const position=p.sample(now);expect(position).toBeGreaterThan(previous);previous=position;
  }
});

test("the reserve remains continuous through eleven minutes of alternating publication jitter",()=>{
  test.setTimeout(60000);
  const p=players();p.publish(0,0);p.publish(1,5550);
  let previous=p.sample(8000);
  for(let now=8050;now<=660000;now+=50) {
    if(now%10000===0)p.publish(now/5000,now);
    else if((now-5550)%10000===0)p.publish((now-5550)/5000+1,now);
    const position=p.sample(now);expect(position,`motion at ${now} ms`).toBeGreaterThan(previous);previous=position;
  }
});

test("boarding remains aligned with the bus and reduced motion resolves both endpoints",()=>{
  const timeline=new PlaybackTimeline(2000),traffic=new TrafficPlayback(timeline),resident=new ResidentPlayback(timeline);
  const start={...vehicle(0),id:"bus-0",type:"bus"};
  traffic.ingest(0,[start],5000,0);resident.ingest(0,{x:0,z:0,activity:"BOARD_BUS"},5000,0);
  const bus={...vehicle(1),id:"bus-0",type:"bus",stop:"stop-market"};
  traffic.ingest(1,[bus],5000,5000);resident.ingest(1,{x:6,z:0,activity:"RIDE_BUS",motion:bus.motion},5000,5000);
  for(let now=7100;now<12000;now+=100) {
    const car=traffic.sample(now).get("bus-0")!,person=resident.sample(now);
    expect(person.x).toBeCloseTo(car.x,6);expect(person.visible).toBe(true);expect(person.activity).toBe("BOARD_BUS");
  }
  expect(traffic.sample(12000).get("bus-0")!.stop).toBe("stop-market");
  expect(resident.sample(12000).visible).toBe(false);
  const reduced=players();reduced.publish(0,0);reduced.publish(1,5000);
  expect(reduced.traffic.sample(6000,true).get("car-0")!.x).toBe(6);
  expect(reduced.resident.sample(6000,true).x).toBe(6);
});

test("same-minute completion retains the known walk and reserves seating until arrival",()=>{
  const timeline=new PlaybackTimeline(2000),resident=new ResidentPlayback(timeline);
  resident.ingest(0,walker(0),5000,0);resident.ingest(1,walker(1),5000,5000);
  const seat={x:6,z:0,y:.83,rotation:0};
  resident.ingest(1,{...walker(1),activity:"REST",seat},5000,8000);
  const inFlight=resident.sample(9000);expect(inFlight.moving).toBe(true);expect(inFlight.seat).toBeUndefined();expect(inFlight.x).toBeCloseTo(2.4);
  const done=resident.sample(12000);expect(done.moving).toBe(false);expect(done.seat).toEqual(seat);expect(done.x).toBe(6);
  resident.ingest(1,{...walker(1),activity:"Idle"},5000,12500);
  expect(resident.sample(12500).activity).toBe("Idle");expect(resident.sample(12500).seat).toBeUndefined();
});

test("outages never extrapolate and a new resident does not replay missing history",()=>{
  const p=players();p.publish(0,0);p.publish(1,5000);
  expect(p.sample(20000)).toBe(6);expect(p.sample(25000)).toBe(6);
  p.publish(4,30000,true);
  const position=p.sample(33000);expect(position).toBeGreaterThanOrEqual(18);expect(position).toBeLessThanOrEqual(24);
  const joined=new ResidentPlayback(p.timeline);joined.ingest(4,walker(4),5000,33000);
  expect(joined.sample(33000).x).toBe(24);expect(joined.sample(33500).x).toBe(24);
});

test("same-minute metadata without a trace preserves an unfinished boarding path",()=>{
  const timeline=new PlaybackTimeline(2000),resident=new ResidentPlayback(timeline);
  resident.ingest(0,{x:3,z:0,activity:"BOARD_BUS"},5000,0);
  const motion=[{x:3,z:0,heading:0},{x:3,z:2,heading:0},{x:.8,z:2,heading:-Math.PI/2}];
  resident.ingest(1,{x:.8,z:2,activity:"RIDE_BUS",motion},5000,5000);
  const before=resident.sample(9000);expect(before.visible).toBe(true);expect(before.x).toBe(3);expect(before.z).toBeCloseTo(1.6);
  resident.ingest(1,{x:.8,z:2,activity:"RIDE_BUS"},5000,9000);
  const duplicate=resident.sample(9000);expect(duplicate).toEqual(before);
  const later=resident.sample(10000);expect(later.visible).toBe(true);expect(later.x).toBeLessThan(3);expect(later.z).toBe(2);
  expect(resident.sample(12000).visible).toBe(false);
});
