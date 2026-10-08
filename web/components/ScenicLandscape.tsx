"use client";
import {useEffect,useMemo,useRef,useState} from "react";
import {useFrame} from "@react-three/fiber";
import {Html} from "@react-three/drei";
import * as THREE from "three";
import type {Building} from "@/lib/types";
import {gatheringBenches} from "@/lib/gathering-furniture";
import {riverBounds,riverOffset} from "@/lib/scenic-layout";
import {clearsRoad,prototypeLayout,type CityLayout} from "@/lib/city-layout";
import OrganicRiver from "./OrganicRiver";
import {islandElevation} from "@/lib/island";

function strip(points:THREE.Vector3[],width:number) {
  const positions:number[]=[],indices:number[]=[];
  points.forEach((point,i)=>{
    const direction=points[Math.min(i+1,points.length-1)].clone().sub(points[Math.max(i-1,0)]).normalize();
    const side=new THREE.Vector3(direction.z,0,-direction.x).multiplyScalar(width/2);
    for(const sign of [-1,1])positions.push(point.x+side.x*sign,point.y,point.z+side.z*sign);
    if(i<points.length-1){const n=i*2;indices.push(n,n+2,n+1,n+1,n+2,n+3);}
  });
  const geometry=new THREE.BufferGeometry();
  geometry.setAttribute("position",new THREE.Float32BufferAttribute(positions,3));geometry.setIndex(indices);geometry.computeVertexNormals();
  return geometry;
}
function Path({points,width=1.4,color="#d3b88c",name="scenic-path"}:{points:THREE.Vector3[];width?:number;color?:string;name?:string}) {
  const geometry=useMemo(()=>strip(points,width),[points,width]);
  useEffect(()=>()=>geometry.dispose(),[geometry]);
  return <mesh name={name} geometry={geometry} receiveShadow><meshStandardMaterial color={color} roughness={1} side={THREE.DoubleSide}/></mesh>;
}
function Pine({x,z,size=1}:{x:number;z:number;size?:number}) {
  return <group position={[x,0,z]} scale={size}>
    <mesh castShadow position={[0,1,0]}><cylinderGeometry args={[.14,.24,2,6]}/><meshStandardMaterial color="#72513b"/></mesh>
    {[0,1,2].map(i=><mesh key={i} castShadow position={[0,1.8+i*.85,0]}><coneGeometry args={[1.35-i*.28,2.5,7]}/><meshStandardMaterial color={i===1?"#41654e":"#527b59"} roughness={1}/></mesh>)}
  </group>;
}
function Rock({x,z,size=.5}:{x:number;z:number;size?:number}) {
  return <mesh castShadow receiveShadow position={[x,size*.3,z]} scale={[size,size*.6,size*.8]} rotation={[.2,x,.3]}><icosahedronGeometry args={[1,0]}/><meshStandardMaterial color="#84928c" roughness={1}/></mesh>;
}
function Bench({x,z,rotation}:{x:number;z:number;rotation:number}) {
  return <group position={[x,0,z]} rotation={[0,rotation,0]}>
    <mesh castShadow position={[0,.75,0]}><boxGeometry args={[2.8,.22,.8]}/><meshStandardMaterial color="#9b7552"/></mesh>
    <mesh castShadow position={[0,1.25,-.35]}><boxGeometry args={[2.8,.8,.15]}/><meshStandardMaterial color="#9b7552"/></mesh>
    {[-1,1].map(x=><mesh key={x} position={[x,.35,0]}><boxGeometry args={[.18,.7,.7]}/><meshStandardMaterial color="#52685b"/></mesh>)}
  </group>;
}
function Mountain({x,z,height=13,radius=5}:{x:number;z:number;height?:number;radius?:number}) {
  const geometry=useMemo(()=>{
    const vertices:number[]=[],colors:number[]=[];
    const point=(ring:number,angle:number)=>{
      const theta=angle*Math.PI/6,r=radius*(1-ring/4)*(1+.16*Math.sin(angle*2.8+ring));
      return new THREE.Vector3(Math.cos(theta)*r+ring*.15,height*ring/4*(1+(ring===4?0:.1*Math.sin(angle*1.7))),Math.sin(theta)*r);
    };
    for(let ring=0;ring<4;ring++)for(let angle=0;angle<12;angle++) {
      const a=point(ring,angle),b=point(ring,angle+1),c=point(ring+1,angle),d=point(ring+1,angle+1);
      for(const triangle of [[a,c,b],[b,c,d]]) {
        const heightHere=triangle.reduce((sum,p)=>sum+p.y,0)/3;
        const color=new THREE.Color(heightHere>height*.76?"#e7eee8":heightHere>height*.24?"#6f817c":"#688463");
        color.multiplyScalar(.88+((angle+ring)%3)*.09);
        for(const p of triangle){vertices.push(p.x,p.y,p.z);colors.push(color.r,color.g,color.b);}
      }
    }
    const g=new THREE.BufferGeometry();g.setAttribute("position",new THREE.Float32BufferAttribute(vertices,3));g.setAttribute("color",new THREE.Float32BufferAttribute(colors,3));g.computeVertexNormals();return g;
  },[height,radius]);
  useEffect(()=>()=>geometry.dispose(),[geometry]);
  return <mesh name="rocky-mountain" position={[x,0,z]} geometry={geometry} castShadow receiveShadow><meshStandardMaterial vertexColors flatShading roughness={1} side={THREE.DoubleSide}/></mesh>;
}

/** One continuous river shared by both riverside destinations. */
export function RiverLandscape({buildings,layout=prototypeLayout}:{buildings:readonly Building[];layout?:CityLayout}) {
  const bounds=useMemo(()=>riverBounds(buildings),[buildings]);
  const flow=useRef<THREE.InstancedMesh>(null),elapsed=useRef(0),telemetry=useRef(0),dummy=useMemo(()=>new THREE.Object3D(),[]);
  const [reducedMotion,setReducedMotion]=useState(false);
  useEffect(()=>{
    const query=window.matchMedia("(prefers-reduced-motion: reduce)");const update=()=>setReducedMotion(query.matches);
    update();query.addEventListener("change",update);return()=>query.removeEventListener("change",update);
  },[]);
  const points=useMemo(()=>bounds?Array.from({length:49},(_,i)=>{const z=bounds.from+(bounds.to-bounds.from)*i/48;return new THREE.Vector3(bounds.bankX+riverOffset(z),.14,z);}):[],[bounds]);
  const path=useMemo(()=>points.map(p=>new THREE.Vector3(bounds!.bankX+.12*Math.sin(p.z*.12),.16,p.z)),[bounds,points]);
  const water=useMemo(()=>points.map(p=>new THREE.Vector3(p.x,p.y+.02,p.z)),[points]);
  useFrame(({gl},delta)=>{
    if(!bounds||!flow.current)return;
    if(!reducedMotion)elapsed.current+=Math.min(delta,.1)*1.4;
    for(let i=0;i<36;i++) {
      const z=bounds.from+((i*(bounds.to-bounds.from)/36+elapsed.current)%(bounds.to-bounds.from));
      dummy.position.set(bounds.bankX+riverOffset(z)+Math.sin(i*5.7)*1.35,.17,z);
      dummy.rotation.set(0,.1*Math.cos(z*.12),0);dummy.scale.set(.5+(i%3)*.18,1,1);dummy.updateMatrix();flow.current.setMatrixAt(i,dummy.matrix);
    }
    flow.current.instanceMatrix.needsUpdate=true;
    telemetry.current+=delta;
    if(telemetry.current>.25){telemetry.current=0;gl.domElement.dataset.riverFlow=elapsed.current.toFixed(3);}
  });
  if(layout.version>=2)return <OrganicRiver/>;
  if(!bounds)return null;
  return <group name="silverstream-river">
    <Path points={points} width={5.5} color="#c1b28d" name="river-shore"/>
    <Path points={water} width={4.4} color="#287f99" name="flowing-river"/>
    <Path points={path} width={1.8} name="riverside-walkway"/>
    <instancedMesh ref={flow} args={[undefined,undefined,36]} name="river-current" raycast={()=>null}>
      <boxGeometry args={[1.3,.025,.1]}/><meshStandardMaterial color="#99d5de" emissive="#2b6e80" emissiveIntensity={.15}/>
    </instancedMesh>
    {Array.from({length:9},(_,i)=>{const z=bounds.from+3+i*(bounds.to-bounds.from-6)/8,size=.6+(i%3)*.1;return <group key={i}>{clearsRoad(bounds.bankX-2.6,z,1.35*size,layout)&&<Pine x={bounds.bankX-2.6} z={z} size={size}/>}<Rock x={bounds.bankX+1.3} z={z+1} size={.28+(i%2)*.12}/></group>;})}
  </group>;
}

export default function ScenicDestination({building:b,selected,onSelect,showLabels,organic=false}:{building:Building;selected:boolean;onSelect:()=>void;showLabels:boolean;organic?:boolean}) {
  const mountain=b.type==="mountain",hill=b.type==="hill_station",trail=b.type==="trekking_trail";
  const path=useMemo(()=>Array.from({length:25},(_,i)=>{
    const z=-4+i/3;return new THREE.Vector3(trail?Math.sin(z*.7)*1.35:Math.sin(z*.5)*.2,.13,z);
  }),[trail]);
  return <group name={`scenery-${b.id}`} position={[b.x,organic?islandElevation(b.x,b.z):0,b.z]} onClick={e=>{e.stopPropagation();onSelect();}}>
    {(mountain||hill||trail)&&<>
      <mesh receiveShadow position={[0,.04,0]} rotation={[-Math.PI/2,0,0]}><circleGeometry args={[4.5,20]}/><meshStandardMaterial color={selected?"#88ab72":"#8ca875"} roughness={1}/></mesh>
      <Path points={path} width={trail?1.5:1.3} name={trail?"winding-trekking-trail":"lookout-path"}/>
    </>}
    {mountain&&!organic&&<>
      <Mountain x={0} z={-13} height={15} radius={5}/><Mountain x={-4.3} z={-13.5} height={9} radius={3.5}/>
      {[-2.8,0,2.8].map((x,i)=><Rock key={x} x={x} z={-3} size={.8+i*.25}/>)}
    </>}
    {hill&&!organic&&<>
      {[[0,-13,4.8,3.6],[3,-13,3.5,2.7],[-2.7,-14,3.2,2.5]].map(([x,z,width,height],i)=><mesh key={i} name="rolling-hill" position={[x,-.45,z]} scale={[width,height,width*.85]} castShadow receiveShadow><sphereGeometry args={[1,16,10,0,Math.PI*2,0,Math.PI/2]}/><meshStandardMaterial color={i%2?"#658759":"#779961"} roughness={1}/></mesh>)}
      <Pine x={-3} z={-2.6} size={.65}/><Pine x={2.7} z={-2.5} size={.75}/>
    </>}
    {trail&&<>
      {[-1,1].flatMap(side=>[-2.8,0,2.8].map((z,i)=><Pine key={`${side}-${z}`} x={side*(3.2-i*.2)} z={z} size={.7+(i%2)*.2}/>))}
      <Rock x={2.6} z={2.7} size={.5}/><Rock x={-2} z={-1.7} size={.35}/>
      <group name="trail-marker" position={[2.2,0,3.4]}><mesh position={[0,.9,0]}><boxGeometry args={[.14,1.8,.14]}/><meshStandardMaterial color="#765739"/></mesh><mesh position={[0,1.65,0]}><boxGeometry args={[1.4,.4,.12]}/><meshStandardMaterial color="#bb8b4f"/></mesh></group>
    </>}
    {gatheringBenches(b.type).map((bench,i)=><Bench key={i} {...bench}/>)}
    <Html position={[0,3,0]} center style={{display:showLabels||selected?"block":"none"}}><button className={`map-label ${selected?"selected":""}`} data-place-kind="natural" onClick={onSelect}>{b.name}</button></Html>
  </group>;
}
