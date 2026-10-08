"use client";
import {useEffect,useMemo,useRef} from "react";
import {Html} from "@react-three/drei";
import * as THREE from "three";
import type {Building} from "@/lib/types";
import {outdoorPatch,outdoorPath,outdoorCellSize} from "@/lib/outdoor-layout";
import {gatheringBenches} from "@/lib/gathering-furniture";
import {islandElevation} from "@/lib/island";

function Bench({x,z,y=0,rotation=0}:{x:number;z:number;y?:number;rotation?:number}) {
  return <group position={[x,y,z]} rotation={[0,rotation,0]}><mesh castShadow position={[0,.75,0]}><boxGeometry args={[2.8,.22,.8]}/><meshStandardMaterial color="#9b7552"/></mesh><mesh castShadow position={[0,1.25,-.35]}><boxGeometry args={[2.8,.8,.15]}/><meshStandardMaterial color="#9b7552"/></mesh>{[-1,1].map(x=><mesh key={x} position={[x,.35,0]}><boxGeometry args={[.18,.7,.7]}/><meshStandardMaterial color="#52685b"/></mesh>)}</group>;
}
export default function OutdoorDestination({building:b,buildings,selected,onSelect,showLabels}:{building:Building;buildings:readonly Building[];selected:boolean;onSelect:()=>void;showLabels:boolean}) {
  const spatialKey=buildings.map(p=>`${p.id}:${p.x}:${p.z}`).join("|");
  const patch=useMemo(()=>outdoorPatch(b,buildings),[b.id,b.x,b.z,b.outdoorRadius,spatialKey]);
  const geometry=useMemo(()=>{
    const vertices:number[]=[],colors:number[]=[],indices:number[]=[],half=outdoorCellSize/2;
    const lawn=new THREE.Color(selected?"#a6bd81":"#a0b881"),path=new THREE.Color("#d9ceb5"),sand=new THREE.Color("#dfd1ae"),garden=new THREE.Color("#8b9d65");
    for(const p of patch.cells) {
      const x=p.x-b.x,z=p.z-b.z,pathCell=outdoorPath(b,x,z),edge=Math.hypot(x/patch.radiusX,z/patch.radiusZ)>.86;
      const color=b.id.includes("beach")?sand:b.type==="plaza"?(edge?garden:path):pathCell?path:b.type==="garden"?garden:lawn;
      const n=vertices.length/3;
      for(const [dx,dz] of [[-half,-half],[-half,half],[half,-half],[half,half]]) {vertices.push(x+dx,islandElevation(p.x+dx,p.z+dz)+.055,z+dz);colors.push(color.r,color.g,color.b);}
      indices.push(n,n+1,n+2,n+2,n+1,n+3);
    }
    const g=new THREE.BufferGeometry();g.setAttribute("position",new THREE.Float32BufferAttribute(vertices,3));g.setAttribute("color",new THREE.Float32BufferAttribute(colors,3));g.setIndex(indices);g.computeVertexNormals();return g;
  },[patch,b.type,b.id,b.x,b.z,selected]);
  useEffect(()=>()=>geometry.dispose(),[geometry]);
  const trees=useMemo(()=>patch.cells.filter(p=>Math.hypot(p.x-b.x,p.z-b.z)>8&&!outdoorPath(b,p.x-b.x,p.z-b.z)&&Math.abs(Math.sin(p.x*127.1+p.z*311.7)*43758.5453)%1<.055&&!b.id.includes("beach")).slice(0,90),[patch,b.id,b.x,b.z,b.type]);
  const trunks=useRef<THREE.InstancedMesh>(null),crowns=useRef<THREE.InstancedMesh>(null);
  useEffect(()=>{
    if(!trunks.current||!crowns.current)return;const dummy=new THREE.Object3D();
    trees.forEach((p,i)=>{
      const size=1+(i%4)*.15,height=islandElevation(p.x,p.z);dummy.rotation.set(0,i*2.4,0);
      dummy.position.set(p.x-b.x,height+1.2*size,p.z-b.z);dummy.scale.set(.22*size,2.4*size,.22*size);dummy.updateMatrix();trunks.current!.setMatrixAt(i,dummy.matrix);
      dummy.position.y=height+3.6*size;dummy.scale.set(1.9*size,2.6*size,1.9*size);dummy.updateMatrix();crowns.current!.setMatrixAt(i,dummy.matrix);
    });
    for(const mesh of [trunks.current,crowns.current]){mesh.instanceMatrix.needsUpdate=true;mesh.computeBoundingSphere();}
  },[trees,b.x,b.z]);
  const cellSet=useMemo(()=>new Set(patch.cells.map(p=>`${Math.round((p.x-b.x)/2)}:${Math.round((p.z-b.z)/2)}`)),[patch,b.x,b.z]);
  const inside=(x:number,z:number)=>cellSet.has(`${Math.round(x/2)}:${Math.round(z/2)}`);
  const primary=gatheringBenches(b.type),centerHeight=islandElevation(b.x,b.z),central=b.id==="park";
  const seats=[...primary,...[-1,1].flatMap(side=>[-1,1].map(end=>({x:side*patch.radiusX*.55,z:end*patch.radiusZ*.56,rotation:end<0?Math.PI:0})))].filter(p=>inside(p.x,p.z));
  return <group name={`outdoor-destination-${b.id}`} position={[b.x,0,b.z]} userData={{outdoorArea:patch.area}} onClick={e=>{e.stopPropagation();onSelect();}}>
    <mesh geometry={geometry} receiveShadow><meshStandardMaterial vertexColors roughness={1} side={THREE.DoubleSide}/></mesh>
    <instancedMesh ref={trunks} args={[undefined,undefined,trees.length]} castShadow><cylinderGeometry args={[1,1,1,5]}/><meshStandardMaterial color="#87725a"/></instancedMesh>
    <instancedMesh ref={crowns} args={[undefined,undefined,trees.length]} castShadow receiveShadow><icosahedronGeometry args={[1,1]}/><meshStandardMaterial color="#65885e" roughness={1}/></instancedMesh>
    {(central||b.type==="plaza")&&<group position={[0,centerHeight,0]}><mesh position={[0,.28,0]}><cylinderGeometry args={[1.8,2,.5,24]}/><meshStandardMaterial color="#b8baa9"/></mesh><mesh position={[0,.58,0]}><cylinderGeometry args={[1.5,1.5,.12,24]}/><meshStandardMaterial color="#8eb4ba"/></mesh><mesh position={[0,1.15,0]}><cylinderGeometry args={[.3,.45,1.1,12]}/><meshStandardMaterial color="#b8baa9"/></mesh></group>}
    {seats.map((p,i)=><Bench key={i} {...p} y={islandElevation(b.x+p.x,b.z+p.z)}/>)}
    {b.type==="garden"&&patch.cells.filter((p,i)=>i%33===0&&!outdoorPath(b,p.x-b.x,p.z-b.z)&&Math.hypot(p.x-b.x,p.z-b.z)>5).slice(0,16).map((p,i)=><group key={i} position={[p.x-b.x,islandElevation(p.x,p.z),p.z-b.z]}><mesh position={[0,.25,0]}><boxGeometry args={[2.7,.5,1.4]}/><meshStandardMaterial color="#94755b"/></mesh><mesh position={[0,.55,0]}><boxGeometry args={[2.35,.3,1.1]}/><meshStandardMaterial color={i%2?"#8caa63":"#b99c75"}/></mesh></group>)}
    {b.type==="hangout"&&<group position={[0,centerHeight,0]}>{[-4,4].flatMap(x=>[-3,3].map(z=><mesh key={`${x}-${z}`} position={[x,1.8,z]} castShadow><boxGeometry args={[.25,3.6,.25]}/><meshStandardMaterial color="#a38662"/></mesh>))}{[-4,-2,0,2,4].map(x=><mesh key={x} position={[x,3.7,0]} castShadow><boxGeometry args={[.45,.25,7]}/><meshStandardMaterial color="#a38662"/></mesh>)}</group>}
    <Html position={[0,centerHeight+6,0]} center style={{display:central||showLabels||selected?"block":"none"}}><button className={`map-label ${b.type==="park"?"park-label":""} ${central&&!showLabels?"settlement-marker":""} ${selected?"selected":""}`} onClick={onSelect}>{central?(showLabels||selected?"CENTRAL PARK":"NEW EDEN"):b.name}</button></Html>
  </group>;
}
