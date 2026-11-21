import * as THREE from "three";
import {mergeGeometries} from "three/addons/utils/BufferGeometryUtils.js";
import {buildingDesign,type BuildingBox,type BuildingRoof} from "./building-design";
import type {Building} from "./types";

function roofGeometry(roof:BuildingRoof) {
  const w=roof.width/2,d=roof.depth/2,h=roof.height;
  if(roof.shape==="flat")return new THREE.BoxGeometry(roof.width,h,roof.depth).translate(roof.x,roof.y+h/2,roof.z);
  const ridge=roof.shape==="hip"?Math.max(0,d-w*.65):d;
  const points=roof.shape==="shed"?
    [[-w,0,-d],[w,0,-d],[-w,0,d],[w,0,d],[-w,h,-d],[w,h,-d]]:
    [[-w,0,-d],[w,0,-d],[-w,0,d],[w,0,d],[0,h,-ridge],[0,h,ridge]];
  const indices=roof.shape==="shed"?[0,4,5,0,5,1,2,3,5,2,5,4,0,2,4,1,5,3,0,1,3,0,3,2]:[0,4,1,2,3,5,0,2,5,0,5,4,1,4,5,1,5,3,0,1,3,0,3,2];
  const geometry=new THREE.BufferGeometry();
  geometry.setAttribute("position",new THREE.Float32BufferAttribute(indices.flatMap(i=>[points[i][0]+roof.x,points[i][1]+roof.y,points[i][2]+roof.z]),3));
  geometry.computeVertexNormals();return geometry;
}
function colored(geometry:THREE.BufferGeometry,color:string) {
  const result=geometry.index?geometry.toNonIndexed():geometry;
  if(result!==geometry)geometry.dispose();
  result.deleteAttribute("uv");
  const value=new THREE.Color(color),colors=new Float32Array(result.getAttribute("position").count*3);
  for(let i=0;i<colors.length;i+=3){colors[i]=value.r;colors[i+1]=value.g;colors[i+2]=value.b;}
  result.setAttribute("color",new THREE.BufferAttribute(colors,3));return result;
}
const boxGeometry=(box:BuildingBox)=>new THREE.BoxGeometry(box.width,box.height,box.depth).translate(box.x,box.y,box.z);
function merge(parts:THREE.BufferGeometry[]) {
  const result=parts.length?mergeGeometries(parts)!:new THREE.BufferGeometry();
  for(const part of parts)part.dispose();
  if(parts.length){result.computeBoundingBox();result.computeBoundingSphere();}
  return result;
}

/** Two draw calls per detailed building, independent of window/railing count. */
export function createBuildingGeometry(building:Building,detailed=true) {
  const design=buildingDesign(building,detailed);
  const opaque=merge([...design.solid.map(box=>colored(boxGeometry(box),box.color)),...design.roofs.map(roof=>colored(roofGeometry(roof),roof.color))]);
  const windows=merge(design.glass.map(box=>boxGeometry(box)));
  return {design,opaque,windows,dispose:()=>{opaque.dispose();windows.dispose();}};
}
