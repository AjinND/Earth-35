"use client";
import {useContext,useEffect,useMemo,useRef} from "react";
import {useFrame} from "@react-three/fiber";
import type {BufferGeometry} from "three";
import {createCoastalSurfGeometry,marineShader,marineUniforms,updateMarineUniforms} from "@/lib/coastal-water";
import {sharedResourceCache} from "@/lib/shared-resource";
import WeatherMaterial,{SurfaceWeather} from "./WeatherMaterial";
import {useAtmosphericMotion} from "./AtmosphericMotion";

const surfGeometry=sharedResourceCache<string,BufferGeometry>(geometry=>geometry.dispose());
const marineKey=()=>"marine-ripples-v1",surfKey=()=>"coastal-surf-v1";
const noRaycast=()=>null;

export default function CoastalWater({ocean,sea,shelf}:{ocean:BufferGeometry;sea:BufferGeometry;shelf:BufferGeometry}) {
  const weather=useContext(SurfaceWeather),motion=useAtmosphericMotion(),report=useRef(0);
  const geometry=useMemo(()=>surfGeometry.get("coast",createCoastalSurfGeometry),[]);
  const uniforms=useMemo(marineUniforms,[]);
  const waterShader=useMemo(()=>marineShader(uniforms),[uniforms]),surfShader=useMemo(()=>marineShader(uniforms,true),[uniforms]);
  useEffect(()=>surfGeometry.retain("coast"),[geometry]);
  useFrame(({gl},delta)=>{
    const dt=Math.min(delta,.1);
    updateMarineUniforms(uniforms,motion.current);
    uniforms.uSurfRain.value+=((weather==="Rain"?1:0)-uniforms.uSurfRain.value)*(1-Math.exp(-dt*2));
    report.current+=delta;
    if(report.current>=.2) {
      report.current=0;
      Object.assign(gl.domElement.dataset,{coastalSurf:"shore-break",surfTime:uniforms.uSurfTime.value.toFixed(3),surfWeather:weather==="Rain"?"rain":"calm",waveTime:uniforms.uSurfTime.value.toFixed(3),waveWind:uniforms.uSurfWind.value.toArray().map(v=>v.toFixed(4)).join(",")});
    }
  });
  return <group name="coastal-water-and-surf">
    <mesh name="open-ocean" geometry={ocean} receiveShadow raycast={noRaycast}><WeatherMaterial surface="water" color="#5497a4" roughness={.42} onBeforeCompile={waterShader} customProgramCacheKey={marineKey}/></mesh>
    {/* Fixed depth units preserve dry land at island zoom. Never displace
        these filled backing polygons; only the narrow surf ribbon can rise. */}
    <mesh name="coastal-sea" geometry={sea} renderOrder={1} raycast={noRaycast}><WeatherMaterial surface="water" color="#69b1b6" roughness={.28} metalness={.12} depthWrite={false} polygonOffset polygonOffsetFactor={0} polygonOffsetUnits={-8} onBeforeCompile={waterShader} customProgramCacheKey={marineKey}/></mesh>
    <mesh name="shallow-water-shelf" geometry={shelf} renderOrder={2} raycast={noRaycast}><WeatherMaterial surface="water" color="#9bcac1" roughness={.3} metalness={.08} depthWrite={false} polygonOffset polygonOffsetFactor={0} polygonOffsetUnits={-16} onBeforeCompile={waterShader} customProgramCacheKey={marineKey}/></mesh>
    <mesh name="breaking-waves-and-beach-wash" geometry={geometry} renderOrder={4} raycast={noRaycast}><WeatherMaterial surface="water" color="#69b1b6" transparent depthWrite={false} roughness={.38} metalness={.04} onBeforeCompile={surfShader} customProgramCacheKey={surfKey}/></mesh>
  </group>;
}
