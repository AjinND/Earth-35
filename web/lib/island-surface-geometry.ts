import * as THREE from "three";
import {OCEAN_LEVEL,OUTLET_CHANNEL,scaledCoastline} from "./river-outlet";
import type {IslandPoint} from "./island";

const cross=(a:IslandPoint,b:IslandPoint,p:IslandPoint)=>(b.x-a.x)*(p.z-a.z)-(b.z-a.z)*(p.x-a.x);
function clip(polygon:IslandPoint[],a:IslandPoint,b:IslandPoint,keepInside:boolean) {
  const result:IslandPoint[]=[];
  for(let index=0;index<polygon.length;index++) {
    const current=polygon[index],previous=polygon[(index+polygon.length-1)%polygon.length];
    const c=cross(a,b,current),p=cross(a,b,previous),currentKept=keepInside?c>=0:c<=0,previousKept=keepInside?p>=0:p<=0;
    if(currentKept!==previousKept) {
      const fraction=p/(p-c);
      result.push({x:previous.x+(current.x-previous.x)*fraction,z:previous.z+(current.z-previous.z)*fraction});
    }
    if(currentKept)result.push(current);
  }
  return result;
}
function subtractChannel(polygon:IslandPoint[]) {
  const outside:IslandPoint[][]=[];
  let remaining=polygon;
  for(let index=0;index<OUTLET_CHANNEL.length&&remaining.length>=3;index++) {
    const a=OUTLET_CHANNEL[index],b=OUTLET_CHANNEL[(index+1)%OUTLET_CHANNEL.length];
    const piece=clip(remaining,a,b,false);
    if(piece.length>=3)outside.push(piece);
    remaining=clip(remaining,a,b,true);
  }
  return outside;
}
function outline(scale:number,height:number,cutChannel=false) {
  const shape=new THREE.Shape();
  scaledCoastline(scale).forEach((point,index)=>index===0?shape.moveTo(point.x,-point.z):shape.lineTo(point.x,-point.z));
  shape.closePath();
  const original=new THREE.ShapeGeometry(shape);original.rotateX(-Math.PI/2);original.translate(0,height,0);
  if(!cutChannel)return original;
  const attribute=original.getAttribute("position"),index=original.getIndex()!,positions:number[]=[];
  const minX=Math.min(...OUTLET_CHANNEL.map(point=>point.x)),maxX=Math.max(...OUTLET_CHANNEL.map(point=>point.x));
  const minZ=Math.min(...OUTLET_CHANNEL.map(point=>point.z)),maxZ=Math.max(...OUTLET_CHANNEL.map(point=>point.z));
  for(let triangle=0;triangle<index.count;triangle+=3) {
    const points=Array.from({length:3},(_,offset)=>{const vertex=index.getX(triangle+offset);return {x:attribute.getX(vertex),z:attribute.getZ(vertex)};});
    const intersects=points.some(point=>point.x>=minX)&&points.some(point=>point.x<=maxX)&&points.some(point=>point.z>=minZ)&&points.some(point=>point.z<=maxZ);
    for(const polygon of intersects?subtractChannel(points):[points])for(let vertex=1;vertex<polygon.length-1;vertex++) {
      const a=polygon[0],b=polygon[vertex],c=polygon[vertex+1];
      if(Math.abs(cross(a,b,c))<1e-10)continue;
      for(const point of [a,b,c])positions.push(point.x,height,point.z);
    }
  }
  original.dispose();
  const geometry=new THREE.BufferGeometry();geometry.setAttribute("position",new THREE.Float32BufferAttribute(positions,3));
  geometry.setIndex(Array.from({length:positions.length/3},(_,index)=>index));
  geometry.computeVertexNormals();return geometry;
}

// These are the actual world-space surfaces consumed by the renderer and QA.
export function createIslandSurfaces() {
  const ocean=new THREE.PlaneGeometry(16000,16000);ocean.rotateX(-Math.PI/2);ocean.translate(0,OCEAN_LEVEL,0);
  return {ocean,sea:outline(1.10,OCEAN_LEVEL),shelf:outline(1.04,OCEAN_LEVEL),shore:outline(1.012,-.35,true),land:outline(1,-.12,true),foam:outline(1.012,-.3,true)};
}
