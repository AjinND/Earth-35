import {organicPoint,streetDistance} from "./organic-layout";
export type CityLayout={version:number;centerX:number;centerZ:number;spacing:number;halfExtent:number;metersPerUnit:number;walkingSpeed:number;runningSpeed:number;vehicleSpeed:number};
export const prototypeLayout:CityLayout={version:0,centerX:0,centerZ:0,spacing:12,halfExtent:72,metersPerUnit:10,walkingSpeed:2,runningSpeed:4,vehicleSpeed:6};
export const islandCityLayout:CityLayout={...prototypeLayout,version:1,centerX:-150,centerZ:0,spacing:48,halfExtent:268,walkingSpeed:8,runningSpeed:16,vehicleSpeed:24};
export const organicCityLayout:CityLayout={...islandCityLayout,version:2};
export const roadCenters=Array.from({length:11},(_,i)=>-60+i*12);
export const blockCenters=Array.from({length:10},(_,i)=>-54+i*12);
export const layoutRoads=(layout:CityLayout,east:boolean)=>roadCenters.map(v=>(east?layout.centerX:layout.centerZ)+v*layout.spacing/12);
export const layoutBlocks=(layout:CityLayout,east:boolean)=>blockCenters.map(v=>(east?layout.centerX:layout.centerZ)+v*layout.spacing/12);
export const layoutPoint=(layout:CityLayout,x:number,z:number)=>layout.version>=2?organicPoint(layout.centerX+x*layout.spacing/12,layout.centerZ+z*layout.spacing/12):({x:layout.centerX+x*layout.spacing/12,z:layout.centerZ+z*layout.spacing/12});
export function withinSettlement(x:number,z:number,layout:CityLayout=prototypeLayout) {return Math.abs(x-layout.centerX)<=layout.halfExtent-1&&Math.abs(z-layout.centerZ)<=layout.halfExtent-1;}
export const roadHalfWidth=1.6;
export function enclosedPlace(type:string) {
  return !["park","garden","plaza","promenade","hangout","bus_stop","river","riverside_walk","mountain","hill_station","trekking_trail","wilderness"].includes(type);
}
export function clearsRoad(x:number,z:number,radius=0,layout:CityLayout=prototypeLayout) {
  if(layout.version>=2)return streetDistance(x,z)>roadHalfWidth+radius;
  return Math.abs(x-layout.centerX-Math.round((x-layout.centerX)/layout.spacing)*layout.spacing)>roadHalfWidth+radius&&Math.abs(z-layout.centerZ-Math.round((z-layout.centerZ)/layout.spacing)*layout.spacing)>roadHalfWidth+radius;
}
