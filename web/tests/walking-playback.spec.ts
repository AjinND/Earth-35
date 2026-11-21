import {test,expect} from "@playwright/test";
import {ResidentPlayback,residentFacingHeading} from "../lib/resident-playback";
import {PlaybackTimeline} from "../lib/playback-timeline";
import {separateResidents,type ResidentPoint,type ResidentProjection} from "../lib/resident-spacing";
import type {ActivityProgress} from "../lib/types";

const progress:ActivityProgress={mode:"walk",phase:"travel",label:"A stroll together",activeMinutes:1,requestedMinutes:20,inspectedStops:0,distance:8};

test("shared walking traces retain their formation through corners and camera rotation",()=>{
  const timeline=new PlaybackTimeline(),walkers=[new ResidentPlayback(timeline),new ResidentPlayback(timeline)];
  for(const [index,walker] of walkers.entries()) {
    const offset=index*1.4;
    // Walking partners can start together; the received formation opens up
    // gradually, without manufacturing separate starting coordinates.
    walker.ingest(600,{x:0,z:0,activity:"DO_ACTIVITY",activityProgress:progress},1000,0);
    walker.ingest(601,{x:4+offset,z:8,activity:"DO_ACTIVITY",activityProgress:progress,motion:[
      {x:0,z:0,heading:0},{x:offset,z:4,heading:0},
      {x:4+offset,z:4,heading:Math.PI/2},{x:4+offset,z:8,heading:0},
    ]},1000,0);
  }
  for(const now of [0,100,250,500,750,999,1000]) {
    const samples=walkers.map(walker=>walker.sample(now));
    const points:ResidentPoint[]=samples.map((sample,index)=>({id:`walker-${index}`,x:sample.x,y:.22,z:sample.z,anchored:sample.routeAnchored}));
    expect(samples.every(sample=>sample.routeAnchored)).toBe(true);
    for(const yaw of [0,Math.PI/4,Math.PI/2,Math.PI,-Math.PI/3])for(const tilt of [Math.PI/12,Math.PI/3]) {
      const projection:ResidentProjection={
        right:{x:Math.cos(yaw),y:0,z:-Math.sin(yaw)},
        up:{x:-Math.sin(yaw)*Math.sin(tilt),y:Math.cos(tilt),z:-Math.cos(yaw)*Math.sin(tilt)},
      };
      const crowded=[...points,{id:"idle-visitor",x:points[0].x,y:.22,z:points[0].z}];
      const separated=separateResidents(crowded,[],projection);
      for(const point of points)expect(separated.find(p=>p.id===point.id)).toEqual(point);
    }
    if(now===0)expect(points[0].x).toBe(points[1].x);
    if(now===500) {
      expect(samples[0].x).toBe(2);expect(samples[0].z).toBe(4);
      expect(samples[1].x-samples[0].x).toBeCloseTo(1.4);
      expect(samples[1].z).toBe(samples[0].z);
    }
  }
});

test("walking route protection follows the buffered activity until its final step",()=>{
  const walker=new ResidentPlayback();
  walker.ingest(600,{x:0,z:0,activity:"DO_ACTIVITY",activityProgress:progress},1000,0);
  expect(walker.sample(0).routeAnchored).toBe(true);
  walker.ingest(601,{x:4,z:0,activity:"Idle",motion:[{x:0,z:0,heading:Math.PI/2},{x:4,z:0,heading:Math.PI/2}]},1000,0);
  expect(walker.sample(500).routeAnchored).toBe(true);
  const finished=walker.sample(1000);
  expect(finished.x).toBe(4);expect(finished.moving).toBe(false);
  expect(finished.routeAnchored).toBe(false);
  const observer=new ResidentPlayback();
  observer.ingest(600,{x:0,z:0,activity:"DO_ACTIVITY",activityProgress:{...progress,mode:"observe",phase:"perform"}},1000,0);
  expect(observer.sample(0).routeAnchored).toBe(false);
});

test("walking bodies face their travel direction while stationary residents keep social attention",()=>{
  const forward=Math.PI/2,standing=.3,oldConversation=-Math.PI/2;
  expect(residentFacingHeading({moving:true,activity:"DO_ACTIVITY"},forward,standing,oldConversation)).toBe(forward);
  expect(residentFacingHeading({moving:false,activity:"DO_ACTIVITY"},forward,standing,oldConversation)).toBe(oldConversation);
  expect(residentFacingHeading({moving:false,activity:"CHAT"},forward,standing,oldConversation)).toBe(oldConversation);
  expect(residentFacingHeading({moving:false,activity:"Idle"},forward,standing)).toBe(standing);
});

test("reconnection preserves a completed activity's received path until its final step",()=>{
  const walker=new ResidentPlayback();
  walker.ingest(600,{x:0,z:0,activity:"DO_ACTIVITY",activityProgress:progress},1000,0);
  // The completion receipt after an outage has no remaining activity metadata.
  walker.ingest(604,{x:4,z:4,activity:"Idle",motion:[
    {x:2,z:0,heading:Math.PI/2},{x:4,z:0,heading:Math.PI/2},{x:4,z:4,heading:0},
  ]},1000,0);
  const resumed=walker.sample(0),corner=walker.sample(500),finished=walker.sample(1000);
  expect(resumed.x).toBe(2);expect(resumed.z).toBe(0);
  expect(resumed.routeAnchored).toBe(true);
  expect(corner.x).toBe(4);expect(corner.z).toBe(0);
  expect(corner.routeAnchored).toBe(true);
  expect(finished.x).toBe(4);expect(finished.z).toBe(4);
  expect(finished.routeAnchored).toBe(false);
});

test("tiny time cuts cannot turn a steady stroll into repeated idle pauses",()=>{
  const walker=new ResidentPlayback();
  walker.ingest(600,{x:0,z:0,activity:"DO_ACTIVITY",activityProgress:progress},1000,0);
  walker.ingest(601,{x:8,z:0,activity:"DO_ACTIVITY",activityProgress:progress,motion:[
    {x:0,z:0,heading:Math.PI/2,fraction:0},
    {x:2,z:0,heading:Math.PI/2,fraction:.25},{x:2.000008,z:0,heading:Math.PI/2,fraction:.250001},
    {x:4,z:0,heading:Math.PI/2,fraction:.5},{x:4.000008,z:0,heading:Math.PI/2,fraction:.500001},
    {x:6,z:0,heading:Math.PI/2,fraction:.75},{x:6.000008,z:0,heading:Math.PI/2,fraction:.750001},
    {x:8,z:0,heading:Math.PI/2,fraction:1},
  ]},1000,0);
  for(const now of [100,200,300,400,600,700,800,900]) {
    const sample=walker.sample(now);
    expect(sample.x).toBeCloseTo(now*.008,6);
    expect(sample.z).toBe(0);expect(sample.moving).toBe(true);
    expect(sample.routeAnchored).toBe(true);
  }
});

test("different companion path densities share corner times and preserve real waiting",()=>{
  const walkers=[new ResidentPlayback(),new ResidentPlayback()];
  walkers[0].ingest(600,{x:0,z:0,activity:"DO_ACTIVITY",activityProgress:progress},1000,0);
  walkers[1].ingest(600,{x:0,z:1.4,activity:"DO_ACTIVITY",activityProgress:progress},1000,0);
  walkers[0].ingest(601,{x:4,z:4,activity:"DO_ACTIVITY",activityProgress:progress,motion:[
    {x:0,z:0,heading:Math.PI/2,fraction:0},{x:4,z:0,heading:Math.PI/2,fraction:.4},
    {x:4,z:4,heading:0,fraction:.8},{x:4,z:4,heading:0,fraction:1},
  ]},1000,0);
  walkers[1].ingest(601,{x:4,z:5.4,activity:"DO_ACTIVITY",activityProgress:progress,motion:[
    {x:0,z:1.4,heading:Math.PI/2,fraction:0},{x:2,z:1.4,heading:Math.PI/2,fraction:.2},
    {x:4,z:1.4,heading:Math.PI/2,fraction:.4},{x:4,z:3.4,heading:0,fraction:.6},
    {x:4,z:5.4,heading:0,fraction:.8},{x:4,z:5.4,heading:0,fraction:1},
  ]},1000,0);
  for(const now of [100,300,500,700,900]) {
    const [first,second]=walkers.map(walker=>walker.sample(now));
    expect(first.x).toBeCloseTo(second.x,8);
    expect(second.z-first.z).toBeCloseTo(1.4,8);
    expect(Math.hypot(first.x-second.x,first.z-second.z)).toBeCloseTo(1.4,8);
    expect(first.moving).toBe(now<800);expect(second.moving).toBe(now<800);
    if(now===300){expect(first.x).toBeCloseTo(3);expect(first.z).toBe(0);}
    if(now===500){expect(first.x).toBe(4);expect(first.z).toBeCloseTo(1);}
    if(now===900){expect(first.x).toBe(4);expect(first.z).toBe(4);}
  }
});

test("missing or invalid path clocks preserve legacy playback",()=>{
  for(const fractions of [[0,undefined,1],[0,NaN,1],[0,1.2,1],[.1,.4,1],[0,.4,.9]]) {
    const walker=new ResidentPlayback();
    walker.ingest(600,{x:0,z:0,activity:"DO_ACTIVITY",activityProgress:progress},1000,0);
    walker.ingest(601,{x:8,z:0,activity:"DO_ACTIVITY",activityProgress:progress,motion:
      [0,4,8].map((x,index)=>({x,z:0,heading:Math.PI/2,fraction:fractions[index]})),
    },1000,0);
    const sample=walker.sample(250);
    expect(sample.x).toBe(2);expect(sample.moving).toBe(true);
  }
  const walker=new ResidentPlayback();
  walker.ingest(600,{x:0,z:0,activity:"DO_ACTIVITY",activityProgress:progress},1000,0);
  walker.ingest(601,{x:8,z:0,activity:"DO_ACTIVITY",activityProgress:progress,motion:[
    {x:0,z:0,heading:Math.PI/2,fraction:0},{x:4,z:0,heading:Math.PI/2,fraction:.75},
    {x:6,z:0,heading:Math.PI/2,fraction:.5},{x:8,z:0,heading:Math.PI/2,fraction:1},
  ]},1000,0);
  expect(walker.sample(500).x).toBe(5);
});
