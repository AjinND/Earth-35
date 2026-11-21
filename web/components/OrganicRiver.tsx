"use client";
import WeatherMaterial from "./WeatherMaterial";
import {useEffect,useMemo,useRef} from "react";
import {useFrame} from "@react-three/fiber";
import * as THREE from "three";
import {createRiverSurfaces,createRiverWalkGeometry,riverSample,RIVER_COURSE as course} from "@/lib/river-geometry";
import {sharedResourceCache} from "@/lib/shared-resource";
import {useAtmosphericMotion} from "./AtmosphericMotion";
import WindWaterMaterial from "./WindWaterMaterial";
const createGeometry=()=>({...createRiverSurfaces(),walk:createRiverWalkGeometry()});
const riverGeometry=sharedResourceCache<string,ReturnType<typeof createGeometry>>(geometry=>Object.values(geometry).forEach(g=>g.dispose()));
export default function OrganicRiver() {
  const geometry=useMemo(()=>riverGeometry.get("river",createGeometry),[]);
  useEffect(()=>riverGeometry.retain("river"),[geometry]);
  const current=useRef<THREE.InstancedMesh>(null),motion=useAtmosphericMotion(),report=useRef(0);
  const dummy=useMemo(()=>new THREE.Object3D(),[]);
  useFrame(({gl},delta)=>{
    if(!current.current)return;const elapsed=motion.current.seconds*.9;
    for(let i=0;i<64;i++) {
      const position=((i*(course.length-2)/64+elapsed)%(course.length-2)+(course.length-2))%(course.length-2),index=Math.floor(position),t=position-index,a=course[index],b=course[index+1];
      const p=riverSample(index,t),ahead=riverSample(index,Math.min(1,t+.025));
      dummy.position.set(p.x,p.y+.025,p.z);dummy.rotation.set(-Math.atan2(ahead.y-p.y,Math.hypot(ahead.x-p.x,ahead.z-p.z)),Math.atan2(b.x-a.x,b.z-a.z),0,"YXZ");
      dummy.scale.set(.65+(i%3)*.15,1,1);dummy.updateMatrix();current.current.setMatrixAt(i,dummy.matrix);
    }
    current.current.instanceMatrix.needsUpdate=true;current.current.computeBoundingSphere();report.current+=delta;
    if(report.current>.25){report.current=0;gl.domElement.dataset.riverFlow=elapsed.toFixed(3);gl.domElement.dataset.riverCourse="mountain-to-sea";}
  });
  return <group name="mountain-to-sea-silverstream">
    <mesh name="silverstream-rock-bed" geometry={geometry.bed} receiveShadow><WeatherMaterial surface="ground" vertexColors side={THREE.DoubleSide} flatShading roughness={1}/></mesh>
    <mesh name="silverstream-water" geometry={geometry.water} renderOrder={3}><WindWaterMaterial vertexColors transparent depthWrite={false} metalness={.12} roughness={.28} side={THREE.DoubleSide}/></mesh>
    <mesh geometry={geometry.walk} receiveShadow><WeatherMaterial surface="ground" color="#cbb994" roughness={1} side={THREE.DoubleSide}/></mesh>
    <instancedMesh ref={current} args={[undefined,undefined,64]} raycast={()=>null}><boxGeometry args={[.06,.015,1.1]}/><WeatherMaterial surface="ground" color="#b7deda"/></instancedMesh>
  </group>;
}
