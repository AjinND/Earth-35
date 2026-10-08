import {organicPoint,surveyPoint,streetDistance,dryLand,organicStreets} from "./organic-layout";
import {clearsRoad,type CityLayout} from "./city-layout";
import type {IslandPoint} from "./island";
import type {Building,BusStop,BusPlatformPose} from "./types";

/** Match curb platforms to the surveyed carriageway, including older snapshots. */
export function busPlatformPlacements(building:Building,stop:BusStop|undefined,layout:CityLayout):BusPlatformPose[] {
  if(stop?.platforms?.length)return stop.platforms;
  const point=stop??building;
  const road={x:stop?.roadX??(Math.abs(point.x-layout.centerX-Math.round((point.x-layout.centerX)/layout.spacing)*layout.spacing)<3?layout.centerX+Math.round((point.x-layout.centerX)/layout.spacing)*layout.spacing:point.x),
    z:stop?.roadZ??(Math.abs(point.z-layout.centerZ-Math.round((point.z-layout.centerZ)/layout.spacing)*layout.spacing)<3?layout.centerZ+Math.round((point.z-layout.centerZ)/layout.spacing)*layout.spacing:point.z)};
  let heading=stop?.heading??Math.atan2(-(point.z-road.z),point.x-road.x);
  if(layout.version>=2) {
    let best=Infinity;
    for(const points of organicStreets)for(let i=1;i<points.length;i++) {
      const a=points[i-1],b=points[i],dx=b.x-a.x,dz=b.z-a.z,length=Math.hypot(dx,dz);
      const t=Math.max(0,Math.min(1,((road.x-a.x)*dx+(road.z-a.z)*dz)/(length*length)));
      const x=a.x+t*dx,z=a.z+t*dz,distance=Math.hypot(road.x-x,road.z-z);
      if(distance>=best)continue;
      best=distance;heading=Math.atan2(dx,dz);
    }
    if(Math.cos(heading)*(point.x-road.x)-Math.sin(heading)*(point.z-road.z)<0)heading+=Math.PI;
  }
  const clockwise=building.id==="stop-park"?heading:heading+Math.PI;
  return [0,1].map(side=>{
    const h=clockwise+side*Math.PI;
    return {route:side===0?"R1":"R2",x:road.x+Math.cos(h)*2.4,z:road.z-Math.sin(h)*2.4,heading:h};
  });
}

export type SidewalkQuad=readonly [IslandPoint,IslandPoint,IslandPoint,IslandPoint];
/** Small curb segments stop before any intersecting carriageway. */
export function sidewalkQuads(points:readonly IslandPoint[],offset:number,width=.7):SidewalkQuad[] {
  const quads:SidewalkQuad[]=[];
  for(let i=1;i<points.length;i++) {
    const a=points[i-1],b=points[i],dx=b.x-a.x,dz=b.z-a.z,length=Math.hypot(dx,dz),steps=Math.ceil(length/.5);
    const sx=dz/length,sz=-dx/length;
    for(let j=0;j<steps;j++) {
      const at=(t:number,side:number)=>({x:a.x+dx*t+sx*(offset+side*width/2),z:a.z+dz*t+sz*(offset+side*width/2)});
      const quad:SidewalkQuad=[at(j/steps,-1),at(j/steps,1),at((j+1)/steps,-1),at((j+1)/steps,1)];
      const center={x:quad.reduce((sum,p)=>sum+p.x,0)/4,z:quad.reduce((sum,p)=>sum+p.z,0)/4};
      const reach=Math.max(...quad.map(p=>Math.hypot(p.x-center.x,p.z-center.z)));
      // Distance to the street union is Lipschitz: this clears the whole quad,
      // including a diagonal road through its interior, not only its corners.
      if(streetDistance(center.x,center.z)>1.6+reach+.04)quads.push(quad);
    }
  }
  return quads;
}
export function signalPlacement(signal:IslandPoint,layout:CityLayout) {
  if(layout.version<2)return {pole:{x:signal.x+2.4,z:signal.z+2.4},heading:0,crossing:{x:signal.x,z:signal.z+5},crossingHeading:0};
  const q=surveyPoint(signal.x,signal.z);
  for(const offset of [3.1,3.6,4.2,5])for(const [sx,sz] of [[1,1],[-1,1],[1,-1],[-1,-1]]) {
    const pole=organicPoint(q.x+sx*offset,q.z+sz*offset);
    if(!dryLand(pole)||!clearsRoad(pole.x,pole.z,.5,layout))continue;
    const crossing=organicPoint(q.x,q.z+5),next=organicPoint(q.x,q.z+5.1);
    return {pole,heading:Math.atan2(signal.x-pole.x,signal.z-pole.z),crossing,crossingHeading:Math.atan2(next.x-crossing.x,next.z-crossing.z)};
  }
  throw new Error("No clear curb position for traffic signal");
}
