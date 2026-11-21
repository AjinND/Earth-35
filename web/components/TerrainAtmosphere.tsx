"use client";
import {useEffect,useMemo,useRef} from "react";
import {useFrame} from "@react-three/fiber";
import * as THREE from "three";
import type {World} from "@/lib/types";
import {ATMOSPHERE_LIMITS as LIMIT,atmosphereSeed as seed,mistPatches,mistStrength,createPrecipitationSampler} from "@/lib/atmosphere";
import {environmentAt} from "@/lib/environment";
import {rainDrop,rainVelocity,mistDrift} from "@/lib/precipitation-physics";
import {walkElevation} from "@/lib/island";
import {useAtmosphericMotion} from "./AtmosphericMotion";

const mistVertex=`attribute float density;varying vec2 vUv;varying float vDensity;
void main(){vUv=uv;vDensity=density;gl_Position=projectionMatrix*modelViewMatrix*instanceMatrix*vec4(position,1.0);}`;
const mistFragment=`varying vec2 vUv;varying float vDensity;uniform vec3 tint;
void main(){float r=length((vUv-.5)*2.0);float alpha=exp(-r*r*3.0)*(1.0-smoothstep(.4,1.0,r))*vDensity;
if(alpha<.002)discard;gl_FragColor=vec4(tint,alpha);
#include <tonemapping_fragment>
#include <colorspace_fragment>
}`;
// Map rain is a screen-sized weather layer: physical metre-long drops disappear
// at island zoom. It uses the same weather and bounded clock as local rain.
const mapRainVertex=`void main(){gl_Position=vec4(position.xy,0.0,1.0);}`;
const mapRainFragment=`uniform vec3 tint;uniform float opacity;
void main(){gl_FragColor=vec4(tint,opacity);
#include <tonemapping_fragment>
#include <colorspace_fragment>
}`;

export default function TerrainAtmosphere({world,reducedMotion}:{world:World;reducedMotion:boolean}) {
  const motion=useAtmosphericMotion();
  const floorAt=useMemo(()=>createPrecipitationSampler(world),[world.buildings,world.geography]);
  const mist=useRef<THREE.InstancedMesh>(null),rain=useRef<THREE.LineSegments>(null),mapRain=useRef<THREE.LineSegments>(null),rainMaterial=useRef<THREE.LineBasicMaterial>(null);
  const patches=useMemo(mistPatches,[]),dummy=useMemo(()=>new THREE.Object3D(),[]);
  const data=useMemo(()=>{
    const rain=new THREE.BufferGeometry(),positions=new Float32Array(LIMIT.rainDesktop*6);
    rain.setAttribute("position",new THREE.BufferAttribute(positions,3).setUsage(THREE.DynamicDrawUsage));
    const mapRain=new THREE.BufferGeometry(),mapPositions=new Float32Array(LIMIT.rainDesktop*6);
    mapRain.setAttribute("position",new THREE.BufferAttribute(mapPositions,3).setUsage(THREE.DynamicDrawUsage));
    const mist=new THREE.PlaneGeometry(1,1);mist.setAttribute("density",new THREE.InstancedBufferAttribute(new Float32Array(patches.length),1));
    return {rain,mapRain,mist,positions,mapPositions,floors:new Float32Array(LIMIT.rainDesktop),cell:""};
  },[patches]);
  const uniforms=useMemo(()=>({tint:{value:new THREE.Color()}}),[]),dayMist=useMemo(()=>new THREE.Color("#bdcfcb"),[]),report=useRef(0);
  const mapRainUniforms=useMemo(()=>({tint:{value:new THREE.Color("#c4d9ec")},opacity:{value:.5}}),[]);
  const spatial=world.buildings.map(b=>`${b.id}:${b.type}:${b.district}:${b.x}:${b.z}`).join("|");
  useEffect(()=>{data.cell="";},[data,spatial,world.geography?.version]);
  useEffect(()=>()=>{data.rain.dispose();data.mapRain.dispose();data.mist.dispose();},[data]);
  useFrame(({camera,size,gl},delta)=>{
    const overview=camera instanceof THREE.OrthographicCamera,count=size.width<640?LIMIT.rainMobile:LIMIT.rainDesktop;
    const state=environmentAt(world.minute,world.weather),raining=state.rain>0,active=!overview&&raining,mapActive=overview&&raining;
    const {seconds:time,wind,advection}=motion.current;
    const velocity=rainVelocity(wind);
    if(rain.current)rain.current.visible=active;
    if(mapRain.current)mapRain.current.visible=mapActive;
    if(mapActive) {
      const length=size.width<640?8:10;
      for(let i=0;i<count;i++) {
        const phase=((seed(i,7)-time*.65)%1+1)%1;
        const drift=reducedMotion?0:(advection.x*camera.matrixWorld.elements[0]+advection.z*camera.matrixWorld.elements[2])*.025;
        const x=((seed(i,5)*2+drift)%2+2)%2-1,y=phase*2-1;
        const tilt=reducedMotion?-6:THREE.MathUtils.clamp(-(wind.x*camera.matrixWorld.elements[0]+wind.z*camera.matrixWorld.elements[2]),-10,10);
        data.mapPositions[i*6]=x;data.mapPositions[i*6+1]=y;
        data.mapPositions[i*6+3]=x+tilt/size.width;data.mapPositions[i*6+4]=y+length*2/size.height;
      }
      data.mapRain.setDrawRange(0,count*2);data.mapRain.getAttribute("position").needsUpdate=true;
      mapRainUniforms.opacity.value=.45+state.daylight*.15;
    }
    if(active) {
      const cx=Math.round(camera.position.x/LIMIT.rainCell)*LIMIT.rainCell,cz=Math.round(camera.position.z/LIMIT.rainCell)*LIMIT.rainCell;
      const cell=`${cx}:${cz}:${count}`;
      data.cell=cell;
      for(let i=0;i<count;i++) {
        const drop=rainDrop(i,time,{x:cx,z:cz},Math.max(-1,camera.position.y-20),camera.position.y+30,wind);
        const floor=Math.max(floorAt(drop.x,drop.z),floorAt(drop.tail.x,drop.tail.z));
        // Clip each falling streak against the surface at its advected position,
        // so a gust cannot blow rain through a roof or hillside.
        const t=drop.tail.y<=floor?1:drop.y<floor?(floor-drop.y)/(drop.tail.y-drop.y):0;
        data.positions[i*6]=drop.x+(drop.tail.x-drop.x)*t;
        data.positions[i*6+1]=Math.max(floor,drop.y+(drop.tail.y-drop.y)*t);
        data.positions[i*6+2]=drop.z+(drop.tail.z-drop.z)*t;
        data.positions[i*6+3]=drop.tail.x;data.positions[i*6+4]=Math.max(floor,drop.tail.y);data.positions[i*6+5]=drop.tail.z;
      }
      data.rain.setDrawRange(0,count*2);data.rain.getAttribute("position").needsUpdate=true;
      if(rainMaterial.current)rainMaterial.current.opacity=.23+state.daylight*.19;
    }
    uniforms.tint.value.set("#445674").lerp(dayMist,state.daylight);
    let visibleMist=0;
    if(mist.current) {
      const density=data.mist.getAttribute("density") as THREE.InstancedBufferAttribute;
      for(const [i,patch] of patches.entries()) {
        const drift=reducedMotion?{x:0,y:0,z:0,opacity:1}:mistDrift(i,time,wind,patch.kind);
        const x=patch.x+drift.x,z=patch.z+drift.z,y=Math.max(patch.y+drift.y,walkElevation(x,z)+.8);
        const distance=Math.hypot(camera.position.x-x,camera.position.z-z);
        const visible=overview||distance<520;
        const fade=overview?.7:THREE.MathUtils.smoothstep(distance,5,24)*(1-THREE.MathUtils.smoothstep(distance,360,520));
        density.setX(i,visible?mistStrength(patch.kind,world.minute,world.weather)*fade*drift.opacity:0);
        if(visible)visibleMist++;
        dummy.position.set(x,y,z);dummy.quaternion.copy(camera.quaternion);dummy.scale.set(patch.width,patch.height,1);dummy.updateMatrix();mist.current.setMatrixAt(i,dummy.matrix);
      }
      density.needsUpdate=true;mist.current.instanceMatrix.needsUpdate=true;
    }
    report.current+=delta;if(report.current>.25) {
      report.current=0;Object.assign(gl.domElement.dataset,{atmosphereMotion:reducedMotion?"reduced":"animated",rainCount:String(raining?count:0),rainView:raining?(overview?"map":"first-person"):"none",rainCell:mapActive?"map":data.cell,
        rainPose:mapActive?data.mapPositions[1].toFixed(4):active?data.positions[1].toFixed(4):"",rainVelocity:[velocity.x,velocity.y,velocity.z].map(v=>v.toFixed(4)).join(","),mistCount:String(patches.length),mistVisible:String(visibleMist),mistStrength:mistStrength("lake",world.minute,world.weather).toFixed(3)});
    }
  });
  return <group name="terrain-aware-atmosphere">
    <instancedMesh ref={mist} name="mountain-lake-river-mist" args={[data.mist,undefined,patches.length]} frustumCulled={false} raycast={()=>null}>
      <shaderMaterial uniforms={uniforms} vertexShader={mistVertex} fragmentShader={mistFragment} transparent depthWrite={false}/>
    </instancedMesh>
    <lineSegments ref={rain} name="local-terrain-rain" geometry={data.rain} frustumCulled={false} raycast={()=>null}>
      <lineBasicMaterial ref={rainMaterial} color="#c4d9ec" transparent depthWrite={false}/>
    </lineSegments>
    <lineSegments ref={mapRain} name="map-rain" geometry={data.mapRain} visible={false} frustumCulled={false} renderOrder={100} raycast={()=>null}>
      <shaderMaterial uniforms={mapRainUniforms} vertexShader={mapRainVertex} fragmentShader={mapRainFragment} transparent depthTest={false} depthWrite={false}/>
    </lineSegments>
  </group>;
}
