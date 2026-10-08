import {test,expect} from "@playwright/test";
import {ResidentPlayback,type ResidentPose} from "../lib/resident-playback";
import {separateResidents,residentSpacing,type ResidentPoint} from "../lib/resident-spacing";

type Snapshot=Parameters<ResidentPlayback["ingest"]>[1];
const snapshot=(x:number,z:number,activity:string,motion?:Snapshot["motion"]):Snapshot=>({x,z,activity,motion});
const boarding=snapshot(.8,2,"RIDE_BUS",[
  {x:3,z:0,heading:0},
  {x:3,z:2,heading:0},
  {x:1.475,z:2,heading:-Math.PI/2},
  {x:.8,z:2,heading:-Math.PI/2},
]);
function playerAtStop() {
  const player=new ResidentPlayback();
  player.ingest(600,snapshot(3,0,"BOARD_BUS"),1000,0);
  player.ingest(601,boarding,1000,0);
  return player;
}
function expectPose(actual:ResidentPose,x:number,z:number,visible=true,activity="BOARD_BUS") {
  expect(actual.x).toBeCloseTo(x,7);
  expect(actual.z).toBeCloseTo(z,7);
  expect(actual.visible).toBe(visible);
  expect(actual.activity).toBe(activity);
}

test("boarding walks the recorded corner and remains visible until the bus endpoint",()=>{
  const player=playerAtStop();
  expectPose(player.sample(250),3,1.5);
  // Halfway through the path has already rounded the waiting-area corner.
  // Interpolating straight to the bus would instead put the resident at z=1.
  expectPose(player.sample(500),2.2375,2);
  expectPose(player.sample(750),1.30625,2);
  const lastStep=player.sample(999);
  expect(lastStep.x).toBeGreaterThan(.8);
  expect(lastStep.visible).toBe(true);
  expect(lastStep.activity).toBe("BOARD_BUS");
  expectPose(player.sample(1000),.8,2,false,"RIDE_BUS");
  expectPose(player.sample(1500),.8,2,false,"RIDE_BUS");
});

test("duplicate and stale broadcasts preserve the unfinished boarding walk",()=>{
  const player=playerAtStop();
  expectPose(player.sample(250),3,1.5);
  player.ingest(601,boarding,1000,250);
  expectPose(player.sample(500),2.2375,2);
  player.ingest(600,snapshot(3,0,"BOARD_BUS"),1000,500);
  expectPose(player.sample(750),1.30625,2);
  player.ingest(601,boarding,1000,999);
  expect(player.sample(999).visible).toBe(true);
  expectPose(player.sample(1000),.8,2,false,"RIDE_BUS");
});

test("a resident already riding at connection time starts hidden",()=>{
  const player=new ResidentPlayback();
  player.ingest(601,boarding,1000,1234);
  expectPose(player.sample(1234),.8,2,false,"RIDE_BUS");
  expectPose(player.sample(2234),.8,2,false,"RIDE_BUS");
});

test("reduced motion resolves boarding to its final pose and stays there",()=>{
  const player=playerAtStop();
  expectPose(player.sample(250),3,1.5);
  expectPose(player.sample(250,true),.8,2,false,"RIDE_BUS");
  expectPose(player.sample(500),.8,2,false,"RIDE_BUS");
});

test("an early riding tick queues behind the visible boarding interval",()=>{
  const player=playerAtStop();
  const riding=snapshot(.8,8,"RIDE_BUS",[
    {x:.8,z:2,heading:0},{x:.8,z:8,heading:0},
  ]);
  player.ingest(602,riding,1000,500);
  expectPose(player.sample(500),2.2375,2);
  expectPose(player.sample(999),.802025,2);
  expectPose(player.sample(1000),.8,2,false,"RIDE_BUS");
  expectPose(player.sample(1250),.8,3.5,false,"RIDE_BUS");
  expectPose(player.sample(2000),.8,8,false,"RIDE_BUS");
});

test("a delayed boarding tick starts when received without skipping its walk",()=>{
  const player=new ResidentPlayback();
  player.ingest(600,snapshot(3,0,"BOARD_BUS"),1000,0);
  expectPose(player.sample(1500),3,0);
  player.ingest(601,boarding,1000,1500);
  expectPose(player.sample(1500),3,0);
  expectPose(player.sample(1750),3,1.5);
  expectPose(player.sample(2499),.802025,2);
  expectPose(player.sample(2500),.8,2,false,"RIDE_BUS");
});

test("reconnection skips missing history and resumes at the supplied walking start",()=>{
  const player=playerAtStop();
  expectPose(player.sample(1000),.8,2,false,"RIDE_BUS");
  const reconnected=snapshot(10.8,22,"RIDE_BUS",[
    {x:13,z:20,heading:0},
    {x:13,z:22,heading:0},
    {x:11.475,z:22,heading:-Math.PI/2},
    {x:10.8,z:22,heading:-Math.PI/2},
  ]);
  player.ingest(605,reconnected,1000,1500);
  expectPose(player.sample(1500),13,20);
  expectPose(player.sample(1750),13,21.5);
  expectPose(player.sample(2499),10.802025,22);
  expectPose(player.sample(2500),10.8,22,false,"RIDE_BUS");
});

test("reconnection without a walking trace does not fabricate boarding",()=>{
  const player=playerAtStop();
  player.ingest(605,snapshot(10.8,22,"RIDE_BUS"),1000,1500);
  expectPose(player.sample(1500),10.8,22,false,"RIDE_BUS");
  expectPose(player.sample(1750),10.8,22,false,"RIDE_BUS");
});

test("crowd separation keeps transit residents anchored to their selected curb and door",()=>{
  const transit:ResidentPoint[]=[
    {id:"wait-northbound",x:3.2,z:0,anchored:true},
    {id:"wait-southbound",x:-3.2,z:0,anchored:true},
    {id:"board-door",x:1.475,z:4,anchored:true},
  ];
  const walkers=transit.flatMap((p,i)=>Array.from({length:4},(_,j)=>({id:`walker-${i}-${j}`,x:p.x,z:p.z})));
  const positions=separateResidents([...walkers,...transit],[{x:3.2,z:0,radius:1}]);
  for(const p of transit)expect(positions.find(q=>q.id===p.id)).toEqual(p);
  for(const walker of positions.filter(p=>!p.anchored))for(const fixed of transit)
    expect(Math.hypot(walker.x-fixed.x,walker.z-fixed.z)).toBeGreaterThanOrEqual(residentSpacing-.0001);
  expect(separateResidents([...transit,...walkers].reverse(),[{x:3.2,z:0,radius:1}])).toEqual(positions);
});
