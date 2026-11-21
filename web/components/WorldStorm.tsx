"use client";
import {useEffect,useLayoutEffect,useMemo,useRef} from "react";
import {useFrame,useThree} from "@react-three/fiber";
import * as THREE from "three";
import type {World} from "@/lib/types";
import {islandElevation} from "@/lib/island";
import {StormPlayback,lightningSegments,publishStormFrame,clearStormFrame} from "@/lib/storm";

export default function WorldStorm({world,reducedMotion}:{world:World;reducedMotion:boolean}) {
  const {gl}=useThree(),playback=useRef(new StormPlayback()),lastStrike=useRef<number|null>(null);
  const group=useRef<THREE.Group>(null),core=useRef<THREE.LineBasicMaterial>(null),glow=useRef<THREE.LineBasicMaterial>(null),light=useRef<THREE.HemisphereLight>(null);
  const geometry=useMemo(()=>{
    const value=new THREE.BufferGeometry();
    value.setAttribute("position",new THREE.BufferAttribute(new Float32Array(108),3).setUsage(THREE.DynamicDrawUsage));
    return value;
  },[]);
  useLayoutEffect(()=>{playback.current.ingest(world.minute,world.tickMs??5000,world.weather,performance.now());},[world.minute,world.tickMs,world.weather]);
  useEffect(()=>{
    const visibility=()=>publishStormFrame(gl.domElement,playback.current.sample(performance.now(),true));
    document.addEventListener("visibilitychange",visibility);
    return()=>{document.removeEventListener("visibilitychange",visibility);clearStormFrame(gl.domElement);geometry.dispose();};
  },[geometry,gl]);
  useFrame(()=>{
    const frame=playback.current.sample(performance.now(),document.hidden);
    const flash=reducedMotion?0:frame.flash;
    publishStormFrame(gl.domElement,{...frame,flash});
    if(frame.strike&&lastStrike.current!==frame.strike.id) {
      lastStrike.current=frame.strike.id;
      const positions=geometry.getAttribute("position") as THREE.BufferAttribute;
      (positions.array as Float32Array).set(lightningSegments(frame.strike));positions.needsUpdate=true;
      group.current?.position.set(frame.strike.x,Math.max(0,islandElevation(frame.strike.x,frame.strike.z)),frame.strike.z);
    }
    if(group.current)group.current.visible=flash>.005;
    if(core.current)core.current.opacity=flash*.95;
    if(glow.current)glow.current.opacity=flash*.25;
    if(light.current)light.current.intensity=flash*.65;
    Object.assign(gl.domElement.dataset,{stormWeather:world.weather,lightningId:frame.strike?String(frame.strike.id):"",lightningAge:frame.age.toFixed(3),lightningFlash:flash.toFixed(3),lightningMotion:reducedMotion?"reduced":"animated",lightningBoltVisible:String(flash>.005)});
  },-.5);
  return <>
    <hemisphereLight ref={light} color="#c6ddff" groundColor="#aac4ed" intensity={0}/>
    <group ref={group} name="distant-storm-lightning" visible={false}>
      <lineSegments geometry={geometry} frustumCulled={false} raycast={()=>null} renderOrder={20}>
        <lineBasicMaterial ref={glow} color="#8bbdff" transparent opacity={0} depthWrite={false} toneMapped={false}/>
      </lineSegments>
      <lineSegments geometry={geometry} frustumCulled={false} raycast={()=>null} renderOrder={21}>
        <lineBasicMaterial ref={core} color="#edf5ff" transparent opacity={0} depthWrite={false} toneMapped={false}/>
      </lineSegments>
    </group>
  </>;
}
