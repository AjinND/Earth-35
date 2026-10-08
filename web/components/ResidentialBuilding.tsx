"use client";
import {useEffect,useMemo} from "react";
import * as THREE from "three";
import {Html} from "@react-three/drei";
import {architecture} from "@/lib/architecture";
import type {Building} from "@/lib/types";
import BuildingWindows from "./BuildingWindows";

function Gable({width,depth,color}:{width:number;depth:number;color:string}) {
  const geometry=useMemo(()=>{
    const w=width/2,d=depth/2,h=1.35;
    const points=[[-w,0,-d],[w,0,-d],[0,h,-d],[-w,0,d],[w,0,d],[0,h,d]];
    const faces=[0,2,1,3,4,5,0,3,5,0,5,2,1,2,5,1,5,4,0,1,4,0,4,3];
    const g=new THREE.BufferGeometry();g.setAttribute("position",new THREE.Float32BufferAttribute(faces.flatMap(i=>points[i]),3));g.computeVertexNormals();return g;
  },[width,depth]);
  useEffect(()=>()=>geometry.dispose(),[geometry]);
  return <mesh geometry={geometry} castShadow receiveShadow><meshStandardMaterial color={color} roughness={.95}/></mesh>;
}
export default function ResidentialBuilding({building:b,selected,windows,onSelect}:{building:Building;selected:boolean;windows:THREE.Material;onSelect:()=>void}) {
  const a=architecture(b.id,b.type),floors=Math.max(1,Math.round(a.height/2.7)),villa=a.style==="villa",stepped=a.style==="stepped-apartment",courtyard=a.style==="courtyard-house";
  const windowBoxes=useMemo(()=>Array.from({length:floors},(_,floor)=>[-1,1].flatMap(side=>[
    {x:side*a.width*.28,y:.2+(floor+.5)*a.height/floors,z:a.depth/2+.03,width:.9,height:1.05,depth:.06},
    {x:a.width/2+.03,y:.2+(floor+.5)*a.height/floors,z:side*a.depth*.27,width:.06,height:1.05,depth:.9}
  ])).flat(),[floors,a.width,a.height,a.depth]);
  return <group name={`architecture-${a.style}-${b.id}`} position={[b.x,0,b.z]} onClick={e=>{e.stopPropagation();onSelect();}}>
    <group rotation={[0,a.angle,0]}>
      <mesh receiveShadow position={[0,.08,0]}><boxGeometry args={[a.width+.3,.16,a.depth+.3]}/><meshStandardMaterial color="#bdb8a5" roughness={1}/></mesh>
      <mesh castShadow receiveShadow position={[villa?-1:0,a.height/2+.2,0]}><boxGeometry args={[villa?a.width-2:a.width,a.height,a.depth]}/><meshStandardMaterial color={selected?"#a1c3aa":a.color} roughness={.95}/></mesh>
      {villa&&<mesh castShadow receiveShadow position={[a.width/2-1,a.height*.38+.2,a.depth/2-1.4]}><boxGeometry args={[2,a.height*.76,2.8]}/><meshStandardMaterial color={a.color}/></mesh>}
      {courtyard&&<mesh position={[0,a.height+.3,0]}><boxGeometry args={[2,.15,2.2]}/><meshStandardMaterial color="#8fa986"/></mesh>}
      {stepped&&<mesh castShadow position={[-.8,a.height+1.2,-.7]}><boxGeometry args={[a.width-1.6,2,a.depth-1.4]}/><meshStandardMaterial color={a.color}/></mesh>}
      <group position={[villa?-1:0,a.height+.23,0]}>
        {a.roof==="gable"?<Gable width={a.width+.25} depth={a.depth+.25} color={a.roofColor}/>:a.roof==="hip"?<mesh castShadow scale={[1,.65,a.depth/(villa?a.width-2:a.width)]} rotation={[0,Math.PI/4,0]} position={[0,.85,0]}><coneGeometry args={[(villa?a.width-2:a.width)*.59,2.5,4]}/><meshStandardMaterial color={a.roofColor}/></mesh>:<mesh castShadow position={[0,.15,0]} rotation={[a.roof==="shed"?-.12:0,0,0]}><boxGeometry args={[villa?a.width-1.8:a.width+.25,.3,a.depth+.25]}/><meshStandardMaterial color={a.roof==="flat"?"#e1dbc9":a.roofColor}/></mesh>}
      </group>
      <BuildingWindows boxes={windowBoxes} material={windows}/>
      {Array.from({length:floors},(_,floor)=>[-1,1].map(side=><group key={`${floor}-${side}`}>
        {b.type==="apartment"&&floor>0&&a.style!=="narrow-apartment"&&<><mesh castShadow position={[side*a.width*.28,floor*a.height/floors+.35,a.depth/2+.25]}><boxGeometry args={[1.6,.13,.5]}/><meshStandardMaterial color="#d8d2bc"/></mesh><mesh position={[side*a.width*.28,floor*a.height/floors+.85,a.depth/2+.43]}><boxGeometry args={[1.6,.65,.06]}/><meshStandardMaterial color="#7b9088" transparent opacity={.7}/></mesh></>}
      </group>))}
      {b.type==="house"&&<mesh castShadow position={[-a.width*.28,a.height+.8,-a.depth*.25]}><boxGeometry args={[.55,1.1,.6]}/><meshStandardMaterial color="#a6846a"/></mesh>}
    </group>
    <mesh position={[0,1.05,a.depth/2+.1]}><boxGeometry args={[1.05,2.1,.14]}/><meshStandardMaterial color="#705944"/></mesh>
    <mesh receiveShadow position={[0,.12,(a.depth/2+3.6)/2]}><boxGeometry args={[1.6,.15,3.6-a.depth/2+.5]}/><meshStandardMaterial color="#d2cab7"/></mesh>
    {a.style==="villa"&&<group name="villa-veranda" position={[1.7,0,2.6]}><mesh position={[0,2.55,0]}><boxGeometry args={[2.2,.14,1.1]}/><meshStandardMaterial color="#b1936b"/></mesh>{[-.85,.85].map(x=><mesh key={x} position={[x,1.25,.35]}><boxGeometry args={[.12,2.5,.12]}/><meshStandardMaterial color="#b1936b"/></mesh>)}</group>}
    {selected&&<Html position={[0,a.height+3,0]} center style={{pointerEvents:"none"}}><span className="map-label selected">{b.name}</span></Html>}
  </group>;
}
