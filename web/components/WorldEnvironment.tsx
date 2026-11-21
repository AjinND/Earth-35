"use client";
import {useEffect,useMemo,useRef,useState} from "react";
import {useFrame} from "@react-three/fiber";
import * as THREE from "three";
import {skyAt} from "@/lib/sky";
import type {World} from "@/lib/types";
import {prototypeLayout} from "@/lib/city-layout";
import {architecture} from "@/lib/architecture";
import CelestialSky from "./CelestialSky";
import TerrainAtmosphere from "./TerrainAtmosphere";
import WorldStorm from "./WorldStorm";
export function buildingHeight(building:World["buildings"][number],index:number,organic=false) {
  if(building.type==="construction")return (building.plannedType==="house"?2:building.plannedType==="office"?5:4)*2.6+5;
  if(building.type==="wilderness")return 0;
  if(organic&&["house","apartment","office"].includes(building.type))return architecture(building.id,building.type,building.district).height+1.7;
  return ["park","garden","plaza","promenade","river","riverside_walk","trekking_trail"].includes(building.type)?0:building.type==="mountain"?9:building.type==="hill_station"?4:building.type==="mall"?6:building.type==="hangout"?3.5:building.type==="house"?3.4:building.type==="office"?13:
    building.type==="studio"?8:building.capacity===1?5.2+(index%3)*1.8:4;
}

export default function WorldEnvironment({world,windows,lamps,pools,roads}:{world:World;
  windows:THREE.MeshStandardMaterial;lamps:THREE.MeshStandardMaterial;
  pools:THREE.MeshBasicMaterial;roads:THREE.MeshStandardMaterial}) {
  const city=world.geography??prototypeLayout,ratio=city.spacing/12;
  const lightTarget=useMemo(()=>{const target=new THREE.Object3D();target.position.set(city.centerX,0,city.centerZ);return target;},[city.centerX,city.centerZ]);
  const sun=useRef<THREE.DirectionalLight>(null),ambient=useRef<THREE.AmbientLight>(null);
  const hemisphere=useRef<THREE.HemisphereLight>(null),moonA=useRef<THREE.DirectionalLight>(null),moonB=useRef<THREE.DirectionalLight>(null);
  const [reducedMotion,setReducedMotion]=useState(true);
  useEffect(()=>{
    const query=window.matchMedia("(prefers-reduced-motion: reduce)");
    const update=()=>setReducedMotion(query.matches);update();query.addEventListener("change",update);
    return()=>query.removeEventListener("change",update);
  },[]);
  const target=useMemo(()=>{
    const state=skyAt(world.minute,world.weather);
    const weatherSky=new THREE.Color("#1a2637").lerp(new THREE.Color("#83969f"),state.daylight);
    const sky=new THREE.Color("#111e35").lerp(new THREE.Color("#dce9e5"),state.daylight)
      .lerp(new THREE.Color("#eab88f"),state.twilight*.55).lerp(weatherSky,state.cloud*.6);
    const sunlight=new THREE.Color("#fff8df").lerp(new THREE.Color("#ffad70"),state.twilight*.85);
    const position=(direction:readonly number[])=>new THREE.Vector3(city.centerX+direction[0]*110*ratio,direction[1]*110*ratio,city.centerZ+direction[2]*110*ratio);
    return {...state,sky,sunlight,sunPosition:position(state.sunDirection),moonPositions:state.moons.map(m=>position(m.direction))};
  },[world.minute,world.weather,city.centerX,city.centerZ,ratio]);
  const current=useRef({daylight:target.daylight,cloud:target.cloud,rain:target.rain});
  const background=useMemo(()=>target.sky.clone(),[]),fog=useMemo(()=>new THREE.Fog(target.sky,250,480),[]);
  const dryRoad=useMemo(()=>new THREE.Color("#596963"),[]),wetRoad=useMemo(()=>new THREE.Color("#425453"),[]);
  const telemetryTime=useRef(0);
  useFrame(({gl,camera},delta)=>{
    const blend=1-Math.exp(-Math.min(delta,.1)*2),state=current.current;
    state.daylight=THREE.MathUtils.lerp(state.daylight,target.daylight,blend);
    state.cloud=THREE.MathUtils.lerp(state.cloud,target.cloud,blend);
    state.rain=THREE.MathUtils.lerp(state.rain,target.rain,blend);
    const night=1-state.daylight;
    background.lerp(target.sky,blend);fog.color.copy(background);
    const overview=camera instanceof THREE.OrthographicCamera;
    fog.near=overview?camera.position.y+280:(250-state.cloud*80)*Math.sqrt(ratio);
    fog.far=overview?camera.position.y+5400:(480-state.cloud*135)*Math.sqrt(ratio);
    if(ambient.current)ambient.current.intensity=.32+state.daylight*.95;
    if(hemisphere.current)hemisphere.current.intensity=.4+state.daylight*.85;
    if(sun.current) {
      // Position uses precisely the same received-time direction as the disc.
      sun.current.position.copy(target.sunPosition);sun.current.color.lerp(target.sunlight,blend);
      sun.current.intensity=state.daylight*2.5*(1-state.cloud*.65);
    }
    [moonA.current,moonB.current].forEach((light,i)=>{if(light){light.position.copy(target.moonPositions[i]);light.intensity=night*(i===0?.45:.2)*target.moons[i].illuminated*(target.moons[i].aboveHorizon?1:0)*(1-state.cloud*.65);}});
    windows.emissiveIntensity=.08+night*1.35;lamps.emissiveIntensity=.15+night*2;pools.opacity=night*.18;
    roads.roughness=1-state.rain*.55;roads.color.copy(dryRoad).lerp(wetRoad,state.rain);
    telemetryTime.current+=delta;
    if(telemetryTime.current>=.1) {
      telemetryTime.current=0;Object.assign(gl.domElement.dataset,{phase:target.phase,weather:world.weather,daylight:state.daylight.toFixed(3),rain:state.rain.toFixed(3),cloud:state.cloud.toFixed(3),windowGlow:windows.emissiveIntensity.toFixed(3),roadRoughness:roads.roughness.toFixed(3),fogFar:fog.far.toFixed(1)});
      const brightness=background.r*.2126+background.g*.7152+background.b*.0722;
      gl.domElement.closest(".world-stage")?.setAttribute("data-tone",brightness<.3?"dark":"light");
    }
  });
  return <>
    <primitive object={background} attach="background"/><primitive object={fog} attach="fog"/>
    <CelestialSky minute={world.minute} weather={world.weather} reducedMotion={reducedMotion}/>
    <WorldStorm world={world} reducedMotion={reducedMotion}/>
    {(city.version>=4)&&<TerrainAtmosphere world={world} reducedMotion={reducedMotion}/>}
    <ambientLight ref={ambient}/><hemisphereLight ref={hemisphere} args={["#e8efff","#7d8c77",1]}/>
    <primitive object={lightTarget}/>
    <directionalLight ref={sun} position={target.sunPosition} target={lightTarget} color={target.sunlight} castShadow
      shadow-mapSize={[2048,2048]} shadow-camera-left={-city.halfExtent-25} shadow-camera-right={city.halfExtent+25}
      shadow-camera-top={city.halfExtent+25} shadow-camera-bottom={-city.halfExtent-25} shadow-camera-far={300*ratio} shadow-bias={-.001}/>
    <directionalLight ref={moonA} target={lightTarget} color="#9ab9f4"/><directionalLight ref={moonB} target={lightTarget} color="#d3af93"/>
  </>;
}
