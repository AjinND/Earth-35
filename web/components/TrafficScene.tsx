"use client";
import {useEffect,useLayoutEffect,useRef,useState} from "react";
import {useFrame,useThree} from "@react-three/fiber";
import {Html} from "@react-three/drei";
import * as THREE from "three";
import type {Building,BusStop,Transport,Vehicle} from "@/lib/types";
import {TrafficPlayback,type TrafficPose} from "@/lib/traffic-playback";
import {prototypeLayout,clearsRoad,type CityLayout} from "@/lib/city-layout";
import {busPlatformPlacements,signalPlacement} from "@/lib/street-furniture";
import type {PlaybackTimeline} from "@/lib/playback-timeline";

function RoadVehicle({vehicle:v,poses}:{vehicle:Vehicle;poses:{current:Map<string,TrafficPose>}}) {
  const group=useRef<THREE.Group>(null),door=useRef<THREE.Mesh>(null),step=useRef<THREE.Mesh>(null);
  useFrame(()=>{if(!group.current)return;const p=poses.current.get(v.id);group.current.visible=!!p;if(p){group.current.position.set(p.x,0,p.z);group.current.rotation.y=p.heading;if(door.current)door.current.position.z=p.stop?.43:0;if(step.current)step.current.visible=!!p.stop;}},-1);
  const bus=v.type==="bus",length=bus?5:2.8;
  return <group ref={group}>
    <mesh castShadow position={[0,bus?.9:.55,0]}><boxGeometry args={[1.35,bus?1.5:.75,length]}/><meshStandardMaterial color={bus?(v.route==="R1"?"#d6a749":"#54858d"):['#e0d3bd','#a55848','#516f89','#6b8271'][Number(v.id.split('-')[1])%4]}/></mesh>
    <mesh castShadow position={[0,bus?1.2:1,0]}><boxGeometry args={[1.24,bus?.6:.55,bus?length-.6:1.5]}/><meshStandardMaterial color="#344f5b"/></mesh>
    {bus&&<group name={`bus-door-${v.id}`} position={[.69,.85,1.35]}>
      <mesh ref={door}><boxGeometry args={[.035,1.2,.7]}/><meshStandardMaterial color="#253e44"/></mesh>
      <mesh ref={step} position={[.16,-.58,0]}><boxGeometry args={[.32,.12,.65]}/><meshStandardMaterial color="#bec3b4"/></mesh>
    </group>}
    {[-.69,.69].flatMap(x=>[-length*.32,length*.32].map(z=><mesh key={`${x}:${z}`} position={[x,.35,z]} rotation={[0,0,Math.PI/2]}><cylinderGeometry args={[.3,.3,.14,8]}/><meshStandardMaterial color="#303637"/></mesh>))}
    {[-.45,.45].map(x=><mesh key={x} position={[x,.65,length/2+.02]}><boxGeometry args={[.3,.18,.05]}/><meshStandardMaterial color="#ffe8af" emissive="#ffe8af" emissiveIntensity={.5}/></mesh>)}
  </group>;
}
export function BusPlatform({building:b,stop,layout,selected,onSelect,showLabels}:{building:Building;stop?:BusStop;layout:CityLayout;selected:boolean;onSelect:()=>void;showLabels:boolean}) {
  const platforms=busPlatformPlacements(b,stop,layout);
  return <group onClick={e=>{e.stopPropagation();onSelect();}}>
    {platforms.map(p=><group key={p.route} name={`bus-platform-${b.id}-${p.route}`} position={[p.x,0,p.z]} rotation={[0,p.heading,0]}>
      <mesh position={[0,.13,0]} receiveShadow><boxGeometry args={[.8,.2,4.5]}/><meshStandardMaterial color={selected?"#abd2bd":"#b4baa9"}/></mesh>
      <mesh position={[.25,1.2,-1.8]}><boxGeometry args={[.12,2.3,.12]}/><meshStandardMaterial color="#546968"/></mesh>
      <mesh position={[.25,2.4,-1.8]}><boxGeometry args={[.45,.65,.15]}/><meshStandardMaterial color={p.route==="R1"?"#a98237":"#477f80"}/></mesh>
      <mesh position={[.27,.75,.5]}><boxGeometry args={[.2,.15,1.4]}/><meshStandardMaterial color="#7b8a79"/></mesh>
    </group>)}
    <Html center position={[platforms[0].x,3.4,platforms[0].z]} style={{display:showLabels||selected?"block":"none"}}><button onClick={onSelect} className={`map-label ${selected?"selected":""}`}>{b.name}</button></Html>
  </group>;
}
function Signals({signals,layout}:{signals:Transport["signals"];layout:CityLayout}) {
  const poles=useRef<THREE.InstancedMesh>(null),cases=useRef<THREE.InstancedMesh>(null),lights=useRef<THREE.InstancedMesh>(null),walk=useRef<THREE.InstancedMesh>(null),stripes=useRef<THREE.InstancedMesh>(null);
  useEffect(()=>{
    if(!poles.current||!cases.current||!lights.current||!walk.current||!stripes.current)return;
    const transform=new THREE.Object3D(),color=new THREE.Color();
    const put=(mesh:THREE.InstancedMesh,index:number,x:number,y:number,z:number,heading=0)=>{transform.position.set(x,y,z);transform.rotation.set(0,heading,0);transform.updateMatrix();mesh.setMatrixAt(index,transform.matrix);};
    signals.forEach((signal,index)=>{
      const {pole,heading,crossing,crossingHeading}=signalPlacement(signal,layout),faceX=pole.x+Math.sin(heading)*.16,faceZ=pole.z+Math.cos(heading)*.16;
      put(poles.current!,index,pole.x,1.6,pole.z);put(cases.current!,index,pole.x,3.1,pole.z,heading);
      ["east_west","north_south"].forEach((axis,i)=>{put(lights.current!,index*2+i,faceX,3.35-i*.4,faceZ);lights.current!.setColorAt(index*2+i,color.set(signal.vehicleGreen===axis?"#66df8e":"#c86855"));});
      put(walk.current!,index,faceX,2.5,faceZ,heading);walk.current!.setColorAt(index,color.set(signal.pedestrianGreen?"#82eda7":"#d1755b"));
      for(let i=0;i<4;i++){const offset=-1.2+i*.8;put(stripes.current!,index*4+i,crossing.x+Math.cos(crossingHeading)*offset,.12,crossing.z-Math.sin(crossingHeading)*offset,crossingHeading);}
    });
    for(const mesh of [poles.current,cases.current,lights.current,walk.current,stripes.current]){mesh.instanceMatrix.needsUpdate=true;if(mesh.instanceColor)mesh.instanceColor.needsUpdate=true;mesh.computeBoundingSphere();}
  },[signals,layout]);
  return <>
    <instancedMesh ref={poles} args={[undefined,undefined,signals.length]}><cylinderGeometry args={[.07,.1,3.2,5]}/><meshStandardMaterial color="#465b54"/></instancedMesh>
    <instancedMesh ref={cases} args={[undefined,undefined,signals.length]}><boxGeometry args={[.3,1.1,.3]}/><meshStandardMaterial color="#34423e"/></instancedMesh>
    <instancedMesh ref={lights} args={[undefined,undefined,signals.length*2]}><sphereGeometry args={[.13,6,6]}/><meshBasicMaterial vertexColors/></instancedMesh>
    <instancedMesh ref={walk} args={[undefined,undefined,signals.length]}><boxGeometry args={[.26,.25,.04]}/><meshBasicMaterial vertexColors/></instancedMesh>
    <instancedMesh ref={stripes} args={[undefined,undefined,signals.length*4]} receiveShadow><boxGeometry args={[.35,.03,.75]}/><meshStandardMaterial color="#dddcc8"/></instancedMesh>
  </>;
}
export default function TrafficScene({transport,tickMs,minute,layout=prototypeLayout,timeline}:{transport?:Transport;tickMs:number;minute:number;layout?:CityLayout;timeline?:PlaybackTimeline}) {
  const {gl}=useThree();
  const playback=useRef(new TrafficPlayback(timeline)),poses=useRef(new Map<string,TrafficPose>());
  const [vehicles,setVehicles]=useState<Vehicle[]>([]);
  useLayoutEffect(()=>{
    if(!transport)return;
    playback.current.ingest(minute,transport.vehicles,tickMs,performance.now());
    poses.current=playback.current.sample(performance.now());
    setVehicles(playback.current.vehicles());
  },[minute,tickMs,transport]);
  useFrame(()=>{poses.current=playback.current.sample(performance.now(),matchMedia("(prefers-reduced-motion: reduce)").matches);},-2);
  useEffect(()=>{
    gl.domElement.dataset.vehicles=String(transport?.vehicles.length??0);
    gl.domElement.dataset.buses=String(transport?.vehicles.filter(v=>v.type==="bus").length??0);
    gl.domElement.dataset.signals=String(transport?.signals.length??0);
    gl.domElement.dataset.pedestrianSignal=transport?.signals[0]?.pedestrianGreen?"walk":"wait";
    gl.domElement.dataset.signalPolesOffRoad=String(transport?.signals.every(s=>{const p=signalPlacement(s,layout).pole;return clearsRoad(p.x,p.z,.3,layout);})??true);
  },[gl,transport,layout]);
  if(!transport)return null;
  return <>
    {vehicles.map(v=><RoadVehicle key={v.id} vehicle={v} poses={poses}/>)}
    <Signals signals={transport.signals} layout={layout}/>
  </>;
}
