"use client";
import {useEffect,useMemo,useRef,useState} from "react";
import {useFrame} from "@react-three/fiber";
import * as THREE from "three";
import {environmentAt} from "@/lib/environment";
import type {World} from "@/lib/types";
import {prototypeLayout} from "@/lib/city-layout";
import {architecture} from "@/lib/architecture";

const seed=(index:number,salt:number)=>{
  const value=Math.sin(index*127.1+salt*311.7)*43758.5453;
  return value-Math.floor(value);
};
export function buildingHeight(building:World["buildings"][number],index:number,organic=false) {
  if(building.type==="construction")return (building.plannedType==="house"?2:building.plannedType==="office"?5:4)*2.6+5;
  if(building.type==="wilderness")return 0;
  if(organic&&(building.type==="house"||building.type==="apartment"))return architecture(building.id,building.type).height;
  return ["park","garden","plaza","promenade","river","riverside_walk","trekking_trail"].includes(building.type)?0:building.type==="mountain"?9:building.type==="hill_station"?4:building.type==="mall"?6:building.type==="hangout"?3.5:building.type==="house"?3.4:building.type==="office"?13:
    building.type==="studio"?8:building.capacity===1?5.2+(index%3)*1.8:4;
}

export default function WorldEnvironment({world,windows,lamps,pools,roads}:{world:World;
  windows:THREE.MeshStandardMaterial;lamps:THREE.MeshStandardMaterial;
  pools:THREE.MeshBasicMaterial;roads:THREE.MeshStandardMaterial}) {
  const city=world.geography??prototypeLayout,size=city.halfExtent*2,ratio=city.spacing/12;
  const lightTarget=useMemo(()=>{const target=new THREE.Object3D();target.position.set(city.centerX,0,city.centerZ);return target;},[city.centerX,city.centerZ]);
  const sun=useRef<THREE.DirectionalLight>(null),ambient=useRef<THREE.AmbientLight>(null);
  const hemisphere=useRef<THREE.HemisphereLight>(null),moon=useRef<THREE.DirectionalLight>(null);
  const clouds=useRef<THREE.InstancedMesh>(null),cloudMaterial=useRef<THREE.MeshStandardMaterial>(null);
  const stars=useRef<THREE.PointsMaterial>(null),rainMaterial=useRef<THREE.LineBasicMaterial>(null);
  const [reducedMotion,setReducedMotion]=useState(false);
  useEffect(()=>{
    const query=window.matchMedia("(prefers-reduced-motion: reduce)");
    const update=()=>setReducedMotion(query.matches);update();query.addEventListener("change",update);
    return()=>query.removeEventListener("change",update);
  },[]);
  const target=useMemo(()=>{
    const state=environmentAt(world.minute,world.weather);
    const weatherSky=new THREE.Color("#1a2637").lerp(new THREE.Color("#83969f"),state.daylight);
    const sky=new THREE.Color("#111e35").lerp(new THREE.Color("#dce9e5"),state.daylight)
      .lerp(new THREE.Color("#eab88f"),state.twilight*.55)
      .lerp(weatherSky,state.cloud*.6);
    const sunlight=new THREE.Color("#fff8df").lerp(new THREE.Color("#ffad70"),state.twilight*.85);
    return {...state,sky,sunlight,sunPosition:new THREE.Vector3(city.centerX+state.sun[0]*ratio,state.sun[1]*ratio,city.centerZ+state.sun[2]*ratio)};
  },[world.minute,world.weather,city.centerX,city.centerZ,ratio]);
  // Start at the received state, then blend subsequent server updates. The
  // browser never advances the clock or chooses independent weather.
  const current=useRef({daylight:target.daylight,cloud:target.cloud,rain:target.rain});
  const background=useMemo(()=>target.sky.clone(),[]);
  const fog=useMemo(()=>new THREE.Fog(target.sky,250,480),[]);
  const cloudPuffs=useMemo(()=>Array.from({length:48},(_,i)=>{
    const cluster=Math.floor(i/4),puff=i%4;
    return {x:city.centerX-city.halfExtent+seed(cluster,1)*size+puff*3,z:city.centerZ-city.halfExtent+seed(cluster,2)*size,
      y:25+seed(cluster,3)*12+(puff%2)*1.5,scale:3+seed(i,4)*2};
  }),[city.centerX,city.centerZ,city.halfExtent,size]);
  const dummy=useMemo(()=>new THREE.Object3D(),[]);
  const drift=useRef(0);
  const starPositions=useMemo(()=>{
    const positions=new Float32Array(150*3);
    for(let i=0;i<150;i++)positions.set([(seed(i,8)-.5)*380,65+seed(i,9)*120,(seed(i,10)-.5)*380],i*3);
    return positions;
  },[]);
  const layout=world.buildings.map(b=>`${b.id}:${b.type}:${b.capacity}:${b.x}:${b.z}`).join("|");
  const rainfall=useMemo(()=>{
    const positions=new Float32Array(600*6),floors=new Float32Array(600);
    for(let i=0;i<600;i++) {
      const x=city.centerX+(seed(i,5)-.5)*size,z=city.centerZ+(seed(i,6)-.5)*size;
      let floor=.25;
      world.buildings.forEach((b,index)=>{
        if(buildingHeight(b,index,city.version>=2)>0&&Math.abs(x-b.x)<3.6&&Math.abs(z-b.z)<3.6)
          floor=Math.max(floor,buildingHeight(b,index,city.version>=2)+(b.type==="house"?3:1));
      });
      floors[i]=floor;
      const y=floor+seed(i,7)*(43-floor);
      positions.set([x,y,z,x-.35,y+1.7,z+.15],i*6);
    }
    return {positions,floors};
  },[layout,city.centerX,city.centerZ,size]);
  const rainAttribute=useRef<THREE.BufferAttribute>(null);
  const telemetryTime=useRef(0);
  useFrame(({gl,camera},delta)=>{
    const step=Math.min(delta,.1),blend=1-Math.exp(-step*2),state=current.current;
    state.daylight=THREE.MathUtils.lerp(state.daylight,target.daylight,blend);
    state.cloud=THREE.MathUtils.lerp(state.cloud,target.cloud,blend);
    state.rain=THREE.MathUtils.lerp(state.rain,target.rain,blend);
    const night=1-state.daylight;
    background.lerp(target.sky,blend);fog.color.copy(background);
    // Overview camera is above the whole island; retain local fog at eye level.
    const overview=camera instanceof THREE.OrthographicCamera;
    fog.near=overview?camera.position.y+280:(250-state.cloud*80)*Math.sqrt(ratio);
    fog.far=overview?camera.position.y+5400:(480-state.cloud*135)*Math.sqrt(ratio);
    if(ambient.current)ambient.current.intensity=.32+state.daylight*.95;
    if(hemisphere.current)hemisphere.current.intensity=.4+state.daylight*.85;
    if(sun.current) {
      sun.current.position.lerp(target.sunPosition,blend);
      sun.current.color.lerp(target.sunlight,blend);
      sun.current.intensity=state.daylight*2.5*(1-state.cloud*.65);
    }
    if(moon.current)moon.current.intensity=night*.65*(1-state.cloud*.5);
    windows.emissiveIntensity=.08+night*1.35;
    lamps.emissiveIntensity=.15+night*2;
    pools.opacity=night*.18;
    roads.roughness=1-state.rain*.55;
    if(stars.current)stars.current.opacity=night*(1-state.cloud)*.85;
    if(cloudMaterial.current)cloudMaterial.current.opacity=state.cloud*.78;
    if(rainMaterial.current)rainMaterial.current.opacity=state.rain*(.22+state.daylight*.2);
    if(clouds.current) {
      if(!reducedMotion)drift.current+=step*.8;
      cloudPuffs.forEach((puff,i)=>{
        dummy.position.set(city.centerX+((puff.x-city.centerX+drift.current+city.halfExtent)%size+size)%size-city.halfExtent,puff.y,puff.z);
        dummy.scale.set(puff.scale*1.9,puff.scale*.6,puff.scale);dummy.updateMatrix();
        clouds.current!.setMatrixAt(i,dummy.matrix);
      });
      clouds.current.instanceMatrix.needsUpdate=true;
    }
    if(rainAttribute.current&&state.rain>.005&&!reducedMotion) {
      const positions=rainfall.positions;
      for(let i=0;i<600;i++) {
        const offset=i*6,floor=rainfall.floors[i];
        positions[offset+1]-=step*25;
        if(positions[offset+1]<floor)positions[offset+1]=43;
        positions[offset+4]=positions[offset+1]+1.7;
      }
      rainAttribute.current.needsUpdate=true;
    }
    // Track rendered conditions for overlay contrast and browser regression checks.
    telemetryTime.current+=step;
    if(telemetryTime.current>=.1) {
      telemetryTime.current=0;
      gl.domElement.dataset.phase=target.phase;
      gl.domElement.dataset.weather=world.weather;
      gl.domElement.dataset.daylight=state.daylight.toFixed(3);
      gl.domElement.dataset.rain=state.rain.toFixed(3);
      gl.domElement.dataset.cloud=state.cloud.toFixed(3);
      gl.domElement.dataset.windowGlow=windows.emissiveIntensity.toFixed(3);
      const brightness=background.r*.2126+background.g*.7152+background.b*.0722;
      gl.domElement.closest(".world-stage")?.setAttribute("data-tone",brightness<.3?"dark":"light");
    }
  });
  return <>
    <primitive object={background} attach="background"/><primitive object={fog} attach="fog"/>
    <ambientLight ref={ambient}/><hemisphereLight ref={hemisphere} args={["#e8efff","#7d8c77",1]}/>
    <primitive object={lightTarget}/>
    <directionalLight ref={sun} position={target.sunPosition} target={lightTarget} color={target.sunlight} castShadow
      shadow-mapSize={[2048,2048]} shadow-camera-left={-city.halfExtent-25} shadow-camera-right={city.halfExtent+25}
      shadow-camera-top={city.halfExtent+25} shadow-camera-bottom={-city.halfExtent-25} shadow-camera-far={300*ratio} shadow-bias={-.001}/>
    <directionalLight ref={moon} position={[70,100,-60]} color="#9ab9f4"/>
    <points raycast={()=>null}>
      <bufferGeometry><bufferAttribute attach="attributes-position" args={[starPositions,3]}/></bufferGeometry>
      <pointsMaterial ref={stars} color="#e6efff" size={1.2} sizeAttenuation={false} transparent depthWrite={false}/>
    </points>
    <instancedMesh ref={clouds} args={[undefined,undefined,cloudPuffs.length]} raycast={()=>null} frustumCulled={false}>
      <sphereGeometry args={[1,8,6]}/>
      <meshStandardMaterial ref={cloudMaterial} color="#d9e2e3" roughness={1} transparent depthWrite={false}/>
    </instancedMesh>
    <lineSegments raycast={()=>null} frustumCulled={false}>
      <bufferGeometry><bufferAttribute ref={rainAttribute} attach="attributes-position" args={[rainfall.positions,3]} usage={THREE.DynamicDrawUsage}/></bufferGeometry>
      <lineBasicMaterial ref={rainMaterial} color="#c4d9ec" transparent depthWrite={false}/>
    </lineSegments>
  </>;
}
