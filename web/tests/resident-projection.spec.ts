import {test,expect} from "@playwright/test";
import {separateResidents,residentSpacing,type ResidentPoint,type ResidentProjection} from "../lib/resident-spacing";
import {gatheringSeats,fountainObstacles} from "../lib/gathering-furniture";
import type {Building} from "../lib/types";

// The eight idle visitors in the Meadow Commons reproduction snapshot.
const meadowIds=[
  "143b0ef3-77a7-4997-b549-c108a463de50",
  "1aed7638-44c1-4eb0-a817-d13ed69c5080",
  "23be25fb-854f-4213-96ae-a3393737398f",
  "bee81d45-b887-4c56-912d-ebd9b4dd4b83",
  "c3adb994-7922-4675-95d2-79018df0de6b",
  "e05a44b5-2b8f-4cd7-9d02-6a183d1d2edc",
  "048ee884-3f4e-4f9e-99a9-73dcb3cbe427",
  "07ee178c-69c9-4332-b031-3c54635abab2",
].map(id=>`resident-${id}`);

function mapProjection(elevation:number,yaw:number):ResidentProjection {
  return {
    right:{x:Math.cos(yaw),y:0,z:-Math.sin(yaw)},
    up:{x:-Math.sin(yaw)*Math.sin(elevation),y:Math.cos(elevation),z:-Math.cos(yaw)*Math.sin(elevation)},
  };
}
const defaultProjection=mapProjection(Math.atan2(1550,Math.hypot(1800,1950)),Math.atan2(1800,1950));
const mapViews=[
  {name:"default map",projection:defaultProjection},
  ...[Math.PI/2-Math.PI/2.4,Math.PI/2-.2].flatMap((elevation,index)=>
    [0,Math.PI/4,Math.PI/2,3*Math.PI/4,Math.PI,-Math.PI/3].map(yaw=>({
      name:`${index===0?"lowest":"highest"} map elevation, yaw ${yaw.toFixed(3)}`,
      projection:mapProjection(elevation,yaw),
    }))),
];

function assertClear(points:readonly ResidentPoint[],projection:ResidentProjection) {
  for(let i=0;i<points.length;i++)for(let j=i+1;j<points.length;j++) {
    const a=points[i],b=points[j];
    // Seats and transit anchors retain their exact reserved positions.
    if(a.anchored&&b.anchored)continue;
    const dx=b.x-a.x,dy=(b.y??0)-(a.y??0),dz=b.z-a.z;
    expect(Math.hypot(dx,dz),`${a.id} / ${b.id}: ground clearance`).toBeGreaterThanOrEqual(residentSpacing-.0001);
    const horizontal=dx*projection.right.x+dy*projection.right.y+dz*projection.right.z;
    const vertical=dx*projection.up.x+dy*projection.up.y+dz*projection.up.z;
    expect(Math.hypot(horizontal,vertical),`${a.id} / ${b.id}: projected clearance`).toBeGreaterThanOrEqual(residentSpacing-.0001);
  }
}

for(const {name,projection} of mapViews)test(`all eight Meadow Commons visitors remain distinct at ${name}`,()=>{
  const original=meadowIds.map(id=>({id,x:-416,y:.22,z:12}));
  const before=original.map(point=>({...point}));
  const positions=separateResidents(original,[],projection);
  expect(positions).toHaveLength(8);
  expect(new Set(positions.map(point=>point.id))).toEqual(new Set(meadowIds));
  assertClear(positions,projection);
  expect(separateResidents([...original].reverse(),[],projection)).toEqual(positions);
  expect(separateResidents([...original.slice(3),...original.slice(0,3)],[],projection)).toEqual(positions);
  expect(original).toEqual(before);
  for(const point of positions)expect(point.y).toBe(.22);
});

test("camera clearance preserves occupied seats, transit anchors and fountain clearance",()=>{
  const square={id:"town-square",type:"plaza",x:18,z:6} as Building;
  const seats=gatheringSeats(square).map((seat,i)=>({id:`seat-${i}`,x:seat.x,y:seat.y,z:seat.z,anchored:true}));
  const transit={id:"transit-anchor",x:32,y:.22,z:6,anchored:true};
  const original:ResidentPoint[]=[...seats,transit,...Array.from({length:24},(_,i)=>({
    id:meadowIds[i]??`extra-visitor-${i}`,x:square.x,y:.22,z:square.z,
  }))];
  const before=original.map(point=>({...point})),obstacles=fountainObstacles([square]);
  for(const projection of [defaultProjection,mapProjection(Math.PI/12,Math.PI/4)]) {
    const positions=separateResidents(original,obstacles,projection);
    assertClear(positions,projection);
    for(const fixed of [...seats,transit])expect(positions.find(point=>point.id===fixed.id)).toEqual(fixed);
    for(const point of positions)for(const obstacle of obstacles)
      expect(Math.hypot(point.x-obstacle.x,point.z-obstacle.z)).toBeGreaterThanOrEqual(obstacle.radius-.0001);
    expect(separateResidents([...original].reverse(),obstacles,projection)).toEqual(positions);
  }
  expect(original).toEqual(before);
});

test("rendered elevation differences participate in projected clearance without changing heights",()=>{
  const heights=[0,.22,.83,1.4,2,3,7,9];
  const original=meadowIds.map((id,i)=>({id,x:-416,y:heights[i],z:12}));
  const before=original.map(point=>({...point}));
  for(const {projection} of mapViews) {
    const positions=separateResidents(original,[],projection);
    assertClear(positions,projection);
    expect(separateResidents([...original].reverse(),[],projection)).toEqual(positions);
    for(const point of positions)expect(point.y).toBe(original.find(source=>source.id===point.id)!.y);
  }
  expect(original).toEqual(before);
});

test("a ground-level camera retains finite ground spacing and isolated residents stay fixed",()=>{
  const original:ResidentPoint[]=[{id:"a",x:0,z:0},{id:"b",x:0,z:0},{id:"isolated",x:50,z:50}];
  const projection=mapProjection(0,Math.PI/4);
  const positions=separateResidents(original,[],projection);
  expect(positions).toEqual(separateResidents(original));
  expect(positions.find(point=>point.id==="isolated")).toEqual(original[2]);
  for(const point of positions)expect(Number.isFinite(point.x)&&Number.isFinite(point.z)).toBe(true);
});
